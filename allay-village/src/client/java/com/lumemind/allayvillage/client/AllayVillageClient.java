package com.lumemind.allayvillage.client;

import com.lumemind.allayvillage.AllayVillageMod;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口。
 * M3: 交易 GUI (HandledScreens.register)
 * M4: 村庄边界调试渲染
 */
public class AllayVillageClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AllayVillageMod.LOGGER.info("[悦灵村庄] 客户端已加载 (M0 骨架)");
    }
}
