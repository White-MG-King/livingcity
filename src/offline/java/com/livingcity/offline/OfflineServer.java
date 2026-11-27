package com.livingcity.offline;

import citysim.PortTrade;
import citysim.SeaLanes;
import citysim.SeaLanes.Cell;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class OfflineServer {
    private static final Gson JSON = new Gson();
    private static final int FAST = 18_000;
    private static final int MAX_REQUEST_BYTES = 3 * 1024 * 1024;
    private static final int PREPARED_PORT_PAIRS = 8;
    private static final Set<Integer> SPEEDS = Set.of(0, 1, 5, 10, 60, 1000, FAST);
    private final String expectedOrigin;
    private OfflineWorld world;
    private long terrainVersion;
    private int speed;
    private int fraction;
    private boolean preparingSea;
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "LivingCity offline navigation");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        return worker;
    });

    private OfflineServer(int port) {
        expectedOrigin = "http://127.0.0.1:" + port;
    }

    public static void main(String[] args) throws IOException {
        Path page = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        VanillaSeedSampler.prepare();
        OfflineServer offline = new OfflineServer(port);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        server.setExecutor(Executors.newFixedThreadPool(3));
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod()) && "/".equals(exchange.getRequestURI().getPath())) {
                reply(exchange, 200, "text/html", Files.readAllBytes(page));
            } else replyJson(exchange, 404, Map.of("error", "找不到页面"));
        });
        server.createContext("/api/state", offline::state);
        server.createContext("/api/terrain", offline::terrain);
        server.createContext("/api/checkpoint", offline::checkpoint);
        server.createContext("/api/action", offline::action);
        var clock = Executors.newSingleThreadScheduledExecutor();
        clock.scheduleWithFixedDelay(offline::clock, 100, 100, TimeUnit.MILLISECONDS);
        server.start();
        System.out.println("LivingCity offline · " + offline.expectedOrigin + "/");
    }

    private synchronized void clock() {
        if (world == null) return;
        if (speed == 0 || !world.hasCities()) {
            prepareSea();
            return;
        }
        fraction += speed;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(32);
        while (fraction >= 600 && System.nanoTime() < deadline) {
            step();
            fraction -= 600;
        }
        fraction %= 600; // Drop work beyond the time budget instead of accumulating a burst.
        if (deadline - System.nanoTime() >= TimeUnit.MILLISECONDS.toNanos(12)) prepareSea();
    }

    private void prepareSea() {
        if (preparingSea) return;
        var current = world;
        var berths = current.berths();
        if (!current.needsSeaPreparation(berths)) return;
        var snapshot = List.copyOf(current.samples.values());
        preparingSea = true;
        preparation.execute(() -> {
            try {
                var prepared = OfflineWorld.indexSea(snapshot);
                prepared.prepareComponents();
                prepareRoutes(prepared, berths);
                synchronized (this) {
                    if (world == current && current.samples.size() == snapshot.size() && current.berths().equals(berths)) {
                        current.usePreparedSea(prepared, berths);
                    }
                }
            } finally {
                synchronized (this) { preparingSea = false; }
            }
        });
    }

    private record PortPair(Cell first, Cell second, int distance) {}

    private static void prepareRoutes(SeaLanes sea, List<Cell> berths) {
        int limit = PortTrade.MAX_ROUTE_TILES;
        var pairs = new ArrayList<PortPair>();
        for (int i = 0; i < berths.size(); i++) {
            Cell first = berths.get(i);
            int component = sea.component(first);
            if (component == 0) continue;
            for (int j = i + 1; j < berths.size(); j++) {
                Cell second = berths.get(j);
                int distance = Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
                if (distance < limit && component == sea.component(second)) pairs.add(new PortPair(first, second, distance));
            }
        }
        pairs.sort(Comparator.comparingInt(PortPair::distance));
        for (int i = 0; i < Math.min(pairs.size(), PREPARED_PORT_PAIRS); i++) {
            var pair = pairs.get(i);
            sea.route(pair.first(), pair.second(), limit);
            sea.route(pair.second(), pair.first(), limit);
        }
    }

    private void step() {
        int before = world.samples.size();
        world.step();
        if (world.samples.size() != before) terrainVersion++;
    }

    private synchronized Object snapshot() {
        if (world == null) return Map.of("present", false, "tradeSupported", true);
        return Map.ofEntries(Map.entry("present", true), Map.entry("seed", Long.toString(world.seed)),
                Map.entry("terrainVersion", terrainVersion), Map.entry("cycles", world.cycle),
                Map.entry("speed", speed), Map.entry("cities", world.cities()), Map.entry("unlimited", world.unlimited),
                Map.entry("limit", world.limit), Map.entry("timedExpansion", world.timedExpansion),
                Map.entry("imported", world.imported), Map.entry("layer", world.layer == null ? "surface" : world.layer),
                Map.entry("tradeSupported", true), Map.entry("demo", world.demo), Map.entry("battleDemo", world.battleDemo),
                Map.entry("voyages", world.market.voyages(world.cycle)), Map.entry("war", world.war.view()));
    }

    private void state(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { replyJson(exchange,405,Map.of("error","仅支持 GET")); return; }
        replyJson(exchange, 200, snapshot());
    }

    private synchronized void terrain(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { replyJson(exchange,405,Map.of("error","仅支持 GET")); return; }
        if (world == null) { replyJson(exchange,404,Map.of("error","请先扫描地图")); return; }
        replyJson(exchange, 200, Map.of("version", terrainVersion, "samples", world.displayed));
    }

    private void checkpoint(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { replyJson(exchange,405,Map.of("error","仅支持 GET")); return; }
        net.minecraft.nbt.NbtCompound data;
        synchronized (this) { data = world == null ? null : OfflineCheckpoint.write(world); }
        if (data == null) { replyJson(exchange, 404, Map.of("error", "请先扫描地图")); return; }
        var buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, buffer);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"livingcity-offline.dat\"");
        reply(exchange, 200, "application/octet-stream", buffer.toByteArray());
    }

    private void action(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod()) || !expectedOrigin.equals(exchange.getRequestHeaders().getFirst("Origin"))) {
            replyJson(exchange, 403, Map.of("error", "请从本机模拟地图操作"));
            return;
        }
        try {
            byte[] payload = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (payload.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("请求过大");
            JsonObject body = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            apply(body);
            replyJson(exchange, 200, snapshot());
        } catch (IllegalArgumentException error) {
            replyJson(exchange, 400, Map.of("error", error.getMessage() == null ? "参数无效" : error.getMessage()));
        } catch (RuntimeException error) {
            replyJson(exchange, 400, Map.of("error", "请求参数无效"));
        }
    }

    private synchronized void apply(JsonObject body) {
        String action = body.get("action").getAsString();
        if (action.equals("tradeDemo")) {
            world = OfflineWorld.tradeDemo();
            terrainVersion++;
            speed = fraction = 0;
            return;
        }
        if (action.equals("warDemo")) {
            world = OfflineWorld.warDemo();
            terrainVersion++;
            speed = fraction = 0;
            return;
        }
        if (action.equals("restore")) {
            world = OfflineCheckpoint.read(OfflineSave.readDat(body, "checkpoint", "离线模拟快照"));
            terrainVersion++;
            speed = fraction = 0;
            return;
        }
        if (action.equals("scan")) {
            String seed = body.get("seed").getAsString().strip();
            long value;
            try { value = Long.parseLong(seed); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("种子需要是带符号的 64 位整数"); }
            int x = bound(body, "x", -1874968, 1874967);
            int z = bound(body, "z", -1874968, 1874967);
            int radius = bound(body, "radius", 2, 32);
            world = new OfflineWorld(value, x, z, radius);
            terrainVersion++;
            speed = fraction = 0;
            return;
        }
        if (action.equals("import")) {
            world = new OfflineWorld(OfflineSave.read(body), bound(body, "radius", 2, 32));
            terrainVersion++;
            speed = fraction = 0;
            return;
        }
        if (world == null) throw new IllegalArgumentException("请先扫描种子地图或导入存档");
        switch (action) {
            case "found" -> world.found(bound(body, "x", -1875000, 1875000), bound(body, "z", -1875000, 1875000));
            case "port" -> world.buildPort(new citysim.SeaLanes.Cell(
                    bound(body, "x", -7500000, 7500000), bound(body, "z", -7500000, 7500000)));
            case "speed" -> {
                int next = bound(body, "speed", 0, FAST);
                if (!SPEEDS.contains(next)) throw new IllegalArgumentException("不支持该倍率");
                speed = next;
            }
            case "step" -> {
                if (speed != 0) throw new IllegalArgumentException("请先暂停模拟");
                if (!world.hasCities()) throw new IllegalArgumentException("请先建城");
                step();
            }
            case "war" -> {
                int a = bound(body, "a", 1, Integer.MAX_VALUE), b = bound(body, "b", 1, Integer.MAX_VALUE);
                var result = switch (body.get("kind").getAsString()) {
                    case "declare" -> world.war.declare(world, a, b, world.cycle);
                    case "truce" -> world.war.truce(world, a, b, world.cycle);
                    default -> throw new IllegalArgumentException("未知的战事操作");
                };
                if (!com.livingcity.data.WarSave.accepted(result)) {
                    throw new IllegalArgumentException(com.livingcity.data.WarSave.message(result));
                }
            }
            case "layer" -> {
                Integer layer = body.get("y").isJsonNull() ? null : bound(body, "y", VanillaSeedSampler.MIN_Y, VanillaSeedSampler.MAX_Y);
                world.changeLayer(layer);
                terrainVersion++;
            }
            case "rules" -> {
                world.limit = bound(body, "limit", 32, 512);
                world.unlimited = body.get("unlimited").getAsBoolean();
                if (body.has("timedExpansion")) world.timedExpansion = body.get("timedExpansion").getAsBoolean();
            }
            default -> throw new IllegalArgumentException("未知操作");
        }
    }

    private static int bound(JsonObject body, String key, int minimum, int maximum) {
        int value = Integer.parseInt(body.get(key).getAsString());
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " 超出范围 " + minimum + "–" + maximum);
        return value;
    }

    private static void replyJson(HttpExchange exchange, int code, Object value) throws IOException {
        reply(exchange, code, "application/json", JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int code, String type, byte[] payload) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(code, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }
}
