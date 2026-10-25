package com.lumemind.allayvillage.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * 数据生成入口（gradle datagen 任务）。
 * M1 起用于生成方块战利品表、配方、POI 数据。
 */
public class AllayVillageDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        // TODO M1: pack.addProvider(AllayVillageLootTableProvider::new);
        // TODO M1: pack.addProvider(AllayVillageRecipeProvider::new);
    }
}
