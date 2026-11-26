package bpconv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T2：方块分类查表（S/D/C）。
 * 文件格式：每行 "匹配模式 分类"，# 注释；模式支持单个 '*'（前缀/后缀/中缀 glob）。
 * 精确匹配优先；glob 按文件行序首个命中（确定性）。同一精确名冲突分类 → 加载期报错。
 * 本文件是转换器与模组运行时的共用单一来源（BLUEPRINT-FORMAT §5 裁决）。
 */
public final class BlockClasses {

    private final Map<String, Character> exact = new LinkedHashMap<>();
    private final List<String[]> globs = new ArrayList<>(); // [pattern, class]

    public static BlockClasses load(Path file) throws IOException {
        BlockClasses bc = new BlockClasses();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int ln = 0; ln < lines.size(); ln++) {
            String line = lines.get(ln).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 2 || parts[1].length() != 1 || "SDC".indexOf(parts[1].charAt(0)) < 0)
                throw new IOException(file + " 第 " + (ln + 1) + " 行格式错误: " + line);
            char cls = parts[1].charAt(0);
            if (parts[0].contains("*")) {
                bc.globs.add(new String[]{parts[0], String.valueOf(cls)});
            } else {
                Character prev = bc.exact.put(parts[0], cls);
                if (prev != null && prev != cls)
                    throw new IOException(file + " 分类冲突: " + parts[0] + " 同时为 " + prev + " 和 " + cls);
            }
        }
        return bc;
    }

    /** 返回 'S'/'D'/'C'；未命中任何规则返回 null（调用方按任务单默认归 S + 警告）。 */
    public Character classify(String id) {
        Character c = exact.get(id);
        if (c != null) return c;
        for (String[] g : globs)
            if (globMatch(g[0], id)) return g[1].charAt(0);
        return null;
    }

    /** T3 依附判定：D 类即依附块（支撑块在前、依附块在后）。 */
    public boolean isAttached(String id) {
        Character c = classify(id);
        return c != null && c == 'D';
    }

    static boolean globMatch(String pattern, String s) {
        int star = pattern.indexOf('*');
        if (star < 0) return pattern.equals(s);
        String pre = pattern.substring(0, star);
        String suf = pattern.substring(star + 1);
        if (suf.contains("*")) { // 多 '*' 不支持，退化为逐段包含（约定表里不这么写）
            return s.startsWith(pre) && s.endsWith(suf);
        }
        return s.startsWith(pre) && s.endsWith(suf) && s.length() >= pre.length() + suf.length();
    }
}
