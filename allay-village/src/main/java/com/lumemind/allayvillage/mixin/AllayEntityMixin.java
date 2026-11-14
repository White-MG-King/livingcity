package com.lumemind.allayvillage.mixin;

import com.lumemind.allayvillage.entity.AllayVillageData;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 悦灵扩展核心 Mixin (1.20.1 Yarn 映射: AllayEntity)。
 *
 * 当前 (M0/M1): 持久化 AllayVillageData, 重启不丢。
 * 后续:
 *   M2 -> 注入工作/休息/社交 Goal
 *   M3 -> interactMob 分支打开交易界面
 *   M6 -> 声望读写
 *
 * 运行期取数据: AllayVillageData.of(allay)
 *
 * 注意: initGoals 定义在 MobEntity, AllayEntity 并未 override。
 * 直接 @Inject(method = "initGoals") 会抛 InvalidInjectionException
 * (TargetSelectors.validate 找不到目标, 2026-10-26 runClient 实测)。
 * M2 落地时改用 @Inject(method = "<init>", at = @At("TAIL"))
 * (此时父类构造器已调过 initGoals, goalSelector 可用),
 * 或注入 MobEntity 并用 instanceof AllayEntity 判定。
 */
@Mixin(AllayEntity.class)
public abstract class AllayEntityMixin {

    @Unique
    private AllayVillageData allayvillage$data = new AllayVillageData();

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void allayvillage$writeData(NbtCompound nbt, CallbackInfo ci) {
        nbt.put(AllayVillageData.NBT_KEY, allayvillage$data.writeNbt(new NbtCompound()));
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void allayvillage$readData(NbtCompound nbt, CallbackInfo ci) {
        allayvillage$data = AllayVillageData.readNbt(nbt);
    }
}
