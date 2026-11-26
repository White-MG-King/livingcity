package bpconv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * T1 格式⑤（回环）：自家 BLUEPRINT-FORMAT V1 文本 .bp 再输入。
 * 用途：老蓝图重校验、改 kind/id 转录（CLI 参数为准，@meta 行只作回显不校验）。
 * 行语法：「@键 值」元数据｜「# …」注释｜「x,y,z 方块id[props] S|D|C」方块行（空白分隔三段）。
 * 与 Axiom .bp 同扩展名——Main 按文件头 magic 自动识别（AxiomReader.isAxiom）。
 */
public final class TextBpReader {

    public static final class Result {
        public final List<Voxel> voxels;
        public final String id; // @id 值（缺省空串，仅回显）
        Result(List<Voxel> v, String id) { this.voxels = v; this.id = id; }
    }

    public static Result read(byte[] raw) throws IOException {
        String text = new String(raw, StandardCharsets.UTF_8);
        List<Voxel> out = new ArrayList<>();
        String id = "";
        int lineNo = 0;
        for (String rawLine : text.split("\n", -1)) {
            lineNo++;
            String line = rawLine.replace("\r", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("@")) {
                String[] kv = line.split("\\s+", 2);
                if (kv.length == 2 && kv[0].equals("@id")) id = kv[1].trim();
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 3)
                throw new IOException("自家 .bp 第 " + lineNo + " 行应为三段（坐标 方块id 分类）: " + line
                        + "（若此文件本是 Axiom .bp，则 magic 头缺失/已损坏）");
            String[] c = parts[0].split(",");
            if (c.length != 3)
                throw new IOException("自家 .bp 第 " + lineNo + " 行坐标应为 x,y,z: " + parts[0]);
            int x, y, z;
            try {
                x = Integer.parseInt(c[0]); y = Integer.parseInt(c[1]); z = Integer.parseInt(c[2]);
            } catch (NumberFormatException e) {
                throw new IOException("自家 .bp 第 " + lineNo + " 行坐标非整数: " + parts[0]);
            }
            String idp = parts[1];
            int br = idp.indexOf('[');
            String bid = br < 0 ? idp : idp.substring(0, br);
            String props = br < 0 ? "" : idp.substring(br);
            char cls = parts[2].charAt(0);
            if (parts[2].length() != 1 || (cls != 'S' && cls != 'D' && cls != 'C'))
                throw new IOException("自家 .bp 第 " + lineNo + " 行分类应为 S/D/C 单字符: " + parts[2]);
            out.add(new Voxel(x, y, z, bid, props));
        }
        if (out.isEmpty())
            throw new IOException("自家 .bp 内没有方块行（文件为空或只有元数据）");
        return new Result(out, id);
    }
}
