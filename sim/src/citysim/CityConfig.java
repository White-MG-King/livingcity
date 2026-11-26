package citysim;

import java.util.Properties;

/**
 * 调参表外置（扩展纪律 §1 防线四：平衡性迭代不碰代码）。
 * 所有起调值来自《计划者-MVP分配循环》§9、《事件系统-建筑完整性与工程物化管线》§7、
 * 《城防与铁傀儡》§4。字段名 = 配置键名，Properties 可覆盖任意字段。
 */
public class CityConfig {
    // ---- 时间口径（样例村基础量表） ----
    public int cyclesPerDay = 20;              // 20 周期 ≈ 1 游戏日

    // ---- 饱食度制（用户裁决 #4） ----
    public int satietyPerCitizenPerDay = 20;   // 每人每天 20 点
    public double pointsPerWorkHour = 2.0;     // 1 农业工时 → 2 点饱食度（起调，待无头测试台调）

    // ---- 劳动力 ----
    public double hoursPerCitizenPerCycle = 1.0; // 每市民每周期 1 工时
    public double minLightHours = 0.0;           // 最低光照预留（V1 无头端为 0，MC 侧接入光照审计后启用）

    // ---- 分期切片（Kimi #12/#18 裁决） ----
    public double sliceCap = 4.0;   // 分期切片上限 4 工时（是上限非入场门槛）
    public double switchEpsilon = 0.10; // 换档需 10% 成本改善（ε10% 裁决）

    // ---- 三笔账（账 1 TCO） ----
    public int lifespanCycles = 100; // 寿命 100 周期 ≈ 5 游戏日（摊销窗）

    // ---- 建筑基础量（测试卡） ----
    public boolean requireBuildingMaterials;
    /** The adapter confirms placement before a funded building contributes capacity. */
    public boolean deferBuildingActivation;
    public double houseBuildHours = 60;
    public int houseBeds = 4;
    public double houseMaint = 0;
    public double wellBuildHours = 240;
    public double wellMaint = 1;
    public int wellCoverage = 30;
    public double plazaBuildHours = 480;
    public double plazaMaint = 1;
    public int plazaCoverage = 30;

    // ---- 满意度动力学（任务 3 裁决） ----
    public double satGain = 0.01;    // 履约 +0.01/期
    public double satLoss = 0.02;    // 断供 −0.02/期
    public double unlockThreshold = 0.8;  // 解锁阈值（卫生≥0.8 → 广场）
    public double weightEps = 0.1;   // w = 1/(s+0.1)（#17 裁决）
    public double biasRange = 0.3;   // 性格 ±0.3，只进软层

    // ---- 精神层 V1 占位（待拍板：广场兼供比例，0=精神层 V1 暗转） ----
    public double spiritFromPlazaRatio = 0.5;

    // ---- 硬缺口红线 ----
    public int redlineDays = 3;      // 硬事件连续 3 天 → 迁出启动

    // ---- 人口流动阀门 ----
    public double immigrationSatThreshold = 0.6; // 软层均值高于此值才可能移民（待调）
    public double immigrantChance = 0.10;        // 每日概率（待调）
    public double birthChance = 0.05;            // 有床余粮足时每日出生概率（待调）
    public double birthMinFoodSat = 0.5;         // 出生要求的温饱满意度下限

    // ---- 事件系统（V1 桩） ----
    public double harshWinterFarmEff = 0.4;      // 严冬农业效率乘数（0.4=重度，起调待事件表定）

