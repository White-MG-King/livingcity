package com.livingcity.data;

import citysim.CityFrontier;
import citysim.WarSimulation;
import citysim.WarTerrain;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.util.Collection;
import java.util.LinkedHashMap;

/** Supplies march terrain without generating Minecraft chunks or holding chunk tickets. */
final class CityCampaignWorld implements WarSimulation.World {
    private static final int SAMPLES_PER_CYCLE = 6;
    private static final int CACHE_LIMIT = 16_384;
    private final CityRegistry registry;
    private final LinkedHashMap<Long, WarTerrain.Ground> cache = new LinkedHashMap<>(256, 0.75f, true);
    private ServerWorld world;
    private int samples;
    private long deadline;

    CityCampaignWorld(CityRegistry registry) { this.registry = registry; }

    void begin(ServerWorld world) {
        this.world = world;
        samples = 0;
        deadline = 0;
    }

    @Override public Collection<WarSimulation.Town> towns() {
        return registry.cities().stream().filter(CityData::isFounded).map(city -> {
            BlockPos hall = city.townHallPos();
            return new WarSimulation.Town(city.id, city.nationId(), CityFrontier.key(hall.getX() >> 4, hall.getZ() >> 4),
                    city.land.perimeter, city.harness().city);
        }).toList();
    }

    @Override public int cityAt(long key) {
        CityData owner = registry.owner(CityFrontier.x(key), CityFrontier.z(key));
        return owner == null || !owner.isFounded() ? 0 : owner.id;
    }

    @Override public int capital(int nation) { return registry.nations().capital(nation); }

    @Override public boolean transferCity(int city, int from, int to) {
        CityData data = registry.byId(city);
        return data != null && data.isFounded() && registry.nations().transferCity(registry, data, from, to);
    }

    @Override public WarTerrain.Ground ground(long key) {
        var known = cache.get(key);
        if (known != null) return known;
        int cx = CityFrontier.x(key), cz = CityFrontier.z(key);
        if (!world.getWorldBorder().contains(new ChunkPos(cx, cz))) return WarTerrain.BLOCKED;
        if (samples == 0) deadline = System.nanoTime() + 3_000_000;
        if (samples >= SAMPLES_PER_CYCLE || System.nanoTime() >= deadline) return WarTerrain.UNKNOWN;
        samples++;
        var manager = world.getChunkManager();
        var loaded = manager.getWorldChunk(cx, cz);
        var terrain = WarTerrain.sample(index -> {
            int x = (index & 3) * 4 + 2, z = (index >> 2) * 4 + 2;
            int wx = cx * 16 + x, wz = cz * 16 + z;
            if (loaded != null) {
                int y = Math.max(world.getBottomY(), loaded.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
                var biome = loaded.getBiomeForNoiseGen(wx >> 2, y >> 2, wz >> 2);
                var fluid = loaded.getFluidState(new BlockPos(wx, y, wz));
                return new WarTerrain.Sample(y, biome.getKey().map(k -> k.getValue().getPath()).orElse("unknown"),
                        !fluid.isEmpty());
            }
            int y = manager.getChunkGenerator().getHeight(wx, wz, Heightmap.Type.WORLD_SURFACE_WG,
                    world, manager.getNoiseConfig()) - 1;
            var biome = world.getGeneratorStoredBiome(wx >> 2, y >> 2, wz >> 2);
            boolean wet = biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_RIVER);
            if (!wet && y <= world.getSeaLevel()) {
                int floor = manager.getChunkGenerator().getHeight(wx, wz, Heightmap.Type.OCEAN_FLOOR_WG,
                        world, manager.getNoiseConfig()) - 1;
                wet = floor < y;
            }
            return new WarTerrain.Sample(y, biome.getKey().map(k -> k.getValue().getPath()).orElse("unknown"), wet);
        });
        cache.put(key, terrain);
        if (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        return terrain;
    }

    void invalidate(int x, int z) { cache.remove(CityFrontier.key(x, z)); }
}
