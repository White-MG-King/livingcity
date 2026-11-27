package com.livingcity.web;

import com.livingcity.data.CityMapSnapshot;
import com.mojang.serialization.Codec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Enumerates existing Anvil slots and decodes top columns without creating a WorldChunk. */
final class SavedMapTerrain {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?[0-9]{1,6})\\.(-?[0-9]{1,6})\\.mca");
    private record Region(Path path, int x, int z) {}
    private static final Codec<PalettedContainer<BlockState>> BLOCKS = PalettedContainer.createPalettedContainerCodec(
            Block.STATE_IDS, BlockState.CODEC, PalettedContainer.PaletteProvider.BLOCK_STATE, Blocks.AIR.getDefaultState());

    private final List<Region> regions;
    private int nextRegion;
    private Region activeRegion;
    private ByteBuffer offsets = ByteBuffer.allocate(0);
    private int slot;

    SavedMapTerrain(Path directory, ChunkPos center) throws IOException {
        var files = new ArrayList<Region>();
        if (Files.isDirectory(directory)) {
            try (var entries = Files.list(directory)) {
                for (Path path : (Iterable<Path>) entries::iterator) {
                    var name = REGION_NAME.matcher(path.getFileName().toString());
                    if (name.matches() && Files.isRegularFile(path)) {
                        files.add(new Region(path, Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2))));
                    }
                }
            }
        }
        files.sort(Comparator.comparingLong(r -> Math.abs((long) r.x * 32 + 16 - center.x)
                + Math.abs((long) r.z * 32 + 16 - center.z)));
        regions = List.copyOf(files);
    }

    synchronized List<ChunkPos> take(int limit) {
        var result = new ArrayList<ChunkPos>(limit);
        while (result.size() < limit) {
            if (offsets.remaining() >= 4) {
                int index = slot++;
                if (offsets.getInt() != 0) result.add(new ChunkPos(activeRegion.x * 32 + (index & 31),
                        activeRegion.z * 32 + (index >> 5)));
            } else {
                if (nextRegion >= regions.size()) break;
                activeRegion = regions.get(nextRegion++);
                offsets = ByteBuffer.allocate(4096);
                slot = 0;
                try (var input = FileChannel.open(activeRegion.path)) {
                    while (offsets.hasRemaining() && input.read(offsets) > 0) {}
                } catch (IOException ignored) {
                    offsets.clear();
                } finally {
                    offsets.flip();
                }
            }
        }
        return result;
    }

    static TerrainAtlas.Observation sample(ChunkPos pos, NbtCompound nbt, int worldHeight) {
        if (ChunkStatus.byId(nbt.getString("Status")).getChunkType() != ChunkStatus.ChunkType.LEVELCHUNK
                || !nbt.contains("sections", NbtElement.LIST_TYPE)) return null;
        int bottom = nbt.getInt("yPos") * 16;
        Map<Integer, NbtCompound> sections = new HashMap<>();
        for (var value : nbt.getList("sections", NbtElement.COMPOUND_TYPE)) {
            var section = (NbtCompound) value;
            if (section.contains("block_states", NbtElement.COMPOUND_TYPE)) sections.put((int) section.getByte("Y"), section);
        }
        if (sections.isEmpty()) return null;
        int top = Math.min(bottom + worldHeight - 1, (sections.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1) * 16 - 1);
        var columns = new SavedColumns(sections);
        PackedIntegerArray heights = packed(MathHelper.ceilLog2(worldHeight + 1), 256,
                nbt.getCompound("Heightmaps").getLongArray("WORLD_SURFACE"));
        PackedIntegerArray seabed = packed(MathHelper.ceilLog2(worldHeight + 1), 256,
                nbt.getCompound("Heightmaps").getLongArray("OCEAN_FLOOR"));
        var tiles = new ArrayList<CityMapSnapshot.Tile>(256);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = heights == null ? top : Math.min(top, bottom + heights.get(z * 16 + x) - 1);
                BlockState state = columns.block(x, Math.max(bottom, y), z);
                while (y > bottom && CityMapSnapshot.ignoredMapPlant(state)) state = columns.block(x, --y, z);
                var tile = CityMapSnapshot.surfaceTile(Math.max(bottom, y), columns.biome(x, y, z), state);
                if (tile.water()) {
                    int floor = seabed == null ? y : bottom + seabed.get(z * 16 + x) - 1;
                    if (seabed == null) {
                        while (floor > bottom && !columns.block(x, floor, z).getFluidState().isEmpty()) floor--;
                    }
                    tile = tile.withDepth(Math.max(1, y - floor));
                }
                tiles.add(tile);
            }
        }
        return new TerrainAtlas.Observation(pos.x, pos.z, nbt.getLong("LastUpdate"), false, tiles);
    }

    private static PackedIntegerArray packed(int bits, int entries, long[] data) {
        int perLong = 64 / bits;
        return data.length == (entries + perLong - 1) / perLong ? new PackedIntegerArray(bits, entries, data) : null;
    }

    private static final class SavedColumns {
        private final Map<Integer, NbtCompound> sections;
        private final Map<Integer, PalettedContainer<BlockState>> decoded = new HashMap<>();

        SavedColumns(Map<Integer, NbtCompound> sections) { this.sections = sections; }

        BlockState block(int x, int y, int z) {
            int section = y >> 4;
            if (!sections.containsKey(section)) return Blocks.AIR.getDefaultState();
            var states = decoded.computeIfAbsent(section, sy -> BLOCKS.parse(NbtOps.INSTANCE,
                    sections.get(sy).getCompound("block_states")).result().orElse(null));
            return states == null ? Blocks.AIR.getDefaultState() : states.get(x, y & 15, z);
        }

        String biome(int x, int y, int z) {
            var section = sections.get(y >> 4);
            if (section == null) return "unknown";
            var biomes = section.getCompound("biomes");
            var palette = biomes.getList("palette", NbtElement.STRING_TYPE);
            if (palette.isEmpty()) return "unknown";
            int index = 0;
            if (palette.size() > 1) {
                var indices = packed(MathHelper.ceilLog2(palette.size()), 64, biomes.getLongArray("data"));
                if (indices == null) return "unknown";
                index = indices.get((x >> 2) + (z >> 2) * 4 + ((y & 15) >> 2) * 16);
            }
            if (index >= palette.size()) return "unknown";
            String name = palette.getString(index);
            return name.startsWith("minecraft:") ? name.substring(10) : name;
        }
    }

}
