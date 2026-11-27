package com.livingcity.data;

import citysim.BuildingEntry;
import citysim.BuildingKind;
import citysim.Chronicle;
import citysim.CityLedger;
import citysim.SimEvent;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 账本 NBT 序列化（《传感层任务单》S1）。
 * schema 版本号 + 迁移函数是硬规则（《扩展纪律》§2）：字段增删一律先加版本、再补迁移。
 *
 * <p>核心只读：Project/HardEvent 的恢复构造器在核心侧是包私有（核心接口缺口，已回报），
 * 这里用反射恢复，不改 livingcity-sim 一行代码。</p>
 */
public final class CityLedgerCodec {

    /** v3 增加木石库存与产业进度，v2 增加让位工程。 */
    public static final int SCHEMA_VERSION = 4;

    private static final String KEY = "ledger";
    private static final int CHRONICLE_KEEP = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger("livingcity-ledger");

    private CityLedgerCodec() {
    }

    public static void write(CityLedger city, NbtCompound root) {
        NbtCompound n = new NbtCompound();
        n.putInt("schemaVersion", SCHEMA_VERSION);

        n.putInt("population", city.population);
        n.putDouble("timber",city.timber);
        n.putDouble("stone",city.stone);
        n.putDouble("quarryWork",city.quarryWork);
        n.putDouble("industryHours",city.totalIndustryHours);
        n.putBoolean("forestAvailable",city.forestAvailable);
        n.putBoolean("quarryLocated",city.quarryLocated);
        n.putBoolean("portLocated", city.portLocated);
        n.putDouble("portWork", city.portWork);
        n.putInt("incomingStone", city.incomingStone);
        n.putDouble("foodPoints", city.foodPoints);
        n.putLong("cycleCount", city.cycleCount);
        n.putInt("dayCount", city.dayCount);
        n.putInt("nextCitizenId", city.nextCitizenId);
        n.putInt("nextBuildingId", city.nextBuildingId);

        n.putDouble("totalLaborProduced", city.totalLaborProduced);
        n.putDouble("totalFarmHours", city.totalFarmHours);
        n.putDouble("totalOpsHours", city.totalOpsHours);
        n.putDouble("totalBuildHours", city.totalBuildHours);
        n.putDouble("totalIdleHours", city.totalIdleHours);

        n.put("satisfaction", doubles(city.satisfaction));
        n.put("bias", doubles(city.bias));

        NbtList buildings = new NbtList();
        for (BuildingEntry b : city.buildings) buildings.add(writeBuilding(b));
        n.put("buildings", buildings);

        NbtList unlocked = new NbtList();
        for (BuildingKind kind : city.unlocked) unlocked.add(NbtString.of(kind.name()));
        n.put("unlocked", unlocked);

        if (city.activeProject != null) n.put("activeProject", writeProject(city.activeProject));
        if (city.deferredProject != null) n.put("deferredProject", writeProject(city.deferredProject));
        if (city.hardEvent != null) n.put("hardEvent", writeHardEvent(city.hardEvent));

        n.put("chronicle", writeChronicle(city.chronicle));
        n.putIntArray("materializationPool", intArray(city.materializationPool));
        n.putByteArray("random", writeRandom(city.random));

        NbtList events = new NbtList();
        for (SimEvent e : city.activeEvents) events.add(writeEvent(e));
        n.put("activeEvents", events);

        root.put(KEY, n);
    }

