package com.livingcity.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * 市民实体（表演层任务单 P0）：复用村民模型演出，但不继承 VillagerEntity——
 * 交易 / 繁殖 / POI 上班 AI 全部不要，只挂最小氛围行为集（日程是第三批，另出单）。
 *
 * 账本是真，舞台是采样：本实体只是物化池 id 的投影，生死走传感总线，
 * 禁止把实体状态写回账本（《扩展纪律》§2）。
 */
public class CitizenEntity extends PathAwareEntity {

	public static final String NBT_CITIZEN_ID = "citizenId";

	/** 物化池 id；-1 = 未绑定（/summon 直出等异常态，不参与映射）。 */
	private int citizenId = -1;

	public CitizenEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
		super(entityType, world);
		// 禁原版自然消失：物化生命周期归 MaterializationManager（LOD 96/128）管
		setPersistent();
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		// 慢速闲逛（氛围用）。走出 128 格会被去物化、回家重生成——预期行为，不是 bug
		this.goalSelector.add(2, new WanderAroundFarGoal(this, 0.5d));
	}

	@Override
	public boolean canPickUpLoot() {
		return false; // 不捡物（村民会捡食物，市民不需要）
	}

	public int getCitizenId() {
		return this.citizenId;
	}

	public void setCitizenId(int id) {
		this.citizenId = id;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt(NBT_CITIZEN_ID, this.citizenId);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.citizenId = nbt.contains(NBT_CITIZEN_ID) ? nbt.getInt(NBT_CITIZEN_ID) : -1;
	}
}