    // ---- 死锁警告 ----
    public int starvationWarnCycles = 20;        // 工程连续 N 周期拿不到预算 → 死锁警告

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("cyclesPerDay", Integer.toString(cyclesPerDay));
        p.setProperty("satietyPerCitizenPerDay", Integer.toString(satietyPerCitizenPerDay));
        p.setProperty("pointsPerWorkHour", Double.toString(pointsPerWorkHour));
        p.setProperty("hoursPerCitizenPerCycle", Double.toString(hoursPerCitizenPerCycle));
        p.setProperty("sliceCap", Double.toString(sliceCap));
        p.setProperty("switchEpsilon", Double.toString(switchEpsilon));
        p.setProperty("lifespanCycles", Integer.toString(lifespanCycles));
        p.setProperty("houseBuildHours", Double.toString(houseBuildHours));
        p.setProperty("houseBeds", Integer.toString(houseBeds));
        p.setProperty("wellBuildHours", Double.toString(wellBuildHours));
        p.setProperty("wellCoverage", Integer.toString(wellCoverage));
        p.setProperty("plazaBuildHours", Double.toString(plazaBuildHours));
        p.setProperty("plazaCoverage", Integer.toString(plazaCoverage));
        p.setProperty("satGain", Double.toString(satGain));
        p.setProperty("satLoss", Double.toString(satLoss));
        p.setProperty("unlockThreshold", Double.toString(unlockThreshold));
        p.setProperty("weightEps", Double.toString(weightEps));
        p.setProperty("biasRange", Double.toString(biasRange));
        p.setProperty("spiritFromPlazaRatio", Double.toString(spiritFromPlazaRatio));
        p.setProperty("redlineDays", Integer.toString(redlineDays));
        p.setProperty("immigrationSatThreshold", Double.toString(immigrationSatThreshold));
        p.setProperty("immigrantChance", Double.toString(immigrantChance));
        p.setProperty("birthChance", Double.toString(birthChance));
        p.setProperty("harshWinterFarmEff", Double.toString(harshWinterFarmEff));
        return p;
    }

    public void apply(Properties p) {
        cyclesPerDay = intOf(p, "cyclesPerDay", cyclesPerDay);
        satietyPerCitizenPerDay = intOf(p, "satietyPerCitizenPerDay", satietyPerCitizenPerDay);
        pointsPerWorkHour = dblOf(p, "pointsPerWorkHour", pointsPerWorkHour);
        hoursPerCitizenPerCycle = dblOf(p, "hoursPerCitizenPerCycle", hoursPerCitizenPerCycle);
        sliceCap = dblOf(p, "sliceCap", sliceCap);
        switchEpsilon = dblOf(p, "switchEpsilon", switchEpsilon);
        lifespanCycles = intOf(p, "lifespanCycles", lifespanCycles);
        houseBuildHours = dblOf(p, "houseBuildHours", houseBuildHours);
        houseBeds = intOf(p, "houseBeds", houseBeds);
        wellBuildHours = dblOf(p, "wellBuildHours", wellBuildHours);
        wellCoverage = intOf(p, "wellCoverage", wellCoverage);
        plazaBuildHours = dblOf(p, "plazaBuildHours", plazaBuildHours);
        plazaCoverage = intOf(p, "plazaCoverage", plazaCoverage);
        satGain = dblOf(p, "satGain", satGain);
        satLoss = dblOf(p, "satLoss", satLoss);
        unlockThreshold = dblOf(p, "unlockThreshold", unlockThreshold);
        weightEps = dblOf(p, "weightEps", weightEps);
        biasRange = dblOf(p, "biasRange", biasRange);
        spiritFromPlazaRatio = dblOf(p, "spiritFromPlazaRatio", spiritFromPlazaRatio);
        redlineDays = intOf(p, "redlineDays", redlineDays);
        immigrationSatThreshold = dblOf(p, "immigrationSatThreshold", immigrationSatThreshold);
        immigrantChance = dblOf(p, "immigrantChance", immigrantChance);
        birthChance = dblOf(p, "birthChance", birthChance);
        harshWinterFarmEff = dblOf(p, "harshWinterFarmEff", harshWinterFarmEff);
    }

    private static int intOf(Properties p, String k, int d) {
        String v = p.getProperty(k);
        return v == null ? d : Integer.parseInt(v.trim());
    }

    private static double dblOf(Properties p, String k, double d) {
        String v = p.getProperty(k);
        return v == null ? d : Double.parseDouble(v.trim());
    }
}
