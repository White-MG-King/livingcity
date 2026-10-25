package com.lumemind.allayvillage;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 《悦灵村庄》主入口。
 *
 * 里程碑:
 *   M0 骨架(当前)  M1 站点方块+职业绑定  M2 AI日程  M3 交易
 *   M4 村庄系统    M5 繁殖/人口          M6 声望    M7 世界生成结构
 */
public class AllayVillageMod implements ModInitializer {

    public static final String MOD_ID = "allayvillage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[悦灵村庄] 模组已加载 (M0 骨架)");

        // M1: ModBlocks / ModItems / POI 注册
        // M2: 自定义 Schedule 与 Goal 注册
        // M3: 交易表注册 (AllayTradeRegistry)
        // M4: ServerLifecycleEvents.serverStarting 里挂载 AllayVillageManager(SavedData)
    }
}
