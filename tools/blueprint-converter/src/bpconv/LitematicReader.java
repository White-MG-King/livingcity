package bpconv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * T1 格式①：.litematic（Litematica，网上收集最常见）。
 * 结构：{ Version, MinecraftDataVersion, Regions:{ <名>:{ Position:{x,y,z}, Size:{x,y,z},
 *         BlockStatePalette:[{Name,Properties?}], BlockStates:LongArray } } }
 * Position/Size 真实 Litematica 写 Compound 小写键 x/y/z，且 **Size 可为负**（=圈选方向，
 * 索引空间恒按绝对值尺寸，2026-11-10 经真实素材 + litemapy 参考实现双重验证）；
 * 兼容 Compound{X,Y,Z} 与 List<Int>[3] 写法（NbtReader.intTripleEither）。
 * 位打包（真实 Litematica 口径，2026-11-10 经真实素材验证）：bits = max(2, ceil(log2(palette)))，
 * 条目按绝对位序 i*bits 连续排布、**可跨 long**，数组长度 = ceil(体积*bits/64)；
 * 区域内索引序 = (y*sizeZ + z)*sizeX + x。
 * 多区域按区域名字典序遍历合并（确定性），坐标 = 区域 Position + 局部坐标。
 */
public final class LitematicReader {

    public static final class Result {
        public final List<Voxel> voxels;
        public final int mcDataVersion; // 0 = 未声明
        public final List<String> regionNames;
        Result(List<Voxel> v, int dv, List<String> names) { this.voxels = v; this.mcDataVersion = dv; this.regionNames = names; }
    }

    public static Result read(Map<String, Object> root) throws IOException {
        int dv = root.containsKey("MinecraftDataVersion")
                ? NbtReader.intOf(root.get("MinecraftDataVersion"), "MinecraftDataVersion") : 0;

        Map<String, Object> regions = NbtReader.compound(NbtReader.require(root, "Regions", "root"), "Regions");
        // 按区域名排序遍历，保证多区域蓝图输出确定
        TreeMap<String, Object> sorted = new TreeMap<>(regions);

        List<Voxel> out = new ArrayList<>();
        List<String> names = new ArrayList<>(sorted.keySet());
        for (Map.Entry<String, Object> en : sorted.entrySet()) {
            String rname = en.getKey();
            String path = "Regions." + rname;
            Map<String, Object> r = NbtReader.compound(en.getValue(), path);

            int[] pos = NbtReader.intTripleEither(NbtReader.require(r, "Position", path), path + ".Position");
            int[] size = NbtReader.intTripleEither(NbtReader.require(r, "Size", path), path + ".Size");
            int sx = Math.abs(size[0]), sy = Math.abs(size[1]), sz = Math.abs(size[2]);

            List<Object> paletteList = NbtReader.list(
                    NbtReader.require(r, "BlockStatePalette", path), path + ".BlockStatePalette");
            String[] ids = new String[paletteList.size()];
            String[] props = new String[paletteList.size()];
            for (int i = 0; i < paletteList.size(); i++) {
                Map<String, Object> e = NbtReader.compound(paletteList.get(i), path + ".BlockStatePalette[" + i + "]");
                ids[i] = NbtReader.stringOf(NbtReader.require(e, "Name", path), path + ".BlockStatePalette[" + i + "].Name");
                Object p = e.get("Properties");
                props[i] = p == null ? "" : Voxel.normalizeProps(NbtReader.compound(p, path));
            }

            long[] states = NbtReader.longArrayOf(NbtReader.require(r, "BlockStates", path), path + ".BlockStates");
            int bits = 2;
            while ((1L << bits) < paletteList.size()) bits++;
            long mask = (1L << bits) - 1;
            int total = sx * sy * sz;
            // 真实 Litematica 打包：条目按绝对位序 i*bits 连续排布、可跨 long，
            // 数组长度 = ceil(total*bits/64)（2026-11-10 经真实素材 + litemapy LitematicaBitArray 双重验证；
            // 旧实现误按 1.16+ 原版「不跨 long」口径，bits 不整除 64 时全部长度校验失败）
            int needLongs = (total * bits + 63) / 64;
            if (states.length < needLongs)
                throw new IOException(path + ".BlockStates 长度不足: " + states.length + " < " + needLongs
                        + "（bits=" + bits + ", 体积=" + total + "）");

            for (int i = 0; i < total; i++) {
                int bitIndex = i * bits;
                int li = bitIndex >>> 6;
                int off = bitIndex & 63;
                long val = states[li] >>> off;
                if (off + bits > 64 && li + 1 < states.length)
                    val |= states[li + 1] << (64 - off);
                int state = (int) (val & mask);
                if (state >= ids.length)
                    throw new IOException(path + " 索引 " + i + " 的 state=" + state + " 越界（palette 大小 " + ids.length + "）");
                int x = i % sx;
                int z = (i / sx) % sz;
                int y = i / (sx * sz);
                out.add(new Voxel(pos[0] + x, pos[1] + y, pos[2] + z, ids[state], props[state]));
            }
        }
        return new Result(out, dv, names);
    }
}
