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
    private static final CityWorkRotation work = new CityWorkRotation();

    private CitySimTicker() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CitySimTicker::onEndServerTick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                com.livingcity.data.CityRegistry.get(server).construction().close(server.getOverworld()));
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
        var cities = List.copyOf(registry.cities());
        registry.construction().tick(server.getOverworld(), registry, clock.speed() != 0,
                server.getTickTime() < registry.rules().map().guardMs());
        if (clock.speed() != 0) {
            // Discovery has no world writes or chunk tickets. It must keep working even
            // when every funded order is waiting for its first timber and stone.
            var city = cities.get(work.nextResource(cities.size()));
            city.land.tickResources(server.getOverworld(), city);
        }
        if (server.getTickTime() < registry.rules().map().guardMs()) {
            var world = server.getOverworld();
            long deadline = System.nanoTime() + (long) (Math.min(4, registry.rules().map().guardMs() - server.getTickTime()) * 1_000_000);
            int remaining = registry.rules().construction() * 4;
            if (clock.speed() != 0) {
                var city = server.getTicks() % 2 == 0 ? registry.construction().planningCity() : null;
                if (city == null) city = cities.get(work.nextPlanner(cities.size()));
                city.land.tickPlanning(world, city, Math.min(deadline, System.nanoTime() + 2_000_000));
                registry.construction().retainBuild(world, city);
            }
            for (int checked = 0; checked < Math.max(4, cities.size()) && remaining > 0 && System.nanoTime() < deadline; checked++) {
                var city = cities.get(work.nextWriter(cities.size()));
                int slice = Math.min(192, remaining);
                if (!city.land.tickDemolition(world, city, slice) && clock.speed() != 0 && city.land.hasActiveConstruction()) {
                    city.land.tickSites(world, city, slice, deadline);
                }
                remaining -= slice;
            }
        }
        registry.nations().tickLanding(server.getOverworld(), registry,
                clock.speed() != 0 && server.getTickTime() < registry.rules().map().guardMs());
        if (server.getTicks() % 10 == 0 && server.getTickTime() < registry.rules().map().guardMs()) {
            registry.trade().syncShips(server.getOverworld(), registry.worldCycle());
        }
        if (due) {
            long started = System.nanoTime();
            var world = server.getOverworld();
            registry.advanceWorldCycle();
            registry.trade().advance(world, registry);
            for (var city : registry.cities()) {
                advanceCycle(server, city);
                registry.tickExpansion(world, city);
            }
            registry.stepCampaign(world);
            registry.nations().advance(world, registry, server.getTickTime() < registry.rules().map().guardMs());
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
        String remote = com.livingcity.data.CityRegistry.get(server).construction().cityStatus(city.id);
        if (!remote.isEmpty()) return remote + " · " + city.land.siteStatus();
        if (city.harness().city.pendingBuildings() > 0) {
            if (CityData.get(server).clock().speed() == 0) return "城市时钟已暂停";
            if (server.getTickTime() >= com.livingcity.data.CityRegistry.get(server).rules().map().guardMs()) {
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
