package citysim;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划者分配循环（§4，Kimi #16 时序契约）：
 *   1 温度计刷新 → 2 手段选择 → 3 预算分配(a保底 b运营+救援 c竞价)
 *   → 4 生产结算 → 5 消费结算 → 6 满意度更新 → 7 验收与解锁 → 8 记账。
 * 旧版「先全额生产后结算」已废止。
 */
public class PlannerCycle {

    public static class Budget {
        public double labor;        // L = 人口 × 每周期工时
        public double farmHours;    // a 保底口粮（+最低光照）
        public double opsHours;     // b 既有运营实际拨付 O实际
        public double buildHours;   // c 竞价胜者本期切片
        public double industryHours;
        public double surplusHours; // c 剩余转储备粮生产
    }

    public static void step(CityLedger city) {
        CityConfig c = city.config;
        city.cycleCount++;

        // 事件通道推进（V1 桩：严冬；M3 接完整事件发生器）
        for (SimEvent e : city.activeEvents) if (e.remainingCycles > 0) e.remainingCycles--;
        city.activeEvents.removeIf(e -> !e.active());

        // ---- 1 温度计刷新：硬事件置顶、多硬层词典序 ----
        // Multiple legacy plazas could be operating for the same residents. Keep enough
        // active to cover both social and spiritual demand, and retain the rest on standby.
        int neededPlazas=plazasNeeded(city);
        int operating=0;
        for(BuildingEntry b:city.buildings) {
            if(b.kind==BuildingKind.PLAZA && b.state==BuildingEntry.State.ACTIVE && ++operating>neededPlazas) {
                b.state=BuildingEntry.State.STOPPED;
                b.stoppedSince=city.cycleCount;
            }
        }
        Thermometer.Snapshot snap = Thermometer.read(city);
        var obsolete=city.activeProject;
        if(obsolete!=null && obsolete.kind==BuildingKind.PLAZA && obsolete.paidHours==0
                && snap.gap[LayerId.SOCIAL]==0 && snap.gap[LayerId.SPIRIT]==0) {
            city.activeProject=null;
            city.chronicle.log(city.dayCount,"已有广场足够，撤回未投入工时的重复订单");
        }
        updateHardEvent(city, snap);

        // ---- 2 手段选择：每档 N=⌈缺口÷容量⌉ 统一比较；换档只在验收点且需 ε 改善 ----
        MeansSelection.Candidate winner = MeansSelection.selectWinner(city, snap);
        manageProject(city, snap, winner);

        // ---- 3 预算分配 ----
        Budget b = allocate(city, snap, neededPlazas);

        // ---- 4 生产结算：产出 = f(实际拨付工时)，物资缺口预留优先 ----
        city.foodPoints += (b.farmHours + b.surplusHours) * c.pointsPerWorkHour * snap.farmEff;

        // ---- 5 消费结算：维持费单扣（每栋每周期一笔，已在 3b 按工时口径实扣）；
        //      饱食度制口粮在日终统一结算（dailySettlement） ----

        // ---- 6 满意度更新：由实际消费量与缺口修复量驱动 ----
        updateSatisfaction(city, snap);

        // ---- 7 验收与解锁：分期验收→交付；≥0.8 入队 ----
        payConstruction(city, b.buildHours);
        checkUnlocks(city);

        // ---- 8 记账入工时账 ----
        city.totalLaborProduced += b.labor;
        city.totalFarmHours += b.farmHours + b.surplusHours;
        city.totalIndustryHours += b.industryHours;
        city.totalOpsHours += b.opsHours;
        city.totalBuildHours += b.buildHours;
        city.totalIdleHours += Math.max(0,
                b.labor - b.farmHours - b.opsHours - b.buildHours - b.surplusHours - b.industryHours);

        // ---- 日终结算（饱食度制：一天结束统一结算） ----
        if (city.cycleCount % c.cyclesPerDay == 0) dailySettlement(city);
    }

    // ================= 步骤 1：硬事件两级判定 =================

