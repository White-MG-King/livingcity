package citysim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongPredicate;

/** Two-dimensional territory ordering shared by the game and the offline adapter. */
public final class CityFrontier {
    public static final int DEFAULT_LIMIT = 128;

    private CityFrontier() {}

    public static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    public static int x(long key) { return (int) (key >> 32); }
    public static int z(long key) { return (int) key; }

    public static long[] neighbors(long key) {
        int x = x(key), z = z(key);
        return new long[]{key(x - 1, z), key(x, z - 1), key(x, z + 1), key(x + 1, z)};
    }

    public static int adjoining(Set<Long> owned, long key) {
        int count = 0;
        for (long neighbor : neighbors(key)) if (owned.contains(neighbor)) count++;
        return count;
    }

    public static List<Long> candidates(Set<Long> owned, long center, LongPredicate occupied, boolean allowGrowth) {
        Set<Long> frontier = new HashSet<>();
        for (long key : owned) {
            if (occupied.test(key)) continue;
            frontier.add(key);
        }
        if (allowGrowth) {
            for (long key : owned) {
                for (long neighbor : neighbors(key)) {
                    if (!owned.contains(neighbor) && !occupied.test(neighbor)) frontier.add(neighbor);
                }
            }
        }
        return rank(frontier, owned, center);
    }

    private record Candidate(long key, boolean inside, double score) {}

    public static OptionalLong bestClaim(Set<Long> perimeter, Set<Long> owned, long center, LongPredicate eligible) {
        long best = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        for (long key : perimeter) {
            double score = score(owned, center, key);
            if (score > bestScore || (score == bestScore && key >= best)) continue;
            if (!eligible.test(key)) continue;
            best = key;
            bestScore = score;
        }
        return bestScore == Double.POSITIVE_INFINITY ? OptionalLong.empty() : OptionalLong.of(best);
    }

    private static double score(Set<Long> owned, long center, long key) {
        double distance = Math.hypot((double)x(key) - x(center), (double)z(key) - z(center));
        // A useful corner wins over the tip of a spur at a comparable distance.
        return distance - 2.5 * adjoining(owned, key);
    }

    private static List<Long> rank(Set<Long> frontier, Set<Long> owned, long center) {
        List<Candidate> choices = new ArrayList<>(frontier.size());
        for (long key : frontier) {
            choices.add(new Candidate(key, owned.contains(key), score(owned, center, key)));
        }
        choices.sort(Comparator.comparing(Candidate::inside).reversed()
                .thenComparingDouble(Candidate::score).thenComparingLong(Candidate::key));
        return choices.stream().map(Candidate::key).toList();
    }
}
