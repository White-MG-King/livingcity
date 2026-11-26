package com.livingcity.item;

import com.livingcity.LivingCityMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.List;

public final class WorldMapItem extends Item {
    public static final WorldMapItem INSTANCE = new WorldMapItem();

    private WorldMapItem() { super(new Settings().maxCount(1)); }

    public static void register() {
        Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "world_map"), INSTANCE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(INSTANCE));
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.livingcity.world_map.hint").formatted(Formatting.GRAY));
    }
}
