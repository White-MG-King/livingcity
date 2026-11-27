package com.livingcity.data;

import com.livingcity.stage.CityBlueprint;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Only loaded terrain can be paved. Unloaded, previously surveyed roads remain recorded. */
final class CityRoads {
    record Access(BlockPos entrance, List<BlockPos> path) {}
    private record Search(BlockPos pos, Search towardEntrance, int length, int estimate) {}
    private static final int SEARCH_LIMIT = 768;
    private static final int MAX_LENGTH = 64;
    private static final int MAX_FILL = 2;

    private CityRoads() {}

    static Set<BlockPos> connected(Collection<List<BlockPos>> roadChunks, BlockPos hall) {
        Set<BlockPos> remaining = new HashSet<>();
        for (var chunk : roadChunks) remaining.addAll(chunk);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos road : remaining) if (hallEntrance(road, hall)) queue.add(road);
        Set<BlockPos> reachable = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            BlockPos road = queue.remove();
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos next = road.offset(direction);
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = next.add(0, dy, 0);
                    if (remaining.contains(candidate) && reachable.add(candidate)) queue.add(candidate);
                }
            }
        }
        return reachable;
    }

    static boolean hallEntrance(BlockPos road, BlockPos hall) {
        return Math.abs(road.getX() - hall.getX()) + Math.abs(road.getZ() - hall.getZ()) == 1
                && Math.abs(road.getY() - (hall.getY() - 1)) <= 1;
    }

    static Access plan(ServerWorld world, CityData city, BlockPos base, CityBlueprint.Design design, CityPlot plot,
                       Set<BlockPos> connected, Long approach, Long lotChunk) {
        BlockPos center = base.add(design.width() / 2, 0, design.depth() / 2);
        List<BlockPos> destinations = new ArrayList<>(connected.stream()
                .filter(p -> distance(p, center) <= MAX_LENGTH && world.isChunkLoaded(p))
                .sorted(Comparator.comparingInt((BlockPos p) -> distance(p, center)).thenComparingLong(BlockPos::asLong))
                .limit(12).toList());
        destinations.add(city.townHallPos());
        PriorityQueue<Search> queue = new PriorityQueue<>(Comparator.comparingInt(Search::estimate)
                .thenComparing(Comparator.comparingInt(Search::length).reversed()).thenComparingLong(step -> step.pos().asLong()));
        Map<BlockPos, Integer> seen = new HashMap<>();
        Map<Long, Optional<BlockPos>> surfaces = new HashMap<>();
        BlockPos entrance = base.add(design.entrance());
        BlockPos outside = entrance.offset(design.facing());
        surface(world, city, outside, plot, surfaces, approach, lotChunk)
                .flatMap(p -> grade(world, city, p, entrance.getY(), approach, lotChunk))
                .ifPresent(p -> {
                    seen.put(p, 1);
                    queue.add(new Search(p, null, 1, 1 + estimate(p, destinations)));
                });
        for (int visited = 0; visited < SEARCH_LIMIT && !queue.isEmpty(); visited++) {
            Search step = queue.remove();
            BlockPos road = step.pos();
            if (step.length() != seen.get(road)) continue;
            if (hallEntrance(road, city.townHallPos())
                    || (connected.contains(road) && world.getBlockState(road).isOf(Blocks.DIRT_PATH))) {
                List<BlockPos> path = new ArrayList<>();
                Set<Long> columns = new HashSet<>();
                for (Search n = step; n != null; n = n.towardEntrance()) {
                    if (!columns.add(CityData.xzKey(n.pos()))) break;
                    path.add(n.pos());
                }
                if (path.size() == step.length()) {
                    return new Access(entrance, List.copyOf(path));
                }
            }
            if (step.length() == MAX_LENGTH) continue;
            for (Direction direction : Direction.Type.HORIZONTAL) {
                surface(world, city, road.offset(direction), plot, surfaces, approach, lotChunk)
                        .flatMap(p -> grade(world, city, p, road.getY(), approach, lotChunk))
                        .ifPresent(p -> {
                            int length = step.length() + 1;
                            if (length < seen.getOrDefault(p, Integer.MAX_VALUE)) {
                                seen.put(p, length);
                                queue.add(new Search(p, step, length,
                                        length + estimate(p, destinations)));
                            }
                        });
            }
        }
        return null;
    }

    private static Optional<BlockPos> grade(ServerWorld world, CityData city, BlockPos surface, int previousY, Long approach, Long lotChunk) {
        int y = Math.max(surface.getY(), previousY - 1);
        if (y > previousY + 1 || y - surface.getY() > MAX_FILL) return Optional.empty();
        BlockPos candidate = surface.withY(y);
        return candidate.equals(surface) || fillDepth(world, city, candidate, approach, lotChunk) >= 0
                ? Optional.of(candidate) : Optional.empty();
    }

    private static int distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static int estimate(BlockPos origin, List<BlockPos> destinations) {
        int nearest = Integer.MAX_VALUE;
        for (BlockPos target : destinations) nearest = Math.min(nearest, distance(origin, target));
        return nearest;
    }

    private static Optional<BlockPos> surface(ServerWorld world, CityData city, BlockPos p, CityPlot lot,
                                               Map<Long, Optional<BlockPos>> surfaces, Long approach, Long lotChunk) {
        return surfaces.computeIfAbsent(CityData.xzKey(p), key -> {
            int cx = p.getX() >> 4, cz = p.getZ() >> 4;
            if (lot.contains(p) || !available(city, p, approach, lotChunk)) {
                return Optional.empty();
            }
            var chunk = world.getChunkManager().getWorldChunk(cx, cz);
            if (chunk == null) return Optional.empty();
            int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX() & 15, p.getZ() & 15);
            BlockPos ground = p.withY(y);
            return fillDepth(world, city, ground, approach, lotChunk) == 0 ? Optional.of(ground) : Optional.empty();
        });
    }

    private static boolean available(CityData city, BlockPos ground, Long approach, Long lotChunk) {
        CityData owner = city.registry.owner(ground);
        long key = CityData.xzKey(ground.getX() >> 4, ground.getZ() >> 4);
        return owner == city || (owner == null && (approach != null && key == approach || lotChunk != null && key == lotChunk));
    }

    static int fillDepth(ServerWorld world, CityData city, BlockPos ground) {
        return fillDepth(world, city, ground, null, null);
    }

    private static boolean clearable(BlockState state) {
        return CityBlueprint.replaceable(state) || CitySurface.naturalLeaves(state);
    }

    private static int fillDepth(ServerWorld world, CityData city, BlockPos ground, Long approach, Long lotChunk) {
        if (!world.isChunkLoaded(ground) || !available(city, ground, approach, lotChunk)) return -1;
        if (city.land.occupied(ground, city) && !world.getBlockState(ground).isOf(Blocks.DIRT_PATH)) return -1;
        BlockPos hall = city.townHallPos();
        if (hall.getX() == ground.getX() && hall.getZ() == ground.getZ()) return -1;
        if (!clearable(world.getBlockState(ground.up()))
                || !clearable(world.getBlockState(ground.up(2)))) return -1;
        for (int depth = 0; depth <= MAX_FILL; depth++) {
            var state = world.getBlockState(ground.down(depth));
            if (state.isOf(Blocks.DIRT_PATH)) return depth == 0 ? 0 : -1;
            if (CityTerrain.natural(state)) return depth;
            if (!CityBlueprint.replaceable(state)) return -1;
        }
        return -1;
    }
}
