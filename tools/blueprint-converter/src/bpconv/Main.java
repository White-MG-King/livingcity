package bpconv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * bpconv — 蓝图转换器 CLI（《蓝图转换器任务单》T1–T5）。
 * 退出码：0 成功；1 校验/IO 失败；2 许可证门拒绝。
 * 零第三方依赖（JDK 17 javac 直编）。
 */
public final class Main {

    private static final Set<String> KINDS = new LinkedHashSet<>(
            Arrays.asList("HOUSE", "WELL", "PLAZA", "ROAD", "WALL", "GATE", "PORT"));
    private static final Set<String> LICENSES = new LinkedHashSet<>(
            Arrays.asList("CC0", "CC-BY-4.0"));

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    /** 可测试入口：不 System.exit，返回退出码（TestHarness 直接调用）。 */
    public static int run(String[] args) throws IOException {
        Path input = null, outdir = Paths.get("."), classesFile = Paths.get("block-classes.txt");
        String kind = null, id = null, author = null, license = null, bedsArg = "auto";
        int[] entrance = null;
        Integer floorY = null, textFloorY = null;
        boolean force = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-o": outdir = Paths.get(next(args, ++i, a)); break;
                case "--kind": kind = next(args, ++i, a); break;
                case "--id": id = next(args, ++i, a); break;
                case "--author": author = next(args, ++i, a); break;
                case "--license": license = next(args, ++i, a); break;
                case "--entrance": entrance = parseTriple(next(args, ++i, a), a); break;
                case "--floor-y": floorY = Integer.parseInt(next(args, ++i, a)); break;
                case "--beds": bedsArg = next(args, ++i, a); break;
                case "--classes": classesFile = Paths.get(next(args, ++i, a)); break;
                case "--force": force = true; break;
                case "-h": case "--help": usage(); return 0;
                default:
                    if (a.startsWith("-")) { System.err.println("未知参数: " + a); usage(); return 1; }
                    if (input != null) { System.err.println("只接受一个输入文件"); return 1; }
                    input = Paths.get(a);
            }
        }

        // ---- 许可证门（最先执行，BLUEPRINT-FORMAT §4 红线）----
        if (license == null) {
            System.err.println("许可证门: 缺 --license（白名单 CC0 / CC-BY-4.0）。来源不明的蓝图不进管线。");
            return 2;
        }
        if (!LICENSES.contains(license)) {
            System.err.println("许可证门: --license '" + license + "' 不在白名单 {CC0, CC-BY-4.0}，拒绝转换。");
            return 2;
        }

        if (input == null) { System.err.println("缺输入文件"); usage(); return 1; }
        if (kind == null || !KINDS.contains(kind)) {
            System.err.println("--kind 必填且必须是 " + KINDS + " 之一，当前: " + kind); return 1;
        }
        if (id == null) { System.err.println("缺 --id"); return 1; }
        if (author == null) { System.err.println("缺 --author"); return 1; }
        if (!Files.isRegularFile(input)) { System.err.println("输入文件不存在: " + input); return 1; }
        if (!Files.isRegularFile(classesFile)) {
            System.err.println("分类表不存在: " + classesFile.toAbsolutePath() + "（--classes 指定路径）"); return 1;
        }

        // ---- T1：按扩展名分发解析 → 中间结构 List<Voxel> ----
        String name = input.getFileName().toString().toLowerCase();
        List<String> warnings = new ArrayList<>();
        List<Voxel> voxels;
        String formatNote;
        try {
            byte[] raw = Files.readAllBytes(input);
            boolean isSchematic = name.endsWith(".schematic");
            boolean isBp = name.endsWith(".bp");
            Map<String, Object> root = null;
            if (!isBp) {
                if (isSchematic || name.endsWith(".nbt") || name.endsWith(".litematic")) {
                    root = NbtReader.readRoot(raw); // gzip 自动检测
                } else {
                    System.err.println("不认识的扩展名: " + name + "（支持 .litematic / .nbt / .schematic / .bp）");
                    return 1;
                }
            }
            if (isBp) {
                // .bp 内容自动识别：Axiom 容器（magic 头）vs 自家文本格式（BLUEPRINT-FORMAT V1 回环）
                if (AxiomReader.isAxiom(raw)) {
                    AxiomReader.Result r = AxiomReader.read(raw);
                    voxels = r.voxels;
                    warnings.addAll(r.warnings);
                    formatNote = "bp（Axiom 容器，DataVersion=" + r.dataVersion
                            + "，区块段=" + r.sectionCount
                            + "，头部 Name=" + (r.name.isEmpty() ? "-" : r.name)
                            + " Author=" + (r.author.isEmpty() ? "-" : r.author) + "）";
                } else {
                    TextBpReader.Result r = TextBpReader.read(raw);
                    voxels = r.voxels;
                    textFloorY = r.floorY;
                    formatNote = "bp（自家文本格式 V1 回环，@id=" + (r.id.isEmpty() ? "-" : r.id) + "）";
                }
            } else if (name.endsWith(".litematic")) {
                LitematicReader.Result r = LitematicReader.read(root);
                voxels = r.voxels;
                formatNote = "litematic（MinecraftDataVersion=" + r.mcDataVersion
                        + "，区域: " + String.join(",", r.regionNames) + "）";
                if (r.mcDataVersion != 0 && r.mcDataVersion != 3465)
                    warnings.add("MinecraftDataVersion=" + r.mcDataVersion + " ≠ 3465（1.20.1），方块名可能需迁移");
            } else if (isSchematic) {
                SchematicReader.Result r = SchematicReader.read(root);
                voxels = r.voxels;
                warnings.addAll(r.warnings);
                formatNote = "schematic（MCEdit 经典，pre-flattening 数字 id）";
            } else {
                StructureReader.Result r = StructureReader.read(root);
                voxels = r.voxels;
                formatNote = "nbt（原版结构方块，DataVersion=" + r.dataVersion + "）";
                if (r.dataVersion != 0 && r.dataVersion != 3465)
                    warnings.add("DataVersion=" + r.dataVersion + " ≠ 3465（1.20.1），方块名可能需迁移");
            }
        } catch (IOException e) {
            System.err.println("解析失败: " + e.getMessage());
            return 1;
        }

        // 去 air、归一化到原点（bottom 平面 y=0）
        List<Voxel> solid = new ArrayList<>();
        for (Voxel v : voxels) if (!v.isAir()) solid.add(v);
        if (solid.isEmpty()) { System.err.println("校验失败: 蓝图内无有效方块（全是空气）"); return 1; }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Voxel v : solid) {
            minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
        }
        List<Voxel> norm = new ArrayList<>(solid.size());
        for (Voxel v : solid) norm.add(new Voxel(v.x - minX, v.y - minY, v.z - minZ, v.id, v.props));
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if (floorY == null && textFloorY != null) floorY = textFloorY - minY;
        // --entrance 语义（2026-11-10 真实素材端到端拍板）：归一化输出坐标系，与 .bp 方块清单
        // 同口径（footprint 原点 0,0,0）。不做源坐标平移——源世界坐标是搭建者站位细节，
        // 用户看蓝图/冒烟产物定的入口就是输出坐标。回归锁定：测试样本源坐标整体偏移。

        // ---- T2：分类查表（未知默认 S + 警告）----
        BlockClasses bc = BlockClasses.load(classesFile);
        List<Character> classes = new ArrayList<>(norm.size());
        Set<String> unknownBlocks = new LinkedHashSet<>(); // TreeMap 语义：TreeSet 保序
        int sCount = 0, dCount = 0, cCount = 0;
        for (Voxel v : norm) {
            Character c = bc.classify(v.id);
            if (c == null) { c = 'S'; unknownBlocks.add(v.id); }
            classes.add(c);
            if (c == 'S') sCount++; else if (c == 'D') dCount++; else cCount++;
        }
        for (String ub : unknownBlocks)
            warnings.add("未知方块 " + ub + "（不在分类表）→ 默认归 S（保守进完整性账），请人工复核补表");

        // ---- 床数（@beds auto = head 半格数）----
        int beds;
        int bedVoxels = 0, bedHeads = 0;
        for (Voxel v : norm) if (v.isBed()) { bedVoxels++; if (v.isBedHead()) bedHeads++; }
        if (bedsArg.equals("auto")) {
            beds = bedHeads;
            if (bedHeads == 0 && bedVoxels > 0) {
                beds = (bedVoxels + 1) / 2;
                warnings.add("床方块无 part=head 状态（源格式损耗），按 方块数/2 上取整估算床数=" + beds);
            }
        } else {
            try { beds = Integer.parseInt(bedsArg); }
            catch (NumberFormatException e) { System.err.println("--beds 须为 auto 或整数: " + bedsArg); return 1; }
        }

        // ---- T3：拓扑排序 ----
        List<Voxel> ordered = PlacementOrder.sort(norm, bc);
        // 分类表按 norm 顺序算的，排序后需随行携带 → 建索引映射重排 classes
        List<Character> orderedClasses = new ArrayList<>(ordered.size());
        {
            // Voxel 无标识字段，用 IdentityHashMap 保分类随行
            java.util.IdentityHashMap<Voxel, Character> map = new java.util.IdentityHashMap<>();
            for (int i = 0; i < norm.size(); i++) map.put(norm.get(i), classes.get(i));
            for (Voxel v : ordered) orderedClasses.add(map.get(v));
        }

        // ---- T4：校验 ----
        Validator val = new Validator();
        val.validate(kind, sx, sy, sz, norm, entrance, beds, floorY == null ? 0 : floorY);
        val.warnings.addAll(warnings);
        if (!val.errors.isEmpty()) {
            System.err.println("校验失败（" + val.errors.size() + " 项）:");
            for (String e : val.errors) System.err.println("  ✗ " + e);
            return 1;
        }

        // ---- T5：输出与报告 ----
        BlueprintWriter.Meta m = new BlueprintWriter.Meta();
        m.id = id; m.kind = kind; m.author = author; m.license = license;
        m.sx = sx; m.sy = sy; m.sz = sz; m.entrance = entrance; m.beds = beds;
        m.floorY = floorY;

        String bpText = BlueprintWriter.render(m, ordered, orderedClasses);
        String reportText = BlueprintWriter.report(m, input.getFileName().toString(), formatNote,
                norm.size(), sCount, dCount, cCount, val.warnings);
        Path bpPath;
        try {
            bpPath = BlueprintWriter.write(outdir, kind.toLowerCase(), m, bpText, reportText, force);
        } catch (IOException e) {
            System.err.println("输出失败: " + e.getMessage());
            return 1;
        }

        // stdout 摘要
        StringBuilder out = new StringBuilder();
        out.append("✔ 转换成功: ").append(bpPath).append('\n');
        out.append("  ").append(kind).append(' ').append(id)
           .append(" | size ").append(sx).append('x').append(sy).append('x').append(sz)
           .append(" | 方块 ").append(norm.size())
           .append(" (S").append(sCount).append("/D").append(dCount).append("/C").append(cCount).append(')')
           .append(" | 床 ").append(beds).append('\n');
        if (!val.warnings.isEmpty()) {
            List<String> ws = new ArrayList<>(val.warnings);
            java.util.Collections.sort(ws);
            out.append("  警告 ").append(ws.size()).append(" 条（详见 report）:\n");
            for (String w : ws) out.append("  * ").append(w).append('\n');
        }
        System.out.print(out);
        return 0;
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException("参数 " + flag + " 缺值");
        return args[i];
    }

    private static int[] parseTriple(String s, String flag) {
        String[] p = s.split(",");
        if (p.length != 3) throw new IllegalArgumentException(flag + " 需 x,y,z 三整数: " + s);
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    private static void usage() {
        System.out.println(String.join("\n",
            "bpconv — 蓝图转换器（开源蓝图 → BLUEPRINT-FORMAT V1 文本）",
            "用法: java -jar bpconv.jar <input.litematic|input.nbt|input.schematic|input.bp>",
            "        -o <outdir> --kind <HOUSE|WELL|PLAZA|ROAD|WALL|GATE|PORT> --id <id>",
            "        --author <来源作者> --license <CC0|CC-BY-4.0>",
            "        [--entrance x,y,z] [--floor-y N] [--beds auto|N] [--classes block-classes.txt] [--force]",
            "      --entrance 用归一化输出坐标（与 .bp 方块清单同口径，footprint 原点 0,0,0）",
            "      --floor-y 用归一化高度（最低层为 0，第四层为 3；省略时保留文本蓝图声明或使用 0）",
            "      .bp 输入按文件头自动识别：Axiom 容器（magic）或自家文本格式（回环转写）",
            "退出码: 0 成功 | 1 校验/IO 失败 | 2 许可证门拒绝",
            "输出: <outdir>/<kind小写>/<id>.bp + <id>.report.txt"));
    }
}
