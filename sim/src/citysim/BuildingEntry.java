package citysim;

/**
 * 账本建筑条目。扩展纪律 §2：按域拆小类，禁止上帝类。
 * 完整性账本字段（totalStructure/missingBits）在无头端只提供 API，
 * 事件驱动增量与加载对账在 MC 传感层（M3），见《事件系统》§3。
 */
public class BuildingEntry {
    public enum State { UNDER_CONSTRUCTION, ACTIVE, STOPPED, RUIN }

    public final int id;
    public final BuildingKind kind;
    public State state = State.ACTIVE;

    /** 床数挂靠住宅条目（用户裁决 #8）：全城总床数 = Σ bed_count。 */
    public int bedCount = 0;

    /** 建成时刻（周期号），寿命窗摊销用。 */
    public long builtAtCycle = 0;

    // ---- 完整性账本（《事件系统》§3.2） ----
    public int totalStructure = 0;
    /** 缺失位置位集，长度 = totalStructure。无头端用 long[]。 */
    public long[] missingBits = new long[0];

    /** 停摆建筑累计停摆周期（救援排序用）。 */
    public long stoppedSince = -1;

    public BuildingEntry(int id, BuildingKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public int missingCount() {
        int n = 0;
        for (long w : missingBits) n += Long.bitCount(w);
        return n;
    }

    /** integrity = 1 − missing/total；未登记结构的条目恒 1.0。 */
    public double integrity() {
        if (totalStructure <= 0) return 1.0;
        return 1.0 - (double) missingCount() / totalStructure;
    }

    /** 标记一个结构位缺失（MC 侧方块事件翻译后调用；施工上下文自豁免在传感层拦）。 */
    public void markMissing(int index) {
        if (index < 0 || index >= totalStructure) return;
        missingBits[index >> 6] |= (1L << (index & 63));
    }

    public void clearMissing(int index) {
        if (index < 0 || index >= totalStructure) return;
        missingBits[index >> 6] &= ~(1L << (index & 63));
    }

    public boolean isDamaged(double threshold) {
        return integrity() < threshold;
    }

    public boolean isRuin(double ruinThreshold) {
        return integrity() < ruinThreshold;
    }
}
