package com.lumemind.allayvillage.entity;

import net.minecraft.nbt.NbtCompound;

/**
 * 附加在每只悦灵身上的村庄数据 (M1 起点)。
 * 通过 AllayEntityMixin 在原版 NBT 读写钩子里持久化, 重启存档不丢失。
 */
public class AllayVillageData {

    /** 写入原版实体 NBT 的键名 */
    public static final String NBT_KEY = "AllayVillageData";

    /** 职业 id, 空串 = 无职业 */
    private String profession = "";
    /** 所属村庄 id, 空串 = 无村庄 */
    private String villageId = "";
    /** 交易经验, 决定职业等级 novice -> master */
    private int tradeXp = 0;

    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putString("profession", profession);
        nbt.putString("villageId", villageId);
        nbt.putInt("tradeXp", tradeXp);
        return nbt;
    }

    public static AllayVillageData readNbt(NbtCompound nbt) {
        AllayVillageData data = new AllayVillageData();
        if (nbt != null && nbt.contains(NBT_KEY, NbtCompound.COMPOUND_TYPE)) {
            NbtCompound tag = nbt.getCompound(NBT_KEY);
            data.profession = tag.getString("profession");
            data.villageId = tag.getString("villageId");
            data.tradeXp = tag.getInt("tradeXp");
        }
        return data;
    }

    public String getProfession() {
        return profession;
    }

    public void setProfession(String profession) {
        this.profession = profession == null ? "" : profession;
    }

    public String getVillageId() {
        return villageId;
    }

    public void setVillageId(String villageId) {
        this.villageId = villageId == null ? "" : villageId;
    }

    public int getTradeXp() {
        return tradeXp;
    }

    public void addTradeXp(int amount) {
        this.tradeXp = Math.max(0, this.tradeXp + amount);
    }
}