    public static void read(CityLedger city, NbtCompound root) {
        if (!root.contains(KEY, NbtElement.COMPOUND_TYPE)) return;
        NbtCompound n = root.getCompound(KEY);

        int version = n.getInt("schemaVersion");
        migrate(n, version);

        city.population = n.getInt("population");
        city.timber=n.getDouble("timber");
        city.stone=n.getDouble("stone");
        city.quarryWork=n.getDouble("quarryWork");
        city.totalIndustryHours=n.getDouble("industryHours");
        city.forestAvailable=n.getBoolean("forestAvailable");
        city.quarryLocated=n.getBoolean("quarryLocated");
        city.portLocated = n.getBoolean("portLocated");
        city.portWork = n.getDouble("portWork");
        city.incomingStone = n.getInt("incomingStone");
        city.foodPoints = n.getDouble("foodPoints");
        city.cycleCount = n.getLong("cycleCount");
        city.dayCount = Math.max(1, n.getInt("dayCount"));
        city.nextCitizenId = n.getInt("nextCitizenId");
        city.nextBuildingId = Math.max(1, n.getInt("nextBuildingId"));

        city.totalLaborProduced = n.getDouble("totalLaborProduced");
        city.totalFarmHours = n.getDouble("totalFarmHours");
        city.totalOpsHours = n.getDouble("totalOpsHours");
        city.totalBuildHours = n.getDouble("totalBuildHours");
        city.totalIdleHours = n.getDouble("totalIdleHours");

        copyInto(n.getList("satisfaction", NbtElement.DOUBLE_TYPE), city.satisfaction);
        copyInto(n.getList("bias", NbtElement.DOUBLE_TYPE), city.bias);

        city.buildings.clear();
        for (NbtElement e : n.getList("buildings", NbtElement.COMPOUND_TYPE)) {
            city.buildings.add(readBuilding((NbtCompound) e));
        }

        city.unlocked.clear();
        for (NbtElement e : n.getList("unlocked", NbtElement.STRING_TYPE)) {
            try {
                city.unlocked.add(BuildingKind.valueOf(e.asString()));
            } catch (IllegalArgumentException ignored) {
                // 未知档位（未来版本降级回滚）直接跳过
            }
        }

        city.activeProject = n.contains("activeProject", NbtElement.COMPOUND_TYPE)
                ? readProject(n.getCompound("activeProject")) : null;
        city.deferredProject = n.contains("deferredProject", NbtElement.COMPOUND_TYPE)
                ? readProject(n.getCompound("deferredProject")) : null;
        city.hardEvent = n.contains("hardEvent", NbtElement.COMPOUND_TYPE)
                ? readHardEvent(n.getCompound("hardEvent")) : null;

        // 编年史：只保留最近 200 条（任务单契约）
        List<Chronicle.Entry> old = city.chronicle.entries();
        old.clear();
        NbtList chronicle = n.getList("chronicle", NbtElement.COMPOUND_TYPE);
        int from = Math.max(0, chronicle.size() - CHRONICLE_KEEP);
        for (int i = from; i < chronicle.size(); i++) {
            NbtCompound e = chronicle.getCompound(i);
            city.chronicle.log(e.getInt("day"), e.getString("text"));
        }

        city.materializationPool.clear();
        for (int id : n.getIntArray("materializationPool")) city.materializationPool.add(id);

        restoreRandom(city, n.getByteArray("random"));

        city.activeEvents.clear();
        for (NbtElement e : n.getList("activeEvents", NbtElement.COMPOUND_TYPE)) {
            SimEvent event = readEvent((NbtCompound) e);
            if (event != null) city.activeEvents.add(event);
        }
    }

    // ================= schema 迁移 =================

    private static void migrate(NbtCompound n, int from) {
        if (from > SCHEMA_VERSION) {
            throw new IllegalStateException("账本 schema " + from + " 高于本模组支持的 " + SCHEMA_VERSION
                    + "（数据来自更新版本，拒绝加载）");
        }
        if (from == 1) {
            // v1 无让位工程，其余字段（含随机流）格式完全相同。
            n.remove("deferredProject");
            n.putInt("schemaVersion", SCHEMA_VERSION);
        } else if (from == 2 || from == 3) {
            n.putInt("schemaVersion", SCHEMA_VERSION); // Absent warehouse fields read as zero.
        } else if (from < SCHEMA_VERSION) {
            throw new IllegalStateException("缺少账本 schema 迁移函数: v" + from + " -> v" + SCHEMA_VERSION);
        }
    }

    // ================= 建筑 =================

    private static NbtCompound writeBuilding(BuildingEntry b) {
        NbtCompound n = new NbtCompound();
        n.putInt("id", b.id);
        n.putString("kind", b.kind.name());
        n.putString("state", b.state.name());
        n.putInt("bedCount", b.bedCount);
        n.putLong("builtAtCycle", b.builtAtCycle);
        n.putInt("totalStructure", b.totalStructure);
        n.putLongArray("missingBits", b.missingBits);
        n.putLong("stoppedSince", b.stoppedSince);
        return n;
    }

