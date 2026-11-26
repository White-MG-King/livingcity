package com.livingcity.block;

import com.livingcity.LivingCityMod;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class LivingCityBlocks {
	public static final Block ELEVATOR = new ElevatorBlock(AbstractBlock.Settings.copy(Blocks.LAPIS_BLOCK).strength(3.0F, 6.0F));
	public static final Block TOWN_HALL = new TownHallBlock(AbstractBlock.Settings.copy(Blocks.STONE_BRICKS).strength(3.5F, 6.0F));

	public static void register() {
		Registry.register(Registries.BLOCK, new Identifier(LivingCityMod.MOD_ID, "elevator"), ELEVATOR);
		Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "elevator"), new BlockItem(ELEVATOR, new Item.Settings()));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> entries.add(ELEVATOR));

		Registry.register(Registries.BLOCK, new Identifier(LivingCityMod.MOD_ID, "town_hall"), TOWN_HALL);
		Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "town_hall"), new BlockItem(TOWN_HALL, new Item.Settings()));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(TOWN_HALL));
	}

	private LivingCityBlocks() {
	}
}
