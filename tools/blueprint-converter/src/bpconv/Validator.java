package bpconv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T4：元数据校验。规则出处见《蓝图转换器任务单》§T4 表（V1–V5）。
 * 硬规则失败 → errors（退出码 1）；软限超限 → warnings（不失败）。
 */
public final class Validator {

    public static final int SOFT_FOOTPRINT = 64; // V5 软限：footprint 建议 ≤64×64
    public static final int SOFT_HEIGHT = 48;    // V5 软限：高度建议 ≤48

    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public void validate(String kind, int sx, int sy, int sz,
                         List<Voxel> voxels, int[] entrance, int beds) {
        // V4：footprint 为矩形包围盒（尺寸由体素范围算出，天然成立——断言非空与包围盒一致）
        if (voxels.isEmpty()) {
            errors.add("V4: 蓝图无有效方块");
            return;
        }
        int maxX = 0, maxY = 0, maxZ = 0;
        for (Voxel v : voxels) {
            maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
        }
        if (maxX + 1 != sx || maxY + 1 != sy || maxZ + 1 != sz)
            errors.add("V4: 包围盒断言失败 size=" + sx + "x" + sy + "x" + sz
                    + " 但体素范围=" + (maxX + 1) + "x" + (maxY + 1) + "x" + (maxZ + 1));

        // V1：y=0 层完整——footprint 内每列在 y=0 必须有方块（BLUEPRINT-FORMAT §3 规则 2）
        Set<Long> floor = new HashSet<>();
        for (Voxel v : voxels)
            if (v.y == 0) floor.add(((long) v.x << 32) | (v.z & 0xFFFFFFFFL));
        List<String> holes = new ArrayList<>();
        for (int x = 0; x < sx; x++)
            for (int z = 0; z < sz; z++)
                if (!floor.contains(((long) x << 32) | (z & 0xFFFFFFFFL)))
                    holes.add(x + ",0," + z);
        if (!holes.isEmpty()) {
            String sample = String.join(" ", holes.subList(0, Math.min(8, holes.size())));
            errors.add("V1: y=0 地基层不完整，缺 " + holes.size() + " 列（示例: " + sample + "）");
        }

        // 入口必填性 + V2：入口落在 footprint 边缘
        boolean entranceRequired = kind.equals("HOUSE") || kind.equals("WELL") || kind.equals("PLAZA");
        if (entrance == null) {
            if (entranceRequired) errors.add("入口: kind=" + kind + " 必须提供 --entrance x,y,z（城市形态 §2 入口约束）");
        } else {
            int ex = entrance[0], ey = entrance[1], ez = entrance[2];
            if (ex < 0 || ex >= sx || ey < 0 || ey >= sy || ez < 0 || ez >= sz) {
                errors.add("V2: 入口 " + ex + "," + ey + "," + ez + " 超出蓝图范围 " + sx + "x" + sy + "x" + sz);
            } else if (!(ex == 0 || ex == sx - 1 || ez == 0 || ez == sz - 1)) {
                errors.add("V2: 入口 " + ex + "," + ey + "," + ez + " 不在 footprint 边缘");
            }
        }

        // V3：床数与机制类型匹配（bed_count 只挂住宅——账本语义）
        if (kind.equals("HOUSE") && beds < 1)
            errors.add("V3: HOUSE 床数必须 ≥1，当前 " + beds);
        if ((kind.equals("WELL") || kind.equals("PLAZA")) && beds != 0)
            errors.add("V3: " + kind + " 床数必须 =0，当前 " + beds);

        // V5（软）：大 prefab 合法（「一栋多区块」既有裁决），超限仅警告
        if (sx > SOFT_FOOTPRINT || sz > SOFT_FOOTPRINT)
            warnings.add("V5(软): footprint " + sx + "x" + sz + " 超过建议值 " + SOFT_FOOTPRINT
                    + "×" + SOFT_FOOTPRINT + "——占地越大挖填越贵，确认是有意的");
        if (sy > SOFT_HEIGHT)
            warnings.add("V5(软): 高度 " + sy + " 超过建议值 " + SOFT_HEIGHT);
    }
}
