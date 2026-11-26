package com.livingcity.data;

import com.livingcity.block.LivingCityBlocks;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/** Land surveys never request a chunk ticket. */
final class ColonyTerrain {
    private static final int[] CENTERS = {8, 4, 12};

    private ColonyTerrain() {}

    static boolean canTravel(ServerWorld world, long key) {
        int cx = CityData.xOf(key), cz = CityData.zOf(key);
        if (!world.getWorldBorder().contains(new ChunkPos(cx, cz))) return false;
        WorldChunk loaded = world.getChunkManager().getWorldChunk(cx, cz);
        if (loaded != null) {
            for (int offset : CENTERS) {
                if (drySurface(loaded, cx * 16 + offset, cz * 16 + 8)
                        || drySurface(loaded, cx * 16 + 8, cz * 16 + offset)) return true;
            }
            return false;
        }
        var biome = world.getGeneratorStoredBiome(cx * 4 + 2, world.getSeaLevel() >> 2, cz * 4 + 2);
        return !biome.isIn(BiomeTags.IS_OCEAN) && !biome.isIn(BiomeTags.IS_RIVER);
    }

    static BlockPos survey(ServerWorld world, WorldChunk chunk) {
        for (int dx : CENTERS) {
            for (int dz : CENTERS) {
                int x = chunk.getPos().getStartX() + dx, z = chunk.getPos().getStartZ() + dz;
                BlockPos hall = new BlockPos(x, surface(chunk, x, z) + 1, z);
                if (safeHall(world, chunk, hall)) return hall;
            }
        }
        return null;
    }

    static boolean safeHall(ServerWorld world, WorldChunk chunk, BlockPos hall) {
        if (world.isOutOfHeightLimit(hall.getY() + 1) || !world.getWorldBorder().contains(hall)) return false;
        var current = chunk.getBlockState(hall);
        if (!current.isAir() && !current.isOf(LivingCityBlocks.TOWN_HALL)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = hall.getX() + dx, z = hall.getZ() + dz;
                boolean center = dx == 0 && dz == 0;
                int y = center ? hall.getY() - 1 : surface(chunk, x, z);
                BlockPos floor = new BlockPos(x, y, z);
                if (!world.getWorldBorder().contains(floor) || Math.abs(hall.getY() - 1 - y) > 1
                        || !dryGround(chunk, floor)
                        || (!center && !chunk.getBlockState(floor.up()).isAir())
                        || !chunk.getBlockState(floor.up(2)).isAir()) return false;
            }
        }
        return true;
    }

    private static boolean drySurface(WorldChunk chunk, int x, int z) {
        return dryGround(chunk, new BlockPos(x, surface(chunk, x, z), z));
    }

    private static boolean dryGround(WorldChunk chunk, BlockPos floor) {
        var ground = chunk.getBlockState(floor);
        return ground.getFluidState().isEmpty() && CityTerrain.natural(ground);
    }

    private static int surface(WorldChunk chunk, int x, int z) {
        return chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
    }
}
