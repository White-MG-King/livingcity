package com.livingcity.item;

import com.livingcity.LivingCityMod;
import com.livingcity.data.CityRegistry;
import com.livingcity.sensor.CitySimTicker;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public final class CityHandbook extends Item {
    public static final Identifier VIEW = new Identifier(LivingCityMod.MOD_ID, "handbook_view");
    public static final Identifier ACT = new Identifier(LivingCityMod.MOD_ID, "handbook_act");
    public enum Action { REFRESH, UNLIMITED, TERRITORY, ENTITIES, CONSTRUCTION, PAUSE, STEP, SPEED, TIMED_EXPANSION,
        AUTO_ADMISSIONS, AUTO_COLONIZATION, SURVEY_ENABLED, SURVEY_SLOTS, SURVEY_WORKERS }

    private CityHandbook() { super(new Settings().maxCount(1)); }

    public static void register() {
        Item item = Registry.register(Registries.ITEM, new Identifier(LivingCityMod.MOD_ID, "city_handbook"), new CityHandbook());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(item));
        ServerPlayNetworking.registerGlobalReceiver(ACT, (server, player, handler, buffer, sender) -> {
            if (buffer.readableBytes() != 1) return;
            int command = buffer.readUnsignedByte();
            if (command >= Action.values().length) return;
            server.execute(() -> {
                if (!(player.getMainHandStack().isOf(item) || player.getOffHandStack().isOf(item))
                        || player.getWorld() != server.getOverworld()) return;
                var registry = CityRegistry.get(server);
                var rules = registry.rules();
                var data = registry.byId(1);
                var clock = data.clock();
                Action action = Action.values()[command];
                if (action != Action.REFRESH && canEdit(player)) {
                    switch (action) {
                        case UNLIMITED -> rules.toggleUnlimited();
                        case TIMED_EXPANSION -> rules.toggleTimedExpansion();
                        case AUTO_ADMISSIONS -> {
                            rules.toggleAutomaticAdmissions();
                            if (rules.automaticAdmissions()) registry.admissions().requestScan();
                        }
                        case AUTO_COLONIZATION -> rules.setAutomaticColonization(!rules.automaticColonization());
                        case TERRITORY -> rules.nextTerritory();
                        case ENTITIES -> rules.nextEntities();
                        case CONSTRUCTION -> rules.nextConstruction();
                        case SURVEY_ENABLED -> rules.setSurvey(new com.livingcity.data.CityRules.SurveySettings(
                                !rules.survey().enabled(), rules.survey().slots(), rules.survey().workers()));
                        case SURVEY_SLOTS -> rules.setSurvey(new com.livingcity.data.CityRules.SurveySettings(
                                rules.survey().enabled(), rules.survey().slots() % 4 + 1, rules.survey().workers()));
                        case SURVEY_WORKERS -> rules.setSurvey(new com.livingcity.data.CityRules.SurveySettings(
                                rules.survey().enabled(), rules.survey().slots(), rules.survey().workers() % 4 + 1));
                        case PAUSE -> CitySimTicker.setSpeed(data, clock.speed() == 0 ? 1 : 0);
                        case STEP -> clock.requestStep();
                        case SPEED -> CitySimTicker.setSpeed(data, clock.nextSpeed());
                        case REFRESH -> {}
                    }
                    registry.markDirty();
                }
                sendView(player, false);
            });
        });
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        if (!player.isSneaking()) return TypedActionResult.pass(player.getStackInHand(hand));
        if (player instanceof ServerPlayerEntity owner) sendView(owner, true);
        return TypedActionResult.success(player.getStackInHand(hand), world.isClient);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getPlayer() == null || !context.getPlayer().isSneaking()) return ActionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayerEntity owner) sendView(owner, true);
        return ActionResult.success(context.getWorld().isClient);
    }

    private static boolean canEdit(ServerPlayerEntity player) {
        return player.hasPermissionLevel(2) || player.getServer().isHost(player.getGameProfile());
    }

    private static void sendView(ServerPlayerEntity player, boolean open) {
        var server = player.getServer();
        if (!ServerPlayNetworking.canSend(player, VIEW) || server == null) return;
        if (player.getWorld() != server.getOverworld()) {
            if (open) player.sendMessage(Text.translatable("message.livingcity.overworld_handbook"), true);
            return;
        }
        var registry = CityRegistry.get(server);
        var city = registry.at(player.getBlockPos());
        NbtCompound view = registry.rules().write();
        view.put("siteSurvey", registry.construction().status(server.getOverworld(), registry).write());
        view.putBoolean("editable", canEdit(player));
        view.putInt("speed", registry.byId(1).clock().speed());
        view.putDouble("effective", registry.byId(1).clock().effectiveSpeed());
        view.putInt("city", city.id);
        view.putLong("cycle", city.harness().city.cycleCount);
        view.putInt("territoryUsed", city.land.chunkCount());
        view.putFloat("mspt", server.getTickTime());
        view.putInt("projectCursor", city.land.buildCursor());
        view.putInt("roadCursor", city.land.roadCursor());
        view.putInt("roadLength", city.land.roadLength());
        view.putString("status", CitySimTicker.siteStatus(server, city));
        int nationId = city.nationId();
        if (nationId != 0) {
            var nations = registry.nations();
            view.putInt("nationId", nationId);
            view.putString("nationName", nations.name(nationId));
            view.putInt("foundedFrom", city.foundedFrom());
            for (var nation : nations.summaries(registry)) {
                if (nation.id() != nationId) continue;
                view.putInt("nationCities", nation.cities());
                view.putLong("nationResidents", nation.residents());
                break;
            }
            long cycle = registry.worldCycle();
            view.putLong("nationCooldown", nations.cooldown(nationId, cycle));
            var journey = nations.journey(nationId, cycle);
            if (journey != null) {
                NbtCompound team = new NbtCompound();
                team.putInt("source", journey.source());
                team.putInt("x", journey.x());
                team.putInt("z", journey.z());
                team.putInt("settlers", journey.settlers());
                team.putLong("remaining", journey.remaining());
                team.putLong("duration", journey.duration());
                view.put("colonization", team);
            }
        }
        var packet = PacketByteBufs.create();
        packet.writeBoolean(open);
        packet.writeNbt(view);
        ServerPlayNetworking.send(player, VIEW, packet);
    }
}
