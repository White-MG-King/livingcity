package com.livingcity.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.livingcity.LivingCityMod;
import com.livingcity.data.CityData;
import com.livingcity.data.CityMapSnapshot;
import com.livingcity.data.CityRegistry;
import com.livingcity.data.CityRules.MapSettings;
import com.livingcity.sensor.CitySimTicker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Loopback transport for the host's current integrated server, with no separate simulation. */
public final class LiveCityMap {
    private static final Gson JSON = new Gson();
    private static final long ACTIVE_FOR = Duration.ofSeconds(15).toNanos();
    private static final long SNAPSHOT_INTERVAL = Duration.ofMillis(750).toNanos();
    private static final int MAX_ACTION_BYTES = 512;
    private static final Pattern VIEW_QUERY = Pattern.compile("x=(-?[0-9]{1,7})&z=(-?[0-9]{1,7})&w=([0-9]{1,4})&h=([0-9]{1,4})&step=([0-9]{1,6})");
    private static final Pattern TILE_QUERY = Pattern.compile("x=(-?[0-9]{1,7})&z=(-?[0-9]{1,7})&step=([0-9]{1,6})&v=([0-9a-f-]{36})");
    private static final Pattern POINT_QUERY = Pattern.compile("x=(-?[0-9]{1,8})&z=(-?[0-9]{1,8})&step=([0-9]{1,6})");
    private static volatile LiveCityMap opened;

    private static final class HttpProblem extends RuntimeException {
        private final int code;

