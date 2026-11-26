package com.livingcity.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

record CityPlot(int x, int z, int width, int depth) {
    CityPlot {
        if (width < 1 || width > 40 || depth < 1 || depth > 40) {
            throw new IllegalArgumentException("Unsupported building footprint");
        }
    }

    static CityPlot wholeChunk(long chunk) {
        return new CityPlot(CityData.xOf(chunk) << 4, CityData.zOf(chunk) << 4, 16, 16);
    }

    int maxX() { return x + width - 1; }
    int maxZ() { return z + depth - 1; }

    boolean contains(BlockPos pos) { return contains(pos.getX(), pos.getZ()); }

    boolean contains(int blockX, int blockZ) {
        return blockX >= x && blockX <= maxX() && blockZ >= z && blockZ <= maxZ();
    }

    boolean intersects(CityPlot other) {
        return x <= other.maxX() && other.x <= maxX() && z <= other.maxZ() && other.z <= maxZ();
    }

    List<Long> chunkKeys() {
        List<Long> keys = new ArrayList<>();
        for (int cx = x >> 4; cx <= maxX() >> 4; cx++) {
            for (int cz = z >> 4; cz <= maxZ() >> 4; cz++) keys.add(CityData.xzKey(cx, cz));
        }
        return keys;
    }

    NbtCompound write() {
        NbtCompound value = new NbtCompound();
        value.putInt("x", x);
        value.putInt("z", z);
        value.putInt("width", width);
        value.putInt("depth", depth);
        return value;
    }

    static CityPlot read(NbtCompound value) {
        return new CityPlot(value.getInt("x"), value.getInt("z"), value.getInt("width"), value.getInt("depth"));
    }
}
