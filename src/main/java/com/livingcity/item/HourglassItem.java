package com.livingcity.item;

import com.livingcity.LivingCityMod;
import com.livingcity.data.CityData;
import com.livingcity.sensor.CitySimTicker;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public final class HourglassItem extends Item {
    private HourglassItem() {
        super(new Settings().maxCount(1));
    }

    public static void register() {
        Item item = Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "hourglass"),
                new HourglassItem());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(item));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        if (player instanceof ServerPlayerEntity owner) interact(owner);
        return TypedActionResult.success(player.getStackInHand(hand), world.isClient);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getPlayer() == null || !context.getPlayer().isSneaking()) return ActionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayerEntity owner) interact(owner);
        return ActionResult.success(context.getWorld().isClient);
    }

    private static void interact(ServerPlayerEntity player) {
        var server = player.getServerWorld().getServer();
        CityData data = CityData.get(server);
        if (player.isSneaking()) {
            if (!player.hasPermissionLevel(2) && !server.isHost(player.getGameProfile())) {
                player.sendMessage(Text.translatable("message.livingcity.speed_permission"), true);
                return;
            }
            CitySimTicker.setSpeed(data, data.clock().nextSpeed());
        }
        player.sendMessage(CitySimTicker.speedStatus(data.clock()), true);
    }
}