        HttpProblem(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final MinecraftServer game;
    private final UUID host;
    private final BooleanSupplier gamePaused;
    private final HttpServer http;
    private final ExecutorService workers;
    private final String authority;
    private final String origin;
    private final String token = UUID.randomUUID().toString();
    private final byte[] page;
    private volatile long lastActivity;
    private final CityMapTerrain mapTerrain;
    private CityMapSnapshot.State snapshot;
    private long snapshotAt;

    private LiveCityMap(MinecraftServer game, UUID host, BooleanSupplier gamePaused) throws IOException {
        this.game = game;
        this.host = host;
        this.gamePaused = gamePaused;
        mapTerrain = CityMapTerrain.get(game);
        try (var resource = LiveCityMap.class.getResourceAsStream("/livingcity-web/city-map.html")) {
            if (resource == null) throw new IOException("Missing bundled city map");
            page = resource.readAllBytes();
        }
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        authority = "127.0.0.1:" + http.getAddress().getPort();
        origin = "http://" + authority;
        workers = Executors.newFixedThreadPool(4, task -> {
            Thread worker = new Thread(task, "LivingCity map HTTP");
            worker.setDaemon(true);
            return worker;
        });
        http.setExecutor(workers);
        http.createContext("/", this::handle);
        http.start();
    }

    /** Must be called on the game server thread. */
    public static synchronized URI open(MinecraftServer server, UUID host, BooleanSupplier gamePaused) throws IOException {
        var player = server.getPlayerManager().getPlayer(host);
        if (player == null || !server.isHost(player.getGameProfile()) || server.isStopping()) {
            throw new IOException("Integrated host is unavailable");
        }
        if (opened != null && (opened.game != server || !opened.host.equals(host))) closeWorld(opened.game);
        if (opened == null) opened = new LiveCityMap(server, host, gamePaused);
        opened.lastActivity = System.nanoTime();
        return URI.create(opened.origin + "/#live=" + opened.token);
    }

    public static boolean browserActive(MinecraftServer server) {
        var session = opened;
        return session != null && session.game == server && System.nanoTime() - session.lastActivity < ACTIVE_FOR;
    }

    public static synchronized void closeWorld(MinecraftServer server) {
        var session = opened;
        if (session == null || session.game != server) return;
        opened = null;
        session.http.stop(0);
        session.workers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                    + "style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; "
                    + "base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            try {
                if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                    throw new HttpProblem(403, "请在本机打开游戏给出的地图地址。");
                }
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/")) {
                    requireMethod(exchange, "GET");
                    reply(exchange, "text/html", 200, page);
                    return;
                }
                if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    throw new HttpProblem(403, "请在游戏内重新打开世界地图。");
                }
                lastActivity = System.nanoTime();
                Object response;
                switch (path) {
                    case "/api/state" -> {
                        requireMethod(exchange, "GET");
                        response = onServer(() -> state(false));
                    }
                    case "/api/map/status" -> {
                        requireMethod(exchange, "GET");
                        response = mapTerrain.status(0, 0);
                    }
                    case "/api/map/view" -> {
                        requireMethod(exchange, "GET");
                        response = terrainView(exchange.getRequestURI().getRawQuery());
                    }
                    case "/api/map/point" -> {
                        requireMethod(exchange, "GET");
                        var query = POINT_QUERY.matcher(query(exchange));
                        if (!query.matches()) throw new HttpProblem(400, "坐标无效。");
                        int x = Integer.parseInt(query.group(1)), z = Integer.parseInt(query.group(2));
                        if (Math.abs(x) >= TerrainAtlas.WORLD_LIMIT * 16 || Math.abs(z) >= TerrainAtlas.WORLD_LIMIT * 16) {
                            throw new HttpProblem(400, "坐标超出边界。");
                        }
                        response = mapTerrain.point(x, z, step(query.group(3)));
                    }
                    case "/api/map/tile" -> {
                        requireMethod(exchange, "GET");
                        var query = TILE_QUERY.matcher(query(exchange));
                        if (!query.matches()) throw new HttpProblem(400, "瓦片坐标无效。");
                        var key = new TerrainAtlas.Key(Integer.parseInt(query.group(1)), Integer.parseInt(query.group(2)), step(query.group(3)));
                        byte[] png = mapTerrain.png(key, query.group(4));
                        if (png == null) throw new HttpProblem(404, "瓦片正在更新。");
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, png.length);
                        exchange.getResponseBody().write(png);
                        return;
                    }
                    case "/api/action" -> {
                        requireMethod(exchange, "POST");
                        if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                            throw new HttpProblem(403, "请从当前实时地图操作。");
                        }
                        JsonObject command = readCommand(exchange);
                        response = onServer(() -> action(command));
                    }
                    default -> throw new HttpProblem(404, "找不到页面。");
                }
                replyJson(exchange, 200, response);
            } catch (HttpProblem error) {
                replyJson(exchange, error.code, Map.of("error", error.getMessage()));
            }
        }
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equals(exchange.getRequestMethod())) throw new HttpProblem(405, "请求方式不受支持。");
    }

    private static String query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? "" : raw;
    }

    private static int step(String value) {
        int step = Integer.parseInt(value);
        if (step < 1 || step > TerrainAtlas.MAX_STEP || Integer.bitCount(step) != 1) {
            throw new HttpProblem(400, "地图采样精度无效。");
        }
        return step;
    }

    private CityMapTerrain.View terrainView(String query) {
        var fields = VIEW_QUERY.matcher(query == null ? "" : query);
        if (!fields.matches()) throw new HttpProblem(400, "地图范围无效。");
        int x = Integer.parseInt(fields.group(1)), z = Integer.parseInt(fields.group(2));
        int width = Integer.parseInt(fields.group(3)), height = Integer.parseInt(fields.group(4));
        int step = step(fields.group(5));
        if (width < 1 || width > 1024 || height < 1 || height > 1024 || (long) width * height > 16384
                || Math.abs(x) > TerrainAtlas.WORLD_LIMIT || Math.abs(z) > TerrainAtlas.WORLD_LIMIT) {
            throw new HttpProblem(400, "地图范围超出边界。");
        }
        return mapTerrain.view(x, z, width, height, step);
    }

    private static JsonObject readCommand(HttpExchange exchange) throws IOException {
        byte[] payload = exchange.getRequestBody().readNBytes(MAX_ACTION_BYTES + 1);
        if (payload.length > MAX_ACTION_BYTES) throw new HttpProblem(413, "请求太长。");
        try {
            return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new HttpProblem(400, "命令格式无效。");
        }
    }

    private ServerPlayerEntity player() {
        var player = game.getPlayerManager().getPlayer(host);
        if (player == null) throw new HttpProblem(503, "当前存档已关闭。");
        return player;
    }

    private CityMapSnapshot.State state(boolean refresh) {
        var player = player();
        long now = System.nanoTime();
        boolean paused = gamePaused.getAsBoolean();
        if (refresh || snapshot == null || now - snapshotAt >= SNAPSHOT_INTERVAL || snapshot.gamePaused() != paused) {
            snapshot = CityMapSnapshot.capture(game, player, paused);
            snapshotAt = now;
        }
        return snapshot;
    }

    private CityMapSnapshot.State action(JsonObject command) {
        if (!CityMapSnapshot.canControl(game, player())) throw new HttpProblem(403, "只有房主或管理员可以修改模拟状态。");
        if (gamePaused.getAsBoolean()) throw new HttpProblem(409, "Minecraft 已在菜单中暂停。请返回游戏后再推进时钟。");
        String action;
        int speed = 0;
        try {
            action = command.get("action").getAsString();
            if (action.equals("speed")) speed = Integer.parseInt(command.get("speed").getAsString());
        } catch (RuntimeException invalid) {
            throw new HttpProblem(400, "时钟命令无效。");
        }
        CityData root = CityData.get(game);
        switch (action) {
            case "surveySettings" -> {
                try {
                    var registry = CityRegistry.get(game);
                    registry.rules().setSurvey(new com.livingcity.data.CityRules.SurveySettings(command.get("enabled").getAsBoolean(),
                            integer(command, "slots"), integer(command, "workers")));
                    registry.markDirty();
                } catch (RuntimeException invalid) { throw new HttpProblem(400, "现场并发和初筛线程须为 1～4。"); }
            }
            case "mapSettings" -> {
                try {
                    var settings = new MapSettings(integer(command, "border"), integer(command, "radius"), integer(command, "workers"),
                            integer(command, "seedWorkers"), integer(command, "budgetMs"), integer(command, "guardMs"));
                    var registry = CityRegistry.get(game);
                    registry.rules().setMap(settings);
                    registry.markDirty();
                    mapTerrain.configure(settings);
                } catch (RuntimeException invalid) {
                    throw new HttpProblem(400, "地图设置超出范围，种子线程须小于或等于地图总线程。");
                }
            }
            case "mapImport" -> {
                if (!mapTerrain.startImport(player().getChunkPos())) throw new HttpProblem(409, "旧档补绘正在进行。");
            }
            case "mapCancel" -> mapTerrain.cancelImport();
            case "speed" -> {
                if (!CitySimTicker.setSpeed(root, speed)) throw new HttpProblem(400, "请选择 0、1、5、10 或 1000 倍速。");
            }
            case "step" -> {
                if (!root.clock().requestStep()) throw new HttpProblem(409, "请先暂停城市时钟并等上一次单步完成。");
            }
            case "war" -> {
                int a, b;
                String kind;
                try {
                    a = Integer.parseInt(command.get("a").getAsString());
                    b = Integer.parseInt(command.get("b").getAsString());
                    kind = command.get("kind").getAsString();
                } catch (RuntimeException invalid) {
                    throw new HttpProblem(400, "国家编号无效。");
                }
                if (!kind.equals("declare") && !kind.equals("truce")) throw new HttpProblem(400, "未知的战事操作。");
                var result = com.livingcity.data.CityRegistry.get(game).warAction(kind.equals("declare"), a, b);
                if (!com.livingcity.data.WarSave.accepted(result)) {
                    throw new HttpProblem(409, com.livingcity.data.WarSave.message(result));
                }
            }
            default -> throw new HttpProblem(400, "未知的实时地图操作。");
        }
        return state(true);
    }

    private static int integer(JsonObject body, String field) {
        return Integer.parseInt(body.get(field).getAsString());
    }

    private <T> T onServer(Callable<T> operation) {
        if (!game.isRunning() || game.isStopping()) throw new HttpProblem(503, "当前存档正在关闭。");
        FutureTask<T> task = new FutureTask<>(operation);
        game.execute(task);
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(false);
            throw new HttpProblem(503, "游戏暂时未响应，地图将重新连接。");
        } catch (InterruptedException stopping) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new HttpProblem(503, "实时地图正在关闭。");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpProblem known) throw known;
            LivingCityMod.LOGGER.warn("Could not read the live city map", error.getCause());
            throw new HttpProblem(500, "读取当前存档时发生错误。");
        }
    }

    private static void replyJson(HttpExchange exchange, int code, Object data) throws IOException {
        reply(exchange, "application/json", code, JSON.toJson(data).getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, String type, int code, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
    }
}
