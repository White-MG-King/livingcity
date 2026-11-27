package bpconv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 验收闸门测试台（《蓝图转换器任务单》§验收闸门）。
 * 纯 Java、进程内调用 Main.run；样本由 MakeTestData 字节级手工构造（零游戏依赖）。
 *
 * 用法：
 *   java -cp out bpconv.TestHarness --bless   # 首跑：生成黄金期望文件（须人工评审后入库）
 *   java -cp out bpconv.TestHarness           # 回归：逐字节 diff 黄金文件 + 全部断言
 * cwd 必须是工程根（block-classes.txt 所在目录）。
 */
public final class TestHarness {

    static final Path ROOT = Paths.get("").toAbsolutePath();
    static final Path INPUTS = ROOT.resolve("testdata/inputs");
    static final Path EXPECTED = ROOT.resolve("testdata/expected");
    static final Path RUN = ROOT.resolve("testdata/run");
    static final Path CLASSES = ROOT.resolve("block-classes.txt");

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        boolean bless = args.length > 0 && args[0].equals("--bless");
        deleteRecursive(RUN);
        MakeTestData.generate(INPUTS);

        if (bless) {
            Files.createDirectories(EXPECTED);
            blessConvert("tiny_house.nbt", "HOUSE", "tiny_house", "1,0,2", "tiny_house.bp");
            blessConvert("unknown_block.nbt", "ROAD", "unknown_test", null, "unknown_test.bp");
            blessConvert("wide_road.nbt", "ROAD", "wide_road", null, "wide_road.bp");
            blessConvert("axiom_span.bp", "ROAD", "axiom_span", null, "axiom_span.bp");
            System.out.println("GOLDEN WRITTEN → " + EXPECTED + " —— 须人工评审（入口/床数/排序/分类）后入库");
            return;
        }

        // ---- t1–t3+t16：四种格式 → 与同一份黄金文件逐字节一致（交叉验证） ----
        goldenMatch("t1 nbt→黄金", "tiny_house.nbt", "HOUSE", "tiny_house", "CC0", "1,0,2", "nbt", "tiny_house.bp");
        goldenMatch("t2 litematic→黄金", "tiny_house.litematic", "HOUSE", "tiny_house", "CC0", "1,0,2", "lit", "tiny_house.bp");
        goldenMatch("t3 schematic→黄金", "tiny_house.schematic", "HOUSE", "tiny_house", "CC0", "1,0,2", "sch", "tiny_house.bp");
        goldenMatch("t16 axiom bp→黄金", "tiny_house.bp", "HOUSE", "tiny_house", "CC0", "1,0,2", "axm", "tiny_house.bp");

        // ---- t17：Axiom 多区块段合并（18×2 石阵跨 section 边界 → 独立黄金） ----
        goldenMatch("t17 axiom 多段合并→黄金", "axiom_span.bp", "ROAD", "axiom_span", "CC0", null, "axm2", "axiom_span.bp");

        // ---- t4：确定性——同输入跑两次输出逐字节一致 ----
        {
            Path d1 = RUN.resolve("det1"), d2 = RUN.resolve("det2");
            StringBuilder log = new StringBuilder();
            int c1 = runQuiet(convertArgs("tiny_house.nbt", d1, "HOUSE", "tiny_house", "CC0", "1,0,2", false), log);
            int c2 = runQuiet(convertArgs("tiny_house.nbt", d2, "HOUSE", "tiny_house", "CC0", "1,0,2", false), log);
            byte[] b1 = Files.readAllBytes(d1.resolve("house/tiny_house.bp"));
            byte[] b2 = Files.readAllBytes(d2.resolve("house/tiny_house.bp"));
            check("t4 确定性(两次运行字节一致)", c1 == 0 && c2 == 0 && java.util.Arrays.equals(b1, b2), log.toString());
        }

