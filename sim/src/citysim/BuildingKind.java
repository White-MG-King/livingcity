package citysim;

/**
 * 建筑类型（红线：MVP 3 种机制类型；外观变体不在此枚举，属蓝图资产，不新增类型）。
 * ROAD/WALL 为账本占位（形态学笔记），V1 分配循环暂不建造它们。
 * 铁傀儡不是建筑，是安全层特殊守卫条目（《城防与铁傀儡》），M3 接入。
 */
public enum BuildingKind {
    HOUSE("住宅"),
    WELL("水井"),
    PORT("港口"),
    PLAZA("广场"),
    ROAD("道路"),   // 占位：特殊建筑类型进账本（缺口对照 #8 建议，未裁决）
    WALL("城墙");   // 占位：M3 事件系统（袭击）接入

    public final String displayName;

    BuildingKind(String displayName) {
        this.displayName = displayName;
    }

    /** 该类型服务的实现层；HOUSE→住房，WELL→卫生，PLAZA→社交(+精神占位)。 */
    public int primaryLayer() {
        switch (this) {
            case HOUSE: return LayerId.HOUSING;
            case WELL: return LayerId.HYGIENE;
            case PLAZA: return LayerId.SOCIAL;
            default: return -1;
        }
    }

    public double buildHours(CityConfig c) {
        switch (this) {
            case HOUSE: return c.houseBuildHours;
            case WELL: return c.wellBuildHours;
            case PORT: return CityLedger.PORT_HOURS;
            case PLAZA: return c.plazaBuildHours;
            default: return Double.POSITIVE_INFINITY; // 占位类型不可建造
        }
    }

    public double maintPerCycle(CityConfig c) {
        switch (this) {
            case HOUSE: return c.houseMaint;
            case WELL: return c.wellMaint;
            case PLAZA: return c.plazaMaint;
            default: return 0;
        }
    }

    /** 覆盖容量（服务人数）。住宅返回床位数。 */
    public int capacity(CityConfig c) {
        switch (this) {
            case HOUSE: return c.houseBeds;
            case WELL: return c.wellCoverage;
            case PLAZA: return c.plazaCoverage;
            default: return 0;
        }
    }
}
