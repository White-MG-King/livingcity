package com.livingcity.web;

import com.livingcity.LivingCityMod;
import com.livingcity.data.CityData;
import com.livingcity.data.CityMapSnapshot;
import com.livingcity.data.CityRegistry;
import com.livingcity.data.CityRules.MapSettings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.livingcity.web.TerrainAtlas.Key;

/** A world-owned producer. The HTTP reader supplies viewport interest and never does world sampling. */
public final class CityMapTerrain {
    private static final Map<MinecraftServer, CityMapTerrain> SERVICES = new ConcurrentHashMap<>();
    private static final int SAMPLE_QUEUE_LIMIT = 1024;
    private static final int SAVE_QUEUE_LIMIT = 256;
    private static final long VIEW_TTL = Duration.ofSeconds(8).toNanos();
    private static final int BORDER_STEP = 1;

    record TileInfo(int x, int z, int step, String version) {}
    record View(List<TileInfo> tiles, int requested, Status status) {}
    record Point(CityMapSnapshot.Tile ground, String source, int step, int connectedWaterCells) {}
    record ImportState(boolean active, int scanned, int imported, int skipped, int painted, int paintPending, String stage) {}
    record Status(MapSettings settings, int maxWorkers, float mspt, int busy, boolean deferred,
                  boolean parallelSeed, int visibleReady, int visibleTotal, int borderLeft, ImportState history) {}

