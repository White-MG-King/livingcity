package com.livingcity.data;

import citysim.CityFrontier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

/** World-scoped rules and limits shared by all cities in a save. */
public final class CityRules {
    public static final int EXPANSION_INTERVAL = 20;
    public static final boolean DEFAULT_TIMED_EXPANSION = true;
    private static final int[] TERRITORIES = {32, 64, 128, 256, 512};
    private static final int[] ENTITIES = {0, 32, 64, 128, 200};
    private static final int[] CONSTRUCTION = {96, 192, 288};

    private boolean unlimited;
    private boolean timedExpansion = DEFAULT_TIMED_EXPANSION;
    private boolean automaticAdmissions;
    private boolean automaticColonization = true;
    private int territory = CityFrontier.DEFAULT_LIMIT;
    private int entities = 200;
    private int construction = 192;
    private MapSettings map = MapSettings.defaults();
    private SurveySettings survey = SurveySettings.defaults();

    public record SurveySettings(boolean enabled, int slots, int workers) {
        public static final int MAX_SLOTS = 4, MAX_WORKERS = 4, CHUNK_LIMIT = 128;
        public SurveySettings {
            if (slots < 1 || slots > MAX_SLOTS || workers < 1 || workers > MAX_WORKERS)
                throw new IllegalArgumentException("区块检测设置超出范围");
        }
        public static SurveySettings defaults() { return new SurveySettings(true, 2, 1); }
        public NbtCompound write() {
            var tag = new NbtCompound();
            tag.putBoolean("enabled", enabled); tag.putInt("slots", slots); tag.putInt("workers", workers);
            return tag;
        }
        static SurveySettings read(NbtCompound tag) {
            if (!tag.contains("enabled")) return defaults();
            return new SurveySettings(tag.getBoolean("enabled"), Math.max(1, Math.min(MAX_SLOTS, tag.getInt("slots"))),
                    Math.max(1, Math.min(MAX_WORKERS, tag.getInt("workers"))));
        }
    }

    public record MapSettings(int border, int radius, int workers, int seedWorkers, int budgetMs, int guardMs) {
        public static final int MAX_WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));

        public MapSettings {
            if (border < 0 || border > 32 || radius < 0 || radius > 8
                    || workers < 1 || workers > MAX_WORKERS || seedWorkers < 1 || seedWorkers > workers
                    || budgetMs < 0 || budgetMs > 5 || guardMs < 25 || guardMs > 49) {
                throw new IllegalArgumentException("地图设置超出范围");
            }
        }

        static MapSettings defaults() {
            int workers = Math.min(2, MAX_WORKERS);
            return new MapSettings(8, 4, workers, workers, 2, 45);
        }

        NbtCompound write() {
            var value = new NbtCompound();
            value.putInt("border", border);
            value.putInt("radius", radius);
            value.putInt("workers", workers);
            value.putInt("seedWorkers", seedWorkers);
            value.putInt("budgetMs", budgetMs);
            value.putInt("guardMs", guardMs);
            return value;
        }

        static MapSettings read(NbtCompound value) {
            var defaults = defaults();
            int workers = readInt(value, "workers", defaults.workers, 1, MAX_WORKERS);
            return new MapSettings(readInt(value, "border", defaults.border, 0, 32),
                    readInt(value, "radius", defaults.radius, 0, 8), workers,
                    readInt(value, "seedWorkers", defaults.seedWorkers, 1, workers),
                    readInt(value, "budgetMs", defaults.budgetMs, 0, 5),
                    readInt(value, "guardMs", defaults.guardMs, 25, 49));
        }

        private static int readInt(NbtCompound nbt, String key, int fallback, int min, int max) {
            int value = nbt.contains(key, NbtElement.NUMBER_TYPE) ? nbt.getInt(key) : fallback;
            return Math.max(min, Math.min(max, value));
        }
    }

    public boolean unlimited() { return unlimited; }
    public boolean timedExpansion() { return timedExpansion; }
    public boolean automaticAdmissions() { return automaticAdmissions; }
    public boolean automaticColonization() { return automaticColonization; }
    public int territory() { return territory; }
    public int entities() { return entities; }
    public int construction() { return construction; }
    public MapSettings map() { return map; }
    public void setMap(MapSettings settings) { map = settings; }
    public SurveySettings survey() { return survey; }
    public void setSurvey(SurveySettings settings) { survey = settings; }

    public boolean mayExpand(int owned) { return unlimited || owned < territory; }
    public void toggleUnlimited() { unlimited = !unlimited; }
    public void toggleTimedExpansion() { timedExpansion = !timedExpansion; }
    public void toggleAutomaticAdmissions() { automaticAdmissions = !automaticAdmissions; }
    public void setAutomaticColonization(boolean enabled) { automaticColonization = enabled; }
    public void nextTerritory() { territory = next(TERRITORIES, territory); }
    public void nextEntities() { entities = next(ENTITIES, entities); }
    public void nextConstruction() { construction = next(CONSTRUCTION, construction); }

    public static boolean isExpansionCycle(long cycle) {
        return cycle > 0 && cycle % EXPANSION_INTERVAL == 0;
    }

    public static boolean readTimedExpansion(NbtCompound nbt) {
        return nbt.contains("timedExpansion", NbtElement.BYTE_TYPE)
                ? nbt.getBoolean("timedExpansion") : DEFAULT_TIMED_EXPANSION;
    }

    private static int next(int[] values, int now) {
        for (int value : values) if (value > now) return value;
        return values[0];
    }

    public NbtCompound write() {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("unlimited", unlimited);
        nbt.putBoolean("timedExpansion", timedExpansion);
        nbt.putBoolean("automaticAdmissions", automaticAdmissions);
        nbt.putBoolean("automaticColonization", automaticColonization);
        nbt.putInt("territory", territory);
        nbt.putInt("entities", entities);
        nbt.putInt("construction", construction);
        nbt.put("map", map.write());
        nbt.put("siteSurvey", survey.write());
        return nbt;
    }

    void read(NbtCompound nbt) {
        unlimited = nbt.getBoolean("unlimited");
        timedExpansion = readTimedExpansion(nbt);
        automaticAdmissions = nbt.getBoolean("automaticAdmissions");
        automaticColonization = nbt.getBoolean("automaticColonization");
        if (nbt.contains("territory")) territory = Math.max(32, Math.min(512, nbt.getInt("territory")));
        if (nbt.contains("entities")) entities = Math.max(0, Math.min(200, nbt.getInt("entities")));
        if (nbt.contains("construction")) construction = Math.max(96, Math.min(288, nbt.getInt("construction")));
        map = MapSettings.read(nbt.getCompound("map"));
        survey = SurveySettings.read(nbt.getCompound("siteSurvey"));
    }
}
