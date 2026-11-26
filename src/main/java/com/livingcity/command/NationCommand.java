package com.livingcity.command;

import com.livingcity.data.CityNations;
import com.livingcity.data.CityRegistry;
import com.livingcity.data.WarSave;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

final class NationCommand {
    private NationCommand() {}

    static LiteralArgumentBuilder<ServerCommandSource> node() {
        return CommandManager.literal("nation")
                .executes(ctx -> status(ctx.getSource()))
                .then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(CommandManager.literal("dispatch").executes(ctx -> dispatch(ctx.getSource())))
                .then(CommandManager.literal("cancel").executes(ctx -> cancel(ctx.getSource())))
                .then(CommandManager.literal("war")
                        .executes(ctx -> warStatus(ctx.getSource()))
                        .then(warAction("declare"))
                        .then(warAction("truce")))
                .then(CommandManager.literal("rename").then(CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> rename(ctx.getSource(), StringArgumentType.getString(ctx, "name").strip()))))
                .then(CommandManager.literal("expansion").then(CommandManager.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> expansion(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> warAction(String kind) {
        return CommandManager.literal(kind).then(CommandManager.argument("nation", IntegerArgumentType.integer(1))
                .then(CommandManager.argument("target", IntegerArgumentType.integer(1)).executes(context -> {
                    var source = context.getSource();
                    var result = CityRegistry.get(source.getServer()).warAction(kind.equals("declare"),
                            IntegerArgumentType.getInteger(context, "nation"), IntegerArgumentType.getInteger(context, "target"));
                    if (!WarSave.accepted(result)) {
                        source.sendError(Text.literal(WarSave.message(result)));
                        return 0;
                    }
                    source.sendFeedback(() -> Text.literal(WarSave.message(result)), false);
                    return 1;
                })));
    }

    private static int warStatus(ServerCommandSource source) {
        var registry = CityRegistry.get(source.getServer());
        var view = registry.warView();
        if (view.relations().isEmpty()) source.sendFeedback(() -> Text.literal("当前各国和平。"), false);
        for (var p : view.relations()) {
            source.sendFeedback(() -> Text.literal("国家 #" + p.a() + " / #" + p.b()
                    + (p.active() ? " · 交战中" : " · 停战至周期 " + p.until())), false);
        }
        long deployed = view.armies().stream().filter(a -> a.order() != citysim.WarSimulation.Order.HOME).count();
        source.sendFeedback(() -> Text.literal("常备军 " + view.armies().size() + " 支 · 在外 " + deployed + " 支。"), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        var registry = CityRegistry.get(source.getServer());
        var local = registry.at(BlockPos.ofFloored(source.getPosition()));
        if (local.nationId() == 0) return error(source, "no_city");
        var nation = registry.nations();
        source.sendFeedback(() -> named(local.nationId(), nation.name(local.nationId())), false);
        source.sendFeedback(() -> text("role", local.id, local.nationId()), false);
        if (local.foundedFrom() > 0) source.sendFeedback(() -> text("parent", local.foundedFrom()), false);
        var journey = nation.journey(local.nationId(), registry.worldCycle());
        if (journey != null) source.sendFeedback(() -> journey(journey), false);
        else source.sendFeedback(() -> text("idle", nation.cooldown(local.nationId(), registry.worldCycle())), false);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        var registry = CityRegistry.get(source.getServer());
        var list = registry.nations().summaries(registry);
        if (list.isEmpty()) return error(source, "no_city");
        for (var nation : list) {
            source.sendFeedback(() -> text("summary", named(nation.id(), nation.name()), nation.id(),
                    nation.cities(), nation.residents()), false);
        }
        return list.size();
    }

    private static int dispatch(ServerCommandSource source) {
        var registry = CityRegistry.get(source.getServer());
        var local = registry.at(BlockPos.ofFloored(source.getPosition()));
        var result = registry.nations().dispatch(source.getWorld(), registry, local);
        if (result != CityNations.Dispatch.DEPARTED) return error(source, result.name().toLowerCase(Locale.ROOT));
        var journey = registry.nations().journey(local.nationId(), registry.worldCycle());
        source.sendFeedback(() -> journey(journey), false);
        return 1;
    }

    private static int cancel(ServerCommandSource source) {
        var registry = CityRegistry.get(source.getServer());
        var local = registry.at(BlockPos.ofFloored(source.getPosition()));
        if (!registry.nations().cancel(registry, local.nationId())) return error(source, "no_journey");
        source.sendFeedback(() -> text("cancelled"), false);
        return 1;
    }

    private static int rename(ServerCommandSource source, String newName) {
        var registry = CityRegistry.get(source.getServer());
        var local = registry.at(BlockPos.ofFloored(source.getPosition()));
        if (local.nationId() == 0) return error(source, "no_city");
        if (!registry.nations().rename(registry, local, newName)) return error(source, "invalid_name");
        source.sendFeedback(() -> named(local.nationId(), newName), false);
        return 1;
    }

    private static int expansion(ServerCommandSource source, boolean enabled) {
        var registry = CityRegistry.get(source.getServer());
        registry.rules().setAutomaticColonization(enabled);
        registry.markDirty();
        source.sendFeedback(() -> text(enabled ? "auto_on" : "auto_off"), false);
        return 1;
    }

    private static Text journey(CityNations.Journey journey) {
        return journey.remaining() > 0
                ? text("travel", journey.source(), journey.settlers(), journey.x(), journey.z(), journey.remaining())
                : text("landing", journey.source(), journey.x(), journey.z());
    }

    private static Text named(int nation, String name) {
        return name.isEmpty() ? text("default_name", nation) : text("named", nation, name);
    }

    private static int error(ServerCommandSource source, String key) {
        source.sendError(text(key));
        return 0;
    }

    private static Text text(String key, Object... data) { return Text.translatable("message.livingcity.nation." + key, data); }
}
