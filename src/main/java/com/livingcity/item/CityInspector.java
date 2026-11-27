package com.livingcity.item;

import citysim.CityNeedsReport;
import com.livingcity.LivingCityMod;
import com.livingcity.data.CityRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class CityInspector extends Item {
    public static final Identifier VIEW = new Identifier(LivingCityMod.MOD_ID, "city_inspector_view");

    private CityInspector() { super(new Settings().maxCount(1)); }

    public static void register() {
        Item item = Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "city_inspector"), new CityInspector());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(item));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player.isSpectator() || !player.getStackInHand(hand).isOf(item)) return ActionResult.PASS;
            if (player instanceof ServerPlayerEntity owner) inspect(owner, hit.getBlockPos());
            return ActionResult.SUCCESS;
        });
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        if (player.isSpectator()) return TypedActionResult.pass(player.getStackInHand(hand));
        if (player instanceof ServerPlayerEntity owner) inspect(owner, player.getBlockPos());
        return TypedActionResult.success(player.getStackInHand(hand), world.isClient);
    }

    private static void inspect(ServerPlayerEntity player, BlockPos pos) {
        var server = player.getServer();
        if (server == null || player.getWorld() != server.getOverworld()) {
            player.sendMessage(Text.translatable("message.livingcity.inspector_no_city"), true);
            return;
        }
        var city = CityRegistry.get(server).owner(pos);
        if (city == null || !city.isFounded()) {
            player.sendMessage(Text.translatable("message.livingcity.inspector_no_city"), true);
            return;
        }
        if (!ServerPlayNetworking.canSend(player, VIEW)) return;
        var ledger = city.harness().city;
        var report = CityNeedsReport.read(ledger);
        NbtCompound data = new NbtCompound();
        data.putInt("id", city.id);
        data.putInt("population", ledger.population);
        data.putInt("beds", ledger.totalBeds());
        data.putInt("pendingBeds", ledger.pendingBeds());
        data.putInt("pendingBuildings", ledger.pendingBuildings());
        data.putString("siteStatus", com.livingcity.sensor.CitySimTicker.siteStatus(server, city));
        data.putString("portStatus", city.land.portStatus());
        data.putString("resourceStatus", ledger.forestAvailable ? "林业已接通 · 采集消耗工时"
                : city.land.forestScanComplete() ? "采样范围内未找到林地" : "正在搜索林地");
        data.putInt("territory", city.land.chunkCount());
        data.putLong("cycle", ledger.cycleCount);
        data.putDouble("food", ledger.foodPoints);
        data.putDouble("wood", ledger.timber);
        data.putDouble("stone", ledger.stone);
        data.putInt("priority", report.priority());
        data.putString("candidate", report.candidateKind());
        data.putInt("candidateCount", report.candidateCount());
        if (ledger.activeProject != null) {
            var project = ledger.activeProject;
            data.putString("project", project.kind.name());
            data.putDouble("paid", project.paidHours);
            data.putDouble("required", project.requiredHours);
            data.putBoolean("paused", project.paused);
        }
        NbtList needs = new NbtList();
        for (var layer : report.layers()) {
            NbtCompound row = new NbtCompound();
            row.putInt("id", layer.id());
            row.putDouble("satisfaction", layer.satisfaction());
            row.putDouble("gap", layer.gap());
            row.putDouble("weight", layer.weight());
            row.putBoolean("tracked", layer.tracked());
            needs.add(row);
        }
        data.put("needs", needs);
        var buffer = PacketByteBufs.create();
        buffer.writeNbt(data);
        ServerPlayNetworking.send(player, VIEW, buffer);
    }
}
