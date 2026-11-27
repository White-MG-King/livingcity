package com.livingcity.offline;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.CityConfig;
import citysim.CityFrontier;
import citysim.CityLedger;
import citysim.CityNeedsReport;
import citysim.SeaLanes;
import citysim.SeaLanes.Cell;
import citysim.SimHarness;
import citysim.WarSimulation;
import citysim.WarTerrain;
import com.livingcity.data.CityLedgerCodec;
import com.livingcity.data.CityRules;
import com.livingcity.data.WarSave;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class OfflineWorld implements WarSimulation.World {
    record Tile(int y, String biome, boolean water) {}
    record Sample(int x, int z, int y, String biome, boolean water, List<Tile> tiles) {
        Tile tile(int quartX, int quartZ) { return tiles.get((quartZ & 3) * 4 + (quartX & 3)); }
    }
    record Building(int id, String kind, int x, int z) {}
    record Site(int x, int z) {
        Cell tile() { return new Cell(x >> 2, z >> 2); }
    }
    record Port(Site land, Cell berth) {}
    record Project(String kind, double paidHours, double requiredHours, boolean paused) {}
    record CityView(int id, int nationId, String nationName, boolean capital, int x, int z, Site hall, Site quarrySite,
                    int pop, int beds, int pendingBeds, int pendingBuildings,
                    long cycle, double food, double wood,
                    double stone, double quarry, boolean forest, int[] forestChunk, Project project, String status,
                    String importedSiteStatus, List<LongPair> chunks, List<Site> roads, List<Building> buildings,
                    CityNeedsReport needs, OfflineMarket.AccountView trade) {}
    record LongPair(int x, int z) {}

    private static final Set<String> TREES = Set.of("forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
            "dark_forest", "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "jungle", "bamboo_jungle", "sparse_jungle");
    static final int MAX_SAMPLES = 25_000;
    static final int MAX_CITIES = 64;
    final long seed;
    final boolean imported;
    final boolean demo;
    final Map<Long, Sample> samples = new LinkedHashMap<>();
    private final VanillaSeedSampler sampler;
    private final Map<Long, Town> owners = new HashMap<>();
    private final Map<Integer, Integer> capitals = new TreeMap<>();
    private final Map<Integer, String> nationNames = new TreeMap<>();
    final List<Town> towns = new ArrayList<>();
    final OfflineMarket market = new OfflineMarket();
    WarSimulation war = new WarSimulation();
    private final Map<Long, WarTerrain.Ground> campaignTerrain = new HashMap<>();
    private int campaignSamples;
    boolean battleDemo;
    private SeaLanes sea;
    private List<Cell> preparedBerths = List.of();
    private int nextId = 1;
    long cycle;
    boolean unlimited;
    boolean timedExpansion = CityRules.DEFAULT_TIMED_EXPANSION;
    int limit = CityFrontier.DEFAULT_LIMIT;
    Integer layer;
    List<Sample> displayed;

    private OfflineWorld(long seed, boolean imported) {
        this(seed, imported, false);
    }

    OfflineWorld(long seed, boolean imported, boolean demo) {
        this.seed = seed;
        this.imported = imported;
        this.demo = demo;
        sampler = demo ? null : new VanillaSeedSampler(seed);
    }

    OfflineWorld(long seed, int cx, int cz, int radius) {
        this(seed, false);
        sampleArea(cx, cz, radius);
        changeLayer(null);
    }

    OfflineWorld(OfflineSave save, int radius) {
        this(save.seed(), true);
        var records = new TreeMap<Integer, NbtCompound>();
        for (String key : save.cities().getKeys()) {
            var record = save.cities().getCompound(key);
            if (record.contains("townHall", NbtElement.LONG_TYPE)) records.put(Integer.parseInt(key), record);
        }
        if (records.isEmpty()) throw new IllegalArgumentException("存档中没有已建立的城市");
        if (records.size() > MAX_CITIES) throw new IllegalArgumentException("离线会话最多支持 " + MAX_CITIES + " 座城市");
        for (var entry : records.entrySet()) {
            var town = new Town(entry.getKey(), entry.getValue(), seed);
            sampleArea(CityFrontier.x(town.center), CityFrontier.z(town.center), radius);
            for (long key : town.chunks) {
                sample(key);
                for (long neighbor : CityFrontier.neighbors(key)) sample(neighbor);
            }
            addTown(town);
        }
        unlimited = save.rules().getBoolean("unlimited");
        timedExpansion = CityRules.readTimedExpansion(save.rules());
        if (save.rules().contains("territory")) limit = Math.max(32, Math.min(512, save.rules().getInt("territory")));
        var root = save.registry();
        cycle = root.getInt("registryVersion") >= 5 ? root.getLong("worldCycle") : towns.get(0).sim.city.cycleCount;
        if (cycle < 0 || cycle >= Long.MAX_VALUE / 2) throw new IllegalArgumentException("世界周期无效");
        restoreCountries(root.getCompound("nations"));
        war = WarSave.read(root, this, cycle);
        market.importMain(this, com.livingcity.data.CityTrade.saved(root));
        changeLayer(null);
    }

    static OfflineWorld tradeDemo() {
        var world = new OfflineWorld(1, false, true);
        world.sampleArea(0, 0, 4);
        world.changeLayer(null);
        world.found(-1, 0);
        world.found(2, 0);
        world.found(2, 2);
        world.buildPort(new Cell(-3, 3));
        world.buildPort(new Cell(10, 3));
        world.buildPort(new Cell(10, 11));
        for (Town town : world.towns) {
            CityLedger city = town.sim.city;
            city.portWork = CityLedger.PORT_HOURS;
            city.timber = 80;
            city.foodPoints = 2000;
            if (town.id == 1) city.population = 12;
            else city.stone = 64;
        }
        return world;
    }

    static OfflineWorld warDemo() {
        var world = new OfflineWorld(1, false, true);
        world.battleDemo = true;
        world.sampleArea(7, 0, 7);
        world.changeLayer(null);
        world.found(3, 0);
        world.found(12, 2);
        world.found(13, 2);
        world.transferCity(3, 3, 2);
        for (Town town : world.towns) {
            var ledger = town.sim.city;
            ledger.population = switch (town.id) {
                case 1 -> 112;
                case 2 -> 48;
                default -> 4;
            };
            ledger.foodPoints = 6500;
            if (ledger.population > 8) ledger.placeExisting(BuildingKind.HOUSE, ledger.population - 8);
        }
        world.war = new WarSimulation();
        world.war.reconcile(world, world.cycle);
        world.war.declare(world, 1, 2, world.cycle);
        return world;
    }

    private void sampleArea(int cx, int cz, int radius) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) sample(CityFrontier.key(x, z));
        }
    }

    void sample(long key) {
        if (samples.containsKey(key)) return;
        if (samples.size() >= MAX_SAMPLES) throw new IllegalArgumentException("地形采样超过 25,000 区块，请缩小半径");
        int x = CityFrontier.x(key), z = CityFrontier.z(key);
        samples.put(key, sampleOf(x, z, demo ? demoTiles(x, z) : sampler.surfaceChunk(x, z)));
    }

    @Override public List<WarSimulation.Town> towns() {
        return towns.stream().map(town -> new WarSimulation.Town(
                town.id, town.nation, town.center, town.exterior, town.sim.city)).toList();
    }

    @Override public int cityAt(long chunk) {
        Town owner = owners.get(chunk);
        return owner == null ? 0 : owner.id;
    }

    @Override public int capital(int nation) { return capitals.getOrDefault(nation, 0); }

    @Override public boolean transferCity(int id, int from, int to) {
        Town town = towns.stream().filter(t -> t.id == id && t.nation == from).findFirst().orElse(null);
        if (town == null || from == to || !capitals.containsKey(to)) return false;
        town.nation = to;
        if (capital(from) == id) {
            capitals.put(from, towns.stream().filter(t -> t.nation == from).mapToInt(t -> t.id).min().orElse(0));
        }
        if (capital(to) == 0) capitals.put(to, id);
        var ledger = town.sim.city;
        ledger.chronicle.log(ledger.dayCount, "城市易主 · 国家 #" + from + " → 国家 #" + to);
        return true;
    }

    NbtCompound countries() {
        var result = new NbtCompound();
        var catalog = new NbtCompound();
        capitals.forEach((nation, capital) -> {
            if (capital == 0) return;
            var record = new NbtCompound();
            record.putInt("capital", capital);
            record.putString("name", nationNames.getOrDefault(nation, ""));
            catalog.put(Integer.toString(nation), record);
        });
        result.put("catalog", catalog);
        return result;
    }

    void restoreCountries(NbtCompound state) {
        var catalog = state.getCompound("catalog");
        var townById = new HashMap<Integer, Town>();
        towns.forEach(town -> townById.put(town.id, town));
        capitals.replaceAll((nation, fallback) -> {
            var record = catalog.getCompound(Integer.toString(nation));
            nationNames.put(nation, record.getString("name"));
            Town saved = townById.get(record.getInt("capital"));
            return saved != null && saved.nation == nation ? saved.id : fallback;
        });
    }

    @Override public WarTerrain.Ground ground(long key) {
        var saved = campaignTerrain.get(key);
        if (saved != null) return saved;
        if (!samples.containsKey(key)) {
            if (Math.abs((long) CityFrontier.x(key)) > 1875000 || Math.abs((long) CityFrontier.z(key)) > 1875000) {
                return WarTerrain.BLOCKED;
            }
            if (samples.size() >= MAX_SAMPLES || campaignSamples <= 0 || demo) return WarTerrain.UNKNOWN;
            campaignSamples--;
            sample(key);
        }
        var tiles = samples.get(key).tiles();
        var ground = WarTerrain.sample(index -> {
            Tile tile = tiles.get(index);
            return new WarTerrain.Sample(tile.y(), tile.biome(), tile.water());
        });
        campaignTerrain.put(key, ground);
        return ground;
    }

    private static List<Tile> demoTiles(int x, int z) {
        List<Tile> tiles = new ArrayList<>(16);
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                tiles.add(demoTile(x * 4 + dx, z * 4 + dz));
            }
        }
        return tiles;
    }

    private static Sample sampleOf(int x, int z, List<Tile> tiles) {
        Tile center = tiles.get(10);
        return new Sample(x, z, center.y(), center.biome(), tiles.stream().allMatch(Tile::water), List.copyOf(tiles));
    }

    private static Tile demoTile(int x, int z) {
        boolean water = x >= -2 && x <= 9;
        boolean island = x >= 2 && x <= 4 && z >= -4 && z <= 1;
        if (water && !island) return new Tile(62, "river", true);
        return new Tile(67, x < -7 || x > 14 ? "forest" : "plains", false);
    }

    Tile tile(Cell cell) {
        Sample sample = samples.get(chunkOf(cell));
        return sample == null ? null : sample.tile(cell.x(), cell.z());
    }

    private static long chunkOf(Cell tile) {
        return CityFrontier.key(tile.x() >> 2, tile.z() >> 2);
    }

    SeaLanes sea() {
        if (sea == null) sea = indexSea(samples.values());
        return sea;
    }

    static SeaLanes indexSea(Collection<Sample> surface) {
        Set<Cell> water = new HashSet<>();
        for (Sample sample : surface) {
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    if (sample.tiles().get(dz * 4 + dx).water()) {
                        water.add(new Cell(sample.x() * 4 + dx, sample.z() * 4 + dz));
                    }
                }
            }
        }
        return new SeaLanes(water);
    }

    List<Cell> berths() {
        return towns.stream().filter(town -> town.port != null).map(town -> town.port.berth()).toList();
    }

    boolean needsSeaPreparation(List<Cell> berths) {
        return sea == null || !sea.componentsPrepared() || !preparedBerths.equals(berths);
    }

    void usePreparedSea(SeaLanes prepared, List<Cell> berths) {
        if (sea != null) prepared.keepRoutesFrom(sea);
        sea = prepared;
        preparedBerths = berths;
    }

    void changeLayer(Integer y) {
        if (y != null && demo) throw new IllegalArgumentException("示例地图只提供地表");
        if (displayed != null && Objects.equals(layer, y)) return;
        layer = y;
        if (y == null) displayed = List.copyOf(samples.values());
        else displayed = samples.values().stream().map(s -> {
            List<Tile> tiles = new ArrayList<>(16);
            for (int dz = 0; dz < 4; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    int quartX = s.x() * 4 + dx, quartZ = s.z() * 4 + dz;
                    tiles.add(new Tile(y, sampler.biome(quartX, y, quartZ), s.tile(quartX, quartZ).water()));
                }
            }
            return sampleOf(s.x(), s.z(), tiles);
        }).toList();
    }

    void found(int x, int z) {
        long center = CityFrontier.key(x, z);
        Sample place = samples.get(center);
        if (towns.size() >= MAX_CITIES) throw new IllegalArgumentException("一次离线测试最多建 " + MAX_CITIES + " 座城");
        if (place == null || place.water() || owners.containsKey(center)) {
            throw new IllegalArgumentException("请选择采样范围内的无主陆地区块");
        }
        Town town = new Town(nextId, center, seed, drySite(center));
        int best = Integer.MAX_VALUE;
        for (Sample sample : samples.values()) {
            int dx = sample.x() - x, dz = sample.z() - z;
            if (Math.abs(dx) > 16 || Math.abs(dz) > 16) continue;
            int distance = dx * dx + dz * dz;
            if (distance <= 256 && distance < best && sample.tiles().stream()
                    .anyMatch(tile -> !tile.water() && TREES.contains(tile.biome()))) {
                best = distance;
                town.forest = new int[]{sample.x(), sample.z()};
            }
        }
        town.sim.city.forestAvailable = town.forest != null;
        addTown(town);
        war.reconcile(this, cycle);
    }

    void addTown(Town town) {
        towns.add(town);
        capitals.merge(town.nation, town.id, (before, candidate) ->
                before == town.nation || candidate == town.nation ? town.nation : Math.min(before, candidate));
        nextId = Math.max(nextId, town.id + 1);
        for (long key : town.chunks) {
            if (owners.putIfAbsent(key, town) != null) throw new IllegalArgumentException("存档里有重复归属的区块");
        }
        town.chunks.forEach(town::indexExterior);
    }

    boolean validBerth(Port port) {
        Tile land = tile(port.land().tile()), water = tile(port.berth());
        return land != null && !land.water() && water != null && water.water()
                && port.land().tile().neighbors().contains(port.berth());
    }

    void buildPort(Cell land) {
        Tile candidate = tile(land);
        Town town = owners.get(chunkOf(land));
        if (candidate == null || candidate.water() || town == null) throw new IllegalArgumentException("请选择城内的干燥岸上子格");
        if (town.port != null) throw new IllegalArgumentException("这座城已有港口用地");
        if (town.hall.tile().equals(land) || (town.quarry != null && town.quarry.tile().equals(land))
                || town.buildings.values().stream().anyMatch(b -> new Cell(b.x() >> 2, b.z() >> 2).equals(land))) {
            throw new IllegalArgumentException("这里已有建筑，请选择空闲岸上子格");
        }
        List<Cell> berths = land.neighbors().stream().filter(cell -> {
            Tile water = tile(cell);
            return water != null && water.water() && towns.stream().noneMatch(t -> t.port != null && t.port.berth().equals(cell));
        }).toList();
        if (berths.isEmpty()) throw new IllegalArgumentException("港口需要与空闲水格共边");

        List<Cell> road = pathToHall(town, land);
        if (road.isEmpty()) throw new IllegalArgumentException("找不到通往市政厅的连续城内陆地");
        Site point = siteOf(land);
        for (Cell step : road) {
            Site next = step.equals(town.hall.tile()) ? town.hall : siteOf(step);
            connect(town, point, next);
            point = next;
        }
        Cell berth = berths.size() == 1 ? berths.get(0) : berths.stream().max(Comparator.comparingInt(sea()::waterArea)).orElseThrow();
        town.port = new Port(siteOf(land), berth);
        town.lots.remove(chunkOf(land));
        town.sim.city.portLocated = true;
    }

    private List<Cell> pathToHall(Town town, Cell land) {
        Set<Cell> buildings = new HashSet<>();
        for (Building building : town.buildings.values()) buildings.add(new Cell(building.x() >> 2, building.z() >> 2));
        if (town.quarry != null) buildings.add(town.quarry.tile());
        buildings.remove(town.hall.tile());
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(town.hall.tile());
        previous.put(town.hall.tile(), town.hall.tile());
        while (!queue.isEmpty()) {
            Cell cell = queue.remove();
            if (cell.equals(land)) {
                List<Cell> path = new ArrayList<>();
                for (Cell at = land; !at.equals(town.hall.tile()); at = previous.get(at)) path.add(previous.get(at));
                return path;
            }
            for (Cell next : cell.neighbors()) {
                Tile step = tile(next);
                if (owners.get(chunkOf(next)) == town && step != null && !step.water() && !buildings.contains(next)
                        && previous.putIfAbsent(next, cell) == null) queue.add(next);
            }
        }
        return List.of();
    }

    void step() {
        if (towns.isEmpty()) return;
        cycle++;
        market.advance(this);
        for (Town town : towns) {
            CityLedger ledger = town.sim.city;
            if (ledger.timber >= CityLedger.QUARRY_TIMBER && (!ledger.portActive()
                    || ledger.activeProject != null && ledger.stone < CityLedger.BUILD_STONE && ledger.incomingStone == 0)) {
                ledger.quarryLocated = true;
            }
            town.sim.run(1);
            if (timedExpansion && CityRules.isExpansionCycle(ledger.cycleCount) && ledger.population > 0) expand(town);
            project(town);
        }
        int before = samples.size();
        campaignSamples = 2;
        war.step(this, cycle);
        if (samples.size() != before) {
            sea = null;
            displayed = null;
            changeLayer(layer);
        }
    }

    private void project(Town town) {
        CityLedger ledger = town.sim.city;
        BuildingEntry waiting = null;
        for (BuildingEntry entry : ledger.buildings) {
            if (entry.state != BuildingEntry.State.UNDER_CONSTRUCTION || town.buildings.containsKey(entry.id)) continue;
            if (waiting == null || entry.kind == BuildingKind.HOUSE) waiting = entry;
            if (entry.kind == BuildingKind.HOUSE) break;
        }
        if (waiting == null) return;
        while (!town.lots.isEmpty() && samples.get(town.lots.peek()).water()) town.lots.remove();
        if (town.lots.isEmpty() && !timedExpansion) expand(town);
        if (town.lots.isEmpty()) return;
        long plot = town.lots.remove();
        Site marker = drySite(plot);
        town.buildings.put(waiting.id, new Building(waiting.id, waiting.kind.name(), marker.x(), marker.z()));
        connectBuilding(town, plot);
        ledger.finishConstruction(waiting);
    }

    private void expand(Town town) {
        if ((!unlimited && town.chunks.size() >= limit) || town.mapFull) return;
        List<Long> frontier = CityFrontier.candidates(town.chunks, town.center,
                key -> owners.containsKey(key) || !samples.containsKey(key) || samples.get(key).water(), true);
        if (frontier.isEmpty()) {
            town.mapFull = true;
            return;
        }
        long plot = frontier.get(0);
        town.chunks.add(plot);
        town.indexExterior(plot);
        owners.put(plot, town);
        town.lots.add(plot);
    }

    private static Site siteOf(Cell tile) {
        return new Site(tile.x() * 4 + 2, tile.z() * 4 + 2);
    }

    private Site drySite(long chunk) {
        Sample sample = samples.get(chunk);
        int originX = sample.x() * 4, originZ = sample.z() * 4;
        int best = 10, dx = 2, dz = 2;
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                int distance = Math.abs(x - 2) + Math.abs(z - 2);
                if (!sample.tiles().get(z * 4 + x).water() && distance < best) {
                    dx = x;
                    dz = z;
                    best = distance;
                }
            }
        }
        return siteOf(new Cell(originX + dx, originZ + dz));
    }

    /** Join a placed building to the nearest recorded street through the city's chunks. */
    private void connectBuilding(Town town, long plot) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Map<Long, Long> towardBuilding = new HashMap<>();
        queue.add(plot);
        towardBuilding.put(plot, plot);
        while (!queue.isEmpty()) {
            long chunk = queue.remove();
            Site street = town.streetAccess.get(chunk);
            if (street != null) {
                while (chunk != plot) {
                    chunk = towardBuilding.get(chunk);
                    Site next = drySite(chunk);
                    connect(town, street, next);
                    street = next;
                }
                return;
            }
            for (long neighbor : CityFrontier.neighbors(chunk)) {
                if (!town.chunks.contains(neighbor) || samples.get(neighbor).water()) continue;
                if (towardBuilding.putIfAbsent(neighbor, chunk) == null) queue.add(neighbor);
            }
        }
    }

    private static void connect(Town town, Site from, Site to) {
        int x = from.x(), z = from.z();
        while (x != to.x()) {
            x += Integer.compare(to.x(), x);
            town.rememberRoad(new Site(x, z));
        }
        while (z != to.z()) {
            z += Integer.compare(to.z(), z);
            town.rememberRoad(new Site(x, z));
        }
    }

    List<CityView> cities() {
        List<CityView> result = new ArrayList<>();
        for (Town town : towns) {
            var ledger = town.sim.city;
            var p = ledger.activeProject;
            Project project = p == null ? null : new Project(p.kind.name(), p.paidHours, p.requiredHours, p.paused);
            String status = town.mapFull ? "采样范围内无可扩张陆地"
                    : !unlimited && town.chunks.size() >= limit ? "已达领地上限"
                    : timedExpansion ? "定时扩张 · 距下次 "
                    + (CityRules.EXPANSION_INTERVAL - ledger.cycleCount % CityRules.EXPANSION_INTERVAL) + " 周期" : "离线模拟";
            result.add(new CityView(town.id, town.nation, nationNames.getOrDefault(town.nation, ""),
                    capital(town.nation) == town.id, CityFrontier.x(town.center), CityFrontier.z(town.center),
                    town.hall, town.quarry, ledger.population, ledger.totalBeds(), ledger.pendingBeds(), ledger.pendingBuildings(),
                    ledger.cycleCount, ledger.foodPoints, ledger.timber,
                    ledger.stone, ledger.quarryWork, ledger.forestAvailable, town.forest, project, status, town.importedSiteStatus,
                    town.chunks.stream().map(k -> new LongPair(CityFrontier.x(k), CityFrontier.z(k))).toList(),
                    List.copyOf(town.roads), List.copyOf(town.buildings.values()), CityNeedsReport.read(ledger), market.view(town, cycle)));
        }
        return result;
    }

    boolean hasCities() { return !towns.isEmpty(); }

    static final class Town {
        final int id;
        int nation;
        final long center;
        final SimHarness sim;
        final Site hall;
        final String importedSiteStatus;
        Site quarry;
        Port port;
        long coins = OfflineMarket.INITIAL_COINS;
        OfflineMarket.Procurement procurement;
        final java.util.Queue<Long> lots = new java.util.ArrayDeque<>();
        final Set<Long> chunks = new java.util.LinkedHashSet<>();
        final Set<Long> exterior = new java.util.LinkedHashSet<>();
        final Set<Site> roads = new java.util.LinkedHashSet<>();
        final Map<Long, Site> streetAccess = new HashMap<>();
        final Map<Integer, Building> buildings = new LinkedHashMap<>();
        int[] forest;
        boolean mapFull;

        Town(int id, long center, long seed, Site hall) {
            this.id = id;
            nation = id;
            this.center = center;
            importedSiteStatus = "";
            this.hall = hall;
            CityConfig config = new CityConfig();
            config.requireBuildingMaterials = true;
            config.deferBuildingActivation = true;
            sim = new SimHarness(seed ^ center, config);
            sim.city.population = 8;
            sim.city.foodPoints = 480;
            var housing = sim.city.placeExisting(BuildingKind.HOUSE, 8);
            buildings.put(housing.id, new Building(housing.id, housing.kind.name(),
                    hall.x() - 1, hall.z() + 1));
            chunks.add(center);
            streetAccess.put(center, hall);
        }

        Town(int id, NbtCompound city, long seed) {
            this.id = id;
            nation = city.getInt("nationId") > 0 ? city.getInt("nationId") : id;
            importedSiteStatus = city.getString("siteStatus");
            hall = position(city.getLong("townHall"));
            center = CityFrontier.key(hall.x() >> 4, hall.z() >> 4);
            if (!city.contains("ledger", NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("缺少城市账本 #" + id);
            CityConfig config = new CityConfig();
            config.requireBuildingMaterials = true;
            sim = new SimHarness(seed ^ center, config);
            CityLedgerCodec.read(sim.city, city);
            if (city.contains("portSite", NbtElement.COMPOUND_TYPE)) {
                var portSite = city.getCompound("portSite");
                var turn = net.minecraft.util.BlockRotation.values()[Math.floorMod(portSite.getInt("rotation"), 4)];
                var design = com.livingcity.stage.CityBlueprint.design("port", turn);
                var entrance = BlockPos.fromLong(portSite.getLong("origin")).add(design.entrance());
                port = new Port(new Site(entrance.getX(), entrance.getZ()), new Cell(portSite.getInt("berthX"), portSite.getInt("berthZ")));
            }
            if (city.contains("quarry", NbtElement.LONG_TYPE)) quarry = position(city.getLong("quarry"));
            if (sim.city.forestAvailable && city.contains("forestChunk", NbtElement.LONG_TYPE)) {
                long key = city.getLong("forestChunk");
                forest = new int[]{CityFrontier.x(key), CityFrontier.z(key)};
            }
            var anchors = city.getCompound("anchors");
            Set<Long> used = new HashSet<>();
            used.add(center);
            for (BuildingEntry entry : sim.city.buildings) {
                String key = Integer.toString(entry.id);
                if (!anchors.contains(key, NbtElement.LONG_TYPE)) continue;
                Site site = position(anchors.getLong(key));
                used.add(CityFrontier.key(site.x() >> 4, site.z() >> 4));
                buildings.put(entry.id, new Building(entry.id, entry.kind.name(), site.x(), site.z()));
            }
            sim.city.requireBuildingPlacement(buildings::containsKey);
            var footprints = city.getCompound("buildingPlots");
            for (String key : footprints.getKeys()) {
                var lot = footprints.getCompound(key);
                int x = lot.getInt("x"), z = lot.getInt("z");
                int width = lot.getInt("width"), depth = lot.getInt("depth");
                if (width <= 0 || width > 32 || depth <= 0 || depth > 32) continue;
                for (int cx = x >> 4; cx <= (x + width - 1) >> 4; cx++) {
                    for (int cz = z >> 4; cz <= (z + depth - 1) >> 4; cz++) used.add(CityFrontier.key(cx, cz));
                }
            }
            if (quarry != null) used.add(CityFrontier.key(quarry.x() >> 4, quarry.z() >> 4));
            var paths = city.getCompound("roads");
            for (String key : paths.getKeys()) {
                for (long road : paths.getLongArray(key)) rememberRoad(position(road));
                used.add(Long.parseLong(key));
            }
            chunks.add(center);
            for (long key : city.getLongArray("chunks")) chunks.add(key);
            lots.addAll(CityFrontier.candidates(chunks, center, used::contains, false));
            streetAccess.putIfAbsent(center, hall);
        }

        void rememberRoad(Site road) {
            roads.add(road);
            streetAccess.putIfAbsent(CityFrontier.key(road.x() >> 4, road.z() >> 4), road);
        }

        void indexExterior(long key) {
            exterior.remove(key);
            for (long next : CityFrontier.neighbors(key)) if (!chunks.contains(next)) exterior.add(next);
        }

        static Site position(long packed) {
            var pos = BlockPos.fromLong(packed);
            return new Site(pos.getX(), pos.getZ());
        }
    }
}
