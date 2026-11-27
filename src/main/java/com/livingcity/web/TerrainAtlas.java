package com.livingcity.web;

import com.google.gson.Gson;
import com.livingcity.data.CityMapSnapshot.Tile;
import citysim.SeaLanes;
import net.minecraft.util.math.ChunkPos;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Disk access and raster work run in the atlas worker pool. Readers only access published memory entries. */
final class TerrainAtlas {
    static final int WORLD_LIMIT = 1_875_000;
    static final int MAX_STEP = 1;
    static final int CHUNKS = 8;
    static final int PIXELS = CHUNKS * 16;
    private static final int SUMMARY_SIDE = PIXELS / 4;
    private static final Gson JSON = new Gson();
    private static final int OBSERVATION_CACHE = 1024;
    private static final int IMAGE_CACHE = 1024;

    record Key(int x, int z, int step) {
        int span() { return CHUNKS * step; }
        int minX() { return x * span(); }
        int minZ() { return z * span(); }
        boolean insideWorld() {
            long left = (long) x * span(), top = (long) z * span();
            return left < WORLD_LIMIT && left + span() > -WORLD_LIMIT
                    && top < WORLD_LIMIT && top + span() > -WORLD_LIMIT;
        }
        static Key at(int chunkX, int chunkZ, int step) {
            return new Key(Math.floorDiv(chunkX, CHUNKS * step), Math.floorDiv(chunkZ, CHUNKS * step), step);
        }
    }
    record Observation(int x, int z, long tick, boolean live, List<Tile> tiles, List<Integer> waterRegions) {
        Observation {
            tiles = List.copyOf(tiles);
            waterRegions = waterRegions == null ? List.of() : List.copyOf(waterRegions);
        }
        Observation(int x, int z, long tick, boolean live, List<Tile> tiles) {
            this(x, z, tick, live, tiles, List.of());
        }
        Tile at(int blockX, int blockZ) { return tiles.get((blockZ & 15) * 16 + (blockX & 15)); }
        int waterAreaAt(int blockX, int blockZ) {
            int bit = 1 << (((blockZ & 15) >> 2) * 4 + ((blockX & 15) >> 2));
            for (int region : waterRegions) if ((region & bit) != 0) return Integer.bitCount(region);
            return 0;
        }
        Observation withNavigation() {
            int water = 0;
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) if (at(x * 4 + 2, z * 4 + 2).water()) water |= 1 << (z * 4 + x);
            }
            return new Observation(x, z, tick, live, tiles, SeaLanes.localWaterRegions(water));
        }
    }
    record Pixel(Tile tile, boolean observed) {}
    private static final int RENDER_FORMAT = 2;
    record Raster(String version, Pixel[] pixels, byte[] png, int format) {
        Pixel pixel(Key key, int blockX, int blockZ) {
            int x = Math.floorDiv(blockX - (key.minX() << 4), 4);
            int z = Math.floorDiv(blockZ - (key.minZ() << 4), 4);
            return x < 0 || z < 0 || x >= SUMMARY_SIDE || z >= SUMMARY_SIDE ? null : pixels[z * SUMMARY_SIDE + x];
        }
    }
    static final class RenderJob {
        boolean stale;
    }

    private final Object io = new Object();
    private final Path directory;
    private final Map<Key, Raster> images = new LinkedHashMap<>(256, .75f, true);
    private final Map<Long, Optional<Observation>> observed = new LinkedHashMap<>(256, .75f, true);
    private final Map<Key, RenderJob> rendering = new LinkedHashMap<>();

    TerrainAtlas(Path directory) { this.directory = directory; }

    Raster ready(Key key) {
        synchronized (images) { return images.get(key); }
    }

    Observation known(int x, int z) {
        synchronized (observed) { return observed.getOrDefault(ChunkPos.toLong(x, z), Optional.empty()).orElse(null); }
    }

    boolean put(Observation surface) throws IOException {
        surface = surface.withNavigation();
        synchronized (io) {
            var old = observation(surface.x, surface.z);
            if (old != null && (surface.tick < old.tick || (!surface.live && old.live && surface.tick == old.tick)
                    || old.tiles.equals(surface.tiles))) return false;
            // North and north-west samples also shade the tile immediately south/east of an edited chunk.
            for (Key key : affected(surface.x, surface.z)) {
                RenderJob job = rendering.get(key);
                if (job != null) job.stale = true;
                synchronized (images) { images.remove(key); }
                Files.deleteIfExists(rasterPath(key));
            }
            write(surfacePath(surface.x, surface.z), surface);
            rememberObservation(surface.x, surface.z, surface);
            return true;
        }
    }

    Raster render(Key key, BiFunction<Integer, Integer, Tile> seed) throws IOException {
        RenderJob job = new RenderJob();
        synchronized (io) {
            Raster cached = read(rasterPath(key), Raster.class);
            if (cached != null && cached.format() == RENDER_FORMAT) {
                rememberImage(key, cached);
                return cached;
            }
            rendering.put(key, job);
        }
        try {
            // The four-block halo supplies the very same neighbours on both sides of a tile boundary.
            int stride = PIXELS + 4;
            Pixel[] pixels = new Pixel[stride * stride];
            Map<Long, Pixel> estimates = new LinkedHashMap<>();
            boolean complete = true;
            Map<Long, Observation> chunkMemo = new LinkedHashMap<>();
            for (int z = -4; z < PIXELS; z++) {
                for (int x = -4; x < PIXELS; x++) {
                    int blockX = (key.minX() << 4) + x;
                    int blockZ = (key.minZ() << 4) + z;
                    int chunkX = blockX >> 4, chunkZ = blockZ >> 4;
                    if (chunkX < -WORLD_LIMIT || chunkX >= WORLD_LIMIT || chunkZ < -WORLD_LIMIT || chunkZ >= WORLD_LIMIT) continue;
                    long chunk = ChunkPos.toLong(chunkX, chunkZ);
                    if (!chunkMemo.containsKey(chunk)) chunkMemo.put(chunk, observation(chunkX, chunkZ));
                    Observation surface = chunkMemo.get(chunk);
                    if (surface != null) {
                        pixels[(z + 4) * stride + x + 4] = new Pixel(surface.at(blockX, blockZ), true);
                        continue;
                    }
                    int quartX = blockX >> 2, quartZ = blockZ >> 2;
                    long cell = ChunkPos.toLong(quartX, quartZ);
                    if (!estimates.containsKey(cell)) {
                        Tile estimate = seed.apply(quartX, quartZ);
                        estimates.put(cell, estimate == null ? null : new Pixel(estimate, false));
                    }
                    Pixel sample = estimates.get(cell);
                    if (sample == null) complete = false;
                    else pixels[(z + 4) * stride + x + 4] = sample;
                }
            }
            if (!complete) return null;
            Pixel[] summary = new Pixel[SUMMARY_SIDE * SUMMARY_SIDE];
            for (int z = 0; z < SUMMARY_SIDE; z++) {
                for (int x = 0; x < SUMMARY_SIDE; x++) summary[z * SUMMARY_SIDE + x] = pixels[(z * 4 + 6) * stride + x * 4 + 6];
            }
            Raster raster = new Raster(UUID.randomUUID().toString(), summary, paint(pixels), RENDER_FORMAT);
            synchronized (io) {
                if (job.stale) return null;
                write(rasterPath(key), raster);
                rememberImage(key, raster);
            }
            return raster;
        } finally {
            synchronized (io) { rendering.remove(key, job); }
        }
    }

    static java.util.Set<Key> affected(int chunkX, int chunkZ) {
        return java.util.Set.copyOf(List.of(Key.at(chunkX, chunkZ, 1), Key.at(chunkX + 1, chunkZ, 1),
                Key.at(chunkX, chunkZ + 1, 1), Key.at(chunkX + 1, chunkZ + 1, 1)));
    }

    Observation observation(int x, int z) throws IOException {
        long key = ChunkPos.toLong(x, z);
        synchronized (observed) {
            var cached = observed.get(key);
            if (cached != null) return cached.orElse(null);
        }
        synchronized (io) {
            synchronized (observed) {
                var cached = observed.get(key);
                if (cached != null) return cached.orElse(null);
            }
            Observation result = read(surfacePath(x, z), Observation.class);
            rememberObservation(x, z, result);
            return result;
        }
    }

    private void rememberObservation(int x, int z, Observation surface) {
        synchronized (observed) {
            observed.put(ChunkPos.toLong(x, z), Optional.ofNullable(surface));
            if (observed.size() > OBSERVATION_CACHE) observed.remove(observed.keySet().iterator().next());
        }
    }

    private void rememberImage(Key key, Raster image) {
        synchronized (images) {
            images.put(key, image);
            if (images.size() > IMAGE_CACHE) images.remove(images.keySet().iterator().next());
        }
    }

    private Path surfacePath(int x, int z) {
        return directory.resolve("surface").resolve((x >> 5) + "." + (z >> 5))
                .resolve(x + "." + z + ".json.gz");
    }

    private Path rasterPath(Key key) {
        return directory.resolve("tiles").resolve(Integer.toString(key.step)).resolve((key.x >> 5) + "." + (key.z >> 5))
                .resolve(key.x + "." + key.z + ".json.gz");
    }

    private static <T> T read(Path path, Class<T> type) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        try (var in = new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8)) {
            return JSON.fromJson(in, type);
        } catch (IOException | RuntimeException invalidCache) {
            Files.deleteIfExists(path);
            return null;
        }
    }

    static void replace(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "livingcity-", ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void write(Path path, Object value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
            JSON.toJson(value, out);
        }
        replace(path, bytes.toByteArray());
    }

    private static byte[] paint(Pixel[] pixels) throws IOException {
        var image = new BufferedImage(PIXELS, PIXELS, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < PIXELS; z++) {
            for (int x = 0; x < PIXELS; x++) {
                int stride = PIXELS + 4;
                Pixel pixel = pixels[(z + 4) * stride + x + 4];
                if (pixel == null) continue;
                Tile tile = pixel.tile;
                int delta = pixel.observed ? 1 : 4;
                int north = height(pixels, x, z - delta, tile.y());
                int northwest = height(pixels, x - delta, z - delta, tile.y());
                int shade = 18 * (Integer.compare(tile.y(), north) + Integer.compare(tile.y(), northwest));
                if (tile.water() || tile.lava()) shade /= 4;
                int base = color(tile);
                int red = (base >> 16) & 255, green = (base >> 8) & 255, blue = base & 255;
                if (!pixel.observed) {
                    red = (red * 4 + 236) / 5;
                    green = (green * 4 + 238) / 5;
                    blue = (blue * 4 + 228) / 5;
                }
                image.setRGB(x, z, 0xff000000 | (channel(red + shade) << 16)
                        | (channel(green + shade) << 8) | channel(blue + shade));
            }
        }
        var png = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(png)) {
            ImageIO.write(image, "png", output);
        }
        return png.toByteArray();
    }

    private static int height(Pixel[] pixels, int x, int z, int fallback) {
        Pixel sample = pixels[(z + 4) * (PIXELS + 4) + x + 4];
        return sample == null ? fallback : sample.tile.y();
    }

    private static int channel(int v) { return Math.max(0, Math.min(255, v)); }

    private static int color(Tile tile) {
        if (tile.lava()) return 0xbd6743;
        if (tile.water()) {
            if (tile.waterDepth() == null) return 0x7fa8bc;
            return tile.waterDepth() <= 5 ? 0x8ac6d9 : tile.waterDepth() <= 10 ? 0x4c91b7 : 0x285a8b;
        }
        if (tile.color() != null && tile.color() != 0) return tile.color();
        String material = tile.material();
        if (material != null) {
            if (material.endsWith("_leaves")) return 0x77966f;
            if (material.startsWith("snow") || material.endsWith("ice")) return 0xdce6e6;
            if (material.endsWith("sand") || material.endsWith("sandstone")) return 0xdecca1;
            if (material.contains("stone") || material.equals("gravel") || material.equals("deepslate")) return 0xb8b5a7;
            if (material.endsWith("planks") || material.endsWith("_log")) return 0xb18d5e;
            if (material.equals("grass_block") || material.equals("moss_block")) return 0xb9ca94;
            if (material.contains("dirt") || material.equals("podzol")) return 0xbcad8a;
        }
        String biome = tile.biome();
        if (biome.contains("snow") || biome.contains("frozen")) return 0xdbe4e5;
        if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) return 0x98ad8b;
        if (biome.contains("desert") || biome.contains("badlands") || biome.contains("beach")) return 0xdcc99e;
        return 0xc9d3a3;
    }
}
