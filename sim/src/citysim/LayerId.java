package citysim;

/**
 * 层号统一 ID（Kimi #6 裁决）。
 * 实现层 7 层：0温饱/1住房/2安全/3卫生/4社交/5精神/6秩序。
 * HARD = {0,1,2}；软层 = {3,4,5,6}。叙事层仍叫"六层"（温饱并入叙事的安全网）。
 * 多硬层同红：词典序固定 温饱→住房→安全（Kimi #7 裁决）。
 */
public final class LayerId {
    public static final int FOOD = 0;      // 温饱
    public static final int HOUSING = 1;   // 住房
    public static final int SAFETY = 2;    // 安全
    public static final int HYGIENE = 3;   // 卫生
    public static final int SOCIAL = 4;    // 社交
    public static final int SPIRIT = 5;    // 精神
    public static final int ORDER = 6;     // 秩序

    public static final int COUNT = 7;

    /** 硬层集合，词典序即数组顺序（温饱→住房→安全）。 */
    public static final int[] HARD = { FOOD, HOUSING, SAFETY };

    public static final int[] SOFT = { HYGIENE, SOCIAL, SPIRIT, ORDER };

    public static boolean isHard(int layer) {
        return layer >= 0 && layer <= 2;
    }

    public static String name(int layer) {
        switch (layer) {
            case FOOD: return "温饱";
            case HOUSING: return "住房";
            case SAFETY: return "安全";
            case HYGIENE: return "卫生";
            case SOCIAL: return "社交";
            case SPIRIT: return "精神";
            case ORDER: return "秩序";
            default: return "未知层#" + layer;
        }
    }

    private LayerId() {}
}
