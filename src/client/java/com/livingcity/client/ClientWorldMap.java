package com.livingcity.client;

import com.livingcity.LivingCityMod;
import com.livingcity.item.WorldMapItem;
import com.livingcity.web.LiveCityMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;

public final class ClientWorldMap {
    private ClientWorldMap() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(LiveCityMap::closeWorld);
    }

    /** Called once per physical Ctrl + right-click, before vanilla can use a targeted block. */
    public static boolean openIfHeld(MinecraftClient client) {
        var player = client.player;
        if (client.currentScreen != null || player == null || player.isSpectator()) return false;
        if (!player.getMainHandStack().isOf(WorldMapItem.INSTANCE) && !player.getOffHandStack().isOf(WorldMapItem.INSTANCE)) {
            return false;
        }
        var server = client.getServer();
        if (server == null) {
            player.sendMessage(Text.translatable("message.livingcity.world_map.local_only"), true);
            return true;
        }
        var host = player.getUuid();
        server.execute(() -> {
            try {
                var uri = LiveCityMap.open(server, host, client::isPaused);
                client.execute(() -> {
                    if (client.getServer() == server) Util.getOperatingSystem().open(uri);
                });
            } catch (IOException | LinkageError error) {
                LivingCityMod.LOGGER.warn("Could not open the local world map", error);
                client.execute(() -> {
                    if (client.player != null && client.getServer() == server) {
                        client.player.sendMessage(Text.translatable("message.livingcity.world_map.open_failed"), true);
                    }
                });
            }
        });
        return true;
    }

    public static boolean browserActive() {
        return LiveCityMap.browserActive(MinecraftClient.getInstance().getServer());
    }
}
