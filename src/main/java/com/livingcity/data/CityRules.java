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

    public boolean unlimited() { return unlimited; }
    public boolean timedExpansion() { return timedExpansion; }
    public boolean automaticAdmissions() { return automaticAdmissions; }
    public boolean automaticColonization() { return automaticColonization; }
    public int territory() { return territory; }
    public int entities() { return entities; }
    public int construction() { return construction; }

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
    }
}
