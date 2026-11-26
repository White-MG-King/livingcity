package bpconv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试样本生成器：手工构造四种格式的二进制样本（零游戏依赖，字节级确定）。
 * 样本内容统一为 tiny_house（3×2×3）：
 *   y=0 整层 oak_planks 地板（9 块）
 *   y=1 red_bed foot(1,1,0)+head(1,1,1)（facing=south）、chest(2,1,0)、torch(0,1,2)
 * 四种格式（nbt/litematic/schematic/Axiom .bp）转换结果必须逐字节一致（交叉验证黄金回归）。
 * 另有 axiom_span.bp：18×1×2 石阵跨两个 16³ 区块段（验证多段合并）。
 */
public final class MakeTestData {

    /** tiny_house 各格式样本源坐标整体偏移（2026-11-10）：锁死 --entrance 为归一化输出坐标语义——
     * 源 min≠0 时旧实现会把入口错误平移；归一化后黄金输出不变。axiom_span 等无入口样本保持原点。 */
    private static final int OX = 100, OY = 64, OZ = 100;

    public static void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("tiny_house.nbt"), tinyHouseNbt(true));
        Files.write(dir.resolve("broken_floor.nbt"), tinyHouseNbt(false));
        Files.write(dir.resolve("tiny_house.litematic"), tinyHouseLitematic());
        Files.write(dir.resolve("tiny_house.schematic"), tinyHouseSchematic());
        Files.write(dir.resolve("tiny_house.bp"), tinyHouseAxiom());
        Files.write(dir.resolve("axiom_span.bp"), axiomSpan());
        Files.write(dir.resolve("unknown_block.nbt"), unknownBlockNbt());
        Files.write(dir.resolve("wide_road.nbt"), wideRoadNbt());
    }

    // ---- 原版结构方块 .nbt ----

    /** withFullFloor=false 时抽掉 (1,0,1) 地板 → 触发 V1 校验失败。 */
    private static byte[] tinyHouseNbt(boolean withFullFloor) throws IOException {
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:oak_planks"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "foot"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "head"));
        palette.add(NbtWrite.paletteEntry("minecraft:chest"));
        palette.add(NbtWrite.paletteEntry("minecraft:torch"));

        List<Object> blocks = new ArrayList<>();
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++) {
                if (!withFullFloor && x == 1 && z == 1) continue; // 挖洞
                blocks.add(structBlock(OX + x, OY, OZ + z, 0));
            }
        blocks.add(structBlock(OX + 1, OY + 1, OZ, 1));     // bed foot
        blocks.add(structBlock(OX + 1, OY + 1, OZ + 1, 2)); // bed head
        blocks.add(structBlock(OX + 2, OY + 1, OZ, 3));     // chest
        blocks.add(structBlock(OX, OY + 1, OZ + 2, 4));     // torch

        Map<String, Object> root = NbtWrite.compound();
        root.put("size", NbtWrite.ints(3, 2, 3));
        root.put("entities", new ArrayList<>());
        root.put("blocks", blocks);
        root.put("palette", palette);
        root.put("DataVersion", 3465);
        return NbtWrite.root("", root);
    }

    private static Map<String, Object> structBlock(int x, int y, int z, int state) {
        Map<String, Object> b = NbtWrite.compound();
        b.put("pos", NbtWrite.ints(x, y, z));
        b.put("state", state);
        return b;
    }

    // ---- .litematic ----

    private static byte[] tinyHouseLitematic() throws IOException {
        // 真实 Litematica 文件的 palette 必含 air（空位指向 air）——样本如实模拟
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:air"));
        palette.add(NbtWrite.paletteEntry("minecraft:oak_planks"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "foot"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "head"));
        palette.add(NbtWrite.paletteEntry("minecraft:chest"));
        palette.add(NbtWrite.paletteEntry("minecraft:torch"));

        // bits = max(2, ceil(log2(6))) = 3；真实 Litematica 打包按绝对位序 i*bits 可跨 long，
        // 体积 18 → ceil(18*3/64) = 1 个 long（单 long 内与逐条移位写法一致）
        // 区域索引序 i = (y*sizeZ + z)*sizeX + x；空位 state 0 = air
        long packed = 0;
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++)
                packed |= 1L << (idx(x, 0, z) * 3);           // 地板 → state 1
        packed |= 2L << (idx(1, 1, 0) * 3); // bed foot → state 2
        packed |= 4L << (idx(2, 1, 0) * 3); // chest    → state 4
        packed |= 3L << (idx(1, 1, 1) * 3); // bed head → state 3
        packed |= 5L << (idx(0, 1, 2) * 3); // torch    → state 5

        Map<String, Object> region = NbtWrite.compound();
        // 真实 Litematica 的 Position/Size 是 Compound{x,y,z}（小写键；Size 可为负=圈选方向，
        // 2026-11-10 经真实素材验证）——样本如实模拟：Size.x 取负锁住读取端的 abs 行为
        Map<String, Object> pos = NbtWrite.compound();
        pos.put("x", OX); pos.put("y", OY); pos.put("z", OZ);
        Map<String, Object> size = NbtWrite.compound();
        size.put("x", -3); size.put("y", 2); size.put("z", 3);
        region.put("Position", pos);
        region.put("Size", size);
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", new long[]{packed});

        Map<String, Object> regions = NbtWrite.compound();
        regions.put("tiny", region);

        Map<String, Object> root = NbtWrite.compound();
        root.put("Version", 6);          // 真实 1.20.1 Litematica 口径（2026-11-10 素材验证）
        root.put("SubVersion", 1);
        root.put("MinecraftDataVersion", 3465);
        root.put("Regions", regions);
        return NbtWrite.root("", root);
    }

    private static int idx(int x, int y, int z) { return (y * 3 + z) * 3 + x; }

    // ---- .schematic（MCEdit 经典） ----

    private static byte[] tinyHouseSchematic() throws IOException {
        int w = 3, h = 2, l = 3;
        byte[] blocks = new byte[w * h * l];
        byte[] data = new byte[w * h * l];
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++)
                blocks[(0 * l + z) * w + x] = 5;               // oak_planks
        blocks[(1 * l + 0) * w + 1] = 26; data[(1 * l + 0) * w + 1] = 0; // bed foot, south
        blocks[(1 * l + 0) * w + 2] = 54;                                // chest
        blocks[(1 * l + 1) * w + 1] = 26; data[(1 * l + 1) * w + 1] = 8; // bed head, south
        blocks[(1 * l + 2) * w + 0] = 50; data[(1 * l + 2) * w + 0] = 5; // torch (floor)

        Map<String, Object> root = NbtWrite.compound();
        root.put("Width", (short) w);
        root.put("Height", (short) h);
        root.put("Length", (short) l);
        root.put("Materials", "Alpha");
        root.put("Blocks", blocks);
        root.put("Data", data);
        root.put("Entities", new ArrayList<>());
        root.put("TileEntities", new ArrayList<>());
        return NbtWrite.root("Schematic", root);
    }

    // ---- Axiom .bp 容器（magic + 头部NBT + 缩略图 + gzip方块数据NBT） ----

    private static final int AXIOM_MAGIC = 182827830;

    /** 1×1 透明 PNG（缩略图段占位；读取端直接跳过，内容与真实 Axiom 缩略图等价位）。 */
    private static byte[] tinyPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    private static byte[] axiomContainer(String name, String author, int blockCount, boolean containsAir,
                                         byte[] thumb, List<Object> sections) throws IOException {
        Map<String, Object> header = NbtWrite.compound();
        header.put("Version", 1L);
        header.put("Name", name);
        header.put("Author", author);
        List<Object> tags = new ArrayList<>();
        tags.add("test");
        header.put("Tags", tags);
        header.put("ThumbnailYaw", 135f);
        header.put("ThumbnailPitch", 30f);
        header.put("LockedThumbnail", (byte) 0);
        header.put("BlockCount", blockCount);
        header.put("ContainsAir", (byte) (containsAir ? 1 : 0));

        Map<String, Object> blockData = NbtWrite.compound();
        blockData.put("DataVersion", 3465);
        blockData.put("BlockRegion", sections);
        blockData.put("BlockEntities", new ArrayList<>());
        blockData.put("Entities", new ArrayList<>());

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(bos);
        d.writeInt(AXIOM_MAGIC);
        byte[] h = NbtWrite.root("", header);
        d.writeInt(h.length);
        d.write(h);
        d.writeInt(thumb.length);
        d.write(thumb);
        byte[] b = NbtWrite.root("", blockData);
        java.io.ByteArrayOutputStream gzb = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(gzb)) { g.write(b); }
        byte[] gb = gzb.toByteArray();
        d.writeInt(gb.length);
        d.write(gb);
        d.flush();
        return bos.toByteArray();
    }

    /**
     * 单个 16³ 区块段。placements 每项 = {lx, ly, lz, palette索引}；
     * 打包口径与 Axiom 一致：bits = max(4, ceil(log2(palette)))，条目不跨 long，
     * 索引 i = ly*256 + lz*16 + lx，data 固定 4096 条。
     */
    private static Map<String, Object> axiomSection(int x, int y, int z,
                                                    List<Object> palette, int[][] placements) {
        int bits = 4;
        while ((1L << bits) < palette.size()) bits++;
        int perLong = 64 / bits;
        long[] data = new long[(4096 + perLong - 1) / perLong];
        for (int[] p : placements) {
            int i = p[1] * 256 + p[2] * 16 + p[0];
            data[i / perLong] |= ((long) p[3]) << ((i % perLong) * bits);
        }
        Map<String, Object> bs = NbtWrite.compound();
        bs.put("palette", palette);
        bs.put("data", data);
        Map<String, Object> sec = NbtWrite.compound();
        sec.put("X", x);
        sec.put("Y", y);
        sec.put("Z", z);
        sec.put("BlockStates", bs);
        return sec;
    }

    /** tiny_house 的 Axiom 容器版：内容与其余三格式完全一致；额外放 1 个显式 air 体素（应被剔除）。 */
    private static byte[] tinyHouseAxiom() throws IOException {
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:structure_void")); // index 0：空位填充（约定）
        palette.add(NbtWrite.paletteEntry("minecraft:oak_planks"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "foot"));
        palette.add(NbtWrite.paletteEntry("minecraft:red_bed", "facing", "south", "part", "head"));
        palette.add(NbtWrite.paletteEntry("minecraft:chest"));
        palette.add(NbtWrite.paletteEntry("minecraft:torch"));
        palette.add(NbtWrite.paletteEntry("minecraft:air"));

        // 样本落在世界坐标 (OX,OY,OZ)=(100,64,100)：section (6,4,6)，局部起点 (4,0,4)
        final int LX = OX % 16, LY = OY % 16, LZ = OZ % 16;
        List<int[]> placements = new ArrayList<>();
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++)
                placements.add(new int[]{LX + x, LY, LZ + z, 1});          // 地板
        placements.add(new int[]{LX + 1, LY + 1, LZ, 2});                  // bed foot
        placements.add(new int[]{LX + 1, LY + 1, LZ + 1, 3});              // bed head
        placements.add(new int[]{LX + 2, LY + 1, LZ, 4});                  // chest
        placements.add(new int[]{LX, LY + 1, LZ + 2, 5});                  // torch
        placements.add(new int[]{LX + 2, LY + 1, LZ + 2, 6});              // 显式 air（包围盒内部，应被剔除）

        int[][] arr = placements.toArray(new int[0][]);
        List<Object> sections = new ArrayList<>();
        sections.add(axiomSection(OX / 16, OY / 16, OZ / 16, palette, arr));
        return axiomContainer("tiny_house", "bpconv-testdata", 13, true, tinyPng(), sections);
    }

    /** 18×1×2 石阵，横跨 section X=0 与 X=1（验证多区块段合并 + 归一化）。 */
    private static byte[] axiomSpan() throws IOException {
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:structure_void"));
        palette.add(NbtWrite.paletteEntry("minecraft:stone"));

        List<int[]> sec0 = new ArrayList<>();
        for (int z = 0; z < 2; z++)
            for (int x = 0; x < 16; x++)
                sec0.add(new int[]{x, 0, z, 1});
        List<int[]> sec1 = new ArrayList<>();
        for (int z = 0; z < 2; z++)
            for (int x = 0; x < 2; x++)
                sec1.add(new int[]{x, 0, z, 1});

        List<Object> sections = new ArrayList<>();
        sections.add(axiomSection(0, 0, 0, palette, sec0.toArray(new int[0][])));
        sections.add(axiomSection(1, 0, 0, palette, sec1.toArray(new int[0][])));
        // 缩略图段给 0 长度（真实文件是 PNG；读取端只跳过——两种长度都要能过）
        return axiomContainer("axiom_span", "bpconv-testdata", 36, false, new byte[0], sections);
    }

    // ---- 未知方块样本（beacon 不在分类表 → 默认 S + 警告） ----

    private static byte[] unknownBlockNbt() throws IOException {
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:stone"));
        palette.add(NbtWrite.paletteEntry("minecraft:beacon"));

        List<Object> blocks = new ArrayList<>();
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++)
                blocks.add(structBlock(x, 0, z, 0));
        blocks.add(structBlock(1, 1, 1, 1)); // beacon

        Map<String, Object> root = NbtWrite.compound();
        root.put("size", NbtWrite.ints(3, 2, 3));
        root.put("entities", new ArrayList<>());
        root.put("blocks", blocks);
        root.put("palette", palette);
        root.put("DataVersion", 3465);
        return NbtWrite.root("", root);
    }

    // ---- V5 软限样本：65×1×1 石路 → 警告不失败 ----

    private static byte[] wideRoadNbt() throws IOException {
        List<Object> palette = new ArrayList<>();
        palette.add(NbtWrite.paletteEntry("minecraft:stone"));

        List<Object> blocks = new ArrayList<>();
        for (int x = 0; x < 65; x++) blocks.add(structBlock(x, 0, 0, 0));

        Map<String, Object> root = NbtWrite.compound();
        root.put("size", NbtWrite.ints(65, 1, 1));
        root.put("entities", new ArrayList<>());
        root.put("blocks", blocks);
        root.put("palette", palette);
        root.put("DataVersion", 3465);
        return NbtWrite.root("", root);
    }
}
