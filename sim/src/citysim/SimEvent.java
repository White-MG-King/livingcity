package citysim;

/**
 * 模拟事件（V1 桩，M3 接入完整事件发生器）。
 * 架构要点（《事件系统》§1）：事件只是往温度计里塞东西——
 * 受灾结果自动变成硬缺口/效率变化，分配循环零改动。
 * 无头端由 SimHarness.injectEvent 注入；MC 端将来由随机发生器按权重表 roll。
 */
public class SimEvent {
    public enum Type {
        HARSH_WINTER, // 严冬：农业效率乘数下降
        FIRE,         // 火灾：建筑完整性骤降（M3 实现物理端，无头端只打完整性）
        PLAGUE,       // 瘟疫：卫生满意度暴跌（M3）
        RAID          // 袭击：安全层考验（M3）
    }

    public final Type type;
    public int remainingCycles;      // >0 表示持续中
    public final double farmEff;     // 严冬期间的农业效率乘数
    public final int targetBuildingId; // 火灾目标（-1 = 无）

    private SimEvent(Type type, int cycles, double farmEff, int target) {
        this.type = type;
        this.remainingCycles = cycles;
        this.farmEff = farmEff;
        this.targetBuildingId = target;
    }

    public static SimEvent harshWinter(int cycles, double farmEff) {
        return new SimEvent(Type.HARSH_WINTER, cycles, farmEff, -1);
    }

    public static SimEvent fire(int buildingId) {
        return new SimEvent(Type.FIRE, 0, 1.0, buildingId);
    }

    public boolean active() { return remainingCycles > 0; }
}
