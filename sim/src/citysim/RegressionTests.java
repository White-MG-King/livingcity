package citysim;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import citysim.SeaLanes.Cell;

/**
 * 计划者与城市账本回归用例：
 *   A 样例村 A（R=0 合法稳态）
 *   B 样例村 B（落成→爬升→解锁链路）
 *   C 住满不报警（繁殖停滞窄化）
 *   D 硬事件暂停软工程、保留进度
 *   E 收藏死亡不再物化（id 永不复用）
 *   F 灾后住宅接手施工、补床后复工
 *   G 瞬时与持续事件按期清理
 * 零依赖：main() 直跑，退出码 0 = 全绿。
 */
public class RegressionTests {
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        testA_steadyState();
        testB_growthChain();
        testC_bedsFullNoAlarm();
        testD_hardEventPausesSoftProject();
        testE_collectedDeathPermanent();
        testHousingInterruptsConstruction();
        testEventExpiry();
        testH_materialsAndLegacyPlazas();
        testPlacementHandoff();
        testPortTrade();
        testPreparedWater();
        testNationExpansion();
        WarRegression.run();
        System.out.println("==================================");
        System.out.println("通过 " + passed + " 项 / 失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("RESULT: RED");
            System.exit(1);
        }
        System.out.println("RESULT: ALL GREEN");
    }

    private static void testPreparedWater() {
        var left = new Cell(3, 0);
        var right = new Cell(4, 0);
        var crossing = new SeaLanes(Set.of(left, right));
        crossing.prepareComponents();
        check(crossing.componentsPrepared() && crossing.route(left, right, 2).equals(List.of(left, right)),
                "J6 预计算水域连通后保留可航路径", "-");
        check(SeaLanes.localWaterRegions((1 << 3) | (1 << 4) | (1 << 8)).equals(List.of(1 << 3, (1 << 4) | (1 << 8))),
                "J7 区块水域索引按共边连通，行末不接下一行行首", "-");
    }

    static void testNationExpansion() {
        var source = new CityLedger(1, new CityConfig());
        source.population = 32;
        var housing = source.placeExisting(BuildingKind.HOUSE, 37);
        source.foodPoints = 1640;
        source.timber = 32;
        source.stone = 16;
        for (int i = 0; i < 30; i++) source.materializeOne();
        check(NationExpansion.embark(source) == null && source.population == 32,
                "K1 空床足够时保留母城人口与资源", "不满足派队条件仍已扣款");
        housing.bedCount = 36;
        source.foodPoints--;
        check(!NationExpansion.ready(source), "K2 母城必须保留两天口粮", "没有保留母城口粮");
        source.foodPoints++;
        var party = NationExpansion.embark(source);
        check(party != null && source.population == 26 && source.foodPoints == 1280 && source.timber == 0
                        && source.stone == 0 && source.materializationPool.size() == 26
                        && party.retired().equals(List.of(29, 28, 27, 26)),
                "K3 派队转出六人和物资并退役多余身份号", "转出数量或居民记录错误");
        if (party != null) NationExpansion.receive(source, party.supplies());
        check(source.population == 32 && source.foodPoints == 1640 && source.timber == 32
                        && source.stone == 16 && source.materializeOne() == 30,
                "K4 归队原量退款且居民 ID 不复用", "退队重复或丢失资源，或旧 ID 复用");

        long center = CityFrontier.key(0, 0);
        long edge = CityFrontier.key(1, 0), bridge = CityFrontier.key(2, 0), goal = CityFrontier.key(3, 0);
        var corridor = Set.of(edge, bridge, goal);
        var target = NationExpansion.find(Set.of(edge), center, 0, corridor::contains, key -> true, key -> key == goal);
        check(target != null && target.chunk() == goal && target.steps() == 3 && target.travelTime() == 12,
                "K5 目标沿四向陆路搜索且行程按步数计算", "目标从未连接或行程错误");
        check(NationExpansion.find(Set.of(edge), center, 0, corridor::contains, key -> key != bridge, key -> key == goal) == null,
                "K6 隔水目标无法陆路拓殖", "拓殖搜索穿越水格");
        int[] probes = {0};
        NationExpansion.find(Set.of(edge), center, 0, key -> true, key -> { probes[0]++; return true; }, key -> false);
        check(probes[0] <= NationExpansion.MAX_PROBES, "K7 每轮地形探测受预算约束", "勘查超过地形探测预算");
    }

    static void testPortTrade() {
        var origin = new Cell(-1, 0);
        var end = new Cell(1, 0);
        var detour = List.of(origin, new Cell(-1, 1), new Cell(0, 1), new Cell(1, 1), end);
        var bay = new SeaLanes(Set.copyOf(detour));
        check(bay.route(origin, end, 5).equals(detour) && bay.route(origin, end, 4).isEmpty(),
                "J1 航线绕开陆地并遵守搜索距离", "航线跨过陆地或超出距离");
        var barrier = new SeaLanes(Set.of(origin, new Cell(0, 1), end));
        check(barrier.route(origin, end, 20).isEmpty(), "J2 对角相接的水格不连通", "航线穿过陆桥");

        var route = IntStream.range(0, 16).mapToObj(x -> new Cell(x, 0)).toList();
        var seller = new PortTrade.Supply(2, 12, 2400, route);
        var alternate = new PortTrade.Supply(3, 12, 2400, route);
        var single = PortTrade.choose(12, 10000, 6000, 20, List.of(seller));
        var competing = PortTrade.choose(12, 10000, 6000, 20, List.of(alternate, seller));
        check(single != null && single.ceiling() == 4800 && single.price() == 3960
                        && competing != null && competing.price() == 3480 && competing.supply().seller() == 2,
                "J3 竞争将成交价降低到 34.80，报价并列按城市编号", "报价滑到区间边界或排序漂移");
        check(PortTrade.choose(12, 3601, 6000, 20, List.of(seller)) == null
                        && PortTrade.choose(12, 10000, 6000, 8, List.of(seller)) == null,
                "J4 排除加运费后超预算和晚到的订单", "买方付不起或货物来不及");

        var city = new CityLedger(1, new CityConfig());
        city.portLocated = true;
        city.timber = 8;
        city.stone = 4;
        city.runIndustry(6);
        city.runIndustry(6);
        boolean completed = city.portActive() && city.timber == 0 && city.stone == 0;
        city.quarryLocated = true;
        city.quarryWork = CityLedger.QUARRY_HOURS;
        city.incomingStone = 8;
        city.runIndustry(6);
        check(completed && city.stone == 0, "J5 港口支付工时与材料，在途采购锁住重复开采",
                "港口重复付款或收货容量被开采取代");
    }

    static void check(boolean cond, String name, String detail) {
        if (cond) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + " — " + detail);
        }
    }

    static void testPlacementHandoff() {
        CityLedger city = new SimHarness(7, new CityConfig()).city;
        city.population = 6;
        BuildingEntry original = city.placeExisting(BuildingKind.HOUSE, 2);
        BuildingEntry legacyUnplaced = city.placeExisting(BuildingKind.HOUSE, 4);
        city.requireBuildingPlacement(id -> id == original.id);
        check(city.totalBeds() == 2 && city.pendingBeds() == 4 && city.pendingBuildings() == 1,
                "I1 旧账本已付未落地住宅迁入施工队列", "床位提前生效");
        var needs = Thermometer.read(city);
        check(needs.gap[LayerId.HOUSING] == 4 && MeansSelection.best(city, needs, LayerId.HOUSING) == null,
                "I2 缺床照算，已有工单抵扣重复下单", "缺口或下单不正确");
        PlannerCycle.deliver(city, BuildingKind.WELL);
        check(city.coveredBy(BuildingKind.WELL) == 0 && city.countPending(BuildingKind.WELL) == 1,
                "I3 新付清工程等待现场验收", "水井提前生效");
        city.finishConstruction(legacyUnplaced);
        check(city.totalBeds() == 6 && city.pendingBeds() == 0,
                "I4 现场验收后床位才可用", "完工未交付床位");
    }

    static void testHousingInterruptsConstruction() {
        CityConfig cfg = new CityConfig();
        cfg.birthChance = 0;
        cfg.immigrantChance = 0;
        SimHarness sim = new SimHarness(42, cfg);
        CityLedger city = sim.city;
        city.population = 8;
        city.foodPoints = 2000;
        BuildingEntry home = city.placeExisting(BuildingKind.HOUSE, 7);
        CityLedger.Project plaza = new CityLedger.Project(BuildingKind.PLAZA, 1, cfg.plazaBuildHours);
        plaza.paidHours = 12;
        city.activeProject = plaza;

        sim.run(1);
        CityLedger.Project house = city.activeProject;
        check(house != null && house.kind == BuildingKind.HOUSE && house.paidHours > 0,
                "F1 缺床后住宅取得建设工时", "住房没有接手");
        check(city.deferredProject == plaza && plaza.paused && plaza.paidHours == 12,
                "F2 广场保留已付工时", "暂停进度异常");
        if (house == null || house == plaza) return;

        double housePaid = house.paidHours;
        home.bedCount = 8;
        sim.run(1);
        check(city.activeProject == plaza && plaza.paidHours > 12 && city.deferredProject == house
                        && house.paidHours == housePaid, "F3 玩家补床后广场立即复工", "切换丢失工程");
        home.bedCount = 7;
        sim.run(1);
        check(city.activeProject == house && house.paidHours > housePaid,
                "F4 再次缺床沿用原住宅工时", "新开了重复工程");
        for (int i = 0; i < 500 && city.totalBeds() < city.population; i++) sim.run(1);
        sim.run(1);
        check(city.totalBeds() == 7 + cfg.houseBeds && city.deferredProject == null
                        && city.activeProject == plaza && !plaza.paused,
                "F5 住宅只交付一次，原广场恢复", "床位或工程状态异常");
    }

    static void testEventExpiry() {
        CityConfig cfg = new CityConfig();
        SimHarness sim = new SimHarness(1, cfg);
        SimEvent winter = SimEvent.harshWinter(3, cfg.harshWinterFarmEff);
        sim.injectEvent(winter);
        boolean valid = true;
        for (int i = 1; i <= 1000; i++) {
            sim.injectEvent(SimEvent.fire(-1));
            sim.run(1);
            int expectedCount = i < 3 ? 1 : 0;
            valid &= sim.city.activeEvents.size() == expectedCount
                    && sim.city.activeEvents.contains(winter) == (i < 3);
        }
        check(valid, "G1 火灾千周期无残留，严冬按期到期", "活动事件与倒计时不符");
    }

    // ================= 用例 A =================

    static void testA_steadyState() {
        System.out.println("用例 A：样例村 A（L=2，R=0 合法稳态，1000 周期）");
        CityConfig cfg = new CityConfig();
        SimHarness h = new SimHarness(42, cfg);
        h.city.population = 2;
        h.city.placeExisting(BuildingKind.HOUSE, 2);          // 床数挂靠住宅条目
        h.city.foodPoints = 2 * cfg.satietyPerCitizenPerDay * 10.0; // 初始粮 10 天量

        boolean sawHard = false;
        for (int i = 0; i < 1000; i++) {
            PlannerCycle.step(h.city);
            if (h.city.hardEvent != null) sawHard = true;
        }
        check(!sawHard, "A1 千周期无硬事件", "出现过 hardEvent");
        check(h.city.population == 2, "A2 人口稳定为 2", "pop=" + h.city.population);
        check(h.city.satisfaction[LayerId.FOOD] > 0.9, "A3 温饱满意度高位",
                "s0=" + h.city.satisfaction[LayerId.FOOD]);
        check(h.city.satisfaction[LayerId.HOUSING] > 0.9, "A4 住房满意度高位",
                "s1=" + h.city.satisfaction[LayerId.HOUSING]);
        check(h.city.foodPoints > 300, "A5 存粮未枯竭", "stock=" + h.city.foodPoints);
        check(h.city.totalLaborProduced > 0 && h.city.totalBuildHours >= 0,
                "A6 工时账有记录", "produced=" + h.city.totalLaborProduced);
    }

    // ================= 用例 B =================

    static void testB_growthChain() {
        System.out.println("用例 B：样例村 B（L=30，R=28 → 落成→爬升→解锁链路，3000 周期）");
        CityConfig cfg = new CityConfig();
        cfg.birthChance = 0;
        cfg.immigrantChance = 0;   // 确定性：关掉随机阀门
        SimHarness h = new SimHarness(7, cfg);
        h.city.population = 30;
        h.city.placeExisting(BuildingKind.HOUSE, 2);
        h.city.foodPoints = 30 * cfg.satietyPerCitizenPerDay * 10.0;

        boolean sawHousing = false;
        for (int i = 0; i < 3000; i++) {
            PlannerCycle.step(h.city);
            if (h.city.hardEvent != null && h.city.hardEvent.layer == LayerId.HOUSING) sawHousing = true;
        }
        CityLedger city = h.city;
        check(sawHousing, "B1 住房硬事件确实触发过", "从未触发（样例构造错误）");
        check(city.hardEvent == null, "B2 硬事件最终解除",
                city.hardEvent == null ? "-" : LayerId.name(city.hardEvent.layer) + "仍红");
        check(city.totalBeds() >= city.population, "B3 床数覆盖人口",
                city.totalBeds() + "床/" + city.population + "人");
        check(city.countActive(BuildingKind.WELL) >= 1, "B4 水井落成",
                "wells=" + city.countActive(BuildingKind.WELL));
        check(city.unlocked.contains(BuildingKind.PLAZA), "B5 卫生≥0.8 解锁广场",
                "s3=" + city.satisfaction[LayerId.HYGIENE]);
        check(city.countActive(BuildingKind.PLAZA) >= 1, "B6 广场落成",
                "plazas=" + city.countActive(BuildingKind.PLAZA));
        check(city.population >= 20 && city.population <= 30, "B7 危机期迁出后城市存续",
                "pop=" + city.population);
        check(city.chronicle.contains("开工") && city.chronicle.contains("落成"),
                "B8 编年史记录开工与落成", "entries=" + city.chronicle.size());
        check(city.satisfaction[LayerId.HYGIENE] >= 0.8, "B9 卫生满意度爬升到解锁线以上",
                "s3=" + city.satisfaction[LayerId.HYGIENE]);
    }

    // ================= 用例 C =================

    static void testC_bedsFullNoAlarm() {
        System.out.println("用例 C：住满不报警（床数==人口 → 繁殖停是正常闸门，400 周期）");
        CityConfig cfg = new CityConfig();
        SimHarness h = new SimHarness(11, cfg);
        h.city.population = 4;
        h.city.placeExisting(BuildingKind.HOUSE, 4);
        h.city.foodPoints = 4 * cfg.satietyPerCitizenPerDay * 10.0;

        boolean sawHousing = false;
        for (int i = 0; i < 400; i++) {
            PlannerCycle.step(h.city);
            if (h.city.hardEvent != null && h.city.hardEvent.layer == LayerId.HOUSING) sawHousing = true;
        }
        check(!sawHousing, "C1 住满不触发住房硬事件", "误报出现");
        check(h.city.population == 4, "C2 繁殖闸门生效：人口不增", "pop=" + h.city.population);
        check(!h.city.chronicle.contains("新生儿"), "C3 编年史无出生记录", "有出生");
        check(h.city.totalBeds() == 4, "C4 床账本恒等", "beds=" + h.city.totalBeds());
    }

    // ================= 用例 D =================

    static void testD_hardEventPausesSoftProject() {
        System.out.println("用例 D：硬事件暂停软工程、保留进度（严冬→饥荒→恢复→复工落成）");
        CityConfig cfg = new CityConfig();
        cfg.birthChance = 0;
        cfg.immigrantChance = 0;
        SimHarness h = new SimHarness(99, cfg);
        CityLedger city = h.city;
        city.population = 30;
        city.placeExisting(BuildingKind.HOUSE, 32);
        city.placeExisting(BuildingKind.WELL, 0);
        city.unlocked.add(BuildingKind.PLAZA);
        city.satisfaction[LayerId.HYGIENE] = 0.9;
        city.foodPoints = 100; // 库存压低，让严冬立刻打穿温饱线

        // 手动挂起广场工程：已付 200/480 工时
        city.activeProject = new CityLedger.Project(BuildingKind.PLAZA, 1, cfg.plazaBuildHours);
        city.activeProject.paidHours = 200;

        h.injectEvent(SimEvent.harshWinter(100, cfg.harshWinterFarmEff)); // 5 游戏日严冬

        h.run(60); // 严冬第 3 天
        boolean foodCrisis = city.hardEvent != null && city.hardEvent.layer == LayerId.FOOD;
        boolean paused = city.activeProject != null && city.activeProject.paused;
        double paidWinter = city.activeProject == null ? -1 : city.activeProject.paidHours;
        check(foodCrisis, "D1 严冬触发温饱硬事件",
                city.hardEvent == null ? "无硬事件" : "layer=" + LayerId.name(city.hardEvent.layer));
        check(paused, "D2 软工程被暂停", "paused=false");
        check(Math.abs(paidWinter - 200) < 1e-9, "D3 暂停期间进度冻结在 200 工时", "paid=" + paidWinter);

        h.run(1940); // 严冬结束 → 恢复 → 复工 → 落成
        check(city.hardEvent == null, "D4 硬事件最终解除",
                city.hardEvent == null ? "-" : LayerId.name(city.hardEvent.layer) + "仍红");
        check(city.countActive(BuildingKind.PLAZA) >= 1, "D5 广场最终落成（进度未丢）",
                "plazas=" + city.countActive(BuildingKind.PLAZA));
        check(city.chronicle.contains("广场工地复工"), "D6 编年史记录复工", "-");
        check(city.population > 0, "D7 城市存续", "pop=" + city.population);
        check(city.countActive(BuildingKind.WELL) >= 1, "D8 停摆水井被救援复工",
                "wells=" + city.countActive(BuildingKind.WELL));
    }

    // ================= 用例 E =================

    static void testE_collectedDeathPermanent() {
        System.out.println("用例 E：收藏死亡不再物化（死亡即移出，id 永不复用，500 周期）");
        CityConfig cfg = new CityConfig();
        SimHarness h = new SimHarness(5, cfg);
        CityLedger city = h.city;
        city.population = 5;
        city.placeExisting(BuildingKind.HOUSE, 8);
        city.foodPoints = 5 * cfg.satietyPerCitizenPerDay * 10.0;

        h.materializeUpTo(5); // 物化池 = {0,1,2,3,4}
        int dead = 2;
        h.killCitizen(dead, "玩家袭击");
        boolean removedAtOnce = !city.materializationPool.contains(dead);

        h.run(500);
        check(removedAtOnce, "E1 死亡即移出收藏栏（无墓碑标记）", "仍在池中");
        check(!city.materializationPool.contains(dead), "E2 500 周期后死者从未被重新物化", "id 回来了");
        check(city.materializationPool.size() == 4, "E3 物化池保持 4 名",
                "size=" + city.materializationPool.size());
        check(city.population >= 4, "E4 人口不低于存活数", "pop=" + city.population);
        check(city.chronicle.contains("死于"), "E5 编年史记录死亡", "-");
        check(city.nextCitizenId > dead, "E6 市民 id 单调递增永不复用", "next=" + city.nextCitizenId);
    }
    static void testH_materialsAndLegacyPlazas() {
        CityConfig cfg = new CityConfig();
        cfg.requireBuildingMaterials = true;
        CityLedger city = new CityLedger(0, cfg);
        city.population = 8;
        city.foodPoints = 800;
        city.placeExisting(BuildingKind.HOUSE, 8);
        city.placeExisting(BuildingKind.WELL, 0);
        city.placeExisting(BuildingKind.PLAZA, 0);
        city.placeExisting(BuildingKind.PLAZA, 0);
        city.placeExisting(BuildingKind.PLAZA, 0);
        city.activeProject = new CityLedger.Project(BuildingKind.PLAZA, 1, cfg.plazaBuildHours);
        PlannerCycle.step(city);
        check(city.activeProject == null && city.countActive(BuildingKind.PLAZA) == 1
                && city.buildings.size() == 5, "H1 重复广场不占用工时，保留旧建筑记录", "-");

        city.activeProject = new CityLedger.Project(BuildingKind.HOUSE, 2, 2 * cfg.houseBuildHours);
        city.timber = CityLedger.BUILD_TIMBER;
        city.stone = CityLedger.BUILD_STONE - 1;
        PlannerCycle.payConstruction(city, 2 * cfg.houseBuildHours);
        check(city.totalBeds() == 8 && city.timber == CityLedger.BUILD_TIMBER,
                "H2 工时付完仍需材料，缺料时不扣另一种库存", "-");
        city.stone++;
        PlannerCycle.payConstruction(city, 0);
        check(city.totalBeds() == 12 && city.timber == 0 && city.stone == 0 && city.activeProject.delivered == 1,
                "H3 补料后只交一栋，扣料一次", "-");
        PlannerCycle.payConstruction(city, 0);
        check(city.totalBeds() == 12, "H4 材料不足时不会重复交付", "-");
        city.timber = CityLedger.BUILD_TIMBER;
        city.stone = CityLedger.BUILD_STONE;
        PlannerCycle.payConstruction(city, 0);
        check(city.totalBeds() == 16 && city.activeProject == null && city.timber == 0 && city.stone == 0,
                "H5 补足第二批料后无需再付工时", "-");
    }

}
