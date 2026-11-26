package bpconv;

/**
 * 中间结构：一个方块体素（T1 三种格式统一解析到这里，T2–T5 只吃它）。
 * id 形如 minecraft:oak_planks；props 为规范化状态串 "[k=v,k=v]"（键按字典序）或 ""。
 */
public final class Voxel {
    public final int x, y, z;
    public final String id;
    public final String props; // 含方括号，或空串

    public Voxel(int x, int y, int z, String id, String props) {
        this.x = x; this.y = y; this.z = z; this.id = id; this.props = props;
    }

    /** 蓝图行里的「方块id」字段：id + 可选状态后缀（BLUEPRINT-FORMAT §2 增补）。 */
    public String idWithProps() { return id + props; }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** 是否床方块（任意色床）。 */
    public boolean isBed() { return id.endsWith("_bed"); }

    /** 是否床的 head 半格（@beds auto 统计口径：head 数 = 床数）。 */
    public boolean isBedHead() { return isBed() && props.contains("part=head"); }

    /** Properties 复合标签 → 规范化 "[k=v,k=v]"（键字典序，保证黄金回归确定性）。 */
    public static String normalizeProps(java.util.Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) return "";
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : properties.keySet()) keys.add(k);
        java.util.Collections.sort(keys);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(keys.get(i)).append('=').append(String.valueOf(properties.get(keys.get(i))));
        }
        return sb.append(']').toString();
    }
}
