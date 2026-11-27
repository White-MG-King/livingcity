package com.livingcity.web;

import com.livingcity.data.CityMapSnapshot;
import com.livingcity.data.CityMapSnapshot.Tile;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.ChunkPos;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class TerrainAtlasTest {
    public static void run() throws IOException {
        testSavedPlants();
        testSeedGrid();
        testShading();
        testWaterDepth();
        var origin = new citysim.SeaLanes.Cell(0, 0);
        var goal = new citysim.SeaLanes.Cell(3, 0);
        var route = citysim.SeaLanes.findRoute(origin, goal, 8, cell -> cell.z() == 1 || cell.z() == 0 && cell.x() != 1);
        require(route.size() == 6 && route.get(0).equals(origin) && route.get(5).equals(goal), "Lazy sea route crossed land");
        System.out.println("ATLAS PASS: per-block saved surface, plants, 4x4 seed grid, gold, shading and tile boundaries");
    }

    private static void testWaterDepth() throws IOException {
        var atlas = new TerrainAtlas(Files.createTempDirectory("livingcity-depth-test-"));
        var raster = atlas.render(new TerrainAtlas.Key(0, 0, 1), (x, z) -> new Tile(63, "ocean", true, null, false).withDepth(x < 1 ? 5 : x == 1 ? 10 : 11));
        var image = ImageIO.read(new ByteArrayInputStream(raster.png()));
        int shallow = image.getRGB(2, 10) >> 16 & 255;
        int coastal = image.getRGB(6, 10) >> 16 & 255;
        int deep = image.getRGB(10, 10) >> 16 & 255;
        require(shallow > coastal && coastal > deep, "Water depth did not darken at 5/10 boundaries");
    }

    private static void testSavedPlants() {
        NbtList palette = new NbtList();
        for (String name : List.of("grass_block", "grass", "tall_grass", "gold_block")) {
            var entry = new NbtCompound();
            entry.putString("Name", "minecraft:" + name);
            palette.add(entry);
        }
        var blocks = new PackedIntegerArray(4, 4096);
        blocks.set(1 + 2 * 16 + 256, 1);
        blocks.set(2 + 2 * 16 + 256, 2);
        blocks.set(2 + 2 * 16 + 512, 2);
        blocks.set(3 + 2 * 16 + 768, 3);
        var states = new NbtCompound();
        states.put("palette", palette);
        states.putLongArray("data", blocks.getData());
        var section = new NbtCompound();
        section.putByte("Y", (byte) 0);
        section.put("block_states", states);
        var biome = new NbtCompound();
        var biomePalette = new NbtList();
        biomePalette.add(NbtString.of("minecraft:plains"));
        biome.put("palette", biomePalette);
        section.put("biomes", biome);
        var sections = new NbtList();
        sections.add(section);
        var heights = new PackedIntegerArray(9, 256);
        for (int i = 0; i < 256; i++) heights.set(i, 1);
        heights.set(2 * 16 + 1, 2);
        heights.set(2 * 16 + 2, 3);
        heights.set(2 * 16 + 3, 4);
        var heightmaps = new NbtCompound();
        heightmaps.putLongArray("WORLD_SURFACE", heights.getData());
        var chunk = new NbtCompound();
        chunk.putString("Status", "minecraft:full");
        chunk.put("sections", sections);
        chunk.put("Heightmaps", heightmaps);
        var surface = SavedMapTerrain.sample(new ChunkPos(0, 0), chunk, 384);
        require(surface != null && surface.tiles().size() == 256, "Saved surface lost individual columns");
        require(surface.at(1, 2).y() == 0 && surface.at(2, 2).y() == 0, "Small plants changed map height");
        require(surface.at(3, 2).y() == 3 && surface.at(3, 2).material().equals("gold_block"), "Gold column was filtered");
        require(!CityMapSnapshot.ignoredMapPlant(Blocks.OAK_LEAVES.getDefaultState()), "Tree canopy was filtered");
    }

    private static void testSeedGrid() throws IOException {
        var atlas = new TerrainAtlas(Files.createTempDirectory("livingcity-seed-test-"));
        var calls = new HashSet<Long>();
        var raster = atlas.render(new TerrainAtlas.Key(0, 0, 1), (x, z) -> {
            calls.add(ChunkPos.toLong(x, z));
            return new Tile(64, x == 1 ? "desert" : "plains", false, null, false);
        });
        var image = ImageIO.read(new ByteArrayInputStream(raster.png()));
        require(image.getWidth() == 128 && image.getHeight() == 128, "Wrong full resolution raster size");
        require(calls.size() == 33 * 33 && calls.contains(ChunkPos.toLong(31, 31)), "Seed grid skipped four-block cells");
        require(image.getRGB(4, 8) == image.getRGB(7, 8) && image.getRGB(3, 8) != image.getRGB(4, 8), "Seed cells lost fixed 4x4 scale");
    }

    private static void testShading() throws IOException {
        var atlas = new TerrainAtlas(Files.createTempDirectory("livingcity-shading-test-"));
        for (int cx = 7; cx <= 8; cx++) {
            var tiles = new ArrayList<Tile>();
            for (int z = 0; z < 16; z++) {
                for (int dx = 0; dx < 16; dx++) {
                    int x = cx * 16 + dx;
                    int y = x >= 126 && x <= 128 && z >= 5 && z <= 7 ? 65 : 64;
                    if (x >= 132 && x <= 134 && z >= 5 && z <= 7) y = 63;
                    tiles.add(new Tile(y, "plains", false, "grass_block", false, 0x80a060));
                }
            }
            atlas.put(new TerrainAtlas.Observation(cx, 0, 1, true, tiles));
        }
        var left = new TerrainAtlas.Key(0, 0, 1);
        var right = new TerrainAtlas.Key(1, 0, 1);
        var a = atlas.render(left, (x, z) -> new Tile(64, "plains", false, null, false));
        var b = atlas.render(right, (x, z) -> new Tile(64, "plains", false, null, false));
        var first = ImageIO.read(new ByteArrayInputStream(a.png()));
        var second = ImageIO.read(new ByteArrayInputStream(b.png()));
        int flat = first.getRGB(125, 4) & 255;
        require((first.getRGB(127, 5) & 255) > flat, "Raised northern edge did not brighten");
        require(first.getRGB(127, 5) == second.getRGB(0, 5), "Shadow changed across the tile seam");
        require((second.getRGB(5, 5) & 255) < flat, "Depressed northern edge did not darken");
        var old = atlas.observation(7, 0);
        var changed = new ArrayList<>(old.tiles());
        changed.set(5 * 16 + 15, new Tile(66, "plains", false, "grass_block", false, 0x80a060));
        atlas.put(new TerrainAtlas.Observation(7, 0, 2, true, changed));
        require(atlas.ready(left) == null && atlas.ready(right) == null, "Edit left a stale neighbouring tile shadow");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
