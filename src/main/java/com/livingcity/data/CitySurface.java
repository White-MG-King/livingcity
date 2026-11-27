package com.livingcity.data;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

final class CitySurface {
    private static final int MAX_TRUNK_HEIGHT = 32;

    record Column(int groundY, int trunkTop) {
        boolean tree() { return trunkTop > groundY; }
    }

    private CitySurface() {}

    static Column constructionColumn(WorldChunk chunk, int x, int z) {
        int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        BlockPos top = new BlockPos(x, y, z);
        BlockState log = chunk.getBlockState(top);
        if (!log.isIn(BlockTags.OVERWORLD_NATURAL_LOGS)) return new Column(y, y);

        int bottom = y;
        while (bottom > chunk.getBottomY() && y - bottom < MAX_TRUNK_HEIGHT) {
            BlockState under = chunk.getBlockState(top.withY(bottom - 1));
            if (!under.isOf(log.getBlock())) break;
            bottom--;
        }
        if (bottom > chunk.getBottomY()) {
            BlockState soil = chunk.getBlockState(top.withY(bottom - 1));
            if (soil.isIn(BlockTags.DIRT) && soil.getFluidState().isEmpty() && hasCanopy(chunk, top)) {
                return new Column(bottom - 1, y);
            }
        }
        return new Column(y, y);
    }

    private static boolean hasCanopy(WorldChunk chunk, BlockPos top) {
        var pos = new BlockPos.Mutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = top.getX() + dx, z = top.getZ() + dz;
                if ((x >> 4) != chunk.getPos().x || (z >> 4) != chunk.getPos().z) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    BlockState leaf = chunk.getBlockState(pos.set(x, top.getY() + dy, z));
                    if (naturalLeaves(leaf) && leaf.get(LeavesBlock.DISTANCE) <= 3) return true;
                }
            }
        }
        return false;
    }

    static boolean naturalLeaves(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.get(LeavesBlock.PERSISTENT)
                && state.getFluidState().isEmpty();
    }

    static boolean hasDrySurface(WorldChunk chunk) {
        var pos = new BlockPos.Mutable();
        for (int z = 2; z < 16; z += 4) {
            for (int x = 2; x < 16; x += 4) {
                int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState state = chunk.getBlockState(pos.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z));
                if (state.getFluidState().isEmpty() && state.blocksMovement()
                        && !state.isIn(BlockTags.OVERWORLD_NATURAL_LOGS)) return true;
            }
        }
        return false;
    }
}
