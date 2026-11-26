package com.livingcity.data;

import com.livingcity.data.CityBlockChanges.Change;
import com.livingcity.stage.CityBlueprint;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Surveys a local surface and prepares a resumable, guarded set of world edits. */
final class CityTerrain {
    static final int MAX_GRADE = 3;
    private static final int RADIUS = 4;
    private static final int MAX_BLEND_CHANGE = 2;

    /** Older in-progress plans have only the combined terrain and structure slice. */
    record Plan(List<Long> chunks, CityBlockChanges terrain, CityBlockChanges structure) {
        NbtCompound write() {
            NbtCompound tag = terrain.write();
            tag.putLongArray("chunks", chunks.stream().mapToLong(Long::longValue).toArray());
            if (structure != null) tag.put("structure", structure.write());
            return tag;
        }

        static Plan read(NbtCompound tag) {
            var structure = tag.contains("structure", 10) ? CityBlockChanges.read(tag.getCompound("structure")) : null;
            return new Plan(Arrays.stream(tag.getLongArray("chunks")).boxed().toList(), CityBlockChanges.read(tag), structure);
        }
    }

    record Survey(CityPlot plot, CityPlot area, List<Long> chunks, int[] heights, BlockState[] surfaces,
                  boolean[] editable, int baseY) {
        BlockPos origin() { return new BlockPos(plot.x(), baseY, plot.z()); }
    }

    private CityTerrain() {}

    static Survey survey(ServerWorld world, CityPlot plot, Predicate<BlockPos> canChange) {
        var area = new CityPlot(plot.x() - RADIUS, plot.z() - RADIUS, plot.width() + 2 * RADIUS, plot.depth() + 2 * RADIUS);
        List<Long> chunks = area.chunkKeys();
        var loaded = new HashMap<Long, net.minecraft.world.chunk.WorldChunk>();
        for (long key : chunks) {
            var chunk = world.getChunkManager().getWorldChunk(CityData.xOf(key), CityData.zOf(key));
            if (chunk == null) return null;
            loaded.put(key, chunk);
        }
        int size = area.width() * area.depth();
        int[] heights = new int[size];
        BlockState[] surfaces = new BlockState[size];
        boolean[] editable = new boolean[size];
        int[] floor = new int[plot.width() * plot.depth()];
        int floors = 0;
        for (int i = 0; i < size; i++) {
            int x = area.x() + i % area.width(), z = area.z() + i / area.width();
            var chunk = loaded.get(CityData.xzKey(x >> 4, z >> 4));
            int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            BlockPos pos = new BlockPos(x, y, z);
            BlockState ground = chunk.getBlockState(pos);
            heights[i] = y;
            surfaces[i] = ground;
            editable[i] = natural(ground) && canChange.test(pos);
            if (plot.contains(x, z)) {
                if (!editable[i]) return null;
                floor[floors++] = y;
            }
        }
        Arrays.sort(floor);
        int baseY = floor[floor.length / 2];
        if (baseY - floor[0] > MAX_GRADE || floor[floor.length - 1] - baseY > MAX_GRADE) return null;
        return new Survey(plot, area, chunks, heights, surfaces, editable, baseY);
    }

