package com.livingcity.stage;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CityBlueprint {
    public record Cell(BlockPos offset, BlockState state) {}
    public record Design(List<Cell> cells, BlockPos entrance, Direction facing, int width, int depth, int top, int floorY) {
        public BlockPos originAtFloor(BlockPos floor) { return floor.down(floorY); }
    }
    private static final Map<String,Design> cache = new HashMap<>();
    private static final Map<String,EnumMap<BlockRotation,Design>> rotations = new HashMap<>();
    public static final Map<citysim.BuildingKind,String> TYPES=Map.of(
            citysim.BuildingKind.HOUSE,"house_4bed_compact",citysim.BuildingKind.WELL,"well",citysim.BuildingKind.PLAZA,"chapel",
            citysim.BuildingKind.PORT,"port");

    public static List<Cell> cells(String name) {
        return design(name, BlockRotation.NONE).cells();
    }

    public static Design design(String name, BlockRotation rotation) {
        Design base = cache.computeIfAbsent(name, CityBlueprint::read);
        if (rotation == BlockRotation.NONE) return base;
        return rotations.computeIfAbsent(name, key -> new EnumMap<>(BlockRotation.class))
                .computeIfAbsent(rotation, turn -> rotate(base, turn));
    }

    private static Design rotate(Design base, BlockRotation turn) {
        boolean quarterTurn = turn == BlockRotation.CLOCKWISE_90 || turn == BlockRotation.COUNTERCLOCKWISE_90;
        return new Design(base.cells().stream().map(cell -> new Cell(
                rotate(cell.offset(), base.width(), base.depth(), turn), cell.state().rotate(turn))).toList(),
                rotate(base.entrance(), base.width(), base.depth(), turn), turn.rotate(base.facing()),
                quarterTurn ? base.depth() : base.width(), quarterTurn ? base.width() : base.depth(), base.top(), base.floorY());
    }

    private static BlockPos rotate(BlockPos p, int width, int depth, BlockRotation turn) {
        return switch (turn) {
            case NONE -> p;
            case CLOCKWISE_90 -> new BlockPos(depth - 1 - p.getZ(), p.getY(), p.getX());
            case CLOCKWISE_180 -> new BlockPos(width - 1 - p.getX(), p.getY(), depth - 1 - p.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(p.getZ(), p.getY(), width - 1 - p.getX());
        };
    }

    private static Design read(String name) {
        List<Cell> loaded = new ArrayList<>();
        BlockPos entrance = BlockPos.ORIGIN;
        int top = 0, width = 0, depth = 0, height = 0, floorY = 0;
        String path="/data/livingcity/blueprints/"+name+".bp";
        try(var reader=new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CityBlueprint.class.getResourceAsStream(path)),StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                if (line.startsWith("@size")) {
                    BlockPos size = position(line.split("\\s+")[1].replace('x', ','));
                    width = size.getX(); height = size.getY(); depth = size.getZ();
                    if (width < 1 || width > 32 || height < 1 || depth < 1 || depth > 32) {
                        throw new IllegalArgumentException("Invalid blueprint size");
                    }
                    continue;
                }
                if (line.startsWith("@entrance")) {
                    entrance = position(line.split("\\s+")[1]);
                    continue;
                }
                if (line.startsWith("@floor_y")) {
                    floorY = Integer.parseInt(line.split("\\s+")[1]);
                    continue;
                }
                if(line.isBlank() || !Character.isDigit(line.charAt(0))) continue;
                String[] fields=line.split("\\s+");
                if (fields.length >= 3 && fields[2].equals("C")) continue;
                BlockPos offset = position(fields[0]);
                top = Math.max(top, offset.getY());
                if (offset.getX() < 0 || offset.getX() >= width || offset.getZ() < 0 || offset.getZ() >= depth
                        || offset.getY() < 0 || offset.getY() >= height) throw new IllegalArgumentException("Block outside blueprint bounds");
                var state=BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(),fields[1],false).blockState();
                if(state.getBlock() instanceof BedBlock) state=state.with(BedBlock.OCCUPIED,false);
                loaded.add(new Cell(offset,state));
            }
        } catch(Exception e) {throw new IllegalStateException("Invalid bundled blueprint: " + name, e);}
        if (width == 0 || entrance.getX() < 0 || entrance.getX() >= width
                || entrance.getZ() < 0 || entrance.getZ() >= depth || entrance.getY() < 0 || entrance.getY() >= height) {
            throw new IllegalStateException("Invalid blueprint dimensions or entrance: " + name);
        }
        if (floorY < 0 || floorY >= height) {
            throw new IllegalStateException("Blueprint floor must lie within the declared height: " + name);
        }
        Direction facing = entrance.getX() == width - 1 ? Direction.EAST : entrance.getX() == 0 ? Direction.WEST
                : entrance.getZ() == depth - 1 ? Direction.SOUTH : Direction.NORTH;
        if (!loaded.isEmpty() && entrance.getX() != 0 && entrance.getX() != width - 1
                && entrance.getZ() != 0 && entrance.getZ() != depth - 1) {
            throw new IllegalStateException("Blueprint entrance must lie on the edge: " + name);
        }
        return new Design(List.copyOf(loaded), entrance, facing, width, depth, top, floorY);
    }

    private static BlockPos position(String coordinates) {
        String[] xyz = coordinates.split(",");
        return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
    }

    public static boolean replaceable(BlockState s) {
        return s.isAir() || s.isOf(Blocks.GRASS) || s.isOf(Blocks.TALL_GRASS) || s.isOf(Blocks.FERN)
                || s.isOf(Blocks.SNOW);
    }
}
