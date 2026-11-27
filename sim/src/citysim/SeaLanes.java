package citysim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Navigation on the surface grid. Coordinates count four-block tiles. */
public final class SeaLanes {
    public record Cell(int x, int z) {
        public List<Cell> neighbors() {
            return List.of(new Cell(x - 1, z), new Cell(x, z - 1), new Cell(x, z + 1), new Cell(x + 1, z));
        }
    }

    private record Pair(Cell from, Cell to, int maxTiles) {}

    private final Set<Cell> water;
    private final Map<Cell, Integer> components = new HashMap<>();
    private final Map<Integer, Integer> componentSizes = new HashMap<>();
    private final Map<Pair, List<Cell>> routes = new HashMap<>();

    public SeaLanes(Set<Cell> water) {
        this.water = new HashSet<>(water);
    }

    public boolean includes(Cell cell) { return water.contains(cell); }

    public void prepareComponents() { for (Cell cell : water) component(cell); }

    public boolean componentsPrepared() { return components.size() == water.size(); }

    /** Preserve previously calculated routes when replacing an index of the same surface. */
    public void keepRoutesFrom(SeaLanes previous) { routes.putAll(previous.routes); }

    /** Connected water groups in a 4×4 subgrid, in row-major bit order. */
    public static List<Integer> localWaterRegions(int water) {
        List<Integer> groups = new ArrayList<>();
        int remaining = water & 0xffff;
        while (remaining != 0) {
            int open = Integer.lowestOneBit(remaining), group = 0;
            while (open != 0) {
                group |= open;
                remaining &= ~open;
                open = (((open & 0xeeee) >>> 1) | ((open & 0x7777) << 1) | (open >>> 4) | (open << 4)) & remaining;
            }
            groups.add(group);
        }
        return List.copyOf(groups);
    }

    public int component(Cell cell) {
        if (!includes(cell)) return 0;
        var known = components.get(cell);
        if (known != null) return known;
        int id = components.size() + 1;
        ArrayDeque<Cell> frontier = new ArrayDeque<>();
        frontier.add(cell);
        components.put(cell, id);
        int size = 0;
        while (!frontier.isEmpty()) {
            size++;
            for (Cell next : frontier.remove().neighbors()) {
                if (water.contains(next) && components.putIfAbsent(next, id) == null) frontier.add(next);
            }
        }
        componentSizes.put(id, size);
        return id;
    }

    public int waterArea(Cell cell) { return componentSizes.getOrDefault(component(cell), 0); }

    public List<Cell> route(Cell from, Cell to, int maxTiles) {
        if (manhattan(from, to) >= maxTiles) return List.of();
        int component = component(from);
        if (component == 0 || component != component(to)) return List.of();
        return routes.computeIfAbsent(new Pair(from, to, maxTiles), pair -> find(pair.from(), pair.to(), pair.maxTiles()));
    }

    private record Visit(Cell cell, int steps, int estimate) {}

    /** Lazy surface sampling keeps a short harbour route from scanning an entire ocean. */
    public static List<Cell> findRoute(Cell from, Cell to, int maxTiles, java.util.function.Predicate<Cell> water) {
        if (manhattan(from, to) >= maxTiles || !water.test(from) || !water.test(to)) return List.of();
        var open = new java.util.PriorityQueue<Visit>(java.util.Comparator.comparingInt(Visit::estimate)
                .thenComparing(java.util.Comparator.comparingInt(Visit::steps).reversed())
                .thenComparingInt(v -> v.cell().x()).thenComparingInt(v -> v.cell().z()));
        var previous = new HashMap<Cell, Cell>();
        var distance = new HashMap<Cell, Integer>();
        open.add(new Visit(from, 0, manhattan(from, to)));
        distance.put(from, 0);
        for (int visited = 0; !open.isEmpty() && visited < 8192; visited++) {
            var head = open.remove();
            if (head.steps() != distance.get(head.cell())) continue;
            if (head.cell().equals(to)) {
                var path = new ArrayList<Cell>();
                for (Cell at = to; !at.equals(from); at = previous.get(at)) path.add(at);
                path.add(from);
                Collections.reverse(path);
                return List.copyOf(path);
            }
            for (Cell next : head.cell().neighbors()) {
                int steps = head.steps() + 1, estimate = steps + manhattan(next, to);
                if (estimate >= maxTiles || steps >= distance.getOrDefault(next, Integer.MAX_VALUE) || !water.test(next)) continue;
                previous.put(next, head.cell());
                distance.put(next, steps);
                open.add(new Visit(next, steps, estimate));
            }
        }
        return List.of();
    }
    private List<Cell> find(Cell from, Cell to, int maxTiles) {
        ArrayDeque<Cell> frontier = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        frontier.add(from);
        previous.put(from, from);
        for (int steps = 0; !frontier.isEmpty(); steps++) {
            int breadth = frontier.size();
            for (int i = 0; i < breadth; i++) {
                Cell cell = frontier.remove();
                if (cell.equals(to)) {
                    List<Cell> path = new ArrayList<>();
                    for (Cell point = to; !point.equals(from); point = previous.get(point)) path.add(point);
                    path.add(from);
                    Collections.reverse(path);
                    return List.copyOf(path);
                }
                for (Cell next : cell.neighbors()) {
                    if (steps + 1 + manhattan(next, to) < maxTiles && water.contains(next)
                            && previous.putIfAbsent(next, cell) == null) frontier.add(next);
                }
            }
        }
        return List.of();
    }

    private static int manhattan(Cell a, Cell b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }
}
