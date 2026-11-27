package com.livingcity.data;

import citysim.CityNeedsReport;
import citysim.WarSimulation;
import com.livingcity.sensor.CitySimTicker;
import net.minecraft.block.BlockState;
import net.minecraft.block.PlantBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/** Copies only data drawn by the map, on the same thread that owns the city ledgers. */
public final class CityMapSnapshot {
    public record Point(int x, int z) {
        static Point at(BlockPos pos) { return new Point(pos.getX(), pos.getZ()); }
        static Point chunk(long key) { return new Point(CityData.xOf(key), CityData.zOf(key)); }
    }
    public record Building(int id, String kind, int x, int z) {}
    public record Plot(int id, int x, int z, int width, int depth) {}
    public record Project(String kind, double paidHours, double requiredHours, boolean paused) {}
    public record City(int id, int nationId, String nationName, boolean capital, int foundedFrom, Point hall, boolean missing,
                       int pop, int beds, int pendingBeds, int pendingBuildings, long cycle, double food,
                       double wood, double stone, Point quarrySite, double quarry, int[] forestChunk,
                       boolean forestScanComplete, String siteStatus, String portStatus, Project project, List<Point> chunks,
                       List<Point> roads, List<Building> buildings, List<Plot> plots, CityNeedsReport needs, CityTrade.AccountView trade) {}
    public record Expedition(int nation, CityNations.Journey journey) {}
    public record State(String mode, boolean present, boolean editable, String world, long cycles, int speed,
                        double effective, boolean gamePaused, boolean unlimited, int limit, String layer,
                        Point player, List<City> cities, List<Expedition> expeditions,
                        WarSimulation.View war, boolean tradeSupported, List<CityTrade.Sailing> voyages, CityConstructionQueue.Status survey) {}
    public record Tile(int y, String biome, boolean water, String material, boolean lava, Integer color, Integer waterDepth) {
        public Tile(int y, String biome, boolean water, String material, boolean lava, Integer color) {
            this(y, biome, water, material, lava, color, null);
        }
        public Tile(int y, String biome, boolean water, String material, boolean lava) {
            this(y, biome, water, material, lava, null);
        }
        public Tile withDepth(int depth) { return new Tile(y, biome, water, material, lava, color, depth); }
    }

    private CityMapSnapshot() {}

    public static boolean canControl(MinecraftServer server, ServerPlayerEntity player) {
        return player != null && (player.hasPermissionLevel(2) || server.isHost(player.getGameProfile()));
    }

    public static State capture(MinecraftServer server, ServerPlayerEntity player, boolean gamePaused) {
        var registry = CityRegistry.get(server);
        var clock = registry.byId(1).clock();
        List<City> cities = new ArrayList<>();
        List<Expedition> expeditions = new ArrayList<>();
        for (var city : registry.cities()) {
            if (!city.isFounded()) continue;
            cities.add(copyCity(server, registry, city));
            if (registry.nations().capital(city.nationId()) == city.id) {
                var journey = registry.nations().journey(city.nationId(), registry.worldCycle());
                if (journey != null) expeditions.add(new Expedition(city.nationId(), journey));
            }
        }
        Point playerPosition = player.getWorld() == server.getOverworld() ? Point.at(player.getBlockPos()) : null;
        return new State("live", true, canControl(server, player), server.getSaveProperties().getLevelName(),
                registry.worldCycle(), clock.speed(), clock.effectiveSpeed(), gamePaused, registry.rules().unlimited(),
                registry.rules().territory(), "surface", playerPosition, List.copyOf(cities),
                List.copyOf(expeditions), registry.warView(), true, registry.trade().voyages(registry.worldCycle()),
                registry.construction().status(server.getOverworld(), registry));
    }

    private static City copyCity(MinecraftServer server, CityRegistry registry, CityData city) {
        var ledger = city.harness().city;
        var land = city.land;
        List<Building> buildings = new ArrayList<>();
        for (var entry : ledger.buildings) {
            BlockPos anchor = city.buildingAnchor(entry.id);
            if (anchor != null) buildings.add(new Building(entry.id, entry.kind.name(), anchor.getX(), anchor.getZ()));
        }
        List<Plot> plots = land.mapPlots().entrySet().stream().map(entry -> {
            var rect = entry.getValue();
            return new Plot(entry.getKey(), rect.x(), rect.z(), rect.width(), rect.depth());
        }).toList();
        var active = ledger.activeProject;
        Project project = active == null ? null : new Project(active.kind.name(), active.paidHours, active.requiredHours, active.paused);
        Point forest = Point.chunk(land.mapForest());
        int[] forestChunk = ledger.forestAvailable ? new int[]{forest.x(), forest.z()} : null;
        return new City(city.id, city.nationId(), registry.nations().name(city.nationId()),
                registry.nations().capital(city.nationId()) == city.id, city.foundedFrom(),
                Point.at(city.townHallPos()), land.hallMissing, ledger.population, ledger.totalBeds(), ledger.pendingBeds(),
                ledger.pendingBuildings(), ledger.cycleCount, ledger.foodPoints, ledger.timber, ledger.stone,
                land.mapQuarry() == null ? null : Point.at(land.mapQuarry()), ledger.quarryWork, forestChunk, land.forestScanComplete(),
                CitySimTicker.siteStatus(server, city), land.portStatus(), project, land.chunks.stream().map(Point::chunk).toList(),
                land.mapRoads().stream().map(Point::at).toList(), List.copyOf(buildings), plots, CityNeedsReport.read(ledger),
                registry.trade().view(city, registry.worldCycle()));
    }

    public static List<Tile> terrain(WorldChunk chunk) {
        List<Tile> result = new ArrayList<>(256);
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        var pos = new BlockPos.Mutable();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = Math.max(chunk.getBottomY(), chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z));
                var state = chunk.getBlockState(pos.set(cx * 16 + x, y, cz * 16 + z));
                while (y > chunk.getBottomY() && ignoredMapPlant(state)) {
                    state = chunk.getBlockState(pos.setY(--y));
                }
                var biome = chunk.getBiomeForNoiseGen(cx * 4 + (x >> 2), y >> 2, cz * 4 + (z >> 2));
                Tile tile = surfaceTile(y, biome.getKey().map(key -> key.getValue().getPath()).orElse("unknown"), state);
                result.add(tile.water() ? tile.withDepth(Math.max(1, y - chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR, x, z))) : tile);
            }
        }
        return List.copyOf(result);
    }

    public static boolean ignoredMapPlant(BlockState state) {
        return state.isAir() || state.getBlock() instanceof PlantBlock && state.getFluidState().isEmpty();
    }

    public static Tile surfaceTile(int y, String biome, BlockState state) {
        var fluid = state.getFluidState();
        Integer color;
        try { color = state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).color; }
        catch (RuntimeException unavailableColor) { color = null; }
        return new Tile(y, biome, fluid.isOf(Fluids.WATER) || fluid.isOf(Fluids.FLOWING_WATER),
                Registries.BLOCK.getId(state.getBlock()).getPath(),
                fluid.isOf(Fluids.LAVA) || fluid.isOf(Fluids.FLOWING_LAVA), color);
    }
}
