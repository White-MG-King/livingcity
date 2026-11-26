package bpconv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * T1 格式④：Axiom 模组 .bp 蓝图（自定义容器，非纯 NBT 文件）。
 * 容器布局（全部 u32 大端长度前缀）：
 *   magic(=182827830) + 头部NBT(未压缩) + 缩略图PNG + 方块数据NBT(gzip)
 * 头部：{ Version:Long, Name, Author, Tags:[String], ThumbnailYaw/Pitch:Float,
 *         LockedThumbnail:Byte, BlockCount:Int, ContainsAir:Byte }
 * 方块数据：{ DataVersion:Int,
 *             BlockRegion:[{ X,Y,Z:Int, BlockStates:{ palette:[{Name,Properties?}], data:LongArray } }],
 *             BlockEntities:[…], Entities:[…] }
 * 每段为 16³ 区块 section：data 固定 4096 条，索引 i = ly*256 + lz*16 + lx；
 * bits = max(4, ceil(log2(palette)))，条目不跨 long（1.16+ 原版打包口径）。
 * palette 首项约定 minecraft:structure_void 作空位填充 → 视作空气跳过；
 * 显式 minecraft:air 照常产出体素（Main 统一去 air）。
 * 坐标 = section 坐标*16 + 局部坐标（支持负 section）。
 * 格式来源：社区开源写入实现 paxxxw/litematic-converter 交叉 AxiomBlueprintExporter / axiom-viewer。
 */
public final class AxiomReader {

    public static final int MAGIC = 182827830;
    private static final String VOID_ID = "minecraft:structure_void";

    public static final class Result {
        public final List<Voxel> voxels;
        public final List<String> warnings;
        public final String name;      // 头部 Name（缺省空串）
        public final String author;    // 头部 Author（缺省空串）
        public final int dataVersion;  // 0 = 未声明
        public final int sectionCount;
        Result(List<Voxel> v, List<String> w, String name, String author, int dv, int sections) {
            this.voxels = v; this.warnings = w; this.name = name; this.author = author;
            this.dataVersion = dv; this.sectionCount = sections;
        }
    }

    /** 文件头 magic 判断：Axiom 容器 or 自家文本 .bp（内容自动识别的唯一依据）。 */
    public static boolean isAxiom(byte[] raw) {
        if (raw.length < 4) return false;
        long m = ((raw[0] & 0xFFL) << 24) | ((raw[1] & 0xFFL) << 16)
               | ((raw[2] & 0xFFL) << 8) | (raw[3] & 0xFFL);
        return m == (MAGIC & 0xFFFFFFFFL);
    }