    private record Interest(List<Key> keys, long expires) {}
    private record BorderBatch(int radius, List<Key> keys) {}
    private record Generator(ChunkGenerator generator, NoiseConfig noise, HeightLimitView height, int seaLevel) {
        CityMapSnapshot.Tile sample(int quartX, int quartZ) {
            int x = quartX * 4 + 2, z = quartZ * 4 + 2;
            int y = Math.max(height.getBottomY(), generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, height, noise) - 1);
            int floor = generator.getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, height, noise) - 1;
            boolean water = y <= seaLevel && floor < y;
            String biome = generator.getBiomeSource().getBiome(x >> 2, y >> 2, z >> 2, noise.getMultiNoiseSampler())
                    .getKey().map(key -> key.getValue().getPath()).orElse("unknown");
            var tile = new CityMapSnapshot.Tile(y, biome, water, null, false);
            return water ? tile.withDepth(y - floor) : tile;
        }
    }

    private final ServerWorld world;
    private final CityRegistry registry;
    private final TerrainAtlas atlas;
    private final Path regions;
    private final Generator generator;
    private final boolean parallelSeed;
    private final ExecutorService workers = Executors.newFixedThreadPool(MapSettings.MAX_WORKERS, task -> {
        Thread thread = new Thread(task, "LivingCity terrain worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicInteger renderBusy = new AtomicInteger();
    private final Set<Key> rendering = ConcurrentHashMap.newKeySet();
    private final Set<Key> changedTiles = ConcurrentHashMap.newKeySet();
    private record Route(citysim.SeaLanes.Cell from, citysim.SeaLanes.Cell to) {}
    private record Navigation(int revision, List<citysim.SeaLanes.Cell> path) {}
    private final AtomicInteger surfaceRevision = new AtomicInteger();
    private final Map<Route, Navigation> seaRoutes = new ConcurrentHashMap<>();
    private final java.util.Queue<Route> toNavigate = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<Long, CityMapSnapshot.Tile> seedCache = new LinkedHashMap<>(128, .75f, true);
    private final LinkedHashMap<Long, TerrainAtlas.Observation> toSave = new LinkedHashMap<>();
    private final LinkedHashSet<Long> toSample = new LinkedHashSet<>();
    private final LinkedHashSet<Long> constructionSamples = new LinkedHashSet<>();
    private final LinkedHashSet<Long> toPrepare = new LinkedHashSet<>();
    private final LinkedHashSet<Key> toPaint = new LinkedHashSet<>();
    private final LinkedHashSet<Key> borderCheck = new LinkedHashSet<>();
    private Set<Key> borderTargets = Set.of();
    private Set<Long> nearby = Set.of();
    private volatile List<ChunkPos> playerChunks = List.of();
    private List<ChunkPos> surveyCenters = List.of();
    private long borderRevision = -1;
    private int borderRadius = -1;
    private final AtomicInteger borderJob = new AtomicInteger();
    private final AtomicReference<BorderBatch> newBorder = new AtomicReference<>();
    private volatile Interest interest = new Interest(List.of(), 0);
    private volatile MapSettings settings;
    private volatile float mspt;
    private volatile boolean deferred;
    private volatile boolean closed;
    private volatile int borderLeft;
    private volatile ImportJob importing;

    private CityMapTerrain(MinecraftServer server) {
        world = server.getOverworld();
        registry = CityRegistry.get(server);
        var manager = world.getChunkManager();
        var chunkGenerator = manager.getChunkGenerator();
        parallelSeed = chunkGenerator.getClass() == NoiseChunkGenerator.class || chunkGenerator.getClass() == FlatChunkGenerator.class;
        generator = new Generator(chunkGenerator, manager.getNoiseConfig(),
                HeightLimitView.create(world.getBottomY(), world.getHeight()), world.getSeaLevel());
        Path root = server.getSavePath(WorldSavePath.ROOT);
        atlas = new TerrainAtlas(root.resolve("data/livingcity-map/overworld-v2"));
        regions = root.resolve("region");
        settings = registry.rules().map();
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).tick(server));
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            var service = SERVICES.get(world.getServer());
            if (service != null && service.world == world) service.changed(chunk.getPos().toLong());
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            var service = SERVICES.get(world.getServer());
            if (service != null && service.world == world) service.registry.groundPreparation().invalidate(chunk.getPos().toLong());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LiveCityMap.closeWorld(server);
            var service = SERVICES.remove(server);
            if (service != null) service.close();
        });
    }

    static CityMapTerrain get(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, CityMapTerrain::new);
    }

    public static void surfaceChanged(ServerWorld world, ChunkPos chunk) {
        var service = SERVICES.get(world.getServer());
        if (service != null && service.world == world && world.getServer().isOnThread()) service.changed(chunk.toLong());
    }

    public static Boolean recordedDrySurface(ServerWorld world, int chunkX, int chunkZ) {
        var service = SERVICES.get(world.getServer());
        if (service == null || service.world != world) return null;
        var surface = service.atlas.known(chunkX, chunkZ);
        if (surface == null) return null;
        return surface.tiles().stream().anyMatch(tile -> !tile.water() && !tile.lava()
                && tile.material() != null && !tile.material().endsWith("_leaves") && !tile.material().endsWith("_log"));
    }

    /** Capture the service on the server thread. The returned work reads immutable map
     * records and the same background-safe vanilla generator used by the map renderer. */
    public static java.util.concurrent.Callable<List<Long>> constructionPreflight(ServerWorld world, List<Long> candidates, boolean shore) {
        var service = get(world.getServer());
        var snapshot = List.copyOf(candidates);
        return () -> {
            var scores = new LinkedHashMap<Long, Integer>();
            for (long key : snapshot) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int x = CityData.xOf(key), z = CityData.zOf(key);
                int score = 0;
                try {
                    var observed = service.atlas.observation(x, z);
                    if (observed != null) {
                        boolean dry = observed.tiles().stream().anyMatch(t -> !t.water() && !t.lava());
                        boolean water = observed.tiles().stream().anyMatch(CityMapSnapshot.Tile::water);
                        score = !dry ? 100 : shore && water ? -20 : -5;
                    } else {
                        var tile = service.seed(x * 4 + 2, z * 4 + 2, service.parallelSeed);
                        if (tile != null) score = tile.lava() ? 100 : tile.water() ? 20 : 0;
                    }
                    if (shore && score < 100) {
                        for (int[] off : new int[][]{{-6,0},{6,0},{0,-6},{0,6}}) {
                            var tile = service.seed(x * 4 + 2 + off[0], z * 4 + 2 + off[1], service.parallelSeed);
                            if (tile != null && tile.water()) { score -= 10; break; }
                        }
                    }
                } catch (IOException unreadable) { /* Unknown terrain remains a field-survey candidate. */ }
                scores.put(key, score);
            }
            return snapshot.stream().sorted(java.util.Comparator.comparingInt(scores::get)).toList();
        };
    }

    public static void observeConstruction(ServerWorld world, List<Long> chunks) {
        var service = get(world.getServer());
        for (long key : chunks) if (service.constructionSamples.size() < SAMPLE_QUEUE_LIMIT) {
            long packed = ChunkPos.toLong(CityData.xOf(key), CityData.zOf(key));
            service.constructionSamples.add(packed); service.toSample.add(packed);
        }
        // These chunks have a short ticket lease; capture them before the periodic
        // player-neighborhood refresh can consume the available map budget.
        var other = List.copyOf(service.toSample);
        service.toSample.clear(); service.toSample.addAll(service.constructionSamples);
        for (long key : other) if (service.toSample.size() < SAMPLE_QUEUE_LIMIT) service.toSample.add(key);
    }

    public static boolean constructionCaptured(ServerWorld world, Set<Long> chunks) {
        var service = get(world.getServer());
        return chunks.stream().noneMatch(key -> service.constructionSamples.contains(ChunkPos.toLong(CityData.xOf(key), CityData.zOf(key))));
    }

    public static List<citysim.SeaLanes.Cell> seaRoute(ServerWorld world, citysim.SeaLanes.Cell from, citysim.SeaLanes.Cell to) {
        var service = get(world.getServer());
        var key = new Route(from, to);
        var cached = service.seaRoutes.get(key);
        int revision = service.surfaceRevision.get();
        if (cached != null && cached.revision() == revision) return cached.path();
        if (service.toNavigate.size() >= 32) return null;
        if (world.getServer().isOnThread()) {
            for (int z = (Math.min(from.z(), to.z()) >> 2) - 1; z <= (Math.max(from.z(), to.z()) >> 2) + 1; z++) {
                for (int x = (Math.min(from.x(), to.x()) >> 2) - 1; x <= (Math.max(from.x(), to.x()) >> 2) + 1; x++) {
                    if (service.toSample.size() < SAMPLE_QUEUE_LIMIT && world.getChunkManager().getWorldChunk(x, z) != null) {
                        service.toSample.add(ChunkPos.toLong(x, z));
                    }
                }
            }
        }
        if (service.seaRoutes.size() > 512) service.seaRoutes.clear();
        service.seaRoutes.put(key, new Navigation(revision, null));
        service.toNavigate.add(key);
        return null;
    }

    private void changed(long chunk) {
        registry.groundPreparation().invalidate(chunk);
        if (nearby.contains(chunk)) toSample.add(chunk);
    }

    void configure(MapSettings next) {
        if (settings.radius() != next.radius()) surveyCenters = List.of();
        settings = next;
    }

    View view(int x, int z, int width, int height, int step) {
        var tiles = new ArrayList<Key>();
        for (int dz = 0; dz < height; dz++) {
            for (int dx = 0; dx < width; dx++) {
                var key = new Key(x + dx, z + dz, step);
                if (!key.insideWorld()) continue;
                tiles.add(key);
            }
        }
        double cx = (x + width / 2.0) * TerrainAtlas.CHUNKS, cz = (z + height / 2.0) * TerrainAtlas.CHUNKS;
        Comparator<Key> near = Comparator.comparingDouble(key -> Math.abs(key.minX() + key.span() / 2.0 - cx)
                + Math.abs(key.minZ() + key.span() / 2.0 - cz));
        tiles.sort(near);
        var requested = List.copyOf(tiles);
        interest = new Interest(List.copyOf(requested), System.nanoTime() + VIEW_TTL);
        var ready = new ArrayList<TileInfo>();
        int fine = 0;
        for (Key key : requested) {
            var raster = atlas.ready(key);
            if (raster != null) {
                ready.add(new TileInfo(key.x(), key.z(), key.step(), raster.version()));
                if (key.step() == step) fine++;
            }
        }
        return new View(ready, tiles.size(), status(fine, tiles.size()));
    }

    byte[] png(Key key, String revision) {
        var raster = atlas.ready(key);
        return raster == null || !raster.version().equals(revision) ? null : raster.png();
    }

    Point point(int x, int z, int step) throws IOException {
        var exact = atlas.observation(x >> 4, z >> 4);
        if (exact != null) {
            boolean nearbySource = playerChunks.stream().anyMatch(center ->
                    Math.abs(center.x - (x >> 4)) <= settings.radius() && Math.abs(center.z - (z >> 4)) <= settings.radius());
            return new Point(exact.at(x, z), exact.live() && nearbySource ? "live" : "saved", 1, exact.waterAreaAt(x, z));
        }
        for (int layer = 1; layer <= Math.min(TerrainAtlas.MAX_STEP, step * 2); layer *= 2) {
            Key key = Key.at(x >> 4, z >> 4, layer);
            var raster = atlas.ready(key);
            var pixel = raster == null ? null : raster.pixel(key, x, z);
            if (pixel != null) return new Point(pixel.tile(), pixel.observed() ? "saved" : "seed", layer, 0);
        }
        return null;
    }

    Status status(int fine, int total) {
        var job = importing;
        return new Status(settings, MapSettings.MAX_WORKERS, mspt, busy.get(), deferred, parallelSeed,
                fine, total, borderLeft, job == null ? new ImportState(false, 0, 0, 0, 0, 0, "idle") : job.status());
    }

    boolean startImport(ChunkPos center) {
        var previous = importing;
        if (previous != null && previous.status().active()) return false;
        importing = new ImportJob(center);
        return true;
    }

    void cancelImport() {
        var job = importing;
        if (job != null) job.cancelled = true;
    }

    private void tick(MinecraftServer server) {
        configure(registry.rules().map());
        mspt = server.getTickTime();
        deferred = mspt >= settings.guardMs() || !Float.isFinite(mspt);
        if (!Float.isFinite(mspt)) mspt = settings.guardMs();
        if (closed || deferred) return;
        long deadline = System.nanoTime() + (long) (Math.max(0, Math.min(settings.budgetMs(), settings.guardMs() - mspt)) * 1_000_000);
        if (server.getTicks() % 10 == 0) {
            playerChunks = world.getPlayers().stream().map(player -> player.getChunkPos()).distinct().toList();
            if (!surveyCenters.equals(playerChunks) || server.getTicks() % 40 == 0) requestNearby();
            queueBorder();
        }
        captureNearby(deadline);
        var border = newBorder.getAndSet(null);
        if (border != null && border.radius() == settings.border()) {
            Set<Key> desired = Set.copyOf(border.keys());
            borderCheck.retainAll(desired);
            for (Key key : border.keys()) if (!borderTargets.contains(key)) borderCheck.add(key);
            borderTargets = desired;
        }
        for (Key key : changedTiles) {
            if (toPaint.size() >= SAMPLE_QUEUE_LIMIT) break;
            changedTiles.remove(key);
            toPaint.add(key);
        }
        dispatch(deadline);
        borderLeft = borderCheck.size();
    }

    private void requestNearby() {
        surveyCenters = playerChunks;
        var requested = new LinkedHashSet<Long>();
        int radius = settings.radius();
        for (int distance = 0; distance <= radius && requested.size() < SAMPLE_QUEUE_LIMIT; distance++) {
            for (ChunkPos center : surveyCenters) {
                for (int dz = -distance; dz <= distance; dz++) {
                    for (int dx = -distance; dx <= distance; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == distance && requested.size() < SAMPLE_QUEUE_LIMIT) {
                            requested.add(ChunkPos.toLong(center.x + dx, center.z + dz));
                        }
                    }
                }
            }
        }
        nearby = requested;
        toSample.retainAll(nearby);
        toSample.addAll(constructionSamples);
        toPrepare.retainAll(nearby);
        toSample.addAll(nearby);
    }

    private void captureNearby(long deadline) {
        for (int count = 0; count < 3 && System.nanoTime() < deadline && toSave.size() < SAVE_QUEUE_LIMIT && !toSample.isEmpty(); count++) {
            long key = first(toSample);
            var pos = new ChunkPos(key);
            var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk == null) { constructionSamples.remove(key); continue; }
            var measured = CityMapSnapshot.terrain(chunk);
            constructionSamples.remove(key);
            var known = atlas.known(pos.x, pos.z);
            if (known == null || !known.tiles().equals(measured)) {
                toSave.put(key, new TerrainAtlas.Observation(pos.x, pos.z, world.getTime(), true, measured));
            }
            if (toPrepare.size() < SAMPLE_QUEUE_LIMIT && nearCity(pos.x, pos.z)) toPrepare.add(key);
        }
        if (System.nanoTime() < deadline && !toPrepare.isEmpty()) {
            long key = toPrepare.iterator().next();
            var pos = new ChunkPos(key);
            var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk == null || registry.groundPreparation().advance(chunk, deadline)) toPrepare.remove(key);
        }
    }

    private boolean nearCity(int x, int z) {
        return registry.owner(x, z) != null || registry.owner(x - 1, z) != null || registry.owner(x + 1, z) != null
                || registry.owner(x, z - 1) != null || registry.owner(x, z + 1) != null;
    }

    private void queueBorder() {
        int radius = settings.border();
        if (radius == 0) {
            borderCheck.clear();
            borderTargets = Set.of();
            borderRadius = 0;
            return;
        }
        if ((radius == borderRadius && registry.landRevision() == borderRevision) || borderJob.get() != 0
                || busy.get() >= settings.workers()) return;
        borderRadius = radius;
        borderRevision = registry.landRevision();
        var bounds = registry.boundaries();
        borderJob.incrementAndGet();
        submit(() -> {
            try {
                var keys = new LinkedHashSet<Key>();
                for (ChunkPos edge : bounds) {
                    Key from = Key.at(edge.x - radius, edge.z - radius, BORDER_STEP);
                    Key to = Key.at(edge.x + radius, edge.z + radius, BORDER_STEP);
                    for (int z = from.z(); z <= to.z(); z++) {
                        for (int x = from.x(); x <= to.x(); x++) {
                            Key key = new Key(x, z, BORDER_STEP);
                            if (key.insideWorld()) keys.add(key);
                        }
                    }
                }
                newBorder.set(new BorderBatch(radius, List.copyOf(keys)));
            } finally {
                borderJob.decrementAndGet();
            }
        });
    }

    private void dispatch(long deadline) {
        for (int attempts = 0; attempts < settings.workers() && busy.get() < settings.workers(); attempts++) {
            if (!toSave.isEmpty()) {
                long key = toSave.keySet().iterator().next();
                var sample = toSave.remove(key);
                submit(() -> saveObservation(sample));
                continue;
            }
            var navigation = toNavigate.poll();
            if (navigation != null) {
                int revision = surfaceRevision.get();
                submit(() -> {
                    var path = citysim.SeaLanes.findRoute(navigation.from(), navigation.to(), citysim.PortTrade.MAX_ROUTE_TILES, cell -> {
                        try {
                            var observed = atlas.observation(cell.x() >> 2, cell.z() >> 2);
                            if (observed != null) return observed.at(cell.x() * 4 + 2, cell.z() * 4 + 2).water();
                            var estimate = seed(cell.x(), cell.z(), parallelSeed);
                            return estimate != null && estimate.water();
                        } catch (IOException unreadable) { return false; }
                    });
                    seaRoutes.put(navigation, new Navigation(revision, path));
                });
                continue;
            }
            if (renderBusy.get() < settings.seedWorkers()) {
                var visible = interest;
                if (visible.expires > System.nanoTime() && queueVisible(visible.keys, deadline)) continue;
            }
            var job = importing;
            if (job != null && job.dispatch(deadline)) continue;
            if (renderBusy.get() < settings.seedWorkers()) {
                if (queuePaint(borderCheck, deadline) || queuePaint(toPaint, deadline)) continue;
            }
            break;
        }
    }

    private boolean queueVisible(List<Key> keys, long deadline) {
        for (Key key : keys) {
            if (atlas.ready(key) == null && !rendering.contains(key)) return render(key, deadline);
        }
        return false;
    }

    private boolean queuePaint(LinkedHashSet<Key> queue, long deadline) {
        var keys = queue.iterator();
        for (int i = 0; i < 24 && keys.hasNext(); i++) {
            Key key = keys.next();
            if (atlas.ready(key) != null || rendering.contains(key)) {
                keys.remove();
                continue;
            }
            if (!render(key, deadline)) return false;
            keys.remove();
            return true;
        }
        return false;
    }

    private boolean render(Key key, long deadline) {
        if (!parallelSeed && !prepareSeed(key, deadline)) return false;
        if (!rendering.add(key)) return false;
        renderBusy.incrementAndGet();
        var job = importing;
        submit(() -> {
            try {
                var raster = atlas.render(key, (x, z) -> seed(x, z, parallelSeed));
                if (raster != null && job != null) job.painted(key);
            } catch (IOException error) {
                if (job != null && job.exhausted && job.pendingPaint.contains(key)) job.failed = true;
                throw error;
            } finally {
                renderBusy.decrementAndGet();
                rendering.remove(key);
            }
        });
        return true;
    }

    private void saveObservation(TerrainAtlas.Observation surface) throws IOException {
        if (!atlas.put(surface)) return;
        surfaceRevision.incrementAndGet();
        if (changedTiles.size() < SAMPLE_QUEUE_LIMIT) {
            changedTiles.addAll(TerrainAtlas.affected(surface.x(), surface.z()));
        }
    }

    private boolean prepareSeed(Key key, long deadline) {
        int side = TerrainAtlas.PIXELS / 4;
        for (int z = -1; z < side; z++) {
            for (int x = -1; x < side; x++) {
                int cx = clamp(key.minX() * 4 + x);
                int cz = clamp(key.minZ() * 4 + z);
                if (seed(cx, cz, false) == null) {
                    if (System.nanoTime() >= deadline) return false;
                    seed(cx, cz, true);
                }
            }
        }
        return true;
    }

    private static int clamp(int v) { return Math.max(-TerrainAtlas.WORLD_LIMIT * 4, Math.min(TerrainAtlas.WORLD_LIMIT * 4 - 1, v)); }

    private CityMapSnapshot.Tile seed(int x, int z, boolean allowed) {
        long key = ChunkPos.toLong(x, z);
        synchronized (seedCache) {
            var known = seedCache.get(key);
            if (known != null || !allowed) return known;
        }
        var sample = generator.sample(x, z);
        synchronized (seedCache) {
            seedCache.put(key, sample);
            if (seedCache.size() > 16384) seedCache.remove(seedCache.keySet().iterator().next());
        }
        return sample;
    }

    @FunctionalInterface
    private interface Work { void run() throws Exception; }

    private void submit(Work work) {
        busy.incrementAndGet();
        workers.execute(() -> {
            try {
                work.run();
            } catch (Exception error) {
                if (!closed) LivingCityMod.LOGGER.warn("LivingCity terrain job failed", error);
            } finally {
                busy.decrementAndGet();
            }
        });
    }

    private static long first(LinkedHashSet<Long> keys) {
        var iterator = keys.iterator();
        long result = iterator.next();
        iterator.remove();
        return result;
    }

    private void close() {
        closed = true;
        cancelImport();
        for (var sample : toSave.values()) submit(() -> atlas.put(sample));
        toSave.clear();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(2500, TimeUnit.MILLISECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class ImportJob {
        private final ChunkPos center;
        private final AtomicInteger scanned = new AtomicInteger(), saved = new AtomicInteger(), skipped = new AtomicInteger();
        private final AtomicInteger painted = new AtomicInteger();
        private final Set<Key> pendingPaint = ConcurrentHashMap.newKeySet();
        private final AtomicInteger jobs = new AtomicInteger();
        private volatile SavedMapTerrain reader;
        private volatile boolean cancelled, exhausted, failed;

        ImportJob(ChunkPos center) { this.center = center; }

        ImportState status() {
            boolean reading = !exhausted || jobs.get() > 0;
            int pending = pendingPaint.size();
            boolean active = !cancelled && !failed && (reading || pending > 0);
            String state = failed ? "failed" : cancelled ? "cancelled" : !active ? "done"
                    : reader == null ? "index" : reading ? "reading" : "painting";
            return new ImportState(active, scanned.get(), saved.get(), skipped.get(), painted.get(), pending, state);
        }

        boolean dispatch(long deadline) {
            if (cancelled || failed) return false;
            if (exhausted) {
                return jobs.get() == 0 && renderBusy.get() < settings.seedWorkers() && paint(deadline);
            }
            if (reader == null && jobs.get() != 0) return false;
            jobs.incrementAndGet();
            submit(() -> {
                try {
                    if (reader == null) {
                        reader = new SavedMapTerrain(regions, center);
                        return;
                    }
                    var batch = reader.take(6);
                    if (batch.isEmpty()) exhausted = true;
                    var storage = world.getChunkManager().threadedAnvilChunkStorage;
                    for (ChunkPos coordinate : batch) {
                        if (cancelled || closed) break;
                        TerrainAtlas.Observation surface;
                        try {
                            var tag = storage.getNbt(coordinate).get(2, TimeUnit.SECONDS).orElse(null);
                            surface = tag == null ? null : SavedMapTerrain.sample(coordinate, tag, generator.height.getHeight());
                        } catch (Exception invalidChunk) {
                            surface = null;
                        }
                        if (surface == null) skipped.incrementAndGet();
                        else {
                            if (atlas.put(surface)) surfaceRevision.incrementAndGet();
                            pendingPaint.addAll(TerrainAtlas.affected(surface.x(), surface.z()));
                            saved.incrementAndGet();
                        }
                        scanned.incrementAndGet();
                    }
                } catch (IOException failure) {
                    failed = true;
                    throw failure;
                } finally {
                    jobs.decrementAndGet();
                }
            });
            return true;
        }

        private boolean paint(long deadline) {
            var pending = pendingPaint.iterator();
            for (int i = 0; i < 24 && pending.hasNext(); i++) {
                Key key = pending.next();
                if (atlas.ready(key) != null) painted(key);
                else if (!rendering.contains(key)) return render(key, deadline);
            }
            return false;
        }

        private void painted(Key key) {
            if (exhausted && jobs.get() == 0 && pendingPaint.remove(key)) painted.incrementAndGet();
        }
    }
}
