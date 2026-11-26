package citysim;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Appointments are identities in the world ledger, independent of Minecraft entity spawning. */
public final class OfficerBook {
    public enum Role { KING, MAYOR, GENERAL }
    public record Officer(int id, Role role, int seat, long appointed) {}
    public record State(int nextId, List<Officer> officers) {}
    public record Marker(int id, Role role, int nation, int city, int x, int z, long appointed) {}

    private final Map<Long, Officer> officers = new TreeMap<>();
    private final Map<Long, WarSimulation.Town> posts = new TreeMap<>();
    private int nextId = 1;

    void reconcile(WarSimulation.World world, Collection<WarSimulation.Town> towns, long cycle) {
        posts.clear();
        for (var town : towns) {
            post(Role.MAYOR, town.id(), town, cycle);
            post(Role.GENERAL, town.id(), town, cycle);
            if (town.id() == world.capital(town.nation())) post(Role.KING, town.nation(), town, cycle);
        }
        officers.keySet().retainAll(posts.keySet());
    }

    private void post(Role role, int seat, WarSimulation.Town town, long cycle) {
        long key = key(role, seat);
        officers.computeIfAbsent(key, ignored -> new Officer(nextId++, role, seat, cycle));
        posts.put(key, town);
    }

    List<Marker> markers(IntFunction<Long> armyPosition) {
        return posts.entrySet().stream().map(entry -> {
            var officer = officers.get(entry.getKey());
            var town = entry.getValue();
            Long location = officer.role() == Role.GENERAL ? armyPosition.apply(town.id()) : null;
            if (location == null) location = town.hall();
            return new Marker(officer.id(), officer.role(), town.nation(), town.id(),
                    CityFrontier.x(location), CityFrontier.z(location), officer.appointed());
        }).toList();
    }

    State save() { return new State(nextId, List.copyOf(officers.values())); }

    void restore(State state, long cycle) {
        if (state == null) return;
        if (state.nextId() <= 0) throw new IllegalArgumentException("官员档案编号无效");
        var ids = new HashSet<Integer>();
        for (Officer officer : state.officers()) {
            if (officer.role() == null || officer.seat() <= 0 || officer.id() <= 0 || officer.id() >= state.nextId()
                    || officer.appointed() < 0 || officer.appointed() > cycle || !ids.add(officer.id())
                    || officers.putIfAbsent(key(officer.role(), officer.seat()), officer) != null) {
                throw new IllegalArgumentException("官员档案数据无效");
            }
        }
        nextId = state.nextId();
    }

    private static long key(Role role, int seat) { return CityFrontier.key(role.ordinal(), seat); }
}
