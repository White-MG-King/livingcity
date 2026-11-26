package bpconv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T1 格式③：.schematic（MCEdit 经典格式）。
 * 结构：{ Width,Height,Length: Short, Blocks: ByteArray, Data: ByteArray,
 *         AddBlocks?: ByteArray（高4位=第2i块、低4位=第2i+1块，id += nibble&lt;&lt;8）, Materials:"Alpha" }
 * 索引序：i = (y*Length + z)*Width + x。
 * 数字 id → 1.20.1 扁平化名：内置常用建筑方块表，未知 id 跳过并进警告清单（任务单 T1 约定）。
 * V1 已知损耗：除床（part/facing）与箱子/陷阱箱（facing）外方块状态一律丢失；床色一律 red_bed。
 */
public final class SchematicReader {

    public static final class Result {
        public final List<Voxel> voxels;
        public final List<String> warnings;
        Result(List<Voxel> v, List<String> w) { this.voxels = v; this.warnings = w; }
    }

    /** 床 data 低 2 位 → 朝向（pre-flattening：0=south 1=west 2=north 3=east）。 */
    private static final String[] BED_FACING = {"south", "west", "north", "east"};

    private static final Map<Integer, String> ID_MAP = new HashMap<>();
    static {
        ID_MAP.put(0, "minecraft:air");
        ID_MAP.put(1, "minecraft:stone");
        ID_MAP.put(2, "minecraft:grass_block");
        ID_MAP.put(3, "minecraft:dirt");
        ID_MAP.put(4, "minecraft:cobblestone");
        ID_MAP.put(5, "minecraft:oak_planks");
        ID_MAP.put(7, "minecraft:bedrock");
        ID_MAP.put(12, "minecraft:sand");
        ID_MAP.put(13, "minecraft:gravel");
        ID_MAP.put(14, "minecraft:gold_ore");
        ID_MAP.put(15, "minecraft:iron_ore");
        ID_MAP.put(16, "minecraft:coal_ore");
        ID_MAP.put(17, "minecraft:oak_log");
        ID_MAP.put(18, "minecraft:oak_leaves");
        ID_MAP.put(20, "minecraft:glass");
        ID_MAP.put(21, "minecraft:lapis_ore");
        ID_MAP.put(22, "minecraft:lapis_block");
        ID_MAP.put(24, "minecraft:sandstone");
        ID_MAP.put(26, "minecraft:red_bed"); // 特殊处理见下（part/facing 由 data 还原）
        ID_MAP.put(29, "minecraft:sticky_piston");
        ID_MAP.put(33, "minecraft:piston");
        ID_MAP.put(35, "minecraft:white_wool");
        ID_MAP.put(41, "minecraft:gold_block");
        ID_MAP.put(42, "minecraft:iron_block");
        ID_MAP.put(43, "minecraft:smooth_stone_slab");
        ID_MAP.put(44, "minecraft:smooth_stone_slab");
        ID_MAP.put(45, "minecraft:bricks");
        ID_MAP.put(46, "minecraft:tnt");
        ID_MAP.put(47, "minecraft:bookshelf");
        ID_MAP.put(48, "minecraft:mossy_cobblestone");
        ID_MAP.put(49, "minecraft:obsidian");
        ID_MAP.put(50, "minecraft:torch");
        ID_MAP.put(52, "minecraft:spawner");
        ID_MAP.put(53, "minecraft:oak_stairs");
        ID_MAP.put(54, "minecraft:chest");
        ID_MAP.put(56, "minecraft:diamond_ore");
        ID_MAP.put(57, "minecraft:diamond_block");
        ID_MAP.put(58, "minecraft:crafting_table");
        ID_MAP.put(60, "minecraft:farmland");
        ID_MAP.put(61, "minecraft:furnace");
        ID_MAP.put(65, "minecraft:ladder");
        ID_MAP.put(66, "minecraft:rail");
        ID_MAP.put(67, "minecraft:cobblestone_stairs");
        ID_MAP.put(69, "minecraft:lever");
        ID_MAP.put(72, "minecraft:oak_pressure_plate");
        ID_MAP.put(76, "minecraft:redstone_torch");
        ID_MAP.put(77, "minecraft:stone_button");
        ID_MAP.put(78, "minecraft:snow");
        ID_MAP.put(79, "minecraft:ice");
        ID_MAP.put(80, "minecraft:snow_block");
        ID_MAP.put(81, "minecraft:cactus");
        ID_MAP.put(82, "minecraft:clay");
        ID_MAP.put(85, "minecraft:oak_fence");
        ID_MAP.put(87, "minecraft:netherrack");
        ID_MAP.put(88, "minecraft:soul_sand");
        ID_MAP.put(89, "minecraft:glowstone");
        ID_MAP.put(91, "minecraft:jack_o_lantern");
        ID_MAP.put(96, "minecraft:oak_trapdoor");
        ID_MAP.put(97, "minecraft:infested_stone");
        ID_MAP.put(98, "minecraft:stone_bricks");
        ID_MAP.put(99, "minecraft:brown_mushroom_block");
        ID_MAP.put(100, "minecraft:red_mushroom_block");
        ID_MAP.put(101, "minecraft:iron_bars");
        ID_MAP.put(102, "minecraft:glass_pane");
        ID_MAP.put(103, "minecraft:melon");
        ID_MAP.put(106, "minecraft:vine");
        ID_MAP.put(107, "minecraft:oak_fence_gate");
        ID_MAP.put(108, "minecraft:brick_stairs");
        ID_MAP.put(109, "minecraft:stone_brick_stairs");
        ID_MAP.put(110, "minecraft:mycelium");
        ID_MAP.put(112, "minecraft:nether_bricks");
        ID_MAP.put(113, "minecraft:nether_brick_fence");
        ID_MAP.put(114, "minecraft:nether_brick_stairs");
        ID_MAP.put(116, "minecraft:enchanting_table");
        ID_MAP.put(121, "minecraft:end_stone");
        ID_MAP.put(123, "minecraft:redstone_lamp");
        ID_MAP.put(125, "minecraft:oak_slab");
        ID_MAP.put(126, "minecraft:oak_slab");
        ID_MAP.put(128, "minecraft:sandstone_stairs");
        ID_MAP.put(129, "minecraft:emerald_ore");
        ID_MAP.put(130, "minecraft:ender_chest");
        ID_MAP.put(131, "minecraft:tripwire_hook");
        ID_MAP.put(133, "minecraft:emerald_block");
        ID_MAP.put(139, "minecraft:cobblestone_wall");
        ID_MAP.put(143, "minecraft:oak_button");
        ID_MAP.put(146, "minecraft:trapped_chest");
        ID_MAP.put(152, "minecraft:redstone_block");
        ID_MAP.put(153, "minecraft:quartz_ore");
        ID_MAP.put(154, "minecraft:hopper");
        ID_MAP.put(155, "minecraft:quartz_block");
        ID_MAP.put(156, "minecraft:quartz_stairs");
        ID_MAP.put(158, "minecraft:dropper");
        ID_MAP.put(159, "minecraft:white_terracotta");
        ID_MAP.put(163, "minecraft:acacia_stairs");
        ID_MAP.put(164, "minecraft:dark_oak_stairs");
        ID_MAP.put(165, "minecraft:slime_block");
        ID_MAP.put(167, "minecraft:iron_trapdoor");
        ID_MAP.put(168, "minecraft:prismarine");
        ID_MAP.put(169, "minecraft:sea_lantern");
        ID_MAP.put(170, "minecraft:hay_block");
        ID_MAP.put(171, "minecraft:white_carpet");
        ID_MAP.put(172, "minecraft:terracotta");
        ID_MAP.put(173, "minecraft:coal_block");
        ID_MAP.put(174, "minecraft:packed_ice");
        ID_MAP.put(179, "minecraft:red_sandstone");
        ID_MAP.put(180, "minecraft:red_sandstone_stairs");
        ID_MAP.put(182, "minecraft:red_sandstone_slab");
        ID_MAP.put(198, "minecraft:end_rod");
        ID_MAP.put(201, "minecraft:purpur_block");
        ID_MAP.put(202, "minecraft:purpur_pillar");
        ID_MAP.put(203, "minecraft:purpur_stairs");
        ID_MAP.put(205, "minecraft:purpur_slab");
        ID_MAP.put(206, "minecraft:end_stone_bricks");
        ID_MAP.put(208, "minecraft:dirt_path");
        ID_MAP.put(213, "minecraft:magma_block");
        ID_MAP.put(214, "minecraft:nether_wart_block");
        ID_MAP.put(215, "minecraft:red_nether_bricks");
        ID_MAP.put(216, "minecraft:bone_block");
        ID_MAP.put(218, "minecraft:observer");
        ID_MAP.put(235, "minecraft:white_shulker_box");
        ID_MAP.put(251, "minecraft:white_concrete");
        ID_MAP.put(255, "minecraft:structure_block");
    }