    private static BuildingEntry readBuilding(NbtCompound n) {
        BuildingKind kind;
        try {
            kind = BuildingKind.valueOf(n.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = BuildingKind.HOUSE;
        }
        BuildingEntry b = new BuildingEntry(n.getInt("id"), kind);
        try {
            b.state = BuildingEntry.State.valueOf(n.getString("state"));
        } catch (IllegalArgumentException ignored) {
            b.state = BuildingEntry.State.ACTIVE;
        }
        b.bedCount = n.getInt("bedCount");
        b.builtAtCycle = n.getLong("builtAtCycle");
        b.totalStructure = Math.max(0, n.getInt("totalStructure"));
        int words = (b.totalStructure + 63) >> 6;
        long[] bits = n.getLongArray("missingBits");
        b.missingBits = bits.length == words ? bits : Arrays.copyOf(bits, words);
        b.stoppedSince = n.getLong("stoppedSince");
        return b;
    }

    // ================= 工程 / 硬事件 =================

    private static NbtCompound writeProject(CityLedger.Project p) {
        NbtCompound n = new NbtCompound();
        n.putString("kind", p.kind.name());
        n.putInt("n", p.n);
        n.putDouble("requiredHours", p.requiredHours);
        n.putDouble("paidHours", p.paidHours);
        n.putInt("delivered", p.delivered);
        n.putInt("starvedCycles", p.starvedCycles);
        n.putBoolean("paused", p.paused);
        n.putBoolean("atCheckpoint", p.atCheckpoint);
        return n;
    }

    private static CityLedger.Project readProject(NbtCompound n) {
        BuildingKind kind;
        try {
            kind = BuildingKind.valueOf(n.getString("kind"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        CityLedger.Project p = newProject(kind, n.getInt("n"), n.getDouble("requiredHours"));
        p.paidHours = n.getDouble("paidHours");
        p.delivered = n.getInt("delivered");
        p.starvedCycles = n.getInt("starvedCycles");
        p.paused = n.getBoolean("paused");
        p.atCheckpoint = n.getBoolean("atCheckpoint");
        return p;
    }

    private static CityLedger.Project newProject(BuildingKind kind, int n, double requiredHours) {
        try {
            Constructor<CityLedger.Project> ctor = CityLedger.Project.class
                    .getDeclaredConstructor(BuildingKind.class, int.class, double.class);
            ctor.setAccessible(true);
            return ctor.newInstance(kind, n, requiredHours);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("核心 Project 恢复构造器不可用（核心接口缺口）", e);
        }
    }

    private static NbtCompound writeHardEvent(CityLedger.HardEvent h) {
        NbtCompound n = new NbtCompound();
        n.putInt("layer", h.layer);
        n.putInt("daysActive", h.daysActive);
        return n;
    }

    private static CityLedger.HardEvent readHardEvent(NbtCompound n) {
        try {
            Constructor<CityLedger.HardEvent> ctor = CityLedger.HardEvent.class
                    .getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            CityLedger.HardEvent h = ctor.newInstance(n.getInt("layer"));
            h.daysActive = n.getInt("daysActive");
            return h;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("核心 HardEvent 恢复构造器不可用（核心接口缺口）", e);
        }
    }

    // ================= 编年史 / 事件 / 随机源 =================

    private static NbtList writeChronicle(Chronicle chronicle) {
        NbtList list = new NbtList();
        List<Chronicle.Entry> entries = chronicle.entries();
        for (int i = Math.max(0, entries.size() - CHRONICLE_KEEP); i < entries.size(); i++) {
            Chronicle.Entry entry = entries.get(i);
            NbtCompound n = new NbtCompound();
            n.putInt("day", entry.day);
            n.putString("text", entry.text);
            list.add(n);
        }
        return list;
    }

    private static NbtCompound writeEvent(SimEvent e) {
        NbtCompound n = new NbtCompound();
        n.putString("type", e.type.name());
        n.putInt("remainingCycles", e.remainingCycles);
        n.putDouble("farmEff", e.farmEff);
        n.putInt("targetBuildingId", e.targetBuildingId);
        return n;
    }

    private static SimEvent readEvent(NbtCompound n) {
        String type = n.getString("type");
        if (SimEvent.Type.HARSH_WINTER.name().equals(type)) {
            return SimEvent.harshWinter(n.getInt("remainingCycles"), n.getDouble("farmEff"));
        }
        if (SimEvent.Type.FIRE.name().equals(type)) {
            return SimEvent.fire(n.getInt("targetBuildingId"));
        }
        // 瘟疫/袭击 V1 无工厂（M3），不恢复
        LOGGER.warn("未支持的事件类型，跳过恢复: {}", type);
        return null;
    }

    /** 随机流指纹（不消耗随机数，仅用于进游戏验收时证明重启前后随机源一致）。 */
    public static String randomFingerprint(Random random) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(writeRandom(random));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "err";
        }
    }

    private static byte[] writeRandom(Random random) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(random);
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("随机源序列化失败", e);
        }
    }

    private static void restoreRandom(CityLedger city, byte[] data) {
        if (data.length == 0) return;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(info -> {
                if (info.depth() > 2 || info.references() > 8 || info.streamBytes() > 1024) {
                    return java.io.ObjectInputFilter.Status.REJECTED;
                }
                Class<?> type = info.serialClass();
                if (type == null) return java.io.ObjectInputFilter.Status.UNDECIDED;
                return type == Random.class ? java.io.ObjectInputFilter.Status.ALLOWED
                        : java.io.ObjectInputFilter.Status.REJECTED;
            });
            Object restored = in.readObject();
            if (!(restored instanceof Random random)) return;
            Field field = CityLedger.class.getDeclaredField("random");
            field.setAccessible(true);
            field.set(city, random);
        } catch (Exception e) {
            LOGGER.warn("随机源恢复失败，将使用新随机流", e);
        }
    }

    // ================= 小工具 =================

    private static NbtList doubles(double[] values) {
        NbtList list = new NbtList();
        for (double v : values) list.add(net.minecraft.nbt.NbtDouble.of(v));
        return list;
    }

    private static void copyInto(NbtList list, double[] target) {
        int n = Math.min(list.size(), target.length);
        for (int i = 0; i < n; i++) target[i] = list.getDouble(i);
    }

    private static int[] intArray(java.util.Set<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (int v : values) out[i++] = v;
        Arrays.sort(out);
        return out;
    }
}