        // ---- t5：拓扑断言——支撑在前、依附在后；y=0 层整体在 y=1 层之前 ----
        {
            List<String> lines = Files.readAllLines(EXPECTED.resolve("tiny_house.bp"), StandardCharsets.UTF_8);
            List<String> blocks = new ArrayList<>();
            for (String l : lines) if (!l.startsWith("@") && !l.startsWith("#") && !l.isEmpty()) blocks.add(l);
            int floorUnderBed = lineOf(blocks, "1,0,0 minecraft:oak_planks S");
            int bedFoot = lineOf(blocks, "1,1,0 minecraft:red_bed[facing=south,part=foot] D");
            int floorUnderTorch = lineOf(blocks, "0,0,2 minecraft:oak_planks S");
            int torch = lineOf(blocks, "0,1,2 minecraft:torch D");
            int chest = lineOf(blocks, "2,1,0 minecraft:chest C");
            boolean ok = floorUnderBed >= 0 && bedFoot > floorUnderBed
                    && floorUnderTorch >= 0 && torch > floorUnderTorch
                    && chest >= 0;
            // y=0 全部在 y=1 之前
            int lastY0 = -1, firstY1 = Integer.MAX_VALUE;
            for (int i = 0; i < blocks.size(); i++) {
                String[] p = blocks.get(i).split(" ")[0].split(",");
                if (p[1].equals("0")) lastY0 = Math.max(lastY0, i);
                if (p[1].equals("1")) firstY1 = Math.min(firstY1, i);
            }
            ok = ok && lastY0 < firstY1;
            check("t5 拓扑排序(地板→床/火把, y分层)", ok, "blocks=" + blocks);
        }

        // ---- t6/t7：许可证门 ----
        {
            StringBuilder log = new StringBuilder();
            List<String> a = new ArrayList<>(List.of(
                    INPUTS.resolve("tiny_house.nbt").toString(),
                    "-o", RUN.resolve("gate").toString(),
                    "--kind", "HOUSE", "--id", "tiny_house", "--author", "bpconv-test",
                    "--classes", CLASSES.toString(), "--entrance", "1,0,2"));
            int noLicense = runQuiet(a.toArray(new String[0]), log);
            List<String> b = new ArrayList<>(a);
            b.addAll(List.of("--license", "MIT"));
            int badLicense = runQuiet(b.toArray(new String[0]), log);
            check("t6 缺 --license → 退出码2", noLicense == 2, "code=" + noLicense + " " + log);
            check("t7 --license MIT → 退出码2", badLicense == 2, "code=" + badLicense + " " + log);
        }

        // ---- t8：V3 床数与机制类型（HOUSE 样本按 WELL 转 → 床数必须为0 → 失败） ----
        {
            StringBuilder log = new StringBuilder();
            int code = runQuiet(convertArgs("tiny_house.nbt", RUN.resolve("v3"), "WELL", "tiny_house", "CC0", "1,0,2", false), log);
            check("t8 WELL+床 → 退出码1(V3)", code == 1, "code=" + code + " " + log);
        }

        // ---- t9：V1 地基不完整 ----
        {
            StringBuilder log = new StringBuilder();
            int code = runQuiet(convertArgs("broken_floor.nbt", RUN.resolve("v1"), "ROAD", "broken", "CC0", null, false), log);
            check("t9 broken_floor → 退出码1(V1)", code == 1 && log.toString().contains("V1"), "code=" + code + " " + log);
        }

        // ---- t10：V2 入口不在边缘 ----
        {
            StringBuilder log = new StringBuilder();
            int code = runQuiet(convertArgs("tiny_house.nbt", RUN.resolve("v2"), "HOUSE", "tiny_house", "CC0", "1,0,1", false), log);
            check("t10 入口(1,0,1)内部 → 退出码1(V2)", code == 1 && log.toString().contains("V2"), "code=" + code + " " + log);
        }

        // ---- t11：覆盖保护 ----
        {
            StringBuilder log = new StringBuilder();
            Path ow = RUN.resolve("ow");
            int first = runQuiet(convertArgs("tiny_house.nbt", ow, "HOUSE", "tiny_house", "CC0", "1,0,2", false), log);
            int second = runQuiet(convertArgs("tiny_house.nbt", ow, "HOUSE", "tiny_house", "CC0", "1,0,2", false), log);
            int forced = runQuiet(convertArgs("tiny_house.nbt", ow, "HOUSE", "tiny_house", "CC0", "1,0,2", true), log);
            check("t11 覆盖保护(0→1→force 0)", first == 0 && second == 1 && forced == 0,
                    "codes=" + first + "," + second + "," + forced + " " + log);
        }

