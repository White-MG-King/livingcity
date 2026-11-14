package com.livingcity.client;

import com.livingcity.entity.CitizenEntity;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.util.Identifier;

/**
 * 市民渲染器（表演层任务单 P0）：复用原版村民模型 + 村民贴图的薄壳（《扩展纪律》§5 裁决：
 * 复用村民模型、不挂职业）。yarn-1.20.1 实名：模型基类 VillagerResemblingModel（泛型上界 Entity，
 * 任意实体可挂）、渲染基类 MobEntityRenderer；原版 VillagerEntityRenderer 绑定 VillagerEntity，不能直接用。
 */
@Environment(EnvType.CLIENT)
public class CitizenRenderer extends MobEntityRenderer<CitizenEntity, VillagerResemblingModel<CitizenEntity>> {

	private static final Identifier TEXTURE = new Identifier("textures/entity/villager/villager.png");

	public CitizenRenderer(EntityRendererFactory.Context context) {
		super(context, new VillagerResemblingModel<>(context.getPart(EntityModelLayers.VILLAGER)), 0.5f);
	}

	@Override
	public Identifier getTexture(CitizenEntity entity) {
		return TEXTURE;
	}
}
