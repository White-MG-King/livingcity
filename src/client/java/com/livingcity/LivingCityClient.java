package com.livingcity;

import com.livingcity.client.CitizenRenderer;
import com.livingcity.entity.LivingCityEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

@Environment(EnvType.CLIENT)
public class LivingCityClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 市民渲染：复用原版村民模型 + 村民贴图（表演层任务单 P0）
		EntityRendererRegistry.register(LivingCityEntities.CITIZEN, CitizenRenderer::new);
		com.livingcity.client.CityHandbookScreen.register();
		com.livingcity.client.CityInspectorScreen.register();
		com.livingcity.client.ClientWorldMap.register();
		LivingCityMod.LOGGER.info("[LivingCity] client entrypoint initialized (citizen renderer registered)");
	}
}