        // ---- t12：未知方块 → 默认 S + 警告（黄金回归） ----
        {
            StringBuilder log = new StringBuilder();
            Path unk = RUN.resolve("unk");
            int code = runQuiet(convertArgs("unknown_block.nbt", unk, "ROAD", "unknown_test", "CC0", null, false), log);
            byte[] got = Files.readAllBytes(unk.resolve("road/unknown_test.bp"));
            byte[] exp = Files.readAllBytes(EXPECTED.resolve("unknown_test.bp"));
            String report = Files.readString(unk.resolve("road/unknown_test.report.txt"), StandardCharsets.UTF_8);
            check("t12 未知方块→S+警告+黄金一致",
                    code == 0 && java.util.Arrays.equals(got, exp)
                            && report.contains("未知方块") && report.contains("minecraft:beacon"),
                    "code=" + code + " report=" + report + " " + diffHint(got, exp));
        }

        // ---- t13：V5 软限警告（65 宽 → 成功但有警告） ----
        {
            StringBuilder log = new StringBuilder();
            Path wide = RUN.resolve("wide");
            int code = runQuiet(convertArgs("wide_road.nbt", wide, "ROAD", "wide_road", "CC0", null, false), log);
            String report = Files.readString(wide.resolve("road/wide_road.report.txt"), StandardCharsets.UTF_8);
            check("t13 V5软限(65宽→0+警告)", code == 0 && report.contains("V5(软)"), "code=" + code + " report=" + report);
        }

        // ---- t14：CC-BY 留名提醒 ----
        {
            StringBuilder log = new StringBuilder();
            Path ccby = RUN.resolve("ccby");
            int code = runQuiet(convertArgs("tiny_house.nbt", ccby, "HOUSE", "tiny_house", "CC-BY-4.0", "1,0,2", false), log);
            String report = Files.readString(ccby.resolve("house/tiny_house.report.txt"), StandardCharsets.UTF_8);
            check("t14 CC-BY → credits留名提醒", code == 0 && report.contains("credits 留名"), "code=" + code + " report=" + report);
        }

        // ---- t15：--help → 0 ----
        {
            StringBuilder log = new StringBuilder();
            int code = runQuiet(new String[]{"--help"}, log);
            check("t15 --help → 退出码0", code == 0, "code=" + code);
        }

        // ---- t18：自家文本 .bp 回环（t1 产物再输入 → 与黄金逐字节一致） ----
        {
            StringBuilder log = new StringBuilder();
            Path first = RUN.resolve("rt1");
            int c1 = runQuiet(convertArgs("tiny_house.nbt", first, "HOUSE", "tiny_house", "CC0", "1,0,2", false), log);
            Path produced = first.resolve("house/tiny_house.bp");
            List<String> a = new ArrayList<>(List.of(
                    produced.toString(),
                    "-o", RUN.resolve("rt2").toString(),
                    "--kind", "HOUSE", "--id", "tiny_house",
                    "--author", "bpconv-test", "--license", "CC0",
                    "--classes", CLASSES.toString(), "--entrance", "1,0,2"));
            int c2 = runQuiet(a.toArray(new String[0]), log);
            byte[] exp = Files.readAllBytes(EXPECTED.resolve("tiny_house.bp"));
            byte[] got = (c1 == 0 && c2 == 0)
                    ? Files.readAllBytes(RUN.resolve("rt2/house/tiny_house.bp")) : new byte[0];
            check("t18 自家.bp回环(输出再输入→逐字节一致)",
                    c1 == 0 && c2 == 0 && java.util.Arrays.equals(got, exp),
                    "codes=" + c1 + "," + c2 + " " + diffHint(got, exp) + " " + log);
        }

