package citysim;

/**
 * 无头测试台入口（《模拟器与投影层》§2）：无头跑 1000 个周期只要几秒。
 * MC 侧（M2+）通过传感层把方块/死亡/区块事件翻译成对本 API 的调用。
 */
public class SimHarness {
    public final CityLedger city;

    public SimHarness(long seed, CityConfig config) {
        this.city = new CityLedger(seed, config);
    }

    /** 推进 n 个周期。 */
    public void run(int cycles) {
        for (int i = 0; i < cycles; i++) PlannerCycle.step(city);
    }

    /** 注入事件（M3 前由测试/MC 侧手动注入；M3 起由随机发生器按权重表 roll）。 */
    public void injectEvent(SimEvent e) {
        city.activeEvents.add(e);
        if (e.type == SimEvent.Type.FIRE && e.targetBuildingId >= 0) {
            for (BuildingEntry b : city.buildings) {
                if (b.id == e.targetBuildingId) {
                    int dmg = Math.max(1, b.totalStructure * 3 / 10); // 火灾打掉三成结构点
                    for (int i = 0; i < dmg; i++) b.markMissing(i);
                    city.chronicle.log(city.dayCount, b.kind.displayName + "遭火灾");
                }
            }
        }
    }

    /** 物化：给最多 cap 名市民分配实体 id（MC 侧受 ≤200 红线与 LOD 96 格约束）。 */
    public void materializeUpTo(int cap) {
        int target = Math.min(cap, city.mouthsAtHome());
        while (city.materializationPool.size() < target) city.materializeOne();
    }

    /** 市民死亡（MC 侧死亡事件翻译；收藏栏移出在 MC 侧联动——死亡即移出，无墓碑标记）。 */
    public void killCitizen(int id, String cause) {
        city.killCitizen(id, cause);
    }
}