    public static Result read(Map<String, Object> root) throws IOException {
        int w = NbtReader.intOf(NbtReader.require(root, "Width", "root"), "Width");
        int h = NbtReader.intOf(NbtReader.require(root, "Height", "root"), "Height");
        int l = NbtReader.intOf(NbtReader.require(root, "Length", "root"), "Length");
        byte[] blocks = NbtReader.byteArrayOf(NbtReader.require(root, "Blocks", "root"), "Blocks");
        byte[] data = root.containsKey("Data")
                ? NbtReader.byteArrayOf(root.get("Data"), "Data") : new byte[blocks.length];
        byte[] add = root.containsKey("AddBlocks")
                ? NbtReader.byteArrayOf(root.get("AddBlocks"), "AddBlocks") : null;

        int total = w * h * l;
        if (blocks.length < total)
            throw new IOException("Blocks 长度不足: " + blocks.length + " < " + total);
        if (data.length < total) data = java.util.Arrays.copyOf(data, total);

        List<Voxel> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        java.util.TreeSet<Integer> unknown = new java.util.TreeSet<>();
        int skipped = 0;

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * l + z) * w + x;
                    int id = blocks[i] & 0xFF;
                    if (add != null) {
                        int nib = add[i / 2] & 0xFF;
                        id += ((i % 2 == 0) ? (nib >> 4) : (nib & 0x0F)) << 8;
                    }
                    if (id == 0) continue; // air
                    String name = ID_MAP.get(id);
                    if (name == null) { unknown.add(id); skipped++; continue; }

