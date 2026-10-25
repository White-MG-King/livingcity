package com.example.tutorialmod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TutorialMod implements ModInitializer {
	public static final String MOD_ID = "tutorialmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 日志用英文，避免 Windows 控制台 GBK 编码把中文显示成乱码
		LOGGER.info("[TutorialMod] main entrypoint initialized (common side)");
	}
}