    public static Result read(byte[] raw) throws IOException {
        if (!isAxiom(raw)) throw new IOException("不是 Axiom .bp 容器（magic 缺失）");
        List<String> warnings = new ArrayList<>();
        int[] pos = {4};

        // ① 头部 NBT（未压缩；只取 Name/Author 作报告回显，元数据以 CLI 为准）
        Map<String, Object> header = NbtReader.readRoot(slice(raw, pos, "头部"));
        String name = header.get("Name") instanceof String ? (String) header.get("Name") : "";
        String author = header.get("Author") instanceof String ? (String) header.get("Author") : "";

        // ② 缩略图 PNG —— 直接跳过
        slice(raw, pos, "缩略图");

        // ③ 方块数据 NBT（gzip，NbtReader 自动检测解压）
        Map<String, Object> data = NbtReader.readRoot(slice(raw, pos, "方块数据"));
        if (pos[0] != raw.length)
            warnings.add("容器在方块数据段后还有 " + (raw.length - pos[0])
                    + " 字节未读（可能是更新版 Axiom 的附加段，已忽略）");

        int dv = data.containsKey("DataVersion")
                ? NbtReader.intOf(data.get("DataVersion"), "DataVersion") : 0;
        if (dv != 0 && dv != 3465)
            warnings.add("DataVersion=" + dv + " ≠ 3465（1.20.1），方块名可能需迁移"
                    + "（若在高版本 Axiom 里搭建，请核对是否用了 1.20.1 没有的新方块）");

        List<Object> regions = NbtReader.list(
                NbtReader.require(data, "BlockRegion", "方块数据"), "BlockRegion");
        List<Voxel> out = new ArrayList<>();
        for (int s = 0; s < regions.size(); s++) {
            String path = "BlockRegion[" + s + "]";
            Map<String, Object> sec = NbtReader.compound(regions.get(s), path);
            int secX = NbtReader.intOf(NbtReader.require(sec, "X", path), path + ".X");
            int secY = NbtReader.intOf(NbtReader.require(sec, "Y", path), path + ".Y");
            int secZ = NbtReader.intOf(NbtReader.require(sec, "Z", path), path + ".Z");

            Map<String, Object> bs = NbtReader.compound(
                    NbtReader.require(sec, "BlockStates", path), path + ".BlockStates");
            List<Object> paletteList = NbtReader.list(
                    NbtReader.require(bs, "palette", path), path + ".BlockStates.palette");
            String[] ids = new String[paletteList.size()];
            String[] props = new String[paletteList.size()];
            for (int i = 0; i < paletteList.size(); i++) {
                String pp = path + ".BlockStates.palette[" + i + "]";
                Map<String, Object> e = NbtReader.compound(paletteList.get(i), pp);
                ids[i] = NbtReader.stringOf(NbtReader.require(e, "Name", pp), pp + ".Name");
                Object p = e.get("Properties");
                props[i] = p == null ? "" : Voxel.normalizeProps(NbtReader.compound(p, pp + ".Properties"));
            }

            long[] arr = NbtReader.longArrayOf(
                    NbtReader.require(bs, "data", path), path + ".BlockStates.data");
            int bits = 4; // Axiom 口径：最少 4 bits
            while ((1L << bits) < paletteList.size()) bits++;
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            int needLongs = (4096 + perLong - 1) / perLong;
            if (arr.length < needLongs)
                throw new IOException(path + ".BlockStates.data 长度不足: " + arr.length
                        + " < " + needLongs + "（bits=" + bits + "）");

            for (int i = 0; i < 4096; i++) {
                int state = (int) ((arr[i / perLong] >>> ((long) (i % perLong) * bits)) & mask);
                if (state >= ids.length)
                    throw new IOException(path + " 索引 " + i + " 的 state=" + state
                            + " 越界（palette 大小 " + ids.length + "）");
                String id = ids[state];
                if (VOID_ID.equals(id)) continue; // 空位填充
                int lx = i % 16, lz = (i / 16) % 16, ly = i / 256;
                out.add(new Voxel(secX * 16 + lx, secY * 16 + ly, secZ * 16 + lz, id, props[state]));
            }
        }

        int be = countList(data, "BlockEntities");
        int en = countList(data, "Entities");
        if (be + en > 0)
            warnings.add("Axiom 通道丢弃 BlockEntities " + be + " 个 / Entities " + en
                    + " 个（V1 约定：蓝图只含方块，回放无副作用）");

        return new Result(out, warnings, name, author, dv, regions.size());
    }

    private static int countList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? ((List<?>) v).size() : 0;
    }

    /** 读一段「u32 BE 长度 + 内容」，pos[0] 为游标（单元素数组传引用）。 */
    private static byte[] slice(byte[] raw, int[] pos, String what) throws IOException {
        if (pos[0] + 4 > raw.length) throw new IOException("Axiom 容器截断：缺 " + what + " 段长度前缀");
        long len = ((raw[pos[0]] & 0xFFL) << 24) | ((raw[pos[0] + 1] & 0xFFL) << 16)
                 | ((raw[pos[0] + 2] & 0xFFL) << 8) | (raw[pos[0] + 3] & 0xFFL);
        pos[0] += 4;
        if (len > raw.length - (long) pos[0])
            throw new IOException("Axiom 容器截断：" + what + "段声明 " + len
                    + " 字节，实际剩 " + (raw.length - pos[0]));
        byte[] out = Arrays.copyOfRange(raw, pos[0], pos[0] + (int) len);
        pos[0] += (int) len;
        return out;
    }
}
