package bpconv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T3：拓扑排序（依赖序）。
 * 契约 = BLUEPRINT-FORMAT §3 规则 1「支撑块在前、依附块在后（墙→火把，地板→床）」。
 * V1 实现为两层规则：① y 升序逐层；② 同层内非依附块在前、依附块（D 类）在后。
 * 同层同类按 (z, x) 升序 —— 全序比较器 + List.sort 稳定排序 ⇒ 同输入必同输出（确定性硬要求）。
 * 运行时按行序回放、不重排，排序正确性 = 放置正确性。
 */
public final class PlacementOrder {

    public static List<Voxel> sort(List<Voxel> voxels, BlockClasses bc) {
        List<Voxel> out = new ArrayList<>(voxels);
        Comparator<Voxel> cmp = Comparator
                .comparingInt((Voxel v) -> v.y)
                .thenComparingInt(v -> bc.isAttached(v.id) ? 1 : 0)
                .thenComparingInt(v -> v.z)
                .thenComparingInt(v -> v.x);
        out.sort(cmp);
        return out;
    }
}