    static Plan plan(ServerWorld world, Survey survey, CityBlueprint.Design blueprint, List<BlockPos> road) {
        CityPlot plot = survey.plot(), area = survey.area();
        int base = survey.baseY(), size = survey.heights().length;
        if (world.isOutOfHeightLimit(base - 1) || world.isOutOfHeightLimit(base + blueprint.top())) return null;
        Map<Long, Integer> roadHeights = new HashMap<>();
        for (BlockPos pos : road) roadHeights.put(CityData.xzKey(pos), pos.getY());
        int[] low = new int[size], high = new int[size], desired = new int[size];
        boolean[] roadColumn = new boolean[size];
        for (int i = 0; i < size; i++) {
            int x = area.x() + i % area.width(), z = area.z() + i / area.width();
            int old = survey.heights()[i];
            Integer roadY = roadHeights.get(CityData.xzKey(x, z));
            if (roadY != null) {
                low[i] = high[i] = desired[i] = roadY;
                roadColumn[i] = true;
                continue;
            }
            if (plot.contains(x, z)) {
                low[i] = high[i] = desired[i] = base;
                continue;
            }
            int dx = Math.max(0, Math.max(plot.x() - x, x - plot.maxX()));
            int dz = Math.max(0, Math.max(plot.z() - z, z - plot.maxZ()));
            double t = Math.max(0, 1 - Math.hypot(dx, dz) / RADIUS);
            if (!survey.editable()[i] || t == 0) {
                low[i] = high[i] = desired[i] = old;
            } else {
                double blend = t * t * (3 - 2 * t);
                low[i] = Math.max(old - MAX_BLEND_CHANGE, Math.min(old, base));
                high[i] = Math.min(old + MAX_BLEND_CHANGE, Math.max(old, base));
                desired[i] = (int) Math.round(old + blend * (base - old));
            }
        }
        int[] target = constrain(survey.heights(), low, high, desired, area.width());
        if (target == null) return null;

        Map<BlockPos, Change> changes = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            if (roadColumn[i]) continue;
            int x = area.x() + i % area.width(), z = area.z() + i / area.width();
            int old = survey.heights()[i], floor = target[i];
            if (old != floor && !grade(world, changes, new BlockPos(x, old, z), floor, survey.surfaces()[i])) return null;
            if (plot.contains(x, z)) {
                for (int y = floor + 1; y <= Math.min(floor + blueprint.top(), floor + 2); y++) {
                    BlockPos plant = new BlockPos(x, y, z);
                    BlockState state = after(world, changes, plant);
                    if (!state.isAir() && CityBlueprint.replaceable(state)) set(world, changes, plant, Blocks.AIR.getDefaultState());
                }
            }
        }
        Map<BlockPos, Change> structure = new LinkedHashMap<>();
        for (var cell : blueprint.cells()) {
            BlockPos pos = survey.origin().add(cell.offset());
            BlockState state = after(world, changes, pos);
            if (!CityBlueprint.replaceable(state) && !(cell.offset().getY() == 0 && natural(state))) return null;
            structure.put(pos, new Change(pos, state, cell.state()));
        }
        return new Plan(survey.chunks(), actualChanges(changes), actualChanges(structure));
    }

    private static CityBlockChanges actualChanges(Map<BlockPos, Change> input) {
        return new CityBlockChanges(input.values().stream().filter(e -> !e.before().equals(e.after())).toList());
    }

    /**
     * Keep the fixed boundaries and keep each local step at most one block or the original
     * height difference, whichever is greater. Relax the intervals before choosing a height.
     */
    static int[] constrain(int[] original, int[] low, int[] high, int[] desired, int width) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] queued = new boolean[original.length];
        for (int i = 0; i < original.length; i++) queue.add(i);
        Arrays.fill(queued, true);
        while (!queue.isEmpty()) {
            int i = queue.remove();
            queued[i] = false;
            for (int n : neighbors(i, width, original.length)) {
                int step = Math.max(1, Math.abs(original[n] - original[i]));
                int min = Math.max(low[n], low[i] - step);
                int max = Math.min(high[n], high[i] + step);
                if (min > max) return null;
                if (min == low[n] && max == high[n]) continue;
                low[n] = min;
                high[n] = max;
                if (!queued[n]) { queue.add(n); queued[n] = true; }
            }
        }
        int[] heights = low.clone();
        for (int i = 0; i < original.length; i++) queue.add(i);
        Arrays.fill(queued, true);
        while (!queue.isEmpty()) {
            int i = queue.remove();
            queued[i] = false;
            int candidate = Math.min(high[i], desired[i]);
            int[] near = neighbors(i, width, original.length);
            for (int n : near) candidate = Math.min(candidate, heights[n] + Math.max(1, Math.abs(original[i] - original[n])));
            if (candidate <= heights[i]) continue;
            heights[i] = candidate;
            for (int n : near) {
                if (!queued[n]) { queue.add(n); queued[n] = true; }
            }
        }
        return heights;
    }

    private static int[] neighbors(int i, int width, int size) {
        int[] neighbors = new int[4];
        int n = 0;
        if (i % width != 0) neighbors[n++] = i - 1;
        if (i % width != width - 1) neighbors[n++] = i + 1;
        if (i >= width) neighbors[n++] = i - width;
        if (i + width < size) neighbors[n++] = i + width;
        return Arrays.copyOf(neighbors, n);
    }

    private static boolean grade(ServerWorld world, Map<BlockPos, Change> changes, BlockPos source, int floor, BlockState surface) {
        if (world.isOutOfHeightLimit(floor) || world.isOutOfHeightLimit(floor - 1)) return false;
        boolean lowering = source.getY() > floor;
        if (lowering) {
            if (!natural(world.getBlockState(source.withY(floor)))) return false;
            for (int y = source.getY(); y > floor; y--) {
                BlockPos pos = source.withY(y);
                BlockState state = world.getBlockState(pos);
                if (!natural(state) && !CityBlueprint.replaceable(state)) return false;
                set(world, changes, pos, Blocks.AIR.getDefaultState());
            }
        } else {
            for (int y = source.getY() + 1; y <= floor; y++) {
                BlockPos pos = source.withY(y);
                if (!CityBlueprint.replaceable(world.getBlockState(pos))) return false;
                set(world, changes, pos, Blocks.DIRT.getDefaultState());
            }
        }
        // Skip gravity blocks as fill caps so a shaping pass cannot start a collapse.
        BlockState cover = surface.isOf(Blocks.SAND) || surface.isOf(Blocks.GRAVEL) ? Blocks.DIRT.getDefaultState() : surface;
        set(world, changes, source.withY(floor), cover);
        return true;
    }

    private static BlockState after(ServerWorld world, Map<BlockPos, Change> changes, BlockPos pos) {
        var change = changes.get(pos);
        return change == null ? world.getBlockState(pos) : change.after();
    }

    private static void set(ServerWorld world, Map<BlockPos, Change> changes, BlockPos pos, BlockState state) {
        var previous = changes.get(pos);
        changes.put(pos, new Change(pos, previous == null ? world.getBlockState(pos) : previous.before(), state));
    }

    static boolean natural(BlockState state) {
        return state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.SAND) || state.isOf(Blocks.STONE) || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRANITE);
    }
}
