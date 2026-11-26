package bpconv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T1 格式②：原版结构方块 .nbt（1.20.1 = DataVersion 3465）。
 * 结构：{ size:[3], palette:[{Name,Properties?}], blocks:[{pos:[3], state:int}], DataVersion }
 */
public final class StructureReader {

    public static final class Result {
        public final List<Voxel> voxels;
        public final int dataVersion; // 0 = 未声明
        Result(List<Voxel> v, int dv) { this.voxels = v; this.dataVersion = dv; }
    }

    public static Result read(Map<String, Object> root) throws IOException {
        int dataVersion = root.containsKey("DataVersion")
                ? NbtReader.intOf(root.get("DataVersion"), "DataVersion") : 0;

        List<Object> paletteList = NbtReader.list(NbtReader.require(root, "palette", "root"), "palette");
        String[] ids = new String[paletteList.size()];
        String[] props = new String[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            Map<String, Object> e = NbtReader.compound(paletteList.get(i), "palette[" + i + "]");
            ids[i] = NbtReader.stringOf(NbtReader.require(e, "Name", "palette[" + i + "]"), "palette[" + i + "].Name");
            Object p = e.get("Properties");
            props[i] = p == null ? "" : Voxel.normalizeProps(NbtReader.compound(p, "palette[" + i + "].Properties"));
        }

        List<Object> blocks = NbtReader.list(NbtReader.require(root, "blocks", "root"), "blocks");
        List<Voxel> out = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, Object> b = NbtReader.compound(blocks.get(i), "blocks[" + i + "]");
            int[] pos = NbtReader.intTriple(NbtReader.require(b, "pos", "blocks[" + i + "]"), "blocks[" + i + "].pos");
            int state = NbtReader.intOf(NbtReader.require(b, "state", "blocks[" + i + "]"), "blocks[" + i + "].state");
            if (state < 0 || state >= ids.length)
                throw new IOException("blocks[" + i + "].state=" + state + " 越界（palette 大小 " + ids.length + "）");
            out.add(new Voxel(pos[0], pos[1], pos[2], ids[state], props[state]));
        }
        return new Result(out, dataVersion);
    }
}
