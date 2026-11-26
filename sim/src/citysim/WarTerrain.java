package citysim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** Chunk routes use matching openings in the connected 4×4 surface samples on each side. */
public final class WarTerrain {
    public static final int MAX_MARCH = 192;
    private static final int MAX_VISITS = 768;
    private static final int MAX_RISE = 6;
    private static final int[] DX = {-1, 0, 0, 1};
    private static final int[] DZ = {0, -1, 1, 0};
    private static final int[] CENTER_FIRST = {5, 6, 9, 10, 1, 2, 4, 7, 8, 11, 13, 14, 0, 3, 12, 15};
    private static final Set<String> IMPASSABLE = Set.of(
            "frozen_peaks", "jagged_peaks", "stony_peaks", "snowy_slopes", "eroded_badlands");
    public static final Ground BLOCKED = new Ground(0, new int[16], 0, 1);
    public static final Ground UNKNOWN = new Ground(0, new int[16], 0, 1);

    public record Sample(int height, String biome, boolean water) {
        boolean dry() {
            return !water && !IMPASSABLE.contains(biome) && !biome.endsWith("ocean")
                    && !biome.endsWith("river") && !biome.equals("void") && !biome.equals("the_void");
        }
    }

    public record Ground(int openings, int[] heights, int width, double cover) {
        public boolean land() { return width > 0; }
        int edge(int direction) { return (openings >>> (direction * 4)) & 15; }
    }

    private record Visit(long key, int steps, int estimate) {}
    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
        int distance(long key) {
            int x = CityFrontier.x(key), z = CityFrontier.z(key);
            return Math.max(0, Math.max(minX - x, x - maxX)) + Math.max(0, Math.max(minZ - z, z - maxZ));
        }
    }

    private WarTerrain() {}

    public static Ground sample(IntFunction<Sample> reader) {
        var tiles = new Sample[16];
        for (int i = 0; i < tiles.length; i++) tiles[i] = reader.apply(i);
        int start = -1;
        for (int i : CENTER_FIRST) {
            if (tiles[i].dry()) {
                start = i;
                break;
            }
        }
        if (start == -1) return BLOCKED;
        var reached = new boolean[16];
        var queue = new ArrayDeque<Integer>();
        reached[start] = true;
        queue.add(start);
        int low = tiles[start].height(), high = low, openings = 0, cells = 0, trees = 0;
        int[] edges = new int[16];
        while (!queue.isEmpty()) {
            int i = queue.remove(), x = i & 3, z = i >> 2;
            var tile = tiles[i];
            cells++;
            if (tile.biome().contains("forest") || tile.biome().contains("taiga") || tile.biome().contains("jungle")) trees++;
            low = Math.min(low, tile.height());
            high = Math.max(high, tile.height());
            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                if (nx < 0 || nx > 3 || nz < 0 || nz > 3) {
                    int edge = d * 4 + (DX[d] == 0 ? x : z);
                    openings |= 1 << edge;
                    edges[edge] = tile.height();
                    continue;
                }
                int n = nz * 4 + nx;
                if (!reached[n] && tiles[n].dry() && Math.abs(tiles[n].height() - tile.height()) <= MAX_RISE) {
                    reached[n] = true;
                    queue.add(n);
                }
            }
        }
        boolean rough = high - low > MAX_RISE || trees * 2 >= cells;
        return new Ground(openings, edges, Math.max(4, cells * (rough ? 2 : 4)), rough ? 1.25 : 1);
    }

    public static int distance(long a, long b) {
        return Math.abs(CityFrontier.x(a) - CityFrontier.x(b)) + Math.abs(CityFrontier.z(a) - CityFrontier.z(b));
    }

    public static boolean crosses(long from, long to, LongFunction<Ground> ground) {
        if (distance(from, to) != 1) return false;
        int d = CityFrontier.x(from) != CityFrontier.x(to)
                ? (CityFrontier.x(from) > CityFrontier.x(to) ? 0 : 3)
                : (CityFrontier.z(from) > CityFrontier.z(to) ? 1 : 2);
        var a = ground.apply(from);
        var b = ground.apply(to);
        int opposite = 3 - d;
        int openings = a.edge(d) & b.edge(opposite);
        for (int i = 0; i < 4; i++) {
            if ((openings & (1 << i)) != 0
                    && Math.abs(a.heights()[d * 4 + i] - b.heights()[opposite * 4 + i]) <= MAX_RISE) return true;
        }
        return false;
    }

    /** Returns the steps after the origin, or null if the destination has no known route within the budget. */
    public static List<Long> route(long from, long to, int limit, LongPredicate mayEnter, LongFunction<Ground> ground) {
        return route(from, Set.of(to), limit, mayEnter, ground);
    }

    /** Routes to the closest reachable goal. The bounding box provides an admissible lower bound. */
    public static List<Long> route(long from, Set<Long> goals, int limit, LongPredicate mayEnter, LongFunction<Ground> ground) {
        if (goals.isEmpty()) return null;
        if (goals.contains(from)) return List.of();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = minX, maxZ = maxX;
        for (long key : goals) {
            minX = Math.min(minX, CityFrontier.x(key));
            maxX = Math.max(maxX, CityFrontier.x(key));
            minZ = Math.min(minZ, CityFrontier.z(key));
            maxZ = Math.max(maxZ, CityFrontier.z(key));
        }
        var bounds = new Bounds(minX, minZ, maxX, maxZ);
        int direct = bounds.distance(from);
        if (direct > limit) return null;
        var open = new PriorityQueue<>(Comparator.comparingInt(Visit::estimate)
                .thenComparing(Comparator.comparingInt(Visit::steps).reversed()).thenComparingLong(Visit::key));
        var distance = new HashMap<Long, Integer>();
        var previous = new HashMap<Long, Long>();
        open.add(new Visit(from, 0, direct));
        distance.put(from, 0);
        for (int attempts = 0; attempts < MAX_VISITS && !open.isEmpty(); attempts++) {
            Visit head = open.remove();
            if (head.steps() != distance.get(head.key())) continue;
            if (goals.contains(head.key())) {
                var steps = new ArrayList<Long>();
                for (long key = head.key(); key != from; key = previous.get(key)) steps.add(key);
                Collections.reverse(steps);
                return steps;
            }
            for (long next : CityFrontier.neighbors(head.key())) {
                int steps = head.steps() + 1, estimate = steps + bounds.distance(next);
                if (estimate > limit || steps >= distance.getOrDefault(next, Integer.MAX_VALUE)
                        || !mayEnter.test(next) || !crosses(head.key(), next, ground)) continue;
                distance.put(next, steps);
                previous.put(next, head.key());
                open.add(new Visit(next, steps, estimate));
            }
        }
        return null;
    }
}
