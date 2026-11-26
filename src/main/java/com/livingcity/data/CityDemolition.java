package com.livingcity.data;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

import java.util.Set;

/** Restores only cells still occupied by the corresponding blueprint block. */
final class CityDemolition {
    private static final Set<Property<?>> RUNTIME_PROPERTIES = Set.of(
            Properties.OPEN, Properties.POWERED, Properties.OCCUPIED, Properties.SNOWY, Properties.STAIR_SHAPE,
            Properties.NORTH, Properties.EAST, Properties.SOUTH, Properties.WEST, Properties.UP,
            Properties.NORTH_WALL_SHAPE, Properties.EAST_WALL_SHAPE,
            Properties.SOUTH_WALL_SHAPE, Properties.WEST_WALL_SHAPE);

    final CityBlockChanges snapshot;
    private int cursor;
    private int preserved;

    CityDemolition(CityBlockChanges snapshot) {
        this.snapshot = snapshot;
        this.cursor = snapshot.edits().size();
    }

    int preserved() { return preserved; }

    boolean tick(ServerWorld world, CityData city, CityPlot plot, int budget) {
        for (int visited = 0; visited < budget && cursor > 0; visited++, cursor--) {
            var edit = snapshot.edits().get(cursor - 1);
            if (!plot.contains(edit.pos()) || city.registry.owner(edit.pos()) != city) {
                preserved++;
                continue;
            }
            BlockState current = world.getBlockState(edit.pos());
            if (current.equals(edit.before())) continue;
            if (!matches(current, edit.after()) || (current.hasBlockEntity() && !(current.getBlock() instanceof BedBlock))) {
                preserved++;
                continue;
            }
            if (!world.setBlockState(edit.pos(), edit.before(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS)) {
                return false;
            }
        }
        return cursor == 0;
    }

    static boolean matches(BlockState current, BlockState placed) {
        if (!current.isOf(placed.getBlock())) return false;
        for (var property : placed.getProperties()) {
            if (!RUNTIME_PROPERTIES.contains(property) && !current.get(property).equals(placed.get(property))) return false;
        }
        return true;
    }
}
