package com.livingcity.data;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Sparse, palette-encoded edits. The original and replacement states survive blueprint updates. */
record CityBlockChanges(List<Change> edits) {
    record Change(BlockPos pos, BlockState before, BlockState after) {}

    NbtCompound write() {
        NbtCompound tag = new NbtCompound();
        NbtList palette = new NbtList();
        Map<BlockState, Integer> states = new HashMap<>();
        long[] positions = new long[edits.size()];
        int[] materials = new int[edits.size() * 2];
        for (int i = 0; i < edits.size(); i++) {
            Change edit = edits.get(i);
            positions[i] = edit.pos().asLong();
            materials[2 * i] = stateId(edit.before(), palette, states);
            materials[2 * i + 1] = stateId(edit.after(), palette, states);
        }
        tag.put("palette", palette);
        tag.putLongArray("positions", positions);
        tag.putIntArray("materials", materials);
        return tag;
    }

    private static int stateId(BlockState state, NbtList palette, Map<BlockState, Integer> states) {
        return states.computeIfAbsent(state, key -> {
            int id = palette.size();
            palette.add(NbtHelper.fromBlockState(key));
            return id;
        });
    }

    static CityBlockChanges read(NbtCompound tag) {
        var states = tag.getList("palette", 10).stream().map(n -> NbtHelper.toBlockState(
                Registries.BLOCK.getReadOnlyWrapper(), (NbtCompound) n)).toList();
        var positions = tag.getLongArray("positions");
        var materials = tag.getIntArray("materials");
        if (materials.length != positions.length * 2) throw new IllegalArgumentException("Incomplete block changes");
        return new CityBlockChanges(IntStream.range(0, positions.length).mapToObj(i -> new Change(
                BlockPos.fromLong(positions[i]), states.get(materials[2 * i]), states.get(materials[2 * i + 1]))).toList());
    }
}
