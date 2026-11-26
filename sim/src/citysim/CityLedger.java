package citysim;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 城市账本（账本是真，舞台是采样）。
 * 户口口径（Kimi #20）：户口本市民无个体账户，只有人口数；
 * 物化池（materializationPool）里的 id 才对应实体档案，id 单调递增永不复用。
 */
public class CityLedger {

    /** 在建工程（分期切片，沉没工时不迁移——换档只在验收点）。 */
    public static class Project {
        public final BuildingKind kind;
        public final int n;               // 本档数量 N=⌈gap/capacity⌉
        public final double requiredHours; // N × 单栋建造工时
        public double paidHours = 0;
        public int delivered = 0;          // 分期验收：已交付栋数（每付满一栋造价交付一栋）
        public int starvedCycles = 0;      // 连续拿不到预算的周期数（死锁警告）
        public boolean paused = false;     // 硬事件暂停（进度保留）
        public boolean atCheckpoint = false; // 刚到达分期验收点（换档只在此刻允许，Kimi #18）

        Project(BuildingKind kind, int n, double requiredHours) {
            this.kind = kind; this.n = n; this.requiredHours = requiredHours;
        }
        public double remaining() { return Math.max(0, requiredHours - paidHours); }
    }

    /** 硬事件（温度计两级判定的第二级）。 */
    public static class HardEvent {
        public final int layer;
        public int daysActive = 0;
        HardEvent(int layer) { this.layer = layer; }
    }

    public final CityConfig config;
    public final Random random;

    public int dayCount = 1;
    public long cycleCount = 0;

    public int population = 0;
    /** Derived from the world campaign ledger, including the local garrison. */
    int militaryDuty;
    int troopsAway;

    public int workforce() { return Math.max(0, population - militaryDuty); }
    public int mouthsAtHome() { return Math.max(0, population - troopsAway); }

    void assignMilitary(int standing, int garrison, boolean abroad) {
        troopsAway = abroad ? standing : 0;
        militaryDuty = standing + garrison;
        trimMaterialization(mouthsAtHome());
    }
    public double timber, stone, quarryWork, totalIndustryHours;
    public boolean forestAvailable, quarryLocated;
    public static final int QUARRY_TIMBER = 12, QUARRY_HOURS = 8;
    public static final int INDUSTRY_HOUR_LIMIT = 6;
    public static final int QUARRY_CAPACITY_DIVISOR = 2;
    public static final int QUARRY_HOURS_PER_STONE = 1;
    public static final int BUILD_TIMBER = 16, BUILD_STONE = 8;
    public static final int STOCK_LIMIT = 128;
    public static final int PORT_TIMBER = 8, PORT_STONE = 4, PORT_HOURS = 12;
    public boolean portLocated;
    public double portWork;
    public int incomingStone;

    public boolean portActive() { return portLocated && portWork >= PORT_HOURS; }

    boolean payBuildingMaterials() {
        if (!config.requireBuildingMaterials) return true;
        if (timber < BUILD_TIMBER || stone < BUILD_STONE) return false;
        timber -= BUILD_TIMBER;
        stone -= BUILD_STONE;
        return true;
    }

    public boolean quarryActive() { return quarryLocated && quarryWork >= QUARRY_HOURS; }

    /** Production spends labor left after subsistence and operations. Stockpiles are bounded. */
    public double runIndustry(double budget) {
        double left = Math.max(0, Math.min(INDUSTRY_HOUR_LIMIT, budget));
        double available = left;
        if (portLocated && !portActive() && timber >= PORT_TIMBER && stone >= PORT_STONE) {
            double hours = Math.min(left, PORT_HOURS - portWork);
            portWork += hours;
            left -= hours;
            if (portActive()) {
                timber -= PORT_TIMBER;
                stone -= PORT_STONE;
                chronicle.log(dayCount, "港口落成");
            }
        }
        if (incomingStone == 0 && quarryLocated && !quarryActive() && timber >= QUARRY_TIMBER) {
            double hours = Math.min(left, QUARRY_HOURS - quarryWork);
            quarryWork += hours;
            left -= hours;
            if (quarryActive()) timber -= QUARRY_TIMBER;
        }
        if (incomingStone == 0 && quarryActive()) {
            double units = Math.min(left / QUARRY_CAPACITY_DIVISOR, Math.max(0, STOCK_LIMIT - stone));
            stone += units;
            left -= units * QUARRY_HOURS_PER_STONE;
        }
        if (forestAvailable) {
            double hours = Math.min(left, Math.max(0, STOCK_LIMIT - timber));
            timber += hours;
            left -= hours;
        }
        return available - left;
    }
    public double foodPoints = 0;    // 饱食度点数库存（入库折算点数，一天结束统一结算）

    /** 满意度 s∈[0,1]，初始硬层 1、软层 0（样例村基础量表）。 */
    public final double[] satisfaction = new double[LayerId.COUNT];
    /** 城市性格 bias ±0.3，只进软层。测试默认 0（确定性）。 */
    public final double[] bias = new double[LayerId.COUNT];

    public final List<BuildingEntry> buildings = new ArrayList<>();
    public int nextBuildingId = 1;

    /** 已解锁手段档位（科技树 V1 = 解锁 DAG，规则数据化）。 */
    public final Set<BuildingKind> unlocked = EnumSet.of(BuildingKind.HOUSE, BuildingKind.WELL);

