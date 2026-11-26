package com.livingcity.offline;

import com.google.gson.JsonObject;
import com.livingcity.data.CityRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtTagSizeTracker;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

record OfflineSave(long seed, NbtCompound cities, NbtCompound rules, NbtCompound registry) {
    static OfflineSave read(JsonObject body) {
        var level = readDat(body, "level", "level.dat").getCompound("Data");
        var settings = level.getCompound("WorldGenSettings");
        if (!settings.contains("seed", NbtElement.LONG_TYPE)) {
            throw new IllegalArgumentException("level.dat 中找不到世界种子");
        }
        var root = readDat(body, "cities", "livingcity_city.dat");
        var data = root.getCompound("data");
        int version = data.getInt("registryVersion");
        if (version < 0 || version > CityRegistry.SAVE_VERSION) {
            throw new IllegalArgumentException("城市存档版本 " + version + " 不受当前导入器支持（最高 " + CityRegistry.SAVE_VERSION + "）");
        }
        var cities = data.getCompound("cities");
        if (version == 0 && data.contains("ledger", NbtElement.COMPOUND_TYPE)) cities.put("1", data);
        if (cities.isEmpty()) throw new IllegalArgumentException("livingcity_city.dat 中找不到城市");
        return new OfflineSave(settings.getLong("seed"), cities, data.getCompound("cityRules"), data);
    }

    static NbtCompound readDat(JsonObject body, String key, String name) {
        try {
            byte[] compressed = Base64.getDecoder().decode(body.get(key).getAsString());
            try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                return NbtIo.read(input, new NbtTagSizeTracker(16 * 1024 * 1024));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("无法读取 " + name + "，请选择完整的 Minecraft 存档文件", e);
        }
    }
}
