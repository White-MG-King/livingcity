package com.livingcity.sensor;

import com.livingcity.data.CityData;
import com.livingcity.stage.MaterializationManager;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 城市时钟按完整周期消费传感输入、推进账本并扩充市民池。
 * 1000× 下实际实体按服务器刻限频，仍使用最近一次周期结算的结果。
 */
public final class CitySimTicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("livingcity-sim");
    private static final int SITE_BUDGET_MSPT = 45;
    private static int siteTurn;

    private CitySimTicker() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CitySimTicker::onEndServerTick);
    }

    private static void onEndServerTick(MinecraftServer server) {
        CityData data = CityData.get(server);
        CityClock clock = data.clock();
        boolean due = clock.tick();
        data.markDirty();
        var registry=com.livingcity.data.CityRegistry.get(server);
        registry.admissions().tick(server.getOverworld(), registry, clock.speed() != 0);
        if (server.getTicks()%20==0) {
            var world=server.getOverworld();
            for(var city:registry.cities()) {
                if(city.isFounded() && world.isChunkLoaded(city.townHallPos()))
                    city.land.hallMissing=!world.getBlockState(city.townHallPos()).isOf(com.livingcity.block.LivingCityBlocks.TOWN_HALL);
            }
        }
        if (server.getTicks()%10==0) {
            var cities=java.util.List.copyOf(registry.cities());
            var city=cities.get(Math.floorMod(server.getTicks()/10,cities.size()));
            city.land.surveyNext(server.getOverworld(),city);
        }
        int siteInterval = clock.speed() == 1000 ? 2 : 5;
        if (server.getTicks() % siteInterval == 0 && server.getTickTime() < SITE_BUDGET_MSPT) {
            var cities = List.copyOf(registry.cities());
            var city = cities.get(Math.floorMod(siteTurn++, cities.size()));
            var world = server.getOverworld();
            int budget = registry.rules().construction();
            // A requested demolition can finish with the economy clock paused.
            if (!city.land.tickDemolition(world, city, budget) && clock.speed() != 0) {
                city.land.tickSites(world, city, budget);
            }
        }
        if (due) {
            long started = System.nanoTime();
            var world = server.getOverworld();
            registry.advanceWorldCycle();
            for (var city : registry.cities()) {
                advanceCycle(server, city);
                registry.tickExpansion(world, city);
            }
            registry.stepCampaign(world);
            registry.nations().advance(world, registry, server.getTickTime() < SITE_BUDGET_MSPT);
            clock.recordCycleCost(System.nanoTime() - started);
        }
        // 高速下每秒投影一次最新实体状态；常速与低倍速仍在原周期边界同步。
        int cap = registry.rules().entities();
        boolean second = server.getTicks() % 20 == 0;
        boolean regular = clock.speed() == 1000 ? second : due;
        boolean trim = second && registry.cities().stream().mapToInt(CityData::citizenEntityCount).sum() > cap;
        if (regular || trim) {
            for (var city : registry.cities()) MaterializationManager.sync(server, city, cap, regular && clock.speed() != 0);
        }
    }

    public static String siteStatus(MinecraftServer server, CityData city) {
        if (city.harness().city.pendingBuildings() > 0) {
            if (CityData.get(server).clock().speed() == 0) return "城市时钟已暂停";
            if (server.getTickTime() >= SITE_BUDGET_MSPT) {
                return String.format(Locale.ROOT, "服务器每刻 %.1f 毫秒 · 暂缓施工", server.getTickTime());
            }
        }
        return city.land.siteStatus();
    }

    private static void advanceCycle(MinecraftServer server, CityData data) {
        List<String> echo = CitySensorBus.drainAndApply(data.harness(), data, server.getOverworld());
        data.harness().run(1);
        data.harness().materializeUpTo(MaterializationManager.ENTITY_CAP);

        for (String line : echo) {
            LOGGER.info("[LivingCity] {}", line);
            server.getPlayerManager().broadcast(Text.literal("[城市 #" + data.id + "] " + line), false);
        }
    }

    public static boolean setSpeed(CityData data, int value) {
        if (!data.clock().setSpeed(value)) return false;
        data.markDirty();
        return true;
    }

    public static Text speedStatus(CityClock clock) {
        if (clock.speed() == 0) return Text.translatable("message.livingcity.paused");
        if (!clock.sampled()) return Text.translatable("message.livingcity.speed_pending", clock.speed());
        return Text.translatable("message.livingcity.speed", clock.speed(),
                String.format(Locale.ROOT, "%.1f", clock.effectiveSpeed()));
    }
}
