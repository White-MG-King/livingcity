package com.livingcity.data;

import citysim.WarSimulation;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.nio.charset.StandardCharsets;

/** Shared by the world registry and the offline checkpoint/importer. */
public final class WarSave {
    private static final String KEY = "campaign";
    private static final Gson JSON = new Gson();

    private WarSave() {}

    public static void write(NbtCompound root, WarSimulation model) {
        // A byte array also handles paths longer than the NBT string byte limit.
        root.putByteArray(KEY, JSON.toJson(model.save()).getBytes(StandardCharsets.UTF_8));
    }

    public static WarSimulation read(NbtCompound root, WarSimulation.World world, long cycle) {
        if (!root.contains(KEY, NbtElement.BYTE_ARRAY_TYPE)) {
            var war = new WarSimulation();
            war.reconcile(world, cycle);
            return war;
        }
        byte[] bytes = root.getByteArray(KEY);
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("战事存档数据过大");
        try {
            var saved = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), WarSimulation.State.class);
            return WarSimulation.restore(world, cycle, saved);
        } catch (JsonParseException | NullPointerException error) {
            throw new IllegalArgumentException("战事存档数据无效", error);
        }
    }

    public static String message(WarSimulation.Action result) {
        return switch (result) {
            case STARTED -> "已宣战。城市将在下一个世界周期部署驻防并尝试派兵。";
            case ENDED -> "已停战。远征军回城整补，尚在进行的占领解除。";
            case UNKNOWN_NATION -> "找不到所选国家。";
            case SAME_NATION -> "请选择两个不同的国家。";
            case ALREADY_AT_WAR -> "两国已经交战。";
            case AT_PEACE -> "两国当前没有交战。";
            case TRUCE -> "两国的停战期尚未结束。";
        };
    }

    public static boolean accepted(WarSimulation.Action result) {
        return result == WarSimulation.Action.STARTED || result == WarSimulation.Action.ENDED;
    }
}
