import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 测试样本生成器（手工构造，仅用于黄金回归）。
 * 输出同一栋 3×3×3 小屋的 litematic / 原版结构 nbt / MCEdit schematic / Axiom .bp 四种格式。
 */
public final class GenSamples {

    private static final int SIZE = 3;

    /** 源坐标整体偏移（2026-11-10）：锁死 --entrance 为归一化输出坐标语义（源 min≠0 时
     * 旧实现会把入口错误平移）。schematic 格式无位置元数据，天然保持原点——
     * 恰好交叉验证「不同源坐标原点 → 归一化后输出一致」。 */
    private static final int OX = 100, OY = 64, OZ = 100;
    private static final int LX = OX % 16, LY = OY % 16, LZ = OZ % 16;

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "testdata/inputs");
        Files.createDirectories(outDir);

        writeLitematic(outDir.resolve("sample_house.litematic"));
        writeStructure(outDir.resolve("sample_house.nbt"));
        writeSchematic(outDir.resolve("sample_house.schematic"));
        writeAxiom(outDir.resolve("sample_house.bp"));

        System.out.println("samples written to " + outDir.toAbsolutePath());
    }

    // ================= litematic =================

    private static void writeLitematic(Path path) throws IOException {
        List<T> palette = new ArrayList<>();
        palette.add(compound(Map.of("Name", string("minecraft:air"))));
        palette.add(compound(Map.of("Name", string("minecraft:stone"))));
        palette.add(compound(Map.of("Name", string("minecraft:oak_planks"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("foot"), "facing", string("east"))))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("head"), "facing", string("east"))))));
        palette.add(compound(Map.of("Name", string("minecraft:torch"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:chest"),
                "Properties", compound(Map.of("facing", string("south"))))));

        int bits = 3;
        int[] states = new int[SIZE * SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    states[index(x, y, z)] = paletteIndex(x, y, z);
                }
            }
        }
        long[] packed = pack(states, bits);

        Map<String, T> region = new LinkedHashMap<>();
        // 真实 Litematica 格式：Position/Size 是 Compound{x,y,z}（小写键；Size 可为负=圈选方向，
        // 2026-11-10 经真实素材验证；此前误写为 List<Int>[3]——读取端多写法兼容，样本以真实格式为准）
        Map<String, T> pos = new LinkedHashMap<>();
        pos.put("x", ints(OX));
        pos.put("y", ints(OY));
        pos.put("z", ints(OZ));
        Map<String, T> size = new LinkedHashMap<>();
        size.put("x", ints(-SIZE));
        size.put("y", ints(SIZE));
        size.put("z", ints(SIZE));
        region.put("Position", compound(pos));
        region.put("Size", compound(size));
        region.put("BlockStatePalette", list(10, palette));
        region.put("BlockStates", longArray(packed));

        Map<String, T> root = new LinkedHashMap<>();
        root.put("Version", ints(6));
        root.put("SubVersion", ints(1));
        root.put("MinecraftDataVersion", ints(3465));
        root.put("Metadata", compound(Map.of(
                "Name", string("sample_house"),
                "Author", string("bpconv-testdata"),
                "Description", string("3x3x3 hand-built house"),
                "RegionCount", ints(1),
                "TotalBlocks", ints(9),
                "TotalVolume", ints(27))));
        root.put("Regions", compound(Map.of("main", compound(region))));

        writeGzip(path, "Litematic", compound(root));
    }

    private static int paletteIndex(int x, int y, int z) {
        if (y == 0) return 1;                       // 地基
        if (y == 1) {
            if (x == 0 && z == 0) return 2;         // 木板
            if (x == 0 && z == 1) return 3;         // 床头脚
            if (x == 1 && z == 1) return 4;         // 床头
            if (x == 2 && z == 1) return 5;         // 火把
            if (x == 0 && z == 2) return 6;         // 箱子
        }
        if (y == 2) {
            if (x == 0 && z == 0) return 2;
            if (x == 1 && z == 1) return 2;
        }
        return 0;
    }

    private static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    /** 真实 Litematica 打包：条目按绝对位序 i*bits 连续排布、可跨 long（2026-11-10 经真实素材验证）。 */
    private static long[] pack(int[] values, int bits) {
        long[] out = new long[(values.length * bits + 63) / 64];
        for (int i = 0; i < values.length; i++) {
            int bitIndex = i * bits;
            int longIndex = bitIndex >>> 6;
            int offset = bitIndex & 63;
            out[longIndex] |= ((long) values[i]) << offset;
            if (offset + bits > 64 && longIndex + 1 < out.length) {
                out[longIndex + 1] |= ((long) values[i]) >>> (64 - offset);
            }
        }
        return out;
    }

    // ================= 原版 structure =================

    private static void writeStructure(Path path) throws IOException {
        List<T> palette = new ArrayList<>();
        palette.add(compound(Map.of("Name", string("minecraft:stone"))));
        palette.add(compound(Map.of("Name", string("minecraft:oak_planks"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("foot"), "facing", string("east"))))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("head"), "facing", string("east"))))));
        palette.add(compound(Map.of("Name", string("minecraft:torch"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:chest"),
                "Properties", compound(Map.of("facing", string("south"))))));

        List<T> blocks = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int paletteIndex = paletteIndex(x, y, z);
                    if (paletteIndex == 0) continue;
                    List<T> pos = new ArrayList<>();
                    pos.add(ints(x + OX));
                    pos.add(ints(y + OY));
                    pos.add(ints(z + OZ));
                    blocks.add(compound(Map.of(
                            "pos", list(3, pos),
                            "state", ints(paletteIndex - 1))));
                }
            }
        }

        Map<String, T> root = new LinkedHashMap<>();
        root.put("DataVersion", ints(3465));
        List<T> size = new ArrayList<>();
        size.add(ints(SIZE));
        size.add(ints(SIZE));
        size.add(ints(SIZE));
        root.put("size", list(3, size));
        root.put("palette", list(10, palette));
        root.put("blocks", list(10, blocks));

        writeGzip(path, "", compound(root));
    }

    // ================= MCEdit schematic =================

    private static void writeSchematic(Path path) throws IOException {
        int volume = SIZE * SIZE * SIZE;
        byte[] blocks = new byte[volume];
        byte[] data = new byte[volume];
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int index = index(x, y, z);
                    switch (paletteIndex(x, y, z)) {
                        case 1 -> blocks[index] = (byte) 1;    // stone
                        case 2 -> blocks[index] = (byte) 5;    // oak planks
                        case 3 -> {                            // bed foot facing east
                            blocks[index] = (byte) 26;
                            data[index] = 3;
                        }
                        case 4 -> {                            // bed head facing east
                            blocks[index] = (byte) 26;
                            data[index] = 11;
                        }
                        case 5 -> {                            // standing torch
                            blocks[index] = (byte) 50;
                            data[index] = 5;
                        }
                        case 6 -> {                            // chest facing south
                            blocks[index] = (byte) 54;
                            data[index] = 3;
                        }
                        default -> {
                        }
                    }
                }
            }
        }

        Map<String, T> root = new LinkedHashMap<>();
        root.put("Width", shorts((short) SIZE));
        root.put("Height", shorts((short) SIZE));
        root.put("Length", shorts((short) SIZE));
        root.put("Materials", string("Alpha"));
        root.put("Blocks", byteArray(blocks));
        root.put("Data", byteArray(data));

        writeGzip(path, "Schematic", compound(root));
    }

    // ================= Axiom .bp 容器 =================

    /**
     * Axiom 容器：magic(u32 BE) + 头部NBT(未压缩) + 缩略图(0长度占位) + 方块数据NBT(gzip)，
     * 每段带 u32 BE 长度前缀。单区块段（3×3×3 < 16³），palette[0] 约定 structure_void 填充，
     * 与 paletteIndex() 的 1..6 一一对应；打包 bits = max(4, ceil(log2(palette)))，条目不跨 long，
     * 索引 i = ly*256 + lz*16 + lx。
     */
    private static void writeAxiom(Path path) throws IOException {
        List<T> palette = new ArrayList<>();
        palette.add(compound(Map.of("Name", string("minecraft:structure_void"))));
        palette.add(compound(Map.of("Name", string("minecraft:stone"))));
        palette.add(compound(Map.of("Name", string("minecraft:oak_planks"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("foot"), "facing", string("east"))))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:red_bed"),
                "Properties", compound(Map.of("part", string("head"), "facing", string("east"))))));
        palette.add(compound(Map.of("Name", string("minecraft:torch"))));
        palette.add(compound(Map.of(
                "Name", string("minecraft:chest"),
                "Properties", compound(Map.of("facing", string("south"))))));

        int bits = 4; // max(4, ceil(log2(7))) = 4
        int perLong = 64 / bits;
        long[] data = new long[(4096 + perLong - 1) / perLong];
        int blockCount = 0;
        // 样本落在世界坐标 (OX,OY,OZ)=(100,64,100)：section (6,4,6)，局部起点 (LX,LY,LZ)=(4,0,4)
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int idx = paletteIndex(x, y, z);
                    if (idx == 0) continue;
                    int i = (LY + y) * 256 + (LZ + z) * 16 + (LX + x);
                    data[i / perLong] |= (long) idx << ((i % perLong) * bits);
                    blockCount++;
                }
            }
        }

        Map<String, T> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", list(10, palette));
        blockStates.put("data", longArray(data));

        Map<String, T> section = new LinkedHashMap<>();
        section.put("X", ints(OX / 16));
        section.put("Y", ints(OY / 16));
        section.put("Z", ints(OZ / 16));
        section.put("BlockStates", compound(blockStates));

        Map<String, T> blockData = new LinkedHashMap<>();
        blockData.put("DataVersion", ints(3465));
        blockData.put("BlockRegion", list(10, new ArrayList<>(List.of(compound(section)))));
        blockData.put("BlockEntities", list(10, new ArrayList<>()));
        blockData.put("Entities", list(10, new ArrayList<>()));

        Map<String, T> header = new LinkedHashMap<>();
        header.put("Version", longs(1L));
        header.put("Name", string("sample_house"));
        header.put("Author", string("bpconv-testdata"));
        header.put("Tags", list(8, new ArrayList<>(List.of(string("test")))));
        header.put("ThumbnailYaw", floats(135f));
        header.put("ThumbnailPitch", floats(30f));
        header.put("LockedThumbnail", bytes((byte) 0));
        header.put("BlockCount", ints(blockCount));
        header.put("ContainsAir", bytes((byte) 0));

        byte[] headerBytes = serializeRoot("", compound(header));
        byte[] dataBytes = gzipBytes(serializeRoot("", compound(blockData)));

        Files.createDirectories(path.getParent());
        try (OutputStream file = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(file)) {
            out.writeInt(182827830);
            out.writeInt(headerBytes.length);
            out.write(headerBytes);
            out.writeInt(0); // 缩略图段 0 长度（PNG 缩略图情形由主闸门 MakeTestData 覆盖）
            out.writeInt(dataBytes.length);
            out.write(dataBytes);
        }
    }

    // ================= 最小 NBT 写入器 =================

    private interface T {
        int type();

        void write(DataOutputStream out) throws IOException;
    }

    private record ByteTag(byte value) implements T {
        public int type() {
            return 1;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeByte(value);
        }
    }

    private record ShortTag(short value) implements T {
        public int type() {
            return 2;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeShort(value);
        }
    }

    private record IntTag(int value) implements T {
        public int type() {
            return 3;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeInt(value);
        }
    }

    private record LongTag(long value) implements T {
        public int type() {
            return 4;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeLong(value);
        }
    }

    private record FloatTag(float value) implements T {
        public int type() {
            return 5;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeFloat(value);
        }
    }

    private record StringTag(String value) implements T {
        public int type() {
            return 8;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(value);
        }
    }

    private record ByteArrayTag(byte[] value) implements T {
        public int type() {
            return 7;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeInt(value.length);
            out.write(value);
        }
    }

    private record LongArrayTag(long[] value) implements T {
        public int type() {
            return 12;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeInt(value.length);
            for (long v : value) out.writeLong(v);
        }
    }

    private record ListTag(int elementType, List<T> value) implements T {
        public int type() {
            return 9;
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeByte(elementType);
            out.writeInt(value.size());
            for (T element : value) element.write(out);
        }
    }

    private record CompoundTag(Map<String, T> value) implements T {
        public int type() {
            return 10;
        }

        public void write(DataOutputStream out) throws IOException {
            for (Map.Entry<String, T> entry : value.entrySet()) {
                out.writeByte(entry.getValue().type());
                out.writeUTF(entry.getKey());
                entry.getValue().write(out);
            }
            out.writeByte(0);
        }
    }

    private static T ints(int value) {
        return new IntTag(value);
    }

    private static T longs(long value) {
        return new LongTag(value);
    }

    private static T floats(float value) {
        return new FloatTag(value);
    }

    private static T bytes(byte value) {
        return new ByteTag(value);
    }

    private static T shorts(short value) {
        return new ShortTag(value);
    }

    private static T string(String value) {
        return new StringTag(value);
    }

    private static T byteArray(byte[] value) {
        return new ByteArrayTag(value);
    }

    private static T longArray(long[] value) {
        return new LongArrayTag(value);
    }

    private static T list(int elementType, List<T> value) {
        return new ListTag(elementType, value);
    }

    private static T compound(Map<String, T> value) {
        return new CompoundTag(new LinkedHashMap<>(value));
    }

    private static byte[] serializeRoot(String name, T root) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(root.type());
        out.writeUTF(name);
        root.write(out);
        out.flush();
        return bos.toByteArray();
    }

    private static byte[] gzipBytes(byte[] raw) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos)) {
            g.write(raw);
        }
        return bos.toByteArray();
    }

    private static void writeGzip(Path path, String name, T root) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream file = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(new GZIPOutputStream(file))) {
            out.writeByte(root.type());
            out.writeUTF(name);
            root.write(out);
        }
    }
}
