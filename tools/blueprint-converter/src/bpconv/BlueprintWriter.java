package bpconv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T5：输出与报告。
 * .bp 纯文本 LF（运行时解析兼容 CRLF）；目标已存在且无 --force 拒绝覆盖。
 * C 类容器保留在清单中并标 C（运行时放置跳过、完整性账注册时排除——事件系统 §3.1）。
 * 报告不含时间戳（黄金回归要求逐字节稳定）。
 */
public final class BlueprintWriter {

    public static final class Meta {
        public String id, kind, author, license;
        public int sx, sy, sz;
        public Integer floorY;
        public int[] entrance; // 可为 null（道路、城防件与待选入口的港口素材）
        public int beds;
    }

    /** 生成 .bp 文本（LF）。分类逐体素由 classifier 提供（'S'/'D'/'C'）。 */
    public static String render(Meta m, List<Voxel> ordered, List<Character> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append(line("@id", m.id));
        sb.append(line("@kind", m.kind));
        sb.append(line("@size", m.sx + "x" + m.sy + "x" + m.sz));
        if (m.floorY != null) sb.append(line("@floor_y", m.floorY.toString()));
        if (m.entrance != null)
            sb.append(line("@entrance", m.entrance[0] + "," + m.entrance[1] + "," + m.entrance[2]));
        sb.append(line("@origin", "bottom-center"));
        sb.append(line("@author", m.author));
        sb.append(line("@license", m.license));
        sb.append(line("@beds", Integer.toString(m.beds)));
        sb.append("# 方块清单：x,y,z 方块id 分类\n");
        for (int i = 0; i < ordered.size(); i++) {
            Voxel v = ordered.get(i);
            sb.append(v.x).append(',').append(v.y).append(',').append(v.z)
              .append(' ').append(v.idWithProps())
              .append(' ').append(classes.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String line(String key, String value) {
        return String.format("%-13s%s%n", key, value).replace("\r\n", "\n");
    }

    /** 写 .bp + .report.txt；返回 .bp 路径。已存在且无 force → 抛 IOException（退出码 1）。 */
    public static Path write(Path outdir, String kindLower, Meta m, String bpText, String reportText, boolean force)
            throws IOException {
        Path dir = outdir.resolve(kindLower);
        Files.createDirectories(dir);
        Path bp = dir.resolve(m.id + ".bp");
        if (Files.exists(bp) && !force)
            throw new IOException("目标已存在: " + bp + "（覆盖需 --force）");
        Files.write(bp, bpText.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(m.id + ".report.txt"), reportText.getBytes(StandardCharsets.UTF_8));
        return bp;
    }

    /** 组装报告文本（确定性排序，无时间戳）。 */
    public static String report(Meta m, String inputName, String formatNote,
                                int total, int sCount, int dCount, int cCount,
                                List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 转换报告 ==\n");
        sb.append("输入: ").append(inputName).append('\n');
        if (formatNote != null) sb.append("格式: ").append(formatNote).append('\n');
        sb.append("输出: ").append(m.kind.toLowerCase()).append('/').append(m.id).append(".bp\n");
        sb.append("尺寸: ").append(m.sx).append('x').append(m.sy).append('x').append(m.sz).append('\n');
        if (m.floorY != null) sb.append("地板高度: ").append(m.floorY).append("（最低层为 0）\n");
        if (m.entrance != null)
            sb.append("入口: ").append(m.entrance[0]).append(',').append(m.entrance[1]).append(',').append(m.entrance[2]).append('\n');
        sb.append("床数: ").append(m.beds).append('\n');
        sb.append("方块: 总 ").append(total)
          .append(" / S ").append(sCount).append(" / D ").append(dCount).append(" / C ").append(cCount)
          .append("（C 类保留在清单，运行时放置跳过、完整性账排除）\n");
        sb.append("许可: ").append(m.license).append("（作者: ").append(m.author).append("）\n");
        if ("CC-BY-4.0".equals(m.license))
            sb.append("提醒: CC-BY 需在模组 credits 留名（@author/@license 字段即为此设）\n");
        List<String> ws = new ArrayList<>(warnings);
        java.util.Collections.sort(ws);
        if (!ws.isEmpty()) {
            sb.append("-- 警告 --\n");
            for (String w : ws) sb.append("* ").append(w).append('\n');
        }
        return sb.toString();
    }
}
