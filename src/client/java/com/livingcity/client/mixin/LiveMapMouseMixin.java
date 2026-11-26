package com.livingcity.client.mixin;

import com.livingcity.client.ClientWorldMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
abstract class LiveMapMouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void livingcity$openMap(long window, int button, int action, int modifiers, CallbackInfo info) {
        var client = MinecraftClient.getInstance();
        if (window == client.getWindow().getHandle() && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && action == GLFW.GLFW_PRESS && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && ClientWorldMap.openIfHeld(client)) info.cancel();
    }
}