    static void updateHardEvent(CityLedger city, Thermometer.Snapshot snap) {
        if (snap.hardLayer >= 0) {
            if (city.hardEvent == null || city.hardEvent.layer != snap.hardLayer) {
                city.hardEvent = new CityLedger.HardEvent(snap.hardLayer);
                city.chronicle.log(city.dayCount, LayerId.name(snap.hardLayer) + "危机爆发");
            }
        } else if (city.hardEvent != null) {
            city.chronicle.log(city.dayCount, LayerId.name(city.hardEvent.layer) + "危机解除");
            city.hardEvent = null;
        }
    }

    // ================= 步骤 2：工程管理（开工/暂停/复工/换档） =================

    static void manageProject(CityLedger city, Thermometer.Snapshot snap, MeansSelection.Candidate winner) {
        CityLedger.Project p = city.activeProject;
        CityLedger.Project waiting = city.deferredProject;
        boolean needsHouse = snap.hardLayer == LayerId.HOUSING && winner != null
                && winner.kind == BuildingKind.HOUSE;
        if (needsHouse && (p == null || p.kind != BuildingKind.HOUSE)) {
            if (p != null) pauseProject(city, p);
            if (waiting != null && waiting.kind == BuildingKind.HOUSE) {
                city.activeProject = waiting;
                city.deferredProject = p;
            } else {
                if (p != null) city.deferredProject = p;
                startProject(city, winner);
            }
        } else if (waiting != null && waiting.kind != BuildingKind.HOUSE
                && snap.gap[LayerId.HOUSING] == 0 && (p == null || p.kind == BuildingKind.HOUSE)) {
            if (p != null) pauseProject(city, p);
            city.activeProject = waiting;
            city.deferredProject = p;
        }
        p = city.activeProject;
        if (p != null) {
            // 硬事件暂停软工程、进度保留；硬层自身的工程继续（它就是解法）
            boolean pause = snap.hardLayer >= 0 && p.kind.primaryLayer() != snap.hardLayer;
            if (pause && !p.paused) {
                pauseProject(city, p);
            } else if (!pause && p.paused) {
                p.paused = false;
                p.atCheckpoint = false;
                city.chronicle.log(city.dayCount, p.kind.displayName + "工地复工");
            }
        }
        if (winner == null) return;
        if (p == null) {
            startProject(city, winner);
        } else if (p.kind != winner.kind && !p.paused && p.atCheckpoint) {
            // 换档只在验收点（Kimi #18），且需 10% 成本改善（ε 裁决）；沉没工时不迁移
            MeansSelection.Candidate cur = MeansSelection.best(city, snap, p.kind.primaryLayer());
            if (cur != null && winner.totalCost < cur.totalCost * (1.0 - city.config.switchEpsilon)) {
                city.chronicle.log(city.dayCount, "换档：" + p.kind.displayName + " 停 → "
                        + winner.kind.displayName + "（沉没工时不迁移）");
                startProject(city, winner);
            }
        }
        if (city.activeProject != null) city.activeProject.atCheckpoint = false; // 验收点消费一次
    }

    static void startProject(CityLedger city, MeansSelection.Candidate winner) {
        double perUnit = winner.kind.buildHours(city.config);
        city.activeProject = new CityLedger.Project(winner.kind, winner.n, winner.n * perUnit);
        city.chronicle.log(city.dayCount, winner.kind.displayName + "开工 ×" + winner.n);
    }

    private static void pauseProject(CityLedger city, CityLedger.Project p) {
        if (p.paused) return;
        p.paused = true;
        city.chronicle.log(city.dayCount, p.kind.displayName + "工地暂停（危机优先，进度保留）");
    }

    // ================= 步骤 3：预算分配 =================

    private static int plazasNeeded(CityLedger city) {
        double ratio=city.config.spiritFromPlazaRatio;
        double capacity=city.config.plazaCoverage*(ratio>0 ? Math.min(1,ratio) : 1);
        return (int)Math.ceil(city.population/Math.max(0.0001,capacity));
    }