                    int d = data[i] & 0xFF;
                    String props = "";
                    if (id == 26) { // 床：part/facing 由 data 还原
                        String part = (d & 0x8) != 0 ? "head" : "foot";
                        String facing = BED_FACING[d & 0x3];
                        props = "[facing=" + facing + ",part=" + part + "]";
                    } else if (id == 54 || id == 146) { // 箱子/陷阱箱：data 2–5 → facing（其余值裸写）
                        String facing = switch (d & 0x7) {
                            case 2 -> "north";
                            case 3 -> "south";
                            case 4 -> "west";
                            case 5 -> "east";
                            default -> null;
                        };
                        if (facing != null) props = "[facing=" + facing + "]";
                    }
                    out.add(new Voxel(x, y, z, name, props));
                }
            }
        }
        for (Integer uid : unknown)
            warnings.add("未知数字 id " + uid + "（不在内置映射表）：已跳过 " + "——请人工确认是否需补表");
        if (skipped > 0)
            warnings.add("共跳过 " + skipped + " 个未知 id 方块（.schematic 通道 V1 约定：未知 id 不进蓝图）");
        warnings.add(".schematic 通道已知损耗：除床（part/facing）外方块状态一律丢失；床色一律 red_bed；数字 id 为 pre-flattening 口径");
        return new Result(out, warnings);
    }
}
