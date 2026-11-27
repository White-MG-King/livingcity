package com.livingcity.data;

import citysim.NationExpansion;
import citysim.NationExpansion.Supplies;
import com.livingcity.block.LivingCityBlocks;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.function.LongPredicate;

/** Politics and in-transit residents belong to the same persisted snapshot as both city ledgers. */
public final class CityNations {
    public enum Dispatch { DEPARTED, NO_CITY, NOT_READY, UNDERWAY, COOLDOWN, NO_LAND }
    public record Summary(int id, String name, int cities, long residents) {}
    public record Journey(int source, int x, int z, int settlers, long remaining, long duration) {}

    private static final class Nation {
        private String name = "";
        private long retryAt;
        private int bearing;
        private int capital;
    }

    record Expedition(int nation, int source, long target, long departed, long arrival, Supplies cargo, BlockPos hall) {
        Expedition approach(BlockPos anchor) {
            return new Expedition(nation, source, target, departed, arrival, cargo, anchor);
        }
    }

    private final TreeMap<Integer, Nation> nations = new TreeMap<>();
    private final TreeMap<Integer, Expedition> expeditions = new TreeMap<>();
    private final Set<Long> reserved = new HashSet<>();
    private int lastSource;
    private int lastLanding;
    private static final ChunkTicketType<ChunkPos> LANDING_TICKET = ChunkTicketType.create(
            "livingcity_colony_survey", java.util.Comparator.comparingLong(ChunkPos::toLong), 200);
    private ChunkPos landingChunk;
    private int landingNation, landingSince, retryLandingAt;

    void registerIndependent(CityData city) {
        city.nationId = city.id;
        Nation nation = new Nation();
        nation.capital = city.id;
        nations.putIfAbsent(city.id, nation);
    }

    public int capital(int nation) {
        Nation record = nations.get(nation);
        return record == null ? 0 : record.capital;
    }

    boolean transferCity(CityRegistry registry, CityData city, int from, int to) {
        Nation former = nations.get(from), winner = nations.get(to);
        if (former == null || winner == null || city.nationId != from || from == to) return false;
        CityData refuge = registry.cities().stream().filter(c -> c.nationId == from && c != city && c.isFounded())
                .findFirst().orElse(null);
        Expedition team = expeditions.get(from);
        if (team != null && team.source() == city.id) {
            CityData recipient = refuge == null ? city : refuge;
            var ledger = recipient.harness().city;
            NationExpansion.receive(ledger, team.cargo());
            ledger.chronicle.log(ledger.dayCount, "城池易主 · 拓殖队撤回");
            release(registry, team);
        }
        city.nationId = to;
        if (former.capital == city.id) former.capital = refuge == null ? 0 : refuge.id;
        if (winner.capital == 0) winner.capital = city.id;
        var ledger = city.harness().city;
        ledger.chronicle.log(ledger.dayCount, "城市易主 · 国家 #" + from + " → 国家 #" + to);
        registry.markDirty();
        return true;
    }

    boolean reserves(long chunk) { return reserved.contains(chunk); }
    Expedition expedition(int nation) { return expeditions.get(nation); }

    public String name(int nation) {
        Nation record = nations.get(nation);
        return record == null ? "" : record.name;
    }

    public boolean rename(CityRegistry registry, CityData city, String name) {
        Nation nation = nations.get(city.nationId);
        if (nation == null || name.isBlank() || name.codePointCount(0, name.length()) > 32
                || name.codePoints().anyMatch(Character::isISOControl)) return false;
        nation.name = name;
        registry.markDirty();
        return true;
    }

    public List<Summary> summaries(CityRegistry registry) {
        var totals = new TreeMap<Integer, long[]>();
        for (CityData city : registry.cities()) {
            if (city.nationId == 0) continue;
            var total = totals.computeIfAbsent(city.nationId, ignored -> new long[2]);
            total[0]++;
            total[1] += city.harness().city.population;
        }
        List<Summary> result = new ArrayList<>(totals.size());
        totals.forEach((id, total) -> {
            Expedition team = expeditions.get(id);
            long traveling = team == null ? 0 : team.cargo().settlers();
            result.add(new Summary(id, name(id), (int) total[0], total[1] + traveling));
        });
        return result;
    }