    static Budget allocate(CityLedger city, Thermometer.Snapshot snap, int neededPlazas) {
        CityConfig c = city.config;
        Budget b = new Budget();
        b.labor = snap.laborPool;

        // a. 保底：口粮工时 + 最低光照（保底不关停）
        b.farmHours = Math.min(b.labor, snap.farmHoursNeeded + c.minLightHours);
        double remaining = b.labor - b.farmHours;

        // b. 既有运营 + 救援；S = max(0, L − O实际 − B实际)，不足按 精神→社交→卫生→安全 逆序逐级停摆
        double opsCost = 0;
        int activePlazas = 0;
        for (BuildingEntry e : city.buildings) {
            if (e.state != BuildingEntry.State.ACTIVE) continue;
            opsCost += e.kind.maintPerCycle(c);
            if (e.kind == BuildingKind.PLAZA) activePlazas++;
        }

        // 救援：停摆建筑作救援项（与停摆逆序：安全→卫生→社交→精神，即层号小者先救）
        List<BuildingEntry> stopped = new ArrayList<>();
        for (BuildingEntry e : city.buildings)
            if (e.state == BuildingEntry.State.STOPPED) stopped.add(e);
        stopped.sort((x, y) -> Integer.compare(x.kind.primaryLayer(), y.kind.primaryLayer()));
        for (BuildingEntry e : stopped) {
            if (e.kind == BuildingKind.PLAZA && activePlazas >= neededPlazas) continue;
            double m = e.kind.maintPerCycle(c);
            if (remaining - opsCost - m >= -1e-9) {
                e.state = BuildingEntry.State.ACTIVE;
                e.stoppedSince = -1;
                opsCost += m;
                if (e.kind == BuildingKind.PLAZA) activePlazas++;
                city.chronicle.log(city.dayCount, e.kind.displayName + "救援复工");
            }
        }

        // 停摆：逆序 精神→社交→卫生→安全（层号大者先停）
        while (remaining - opsCost < -1e-9) {
            BuildingEntry victim = null;
            for (BuildingEntry e : city.buildings) {
                if (e.state != BuildingEntry.State.ACTIVE) continue;
                if (e.kind.maintPerCycle(c) <= 0) continue;
                if (victim == null || e.kind.primaryLayer() > victim.kind.primaryLayer()) victim = e;
            }
            if (victim == null) break;
            victim.state = BuildingEntry.State.STOPPED;
            victim.stoppedSince = city.cycleCount;
            opsCost -= victim.kind.maintPerCycle(c);
            city.chronicle.log(city.dayCount, victim.kind.displayName + "停摆（预算不足）");
        }
        b.opsHours = Math.min(opsCost, remaining);

        double s = Math.max(0, remaining - b.opsHours);

        if (snap.hardLayer != LayerId.FOOD) {
            b.industryHours = city.runIndustry(s * 0.5);
            s -= b.industryHours;
        }
        // c. 剩余竞价 → 当前工程切片 = min(切片上限4, S, 剩余)
        CityLedger.Project p = city.activeProject;
        if (p != null && !p.paused) {
            b.buildHours = Math.min(Math.min(c.sliceCap, s), p.remaining());
        }
        b.surplusHours = s - b.buildHours; // 剩余转储备粮（物资预留思想的 V1 简化）
        return b;
    }

    // ================= 步骤 6：满意度更新 =================

    static void updateSatisfaction(CityLedger city, Thermometer.Snapshot snap) {
        CityConfig c = city.config;
        for (int l = 0; l < LayerId.COUNT; l++) {
            boolean fulfilled = snap.gap[l] <= 1e-9;
            if (fulfilled) city.satisfaction[l] = Math.min(1.0, city.satisfaction[l] + c.satGain);
            else city.satisfaction[l] = Math.max(0.0, city.satisfaction[l] - c.satLoss);
        }
    }

    // ================= 步骤 7：验收与解锁 =================

