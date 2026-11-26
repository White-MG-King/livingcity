package com.livingcity.mixin;

import com.livingcity.sensor.CityAdmissions;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.FindPointOfInterestTask;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.poi.PointOfInterestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

@Mixin(FindPointOfInterestTask.class)
abstract class VillagerJobSearchMixin {
    @Inject(method = "create(Ljava/util/function/Predicate;Lnet/minecraft/entity/ai/brain/MemoryModuleType;"
            + "Lnet/minecraft/entity/ai/brain/MemoryModuleType;ZLjava/util/Optional;)Lnet/minecraft/entity/ai/brain/task/Task;",
            at = @At("RETURN"), cancellable = true)
    private static void livingcity$observeJobSearch(Predicate<RegistryEntry<PointOfInterestType>> predicate,
                                                    MemoryModuleType<GlobalPos> site, MemoryModuleType<GlobalPos> potential,
                                                    boolean adultsOnly, Optional<Byte> status,
                                                    CallbackInfoReturnable<Task<PathAwareEntity>> callback) {
        if (site == MemoryModuleType.JOB_SITE && potential == MemoryModuleType.POTENTIAL_JOB_SITE) {
            callback.setReturnValue(CityAdmissions.observeJobSearch(callback.getReturnValue()));
        }
    }
}
