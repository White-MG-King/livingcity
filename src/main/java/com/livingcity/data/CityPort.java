package com.livingcity.data;

import com.livingcity.stage.CityBlueprint;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import citysim.SeaLanes.Cell;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A dry road entrance and a sparse pier over water share the blueprint's floor height. */
final class CityPort {
    static final int CANDIDATES_PER_CHUNK = 16 * 16 * 4;
    private static final List<Integer> COLUMN_ORDER = java.util.stream.IntStream.range(0, 256).boxed()
            .sorted(java.util.Comparator.comparingInt(i -> (i % 16 - 8) * (i % 16 - 8) + (i / 16 - 8) * (i / 16 - 8))).toList();
    record Site(BlockPos origin, BlockRotation turn, Cell berth) {
        CityBlueprint.Design design() { return CityBlueprint.design("port", turn); }
        CityPlot plot() { return new CityPlot(origin.getX(), origin.getZ(), design().width(), design().depth()); }
        BlockPos entrance() { return origin.add(design().entrance()); }
        NbtCompound write() {
            var tag = new NbtCompound();
            tag.putLong("origin", origin.asLong());
            tag.putInt("rotation", turn.ordinal());
            tag.putInt("berthX", berth.x());
            tag.putInt("berthZ", berth.z());
            return tag;
        }
        static Site read(NbtCompound tag) {
            return new Site(BlockPos.fromLong(tag.getLong("origin")), BlockRotation.values()[Math.floorMod(tag.getInt("rotation"), 4)],
                    new Cell(tag.getInt("berthX"), tag.getInt("berthZ")));
        }
    }
    record Planned(Site site, CityTerrain.Plan blocks, CityRoads.Access road) {}
    record Attempt(Planned plan, String reason) {}

    static Site candidate(ServerWorld world, CityData city, long chunk, int index) {
        int column = COLUMN_ORDER.get(index / 4);
        int x = (CityData.xOf(chunk) << 4) + column % 16;
        int z = (CityData.zOf(chunk) << 4) + column / 16;
        var loaded = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
        if (loaded == null) return null;
        int y = CitySurface.constructionColumn(loaded, x, z).groundY();
        if (!CityTerrain.natural(loaded.getBlockState(new BlockPos(x, y, z)))) return null;
        var turn = BlockRotation.values()[index % 4];
        var design = CityBlueprint.design("port", turn);
        var water = new BlockPos(x, y, z).offset(design.facing().getOpposite(), Math.max(design.width(), design.depth()) + 4);
        var berth = new Cell(water.getX() >> 2, water.getZ() >> 2);
        int wx = berth.x() * 4 + 2, wz = berth.z() * 4 + 2;
        var waterChunk = world.getChunkManager().getWorldChunk(wx >> 4, wz >> 4);
        if (waterChunk == null) return null;
        int waterY = waterChunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, wx & 15, wz & 15);
        BlockPos center = new BlockPos(wx, waterY, wz);
        if (!world.getFluidState(center).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || !world.getBlockState(center.up()).isAir()) return null;
        // A pier deck belongs one block above the water. The road adapter can bridge
        // a shore up to two blocks below the deck, without moving the whole coastline.
        int deckY = waterY + 1;
        if (y > deckY || deckY - y > 2) return null;
        var origin = new BlockPos(x, deckY, z).subtract(design.entrance());
        var site = new Site(origin, turn, berth);
        var plot = site.plot();
        var hall = city.townHallPos();
        if (plot.intersects(new CityPlot(hall.getX() - 1, hall.getZ() - 1, 3, 3))
                || city.land.mapQuarry() != null && plot.contains(city.land.mapQuarry())
                || !city.registry.canClaimPlot(city, plot) || city.land.overlapsParcel(plot, plot.chunkKeys())) return null;
        for (long key : plot.chunkKeys()) if (world.getChunkManager()
                .getWorldChunk(CityData.xOf(key), CityData.zOf(key)) == null) return null;
        return site;
    }

    static Planned plan(ServerWorld world, CityData city, Site site, Set<BlockPos> roads) {
        return inspect(world, city, site, roads).plan();
    }

    static Attempt inspect(ServerWorld world, CityData city, Site site, Set<BlockPos> roads) {
        var plot = site.plot();
        var hall = city.townHallPos();
        if (plot.intersects(new CityPlot(hall.getX() - 1, hall.getZ() - 1, 3, 3))
                || city.land.mapQuarry() != null && plot.contains(city.land.mapQuarry())) return new Attempt(null, "港址与市政厅或采石场重叠");
        if (!city.registry.canClaimPlot(city, plot)) return new Attempt(null, "港址用地受领地上限或其他城市限制");
        if (city.land.overlapsParcel(plot, plot.chunkKeys())) return new Attempt(null, "港址与已有建筑用地重叠");
        for (long key : plot.chunkKeys()) {
            if (world.getChunkManager().getWorldChunk(CityData.xOf(key), CityData.zOf(key)) == null) return new Attempt(null, "港址尚有未加载区块");
        }
        var design = site.design();
        int floor = site.origin().getY() + design.floorY();
        var berth = new BlockPos(site.berth().x() * 4 + 2, floor - 1, site.berth().z() * 4 + 2);
        if (!world.isChunkLoaded(berth) || !world.getFluidState(berth).isIn(net.minecraft.registry.tag.FluidTags.WATER)) return new Attempt(null, "港址泊位水域已改变");
        var changes = new ArrayList<CityBlockChanges.Change>();
        for (var cell : design.cells()) {
            BlockPos pos = site.origin().add(cell.offset());
            if (world.isOutOfHeightLimit(pos) || !world.getWorldBorder().contains(pos)
                    || city.land.hallReserved(pos, city) || city.land.occupied(pos, city)) return new Attempt(null, "港口结构碰到保留建筑或世界边界");
            BlockState before = world.getBlockState(pos);
            boolean foundation = cell.offset().getY() <= design.floorY();
            boolean water = before.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
            if (!CityBlueprint.replaceable(before) && !(foundation && (water || CityTerrain.natural(before)))) return new Attempt(null, "港口结构碰到地形或现有方块");
            if (!before.equals(cell.state())) changes.add(new CityBlockChanges.Change(pos, before, cell.state()));
        }
        var road = CityRoads.plan(world, city, site.origin(), design, plot, roads, null, null);
        if (road == null) return new Attempt(null, "港口入口尚不能接上城市道路");
        return new Attempt(new Planned(site, new CityTerrain.Plan(plot.chunkKeys(), new CityBlockChanges(List.of()),
                new CityBlockChanges(List.copyOf(changes))), road), "港址可用");
    }
}