    public Project activeProject = null;
    /** 住房应急工程和原工地交替施工；让位的一方保留已付款与逐栋交付进度。 */
    public Project deferredProject = null;
    public HardEvent hardEvent = null;

    public final Chronicle chronicle = new Chronicle();

    /** 物化池：已分配实体的市民 id（≤200 红线在 MC 侧执行）。 */
    public final Set<Integer> materializationPool = new HashSet<>();
    public int nextCitizenId = 0;

    // ---- 工时账（步骤 8 记账，累计值） ----
    public double totalLaborProduced = 0;
    public double totalFarmHours = 0;
    public double totalOpsHours = 0;
    public double totalBuildHours = 0;
    public double totalIdleHours = 0;

    /** 事件通道（V1 桩：严冬已接，其余 M3）。 */
    public final List<SimEvent> activeEvents = new ArrayList<>();

    public CityLedger(long seed, CityConfig config) {
        this.config = config;
        this.random = new Random(seed);
        for (int l : LayerId.HARD) satisfaction[l] = 1.0;
        for (int l : LayerId.SOFT) satisfaction[l] = 0.0;
    }

    // ---- 便捷查询 ----

    public int totalBeds() {
        int beds = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.ACTIVE && b.kind == BuildingKind.HOUSE)
                beds += b.bedCount;
        return beds;
    }

    public int pendingBeds() {
        int beds = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.UNDER_CONSTRUCTION && b.kind == BuildingKind.HOUSE)
                beds += b.bedCount;
        return beds;
    }

    public int countPending(BuildingKind kind) {
        int n = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.UNDER_CONSTRUCTION && b.kind == kind) n++;
        return n;
    }

    public int pendingBuildings() {
        int n = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.UNDER_CONSTRUCTION) n++;
        return n;
    }

    /** Older adapters used ACTIVE even before a paid order received a site. */
    public void requireBuildingPlacement(java.util.function.IntPredicate placed) {
        config.deferBuildingActivation = true;
        for (BuildingEntry b : buildings) {
            if ((b.state == BuildingEntry.State.ACTIVE || b.state == BuildingEntry.State.STOPPED) && !placed.test(b.id)) {
                b.state = BuildingEntry.State.UNDER_CONSTRUCTION;
                b.stoppedSince = -1;
            }
        }
    }

    public void finishConstruction(BuildingEntry building) {
        if (building.state != BuildingEntry.State.UNDER_CONSTRUCTION) return;
        building.state = BuildingEntry.State.ACTIVE;
        building.builtAtCycle = cycleCount;
        chronicle.log(dayCount, building.kind.displayName + " #" + building.id + " 落成");
    }

    public int coveredBy(BuildingKind kind) {
        int cov = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.ACTIVE && b.kind == kind)
                cov += kind.capacity(config);
        return Math.min(cov, population);
    }

    public int countActive(BuildingKind kind) {
        int n = 0;
        for (BuildingEntry b : buildings)
            if (b.state == BuildingEntry.State.ACTIVE && b.kind == kind) n++;
        return n;
    }

    /** 预置一栋已建成建筑（样例村搭建 / 存量登记指令的无头对应物）。 */
    public BuildingEntry placeExisting(BuildingKind kind, int bedCount) {
        BuildingEntry b = new BuildingEntry(nextBuildingId++, kind);
        b.state = BuildingEntry.State.ACTIVE;
        b.bedCount = (kind == BuildingKind.HOUSE) ? bedCount : 0;
        b.builtAtCycle = cycleCount;
        buildings.add(b);
        return b;
    }

    public double softAvg() {
        double sum = 0;
        for (int l : LayerId.SOFT) sum += satisfaction[l];
        return sum / LayerId.SOFT.length;
    }

    /** 严冬是否进行中。 */
    public boolean winterActive() {
        for (SimEvent e : activeEvents)
            if (e.type == SimEvent.Type.HARSH_WINTER && e.active()) return true;
        return false;
    }

    public double currentFarmEff() {
        double eff = 1.0;
        for (SimEvent e : activeEvents)
            if (e.type == SimEvent.Type.HARSH_WINTER && e.active())
                eff = Math.min(eff, e.farmEff);
        return eff;
    }

    // ---- 人口事件 ----

    /** 物化：给未物化市民分配新 id（id 单调递增，死者 id 永不复用——收藏死亡移出裁决）。 */
    public int materializeOne() {
        int id = nextCitizenId++;
        materializationPool.add(id);
        return id;
    }

    /** 市民死亡（MC 侧死亡事件翻译后进来；收藏栏移出在 MC 侧联动）。 */
    public void killCitizen(int id, String cause) {
        if (!materializationPool.remove(id)) return; // 非物化市民走 decrementPopulation
        population = Math.max(0, population - 1);
        chronicle.log(dayCount, "一名市民死于" + cause);
    }

    /** 户口市民死亡（无个体档案，直接减人口）。 */
    public void decrementPopulation(String cause) {
        population = Math.max(0, population - 1);
        chronicle.log(dayCount, "一名市民死于" + cause);
    }

    void loseResidents(int count, String cause) {
        int lost = Math.min(count, population);
        if (lost == 0) return;
        population -= lost;
        trimMaterialization(population);
        chronicle.log(dayCount, cause + " · " + lost + " 人");
    }

    private void trimMaterialization(int max) {
        if (materializationPool.size() > max) {
            var recent = new java.util.TreeSet<>(materializationPool);
            while (recent.size() > max) materializationPool.remove(recent.pollLast());
        }
    }
}