    public long cooldown(int nation, long cycle) {
        Nation record = nations.get(nation);
        return record == null ? 0 : Math.max(0, record.retryAt - cycle);
    }

    public Journey journey(int nation, long cycle) {
        Expedition team = expeditions.get(nation);
        if (team == null) return null;
        return new Journey(team.source(), CityData.xOf(team.target()) * 16 + 8, CityData.zOf(team.target()) * 16 + 8,
                team.cargo().settlers(), Math.max(0, team.arrival() - cycle), team.arrival() - team.departed());
    }

    public void advance(ServerWorld world, CityRegistry registry, boolean hasTickBudget) {
        if (!hasTickBudget) return;
        long cycle = registry.worldCycle();
        if (registry.rules().automaticColonization() && cycle % NationExpansion.PLANNING_INTERVAL == 0) {
            CityData source = registry.nextCity(lastSource);
            lastSource = source.id;
            dispatch(world, registry, source);
        }
    }

    /** One short-lived destination ticket per world. Chunk generation stays asynchronous. */
    public void tickLanding(ServerWorld world, CityRegistry registry, boolean allowed) {
        int tick = world.getServer().getTicks();
        if (landingChunk != null) {
            Expedition team = expeditions.get(landingNation);
            if (!allowed || team == null || team.target() != CityData.xzKey(landingChunk.x, landingChunk.z) || tick - landingSince > 600) {
                world.getChunkManager().removeTicket(LANDING_TICKET, landingChunk, 2, landingChunk);
                landingChunk = null;
                retryLandingAt = tick + 20;
                return;
            }
            if (tick % 40 == 0) world.getChunkManager().addTicket(LANDING_TICKET, landingChunk, 2, landingChunk);
            tryLand(world, registry, team);
            if (!expeditions.containsKey(landingNation)) {
                world.getChunkManager().removeTicket(LANDING_TICKET, landingChunk, 2, landingChunk);
                landingChunk = null;
            }
            return;
        }
        if (!allowed || expeditions.isEmpty() || tick < retryLandingAt) return;
        var next = expeditions.higherEntry(lastLanding);
        if (next == null) next = expeditions.firstEntry();
        lastLanding = next.getKey();
        Expedition team = next.getValue();
        if (team.arrival() > registry.worldCycle()) return;
        var coordinate = new ChunkPos(CityData.xOf(team.target()), CityData.zOf(team.target()));
        if (registry.owner(coordinate.x, coordinate.z) != null || !world.getWorldBorder().contains(coordinate)) {
            returnHome(registry, team, "拓殖用地已失效");
            return;
        }
        landingChunk = coordinate;
        landingNation = team.nation();
        landingSince = tick;
        world.getChunkManager().addTicket(LANDING_TICKET, coordinate, 2, coordinate);
    }

    public Dispatch dispatch(ServerWorld world, CityRegistry registry, CityData source) {
        return dispatch(registry, source, key -> ColonyTerrain.canTravel(world, key), id -> {
            var uuid = source.citizenEntity(id);
            if (uuid != null) {
                var entity = world.getEntity(uuid);
                if (entity != null) entity.discard();
            }
        });
    }

    Dispatch dispatch(CityRegistry registry, CityData source, LongPredicate dry, IntConsumer discardCitizen) {
        if (!source.isFounded()) return Dispatch.NO_CITY;
        var nation = nations.get(source.nationId);
        if (expeditions.containsKey(source.nationId)) return Dispatch.UNDERWAY;
        long cycle = registry.worldCycle();
        if (nation.retryAt > cycle) return Dispatch.COOLDOWN;
        var ledger = source.harness().city;
        if (!NationExpansion.ready(ledger)) return Dispatch.NOT_READY;

        BlockPos origin = source.townHallPos();
        var destination = NationExpansion.find(source.land.perimeter, chunk(origin), nation.bearing++,
                key -> available(registry, key), dry, key -> spacious(registry, key));
        if (destination == null) {
            registry.markDirty();
            return Dispatch.NO_LAND;
        }
        var departure = NationExpansion.embark(ledger);
        var team = new Expedition(source.nationId, source.id, destination.chunk(), cycle,
                cycle + destination.travelTime(), departure.supplies(), null);
        expeditions.put(team.nation(), team);
        reserved.add(team.target());
        nation.retryAt = cycle + NationExpansion.COOLDOWN;
        registry.markDirty();
        for (int id : departure.retired()) {
            discardCitizen.accept(id);
            source.unbindCitizenEntity(id);
            source.removeCitizenHome(id);
        }
        ledger.chronicle.log(ledger.dayCount, "拓殖队启程 · 目的地 " + CityData.xOf(team.target()) + ", " + CityData.zOf(team.target()));
        return Dispatch.DEPARTED;
    }

