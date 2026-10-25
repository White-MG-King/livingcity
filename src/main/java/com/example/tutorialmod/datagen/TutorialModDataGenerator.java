package com.example.tutorialmod.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class TutorialModDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		// 后续在这里注册各种 Provider（模型、方块状态、配方、战利品表等）
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
	}
}
