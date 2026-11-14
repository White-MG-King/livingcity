package com.livingcity.entity;

import com.livingcity.LivingCityMod;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 实体注册（表演层任务单 P0）。
 *
 * maxTrackingRange=10 区块（160 格）：覆盖物化 96 / 去物化 128 的 LOD 带，
 * 避免边缘市民实体不同步给客户端。体型取村民同款 0.6×1.95。
 */
public final class LivingCityEntities {

	public static final EntityType<CitizenEntity> CITIZEN = EntityType.Builder
			.create(CitizenEntity::new, SpawnGroup.CREATURE)
			.setDimensions(0.6f, 1.95f)
			.maxTrackingRange(10)
			.trackingTickInterval(3)
			.build("citizen");

	private LivingCityEntities() {
	}

	public static void register() {
		Registry.register(Registries.ENTITY_TYPE, new Identifier(LivingCityMod.MOD_ID, "citizen"), CITIZEN);
		// 复用村民属性表（1.20.1 yarn 下 public static）：移速 0.5 / 跟随范围 48，与氛围步速一致
		FabricDefaultAttributeRegistry.register(CITIZEN, VillagerEntity.createVillagerAttributes());
	}
}