    private boolean spacious(CityRegistry registry, long key) {
        int x = CityData.xOf(key), z = CityData.zOf(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!available(registry, CityData.xzKey(x + dx, z + dz))) return false;
            }
        }
        return true;
    }

    private boolean available(CityRegistry registry, long key) {
        return registry.owner(CityData.xOf(key), CityData.zOf(key)) == null && !reserves(key);
    }

    private void tryLand(ServerWorld world, CityRegistry registry, Expedition team) {
        int x = CityData.xOf(team.target()), z = CityData.zOf(team.target());
        if (registry.owner(x, z) != null || !world.getWorldBorder().contains(new ChunkPos(x, z))) {
            returnHome(registry, team, "拓殖用地已失效");
            return;
        }
        var loaded = world.getChunkManager().getWorldChunk(x, z);
        if (loaded == null) return;

        if (team.hall() == null) {
            BlockPos anchor = ColonyTerrain.survey(world, loaded);
            if (anchor == null) returnHome(registry, team, "拓殖地缺少干燥平地");
            else {
                expeditions.put(team.nation(), team.approach(anchor));
                registry.markDirty();
            }
            return;
        }
        BlockPos anchor = team.hall();
        if (!ColonyTerrain.safeHall(world, loaded, anchor)) {
            returnHome(registry, team, "拓殖用地受阻");
            return;
        }
        // Avoid trapping a player or entity inside a newly placed hall.
        var before = loaded.getBlockState(anchor);
        var hall = LivingCityBlocks.TOWN_HALL.getDefaultState();
        boolean needsHall = !before.isOf(LivingCityBlocks.TOWN_HALL);
        if (needsHall && !world.getOtherEntities(null, new Box(anchor)).isEmpty()) return;
        if (needsHall && (!world.setBlockState(anchor, hall, Block.NOTIFY_ALL)
                || !loaded.getBlockState(anchor).isOf(LivingCityBlocks.TOWN_HALL))) {
            returnHome(registry, team, "市政厅放置失败");
            return;
        }
        if (complete(registry, team, anchor) == null) {
            if (needsHall && loaded.getBlockState(anchor).isOf(LivingCityBlocks.TOWN_HALL)) {
                world.setBlockState(anchor, before, Block.NOTIFY_ALL);
            }
            returnHome(registry, team, "拓殖登记失败");
        }
    }

    CityData complete(CityRegistry registry, Expedition team, BlockPos hall) {
        if (expeditions.get(team.nation()) != team || team.arrival() > registry.worldCycle()
                || !hall.equals(team.hall()) || team.target() != chunk(hall)) return null;
        CityData child = registry.foundColony(hall, team.nation(), team.source());
        if (child == null) return null;
        var parent = registry.byId(team.source()).harness().city;
        var receiving = child.harness().city;
        NationExpansion.receive(receiving, team.cargo());
        receiving.chronicle.log(receiving.dayCount, "接受母城 #" + team.source() + " 的拓殖队");
        parent.chronicle.log(parent.dayCount, "拓殖成功 · 城市 #" + child.id + " 建立");
        release(registry, team);
        return child;
    }

    public boolean cancel(CityRegistry registry, int nation) {
        Expedition team = expeditions.get(nation);
        if (team == null) return false;
        returnHome(registry, team, "拓殖队撤回");
        return true;
    }

    private void returnHome(CityRegistry registry, Expedition team, String reason) {
        var origin = registry.byId(team.source()).harness().city;
        NationExpansion.receive(origin, team.cargo());
        origin.chronicle.log(origin.dayCount, reason + " · 人员与物资已归还");
        release(registry, team);
    }

    private void release(CityRegistry registry, Expedition team) {
        reserved.remove(team.target());
        expeditions.remove(team.nation());
        registry.markDirty();
    }

    private static long chunk(BlockPos pos) { return CityData.xzKey(pos.getX() >> 4, pos.getZ() >> 4); }

    NbtCompound write() {
        var state = new NbtCompound();
        var catalog = new NbtCompound();
        nations.forEach((id, nation) -> {
            var record = new NbtCompound();
            record.putString("name", nation.name);
            record.putLong("retryAt", nation.retryAt);
            record.putInt("bearing", nation.bearing);
            record.putInt("capital", nation.capital);
            catalog.put(id.toString(), record);
        });
        state.put("catalog", catalog);
        var trips = new NbtList();
        for (Expedition team : expeditions.values()) {
            var tag = new NbtCompound();
            tag.putInt("nation", team.nation());
            tag.putInt("source", team.source());
            tag.putLong("target", team.target());
            tag.putLong("departed", team.departed());
            tag.putLong("arrival", team.arrival());
            tag.putInt("settlers", team.cargo().settlers());
            tag.putDouble("food", team.cargo().food());
            tag.putDouble("wood", team.cargo().wood());
            tag.putDouble("stone", team.cargo().stone());
            if (team.hall() != null) tag.putLong("hall", team.hall().asLong());
            trips.add(tag);
        }
        state.put("expeditions", trips);
        return state;
    }

    void read(CityRegistry registry, NbtCompound state) {
        var catalog = state.getCompound("catalog");
        for (CityData city : registry.cities()) {
            if (!city.isFounded()) continue;
            if (city.nationId <= 0) {
                city.nationId = city.id;
                registry.markDirty();
            }
            nations.computeIfAbsent(city.nationId, id -> {
                var tag = catalog.getCompound(Integer.toString(id));
                var nation = new Nation();
                nation.name = tag.getString("name");
                nation.retryAt = tag.getLong("retryAt");
                nation.bearing = tag.getInt("bearing");
                int savedCapital = tag.contains("capital", NbtElement.INT_TYPE) ? tag.getInt("capital") : id;
                CityData capital = registry.byId(savedCapital);
                nation.capital = capital != null && capital.nationId == id && capital.isFounded() ? capital.id : city.id;
                return nation;
            });
        }
        var trips = state.getList("expeditions", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < trips.size(); i++) {
            var tag = trips.getCompound(i);
            int nation = tag.getInt("nation"), sourceId = tag.getInt("source");
            var source = registry.byId(sourceId);
            var cargo = new Supplies(tag.getInt("settlers"), tag.getDouble("food"), tag.getDouble("wood"), tag.getDouble("stone"));
            long target = tag.getLong("target"), departed = tag.getLong("departed"), arrival = tag.getLong("arrival");
            BlockPos hall = tag.contains("hall", NbtElement.LONG_TYPE) ? BlockPos.fromLong(tag.getLong("hall")) : null;
            if (source == null || !source.isFounded() || source.nationId != nation || !nations.containsKey(nation)
                    || departed < 0 || departed > registry.worldCycle() || arrival <= departed
                    || !valid(cargo) || (hall != null && chunk(hall) != target)
                    || !available(registry, target) || expeditions.containsKey(nation)) {
                throw new IllegalArgumentException("Invalid saved colonization expedition");
            }
            expeditions.put(nation, new Expedition(nation, sourceId, target, departed, arrival, cargo, hall));
            reserved.add(target);
        }
    }

    private static boolean valid(Supplies cargo) {
        return cargo.settlers() > 0 && cargo.settlers() <= 64
                && cargo.food() >= 0 && Double.isFinite(cargo.food())
                && cargo.wood() >= 0 && Double.isFinite(cargo.wood())
                && cargo.stone() >= 0 && Double.isFinite(cargo.stone());
    }
}
