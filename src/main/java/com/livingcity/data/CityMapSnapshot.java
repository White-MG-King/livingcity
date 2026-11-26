package com.livingcity.data;

import citysim.CityNeedsReport;
import citysim.WarSimulation;
import com.livingcity.sensor.CitySimTicker;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Copies only data drawn by the map, on the same thread that owns the city ledgers. */
public final class CityMapSnapshot {
    private static final int TERRAIN_RADIUS = 4;

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
                       boolean forestScanComplete, String siteStatus, Project project, List<Point> chunks,
                       List<Point> roads, List<Building> buildings, List<Plot> plots, CityNeedsReport needs) {}
    public record Expedition(int nation, CityNations.Journey journey) {}
    public record State(String mode, boolean present, boolean editable, String world, long cycles, int speed,
                        double effective, boolean gamePaused, boolean unlimited, int limit, String layer,
                        Point player, long terrainVersion, List<City> cities, List<Expedition> expeditions,
                        WarSimulation.View war) {}
    public record Tile(int y, String biome, boolean water) {}
    public record Surface(int x, int z, List<Tile> tiles) {}

    private CityMapSnapshot() {}

    public static boolean canControl(MinecraftServer server, ServerPlayerEntity player) {
        return player != null && (player.hasPermissionLevel(2) || server.isHost(player.getGameProfile()));
    }

    public static State capture(MinecraftServer server, ServerPlayerEntity player, boolean gamePaused, long terrainVersion) {
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
                registry.rules().territory(), "surface", playerPosition, terrainVersion, List.copyOf(cities),
                List.copyOf(expeditions), registry.warView());
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
                CitySimTicker.siteStatus(server, city), project, land.chunks.stream().map(Point::chunk).toList(),
                land.mapRoads().stream().map(Point::at).toList(), List.copyOf(buildings), plots, CityNeedsReport.read(ledger));
    }

    /** A bounded view of loaded ground under the local player. This never asks the chunk manager to load a chunk. */
    public static List<Surface> terrain(MinecraftServer server, ServerPlayerEntity player) {
        var world = server.getOverworld();
        if (player.getWorld() != world) return List.of();
        var center = player.getChunkPos();
        List<Surface> result = new ArrayList<>();
        for (int cx = center.x - TERRAIN_RADIUS; cx <= center.x + TERRAIN_RADIUS; cx++) {
            for (int cz = center.z - TERRAIN_RADIUS; cz <= center.z + TERRAIN_RADIUS; cz++) {
                var chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                List<Tile> tiles = new ArrayList<>(16);
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int x = dx * 4 + 2, z = dz * 4 + 2;
                        int y = Math.max(world.getBottomY(), chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
                        var fluid = chunk.getFluidState(new BlockPos(cx * 16 + x, y, cz * 16 + z));
                        var biome = chunk.getBiomeForNoiseGen(cx * 4 + dx, y >> 2, cz * 4 + dz);
                        tiles.add(new Tile(y, biome.getKey().map(key -> key.getValue().getPath()).orElse("unknown"),
                                fluid.isOf(Fluids.WATER) || fluid.isOf(Fluids.FLOWING_WATER)));
                    }
                }
                result.add(new Surface(cx, cz, List.copyOf(tiles)));
            }
        }
        return List.copyOf(result);
    }
}
