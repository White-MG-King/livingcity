package com.livingcity.sensor;

import com.livingcity.data.CityData;
import com.livingcity.data.CityRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One rate-limited admission pass across the loaded villagers of a save. */
public final class CityAdmissions {
    public static final int COOLDOWN_TICKS = 100;
    private static final int CHECKS_PER_TICK = 128;
    private static final int ADMISSIONS_PER_TICK = 4;

    private final Map<UUID, VillagerEntity> loaded = new LinkedHashMap<>();
    private final ArrayDeque<VillagerEntity> candidates = new ArrayDeque<>();
    private final Map<CityData, Integer> admittedByCity = new LinkedHashMap<>();
    private boolean requested;
    private int cooldown;

    public void load(VillagerEntity villager) {
        loaded.put(villager.getUuid(), villager);
    }

    public void unload(VillagerEntity villager) {
        loaded.remove(villager.getUuid(), villager);
    }

    public void requestScan() {
        requested = true;
    }

    public void tick(ServerWorld world, CityRegistry registry, boolean running) {
        if (!registry.rules().automaticAdmissions()) {
            requested = false;
            cooldown = 0;
            candidates.clear();
            logAdmissions();
            return;
        }
        if (cooldown > 0) cooldown--;
        if (!running || cooldown > 0) return;

        if (candidates.isEmpty()) {
            if (!requested) return;
            requested = false;
            candidates.addAll(loaded.values());
        }
        int admitted = 0;
        for (int checked = 0; checked < CHECKS_PER_TICK && admitted < ADMISSIONS_PER_TICK && !candidates.isEmpty(); checked++) {
            VillagerEntity villager = candidates.removeFirst();
            if (loaded.get(villager.getUuid()) != villager || villager.getWorld() != world || !villager.isAlive()
                    || villager.isBaby() || villager.getExperience() > 0 || villager.hasCustomer()) continue;

            CityData city = registry.owner(villager.getBlockPos());
            if (city == null || !city.isFounded()) continue;

            // Discarding does not run the villager's death routine or release its village claims.
            if (villager.isSleeping()) villager.wakeUp();
            villager.releaseTicketFor(MemoryModuleType.HOME);
            villager.releaseTicketFor(MemoryModuleType.JOB_SITE);
            villager.releaseTicketFor(MemoryModuleType.POTENTIAL_JOB_SITE);
            villager.releaseTicketFor(MemoryModuleType.MEETING_POINT);
            registry.byId(1).releaseHome(villager.getUuid());
            villager.discard();
            city.harness().city.population++;
            city.markDirty();
            admittedByCity.merge(city, 1, Integer::sum);
            admitted++;
        }
        if (candidates.isEmpty()) {
            logAdmissions();
            cooldown = COOLDOWN_TICKS;
        }
    }

    private void logAdmissions() {
        admittedByCity.forEach((data, count) -> {
            var ledger = data.harness().city;
            ledger.chronicle.log(ledger.dayCount, "自动入籍 " + count + " 名村民");
        });
        admittedByCity.clear();
    }

    /** Observe successful task starts, including searches which find no workstation. */
    public static Task<PathAwareEntity> observeJobSearch(Task<PathAwareEntity> search) {
        return new Task<>() {
            @Override public MultiTickTask.Status getStatus() { return search.getStatus(); }
            @Override public String getName() { return search.getName(); }

            @Override public boolean tryStarting(ServerWorld world, PathAwareEntity entity, long time) {
                if (!search.tryStarting(world, entity, time)) return false;
                if (entity.getType() == EntityType.VILLAGER && world == world.getServer().getOverworld()) {
                    var registry = CityRegistry.get(world.getServer());
                    if (registry.rules().automaticAdmissions()) registry.admissions().requestScan();
                }
                return true;
            }

            @Override public void tick(ServerWorld world, PathAwareEntity entity, long time) {
                search.tick(world, entity, time);
            }

            @Override public void stop(ServerWorld world, PathAwareEntity entity, long time) {
                search.stop(world, entity, time);
            }
        };
    }
}