        testPortFloor();
        System.out.println("\n==== 结果: " + passed + " 通过 / " + failed + " 失败 ====");
        if (failed > 0) System.exit(1);
    }

    // ---- 助手 ----

    static void blessConvert(String input, String kind, String id, String entrance, String goldenName)
            throws IOException {
        Path tmp = RUN.resolve("bless");
        StringBuilder log = new StringBuilder();
        int code = runQuiet(convertArgs(input, tmp, kind, id, "CC0", entrance, true), log);
        if (code != 0) throw new IOException("bless 转换失败 " + input + ": code=" + code + "\n" + log);
        Path bp = tmp.resolve(kind.toLowerCase() + "/" + id + ".bp");
        Files.copy(bp, EXPECTED.resolve(goldenName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("golden: " + goldenName + " ← " + input);
    }

    static void goldenMatch(String name, String input, String kind, String id,
                            String license, String entrance, String sub, String goldenName) throws IOException {
        StringBuilder log = new StringBuilder();
        Path out = RUN.resolve(sub);
        int code = runQuiet(convertArgs(input, out, kind, id, license, entrance, false), log);
        byte[] exp = Files.readAllBytes(EXPECTED.resolve(goldenName));
        if (code != 0) { check(name, false, "code=" + code + " " + log); return; }
        byte[] got = Files.readAllBytes(out.resolve(kind.toLowerCase() + "/" + id + ".bp"));
        check(name, java.util.Arrays.equals(got, exp), diffHint(got, exp) + " " + log);
    }

    private static void testPortFloor() throws IOException {
        Path input = RUN.resolve("raised_port.bp");
        Files.writeString(input, """
                @floor_y 3
                0,0,0 minecraft:stone_bricks S
                1,3,0 minecraft:oak_planks S
                """);
        Path out = RUN.resolve("port_floor");
        var log = new StringBuilder();
        String[] args = convertArgs(input.toString(), out, "PORT", "raised_port", "CC0", null, false);
        int result = runQuiet(args, log);
        Path output = out.resolve("port/raised_port.bp");
        check("t19 港口保留第 4 层地板及稀疏桩基",
                result == 0 && Files.readString(output).contains("@floor_y     3"), log.toString());

        var invalid = new ArrayList<>(List.of(convertArgs(input.toString(), out, "PORT", "invalid_floor", "CC0", null, false)));
        invalid.addAll(List.of("--floor-y", "4"));
        log.setLength(0);
        check("t20 --floor-y 覆盖原声明并拒绝越界高度",
                runQuiet(invalid.toArray(new String[0]), log) == 1 && log.toString().contains("地板高度 4"), log.toString());
    }

    static String[] convertArgs(String input, Path outdir, String kind, String id,
                                String license, String entrance, boolean force) {
        List<String> a = new ArrayList<>(List.of(
                INPUTS.resolve(input).toString(),
                "-o", outdir.toString(),
                "--kind", kind, "--id", id,
                "--author", "bpconv-test", "--license", license,
                "--classes", CLASSES.toString()));
        if (entrance != null) a.addAll(List.of("--entrance", entrance));
        if (force) a.add("--force");
        return a.toArray(new String[0]);
    }

    static int runQuiet(String[] args, StringBuilder log) {
        PrintStream oldOut = System.out, oldErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code;
        try {
            PrintStream p = new PrintStream(buf, true, "UTF-8");
            System.setOut(p);
            System.setErr(p);
            try {
                code = Main.run(args);
            } finally {
                p.flush();
                System.setOut(oldOut);
                System.setErr(oldErr);
            }
        } catch (Exception e) {
            System.setOut(oldOut);
            System.setErr(oldErr);
            log.append("EXCEPTION: ").append(e).append('\n');
            return -99;
        }
        log.append(buf.toString(StandardCharsets.UTF_8));
        return code;
    }

    static int lineOf(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).equals(target)) return i;
        return -1;
    }

    static String diffHint(byte[] got, byte[] exp) {
        if (java.util.Arrays.equals(got, exp)) return "";
        String[] g = new String(got, StandardCharsets.UTF_8).split("\n", -1);
        String[] e = new String(exp, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < Math.max(g.length, e.length); i++) {
            String gl = i < g.length ? g[i] : "<无>", el = i < e.length ? e[i] : "<无>";
            if (!gl.equals(el)) return "首差异行 " + (i + 1) + ": 实际[" + gl + "] 期望[" + el + "]";
        }
        return "字节数不同: got=" + got.length + " exp=" + exp.length;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else {
            failed++;
            System.out.println("FAIL  " + name);
            if (detail != null && !detail.isBlank()) {
                String d = detail.length() > 900 ? detail.substring(0, 900) + "…" : detail;
                System.out.println("      " + d.replace("\n", "\n      "));
            }
        }
    }

    static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ignored) { } });
        }
    }
}
