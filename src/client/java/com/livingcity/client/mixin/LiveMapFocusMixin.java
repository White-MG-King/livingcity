package com.livingcity.client.mixin;

import com.livingcity.client.ClientWorldMap;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
abstract class LiveMapFocusMixin {
    @Redirect(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/option/GameOptions;pauseOnLostFocus:Z"))
    private boolean livingcity$allowBrowserFocus(GameOptions options) {
        return options.pauseOnLostFocus && !ClientWorldMap.browserActive();
    }
}
