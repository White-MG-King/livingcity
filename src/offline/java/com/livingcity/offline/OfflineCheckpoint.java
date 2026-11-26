package com.livingcity.offline;

import citysim.CityFrontier;
import citysim.CityLedger;
import citysim.PortTrade.Voyage;
import citysim.SeaLanes.Cell;
import com.livingcity.data.CityLedgerCodec;
import com.livingcity.offline.OfflineWorld.Port;
import com.livingcity.offline.OfflineWorld.Site;
import com.livingcity.offline.OfflineWorld.Town;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class OfflineCheckpoint {
    private static final int VERSION = 3;
    private static final String FORMAT = "LivingCityOffline";

    private OfflineCheckpoint() {}

    static NbtCompound write(OfflineWorld world) {
        var root = new NbtCompound();
        root.putString("format", FORMAT);
        root.putInt("version", VERSION);
        root.putLong("seed", world.seed);
        root.putLong("cycle", world.cycle);
        root.putBoolean("demo", world.demo);
        root.putBoolean("battleDemo", world.battleDemo);
        com.livingcity.data.WarSave.write(root, world.war);
        root.put("nations", world.countries());
        root.putBoolean("imported", world.imported);
        root.putBoolean("unlimited", world.unlimited);
        root.putBoolean("timedExpansion", world.timedExpansion);
        root.putInt("limit", world.limit);
        if (world.layer != null) root.putInt("layer", world.layer);
        root.putLongArray("samples", world.samples.keySet().stream().mapToLong(Long::longValue).toArray());

        var towns = new NbtList();
        for (Town town : world.towns) towns.add(writeTown(town));
        root.put("towns", towns);
        var voyages = new NbtList();
        for (Voyage voyage : world.market.active.values()) voyages.add(writeVoyage(voyage));
        for (Voyage voyage : world.market.history) voyages.add(writeVoyage(voyage));
        root.put("voyages", voyages);
        root.putLong("nextVoyage", world.market.nextId);
        return root;
    }

    private static NbtCompound writeTown(Town town) {
        var record = new NbtCompound();
        record.putInt("id", town.id);
        record.putInt("nationId", town.nation);
        record.putLong("townHall", packed(town.hall));
        record.putString("siteStatus", town.importedSiteStatus);
        record.putBoolean("mapFull", town.mapFull);
        if (town.quarry != null) record.putLong("quarry", packed(town.quarry));
        if (town.forest != null) record.putLong("forestChunk", CityFrontier.key(town.forest[0], town.forest[1]));
        record.putLongArray("chunks", town.chunks.stream().mapToLong(Long::longValue).toArray());
        record.putLongArray("lots", town.lots.stream().mapToLong(Long::longValue).toArray());
        record.putLongArray("offlineRoads", town.roads.stream().mapToLong(OfflineCheckpoint::packed).toArray());
        var streets = new NbtList();
        town.streetAccess.forEach((chunk, site) -> {
            var entry = new NbtCompound();
            entry.putLong("chunk", chunk);
            entry.putLong("site", packed(site));
            streets.add(entry);
        });
        record.put("streets", streets);
        var anchors = new NbtCompound();
        for (var building : town.buildings.values()) {
            anchors.putLong(Integer.toString(building.id()), packed(new Site(building.x(), building.z())));
        }
        record.put("anchors", anchors);
        record.putLong("coins", town.coins);
        if (town.port != null) {
            record.putLong("port", packed(town.port.land()));
            record.putLong("berth", packed(town.port.berth()));
            record.putDouble("portWork", town.sim.city.portWork);
        }
        CityLedgerCodec.write(town.sim.city, record);
        return record;
    }

    private static NbtCompound writeVoyage(Voyage voyage) {
        var record = new NbtCompound();
        record.putLong("id", voyage.id());
        record.putInt("buyer", voyage.buyer());
        record.putInt("seller", voyage.seller());
        record.putInt("units", voyage.units());
        record.putLong("price", voyage.price());
        record.putLong("freight", voyage.freight());
        record.putLong("floor", voyage.floor());
        record.putLong("ceiling", voyage.ceiling());
        record.putInt("rivals", voyage.rivals());
        record.putLong("departure", voyage.departure());
        record.putLong("pickup", voyage.pickup());
        record.putLong("arrival", voyage.arrival());
        record.putLong("latest", voyage.latest());
        if (voyage.localCost() != null) record.putLong("localCost", voyage.localCost());
        record.putLongArray("route", voyage.route().stream().mapToLong(OfflineCheckpoint::packed).toArray());
        return record;
    }

    static OfflineWorld read(NbtCompound root) {
        int version = root.getInt("version");
        require(FORMAT.equals(root.getString("format")) && version >= 1 && version <= VERSION);
        long[] samples = root.getLongArray("samples");
        NbtList towns = root.getList("towns", NbtElement.COMPOUND_TYPE);
        require(samples.length > 0 && samples.length <= OfflineWorld.MAX_SAMPLES && towns.size() <= OfflineWorld.MAX_CITIES);
        var world = new OfflineWorld(root.getLong("seed"), root.getBoolean("imported"), root.getBoolean("demo"));
        world.cycle = root.getLong("cycle");
        world.battleDemo = root.getBoolean("battleDemo");
        world.limit = root.getInt("limit");
        require(world.cycle >= 0 && world.cycle < Long.MAX_VALUE / 2 && world.limit >= 32 && world.limit <= 512);
        world.unlimited = root.getBoolean("unlimited");
        world.timedExpansion = root.getBoolean("timedExpansion");
        for (long key : samples) {
            require(Math.abs((long) CityFrontier.x(key)) <= 1875000 && Math.abs((long) CityFrontier.z(key)) <= 1875000);
            world.sample(key);
        }
        Set<Integer> cityIds = new HashSet<>();
        Set<Cell> berths = new HashSet<>();
        for (int i = 0; i < towns.size(); i++) {
            NbtCompound record = towns.getCompound(i);
            int id = record.getInt("id");
            require(id > 0 && id < Integer.MAX_VALUE && cityIds.add(id));
            Town town = new Town(id, record, world.seed);
            require(world.samples.keySet().containsAll(town.chunks));
            town.mapFull = record.getBoolean("mapFull");
            town.lots.clear();
            for (long lot : record.getLongArray("lots")) {
                require(town.chunks.contains(lot));
                town.lots.add(lot);
            }
            for (long path : record.getLongArray("offlineRoads")) town.roads.add(Town.position(path));
            var streets = record.getList("streets", NbtElement.COMPOUND_TYPE);
            town.streetAccess.clear();
            for (int j = 0; j < streets.size(); j++) {
                var street = streets.getCompound(j);
                town.streetAccess.put(street.getLong("chunk"), Town.position(street.getLong("site")));
            }
            town.coins = record.getLong("coins");
            require(town.coins >= 0 && town.coins <= OfflineWorld.MAX_CITIES * OfflineMarket.INITIAL_COINS);
            if (record.contains("port", NbtElement.LONG_TYPE)) {
                Site land = Town.position(record.getLong("port"));
                Cell berth = cell(record.getLong("berth"));
                town.port = new Port(land, berth);
                var ledger = town.sim.city;
                ledger.portLocated = true;
                ledger.portWork = record.getDouble("portWork");
                require(ledger.portWork >= 0 && ledger.portWork <= CityLedger.PORT_HOURS
                        && world.validBerth(town.port) && berths.add(berth)
                        && town.chunks.contains(CityFrontier.key(land.x() >> 4, land.z() >> 4)));
            }
            world.addTown(town);
        }
        restoreVoyages(root, world);
        world.restoreCountries(root.getCompound("nations"));
        world.war = com.livingcity.data.WarSave.read(root, world, world.cycle);
        Integer layer = root.contains("layer", NbtElement.INT_TYPE) ? root.getInt("layer") : null;
        require(layer == null || !world.demo && layer >= VanillaSeedSampler.MIN_Y && layer <= VanillaSeedSampler.MAX_Y);
        world.changeLayer(layer);
        return world;
    }

    private static void restoreVoyages(NbtCompound root, OfflineWorld world) {
        NbtList voyages = root.getList("voyages", NbtElement.COMPOUND_TYPE);
        require(voyages.size() <= OfflineMarket.HISTORY_LIMIT + world.towns.size() / 2);
        long nextId = root.getLong("nextVoyage");
        require(nextId > 0 && nextId < Long.MAX_VALUE);
        Set<Long> ids = new HashSet<>();
        Set<Integer> portsInUse = new HashSet<>();
        for (int i = 0; i < voyages.size(); i++) {
            NbtCompound v = voyages.getCompound(i);
            long id = v.getLong("id");
            require(id > 0 && id < nextId && ids.add(id));
            Town buyer = OfflineMarket.town(world, v.getInt("buyer")), seller = OfflineMarket.town(world, v.getInt("seller"));
            require(buyer != seller && buyer.sim.city.portActive() && seller.sim.city.portActive());
            int units = v.getInt("units"), rivals = v.getInt("rivals");
            long price = v.getLong("price"), freight = v.getLong("freight"), floor = v.getLong("floor"), ceiling = v.getLong("ceiling");
            long departure = v.getLong("departure"), pickup = v.getLong("pickup"), arrival = v.getLong("arrival");
            long latest = v.getLong("latest");
            Long localCost = v.contains("localCost", NbtElement.LONG_TYPE) ? v.getLong("localCost") : null;
            require(units > 0 && units <= CityLedger.BUILD_STONE && rivals >= 0 && rivals <= 14);
            require(floor > 0 && floor < price && price < ceiling && ceiling <= OfflineMarket.INITIAL_COINS
                    && freight > 0 && freight <= OfflineMarket.INITIAL_COINS
                    && departure > 0 && departure <= world.cycle && pickup > departure && arrival > pickup && latest >= arrival
                    && (localCost == null || localCost > 0)
                    && arrival - departure <= 2 * (long) OfflineWorld.MAX_SAMPLES * 16 + 1);
            List<Cell> route = new ArrayList<>();
            for (long position : v.getLongArray("route")) {
                Cell cell = cell(position);
                var tile = world.tile(cell);
                require(tile != null && tile.water()
                        && (route.isEmpty() || route.get(route.size() - 1).neighbors().contains(cell)));
                route.add(cell);
            }
            require(!route.isEmpty() && route.get(0).equals(buyer.port.berth())
                    && route.get(route.size() - 1).equals(seller.port.berth()));
            var restored = new Voyage(id, buyer.id, seller.id, units, price, freight, floor, ceiling, rivals,
                    departure, pickup, arrival, latest, localCost, List.copyOf(route));
            if (arrival <= world.cycle) {
                world.market.history.add(restored);
            } else {
                require(portsInUse.add(buyer.id) && portsInUse.add(seller.id)
                        && buyer.sim.city.stone >= 0 && buyer.sim.city.stone <= CityLedger.STOCK_LIMIT - units);
                buyer.sim.city.incomingStone = units;
                world.market.active.put(id, restored);
            }
        }
        require(world.market.history.size() <= OfflineMarket.HISTORY_LIMIT);
        world.market.nextId = nextId;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("离线快照数据不完整或版本不受支持");
    }

    private static long packed(Site site) { return BlockPos.asLong(site.x(), 0, site.z()); }
    private static long packed(Cell cell) { return CityFrontier.key(cell.x(), cell.z()); }
    private static Cell cell(long key) { return new Cell(CityFrontier.x(key), CityFrontier.z(key)); }
}