    static void payConstruction(CityLedger city, double buildHours) {
        CityLedger.Project p = city.activeProject;
        if (p == null || p.paused) return;
        if (buildHours <= 1e-12 && p.remaining() > 1e-9) {
            p.starvedCycles++;
            if (p.starvedCycles == city.config.starvationWarnCycles)
                city.chronicle.log(city.dayCount,
                        "警告：工程连续 " + p.starvedCycles + " 周期未获预算（死锁风险）");
        } else {
            p.starvedCycles = 0;
        }
        double prevPaid = p.paidHours;
        p.paidHours += buildHours;
        // 分期验收点：每付满一个完整切片（4 工时）到达一次验收点，允许换档重选
        double slice = city.config.sliceCap;
        if (Math.floor(p.paidHours / slice) > Math.floor(prevPaid / slice)) p.atCheckpoint = true;
        double perUnit = p.kind.buildHours(city.config);
        // Each fully funded unit becomes an order. Adapters may require a physical handoff.
        while (p.delivered < p.n && p.paidHours >= (p.delivered + 1) * perUnit - 1e-9) {
            if (!city.payBuildingMaterials()) break;
            deliver(city, p.kind);
            p.delivered++;
        }
        if (p.delivered >= p.n) {
            String status = city.config.deferBuildingActivation ? " 已备料" : " 落成";
            city.chronicle.log(city.dayCount, p.kind.displayName + " ×" + p.n + status);
            city.activeProject = null;
        }
    }

    static void deliver(CityLedger city, BuildingKind kind) {
        BuildingEntry e = new BuildingEntry(city.nextBuildingId++, kind);
        e.state = city.config.deferBuildingActivation ? BuildingEntry.State.UNDER_CONSTRUCTION : BuildingEntry.State.ACTIVE;
        e.builtAtCycle = city.cycleCount;
        if (kind == BuildingKind.HOUSE) e.bedCount = city.config.houseBeds;
        city.buildings.add(e);
    }

    /** 解锁 DAG（V1 硬编码一条：卫生≥0.8 → 广场；规则数据化见科技树裁决）。 */
    static void checkUnlocks(CityLedger city) {
        if (!city.unlocked.contains(BuildingKind.PLAZA)
                && city.satisfaction[LayerId.HYGIENE] >= city.config.unlockThreshold) {
            city.unlocked.add(BuildingKind.PLAZA);
            city.chronicle.log(city.dayCount,
                    "解锁广场图纸（卫生达到 " + city.config.unlockThreshold + "）");
        }
    }

    // ================= 日终结算 =================

    static void dailySettlement(CityLedger city) {
        CityConfig c = city.config;

        // 消费结算（饱食度制）：日需 = 人口 × 20 点；扣到具体食物的向上取整在 MC 侧库存执行，
        // 无头端按点数记账（多种食物先扣工时估值低的——MC 侧规则）
        double need = city.mouthsAtHome() * (double) c.satietyPerCitizenPerDay;
        if (city.foodPoints + 1e-9 >= need) {
            city.foodPoints -= need;
        } else {
            double shortfall = need - city.foodPoints;
            city.foodPoints = 0;
            int starved = (int) (shortfall / c.satietyPerCitizenPerDay);
            for (int i = 0; i < starved && city.population > 0; i++) city.decrementPopulation("饥饿");
            city.chronicle.log(city.dayCount, "饥荒：口粮短缺 " + (int) Math.ceil(shortfall)
                    + " 点，" + starved + " 人饿死");
        }

        // 繁殖闸门：床余 + 粮足才可能出生；住满→繁殖停是正常闸门，不报硬事件（#8 窄化）
        int beds = city.totalBeds();
        if (beds > city.population && city.hardEvent == null
                && city.satisfaction[LayerId.FOOD] >= c.birthMinFoodSat
                && city.random.nextDouble() < c.birthChance) {
            city.population++;
            city.chronicle.log(city.dayCount, "一名新生儿降生");
        }

        // 人口流动阀门：危机持续过红线 → 每期迁出 1；软层均值高且有床余 → 概率迁入
        if (city.hardEvent != null && city.hardEvent.daysActive >= c.redlineDays && city.population > 1) {
            city.population--;
            if (!city.materializationPool.isEmpty()) {
                int max = -1;
                for (int id : city.materializationPool) if (id > max) max = id;
                city.materializationPool.remove(max);
            }
            city.chronicle.log(city.dayCount, "一户人家迁出（危机持续超过红线）");
        } else if (city.hardEvent == null && beds > city.population
                && city.softAvg() >= c.immigrationSatThreshold
                && city.random.nextDouble() < c.immigrantChance) {
            city.population++;
            city.chronicle.log(city.dayCount, "移民迁入");
        }

        if (city.hardEvent != null) city.hardEvent.daysActive++;
        city.dayCount++;
    }
}
