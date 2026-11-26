package citysim;

/**
 * 需求温度计（§5）。两级判定：第一级逐层算缺口，第二级硬层缺口升格硬事件。
 * 多硬层同红：词典序固定 温饱→住房→安全（Kimi #7）。
 * V1 无头占位：安全层恒无缺口（M3 接光照审计/袭击）；秩序层恒无缺口（待市政厅覆盖裁决）。
 */
public class Thermometer {

    public static class Snapshot {
        public final double[] gap = new double[LayerId.COUNT];
        public int beds;
        public int wellCovered;
        public int plazaCovered;
        public double dailyNeedPoints;
        public double farmEff;
        public double farmHoursNeeded;
        public double laborPool;
        public int hardLayer = -1; // -1 = 无硬事件
    }

    public static Snapshot read(CityLedger city) {
        CityConfig c = city.config;
        Snapshot s = new Snapshot();

        s.beds = city.totalBeds();
        s.wellCovered = city.coveredBy(BuildingKind.WELL);
        double plazaCapacity = city.countActive(BuildingKind.PLAZA) * (double) c.plazaCoverage;
        s.plazaCovered = (int) Math.min(plazaCapacity, city.population);

        s.laborPool = city.workforce() * c.hoursPerCitizenPerCycle;
        s.farmEff = city.currentFarmEff();
        s.dailyNeedPoints = city.mouthsAtHome() * (double) c.satietyPerCitizenPerDay;
        s.farmHoursNeeded = s.dailyNeedPoints
                / (c.pointsPerWorkHour * s.farmEff * c.cyclesPerDay);

        // 0 温饱：预测性缺口 = 日需 −（当前库存 + 今日剩余周期预计产量）。
        // 只计今日剩余产量，否则日内库存累积会把缺口冲成负数 → 危机日内闪烁（暂停/复工振荡）。
        // 该公式在一天内恒等于：日需 − 日初库存 − 全日产量。
        double plannedFarmHours = Math.min(s.laborPool, s.farmHoursNeeded);
        int cyclesRemainingToday = c.cyclesPerDay - (int) ((city.cycleCount - 1) % c.cyclesPerDay);
        double expectedProductionToday =
                plannedFarmHours * c.pointsPerWorkHour * s.farmEff * cyclesRemainingToday;
        s.gap[LayerId.FOOD] = Math.max(0,
                s.dailyNeedPoints - city.foodPoints - expectedProductionToday);

        // 1 住房：既有人口 > 总床数才记硬缺口（繁殖停滞窄化，#8）
        s.gap[LayerId.HOUSING] = Math.max(0, city.population - s.beds);

        // 2 安全：V1 无头占位（M3：暗区比例 + 袭击强度 + 守军）
        s.gap[LayerId.SAFETY] = 0;

        // 3 卫生 / 4 社交：覆盖缺口
        s.gap[LayerId.HYGIENE] = Math.max(0, city.population - s.wellCovered);
        s.gap[LayerId.SOCIAL] = Math.max(0, city.population - s.plazaCovered);

        // 5 精神：V1 占位 = 广场兼供比例（待拍板，CityConfig.spiritFromPlazaRatio）
        if (c.spiritFromPlazaRatio > 0) {
            s.gap[LayerId.SPIRIT] = Math.max(0, city.population - plazaCapacity * c.spiritFromPlazaRatio);
        }

        // 6 秩序：V1 占位恒 0
        s.gap[LayerId.ORDER] = 0;

        // 第二级判定：词典序取第一个硬缺口
        for (int l : LayerId.HARD) {
            if (s.gap[l] > 0) { s.hardLayer = l; break; }
        }
        return s;
    }
}
