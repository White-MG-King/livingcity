package citysim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/** Resource transfers and tactical decisions for a land settlement. Coordinates are chunk keys. */
public final class NationExpansion {
    public static final int MIN_POPULATION = 32;
    public static final int MAX_SPARE_BEDS = 4;
    public static final int SETTLERS = 6;
    public static final int STARTER_WOOD = 2 * CityLedger.BUILD_TIMBER;
    public static final int STARTER_STONE = 2 * CityLedger.BUILD_STONE;
    public static final int PLANNING_INTERVAL = 8;
    public static final int COOLDOWN = 40;
    static final int MAX_PROBES = 96;
    private static final int MAX_WALK = 8;
    private static final int[][] BEARINGS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    public record Supplies(int settlers, double food, double wood, double stone) {}
    public record Departure(Supplies supplies, List<Integer> retired) {}
    public record Target(long chunk, int steps) {
        public int travelTime() { return 6 + 2 * steps; }
    }
    private record Visit(long chunk, int distance) {}

    private NationExpansion() {}

    public static Supplies needed(CityLedger source) {
        double daily = source.config.satietyPerCitizenPerDay;
        return new Supplies(SETTLERS, 3 * daily * SETTLERS, STARTER_WOOD, STARTER_STONE);
    }

    public static boolean ready(CityLedger source) {
        Supplies supplies = needed(source);
        return source.workforce() >= MIN_POPULATION
                && source.totalBeds() - source.population <= MAX_SPARE_BEDS
                && (source.hardEvent == null || source.hardEvent.layer != LayerId.FOOD)
                && source.foodPoints >= supplies.food() + 2.0 * source.population * source.config.satietyPerCitizenPerDay
                && source.timber >= supplies.wood() && source.stone >= supplies.stone();
    }

    public static Departure embark(CityLedger source) {
        if (!ready(source)) return null;
        Supplies supplies = needed(source);
        source.population -= supplies.settlers();
        source.foodPoints -= supplies.food();
        source.timber -= supplies.wood();
        source.stone -= supplies.stone();

        // Prefer residents who do not have an individual record. IDs of any retired records are never reused.
        List<Integer> retired = new ArrayList<>();
        int excess = source.materializationPool.size() - source.population;
        if (excess > 0) {
            retired = source.materializationPool.stream().sorted(Comparator.reverseOrder()).limit(excess).toList();
            source.materializationPool.removeAll(retired);
        }
        return new Departure(supplies, List.copyOf(retired));
    }

    /** The same transfer credits a successful settlement or returns its supplies to the origin. */
    public static void receive(CityLedger receiving, Supplies supplies) {
        receiving.population += supplies.settlers();
        receiving.foodPoints += supplies.food();
        receiving.timber += supplies.wood();
        receiving.stone += supplies.stone();
    }

    public static Target find(Set<Long> frontier, long center, int direction,
                              LongPredicate outsideAndAvailable, LongPredicate dry, LongPredicate spacious) {
        var bearing = BEARINGS[Math.floorMod(direction, BEARINGS.length)];
        long start = 0;
        long bestScore = Long.MIN_VALUE;
        boolean found = false;
        for (long edge : frontier) {
            if (!outsideAndAvailable.test(edge)) continue;
            long x = (long) CityFrontier.x(edge) - CityFrontier.x(center);
            long z = (long) CityFrontier.z(edge) - CityFrontier.z(center);
            // Pick a frontier in the current direction, favoring the centerline on ties.
            long score = (x * bearing[0] + z * bearing[1]) * 4 - Math.abs(x * bearing[1] - z * bearing[0]);
            if (!found || score > bestScore || (score == bestScore && edge < start)) {
                bestScore = score;
                start = edge;
                found = true;
            }
        }
        if (!found || !dry.test(start)) return null;

        Set<Long> checked = new HashSet<>();
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        checked.add(start);
        queue.add(new Visit(start, 1));
        while (!queue.isEmpty()) {
            Visit here = queue.remove();
            if (here.distance() >= 3 && spacious.test(here.chunk())) return new Target(here.chunk(), here.distance());
            if (here.distance() >= MAX_WALK || checked.size() >= MAX_PROBES) continue;
            for (long next : CityFrontier.neighbors(here.chunk())) {
                if (checked.size() >= MAX_PROBES) break;
                if (checked.add(next) && outsideAndAvailable.test(next) && dry.test(next)) {
                    queue.add(new Visit(next, here.distance() + 1));
                }
            }
        }
        return null;
    }
}
