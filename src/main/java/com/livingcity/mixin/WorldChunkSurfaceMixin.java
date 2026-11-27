package com.livingcity.mixin;

import com.livingcity.web.CityMapTerrain;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
abstract class WorldChunkSurfaceMixin {
    @Shadow public abstract World getWorld();

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void livingcity$invalidateTerrain(BlockPos pos, BlockState state, boolean moved,
                                             CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null && getWorld() instanceof ServerWorld serverWorld) {
            CityMapTerrain.surfaceChanged(serverWorld, ((WorldChunk) (Object) this).getPos());
        }
    }
}
