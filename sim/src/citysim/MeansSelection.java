package citysim;

/**
 * 手段选择（三笔账，§6）。
 * 账 1：TCO 统一 C(r) = 维护/期 + 自身建造/寿命 + 未建前置建造/寿命（寿命 100 周期）。
 * 账 2：每档算 N = ⌈gap/capacity⌉ 与 C(N,r) = N × C(r)，全候选统一取最小（Kimi #11）。
 * 账 3：摊入金额只比较不扣款（Kimi #13）。
 * 软层竞价权重 w = 1/(s+0.1) × (1+bias)，bias 只进软层（#17 裁决）。
 */
public class MeansSelection {

    public static class Candidate {
        public final BuildingKind kind;
        public final int layer;
        public final int n;
        public final double unitTco;
        public final double totalCost;
        public final double weight;

        Candidate(BuildingKind kind, int layer, int n, double unitTco, double totalCost, double weight) {
            this.kind = kind; this.layer = layer; this.n = n;
            this.unitTco = unitTco; this.totalCost = totalCost; this.weight = weight;
        }
    }

    /** 该类型是否服务指定层（V1：广场兼供精神，占位比例可配）。 */
    public static boolean servesLayer(CityLedger city, BuildingKind kind, int layer) {
        if (kind.primaryLayer() == layer) return true;
        return layer == LayerId.SPIRIT && kind == BuildingKind.PLAZA
                && city.config.spiritFromPlazaRatio > 0;
    }

    /** 账 1：单位 TCO（比较用速率量纲：工时/周期）。 */
    public static double unitTco(CityLedger city, BuildingKind kind) {
        CityConfig c = city.config;
        double tco = kind.maintPerCycle(c) + kind.buildHours(c) / c.lifespanCycles;
        // 未建前置摊销：广场的前置 = 水井（解锁链）
        if (kind == BuildingKind.PLAZA && city.countActive(BuildingKind.WELL) == 0) {
            tco += BuildingKind.WELL.buildHours(c) / c.lifespanCycles;
        }
        return tco;
    }

    /** 账 2：某层的最优档（全候选统一取最小）。无可建候选返回 null。 */
    public static Candidate best(CityLedger city, Thermometer.Snapshot snap, int layer) {
        double gap = snap.gap[layer];
        if (gap <= 0) return null;
        Candidate best = null;
        for (BuildingKind k : BuildingKind.values()) {
            if (!city.unlocked.contains(k)) continue;
            if (!servesLayer(city, k, layer)) continue;
            if (Double.isInfinite(k.buildHours(city.config))) continue; // 占位类型
            if (hasStopped(city, k)) continue; // 停摆缺口走救援通道（步骤 3b），不新建
            double capacity = k.capacity(city.config);
            if (capacity <= 0) continue;
            // 精神层借用广场时，缺口按兼供比例折算容量
            if (layer == LayerId.SPIRIT && k == BuildingKind.PLAZA) {
                capacity *= city.config.spiritFromPlazaRatio;
            }
            double queuedCapacity = k == BuildingKind.HOUSE ? city.pendingBeds() : city.countPending(k) * capacity;
            if (gap <= queuedCapacity) continue;
            int n = (int) Math.ceil((gap - queuedCapacity) / capacity);
            double unit = unitTco(city, k);
            double total = n * unit;
            double w = weight(city, layer);
            if (best == null || total < best.totalCost) {
                best = new Candidate(k, layer, n, unit, total, w);
            }
        }
        return best;
    }

    /** 是否存在该类型的停摆建筑（救援优先于新建）。 */
    public static boolean hasStopped(CityLedger city, BuildingKind kind) {
        for (BuildingEntry e : city.buildings)
            if (e.kind == kind && e.state == BuildingEntry.State.STOPPED) return true;
        return false;
    }

    /** 竞价权重：w = 1/(s+0.1) × (1+bias)，bias 只进软层。 */
    public static double weight(CityLedger city, int layer) {
        double w = 1.0 / (city.satisfaction[layer] + city.config.weightEps);
        if (!LayerId.isHard(layer)) w *= (1.0 + city.bias[layer]);
        return w;
    }

    /**
     * 本周期中标者：硬事件置顶（词典序第一个硬缺口层）；否则软层按权重最高且有候选者。
     */
    public static Candidate selectWinner(CityLedger city, Thermometer.Snapshot snap) {
        if (snap.hardLayer >= 0) {
            return best(city, snap, snap.hardLayer); // V1：温饱/安全无可建手段 → null（等库存/事件结束）
        }
        Candidate winner = null;
        for (int l : LayerId.SOFT) {
            if (snap.gap[l] <= 0) continue;
            Candidate cand = best(city, snap, l);
            if (cand == null) continue;
            if (winner == null || cand.weight > winner.weight) winner = cand;
        }
        // A prosperous town can commission one spare house when its last bed fills.
        if (winner==null && city.activeProject==null && city.quarryActive() && city.population>0
                && city.population==snap.beds && city.pendingBeds()==0 && city.foodPoints>=2*snap.dailyNeedPoints) {
            double cost=unitTco(city,BuildingKind.HOUSE);
            return new Candidate(BuildingKind.HOUSE,LayerId.HOUSING,1,cost,cost,1);
        }
        return winner;
    }
}
