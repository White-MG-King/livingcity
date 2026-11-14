package com.livingcity.sensor;

import com.livingcity.data.CityData;
import com.livingcity.stage.MaterializationManager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 模拟心跳（《传感层任务单》S0）：每 1200 tick（60 现实秒）= 1 模拟周期。
 * 周期边界顺序固定：drainAndApply → run(1) → 物化同步 → markDirty。drain 不许散在 tick 里。
 * 物化放在结算后：用当周期最新人口扩池（表演层任务单 P2）。
 */
public final class CitySimTicker {

    /** 1 模拟周期 = 1200 游戏刻（60 现实秒）。 */
    public static final int TICKS_PER_CYCLE = 1200;

    private static final Logger LOGGER = LoggerFactory.getLogger("livingcity-sim");

    private static int ticksSinceCycle;

    private CitySimTicker() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CitySimTicker::onEndServerTick);
    }

    private static void onEndServerTick(MinecraftServer server) {
        if (++ticksSinceCycle < TICKS_PER_CYCLE) return;
        ticksSinceCycle = 0;

        CityData data = CityData.get(server);
        List<String> echo = CitySensorBus.drainAndApply(data.harness(), data);
        data.harness().run(1);
        MaterializationManager.sync(server);
        data.markDirty();

        for (String line : echo) {
            LOGGER.info("[LivingCity] {}", line);
            server.getPlayerManager().broadcast(Text.literal("[万灵之城] " + line), false);
        }
    }
}
