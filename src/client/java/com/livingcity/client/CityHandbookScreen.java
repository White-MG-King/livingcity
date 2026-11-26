package com.livingcity.client;

import com.livingcity.data.CityRules;
import com.livingcity.item.CityHandbook;
import com.livingcity.item.CityHandbook.Action;
import com.livingcity.sensor.CityAdmissions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import java.util.Locale;

public final class CityHandbookScreen extends Screen {
    private static final int WIDTH = 302;
    private NbtCompound view;
    private int tab;
    private int left;
    private int top;
    private int ticks;

    private CityHandbookScreen(NbtCompound view) {
        super(label("title"));
        this.view = view;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CityHandbook.VIEW, (client, handler, buffer, sender) -> {
            boolean open = buffer.readBoolean();
            NbtCompound data = buffer.readNbt();
            if (data == null) return;
            client.execute(() -> {
                if (open) client.setScreen(new CityHandbookScreen(data));
                else if (client.currentScreen instanceof CityHandbookScreen screen) {
                    screen.view = data;
                    screen.clearAndInit();
                }
            });
        });
    }

    private static Text label(String key, Object... args) {
        return Text.translatable("screen.livingcity.handbook." + key, args);
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - 222) / 2;
        String[] tabs = {"rules", "performance", "debug", "nation"};
        int tabWidth = (WIDTH - 24) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int selected = i;
            var button = addDrawableChild(ButtonWidget.builder(label(tabs[i]), ignored -> {
                tab = selected;
                clearAndInit();
            }).dimensions(left + 12 + i * tabWidth, top + 32, tabWidth - 3, 20).build());
            button.active = i != tab;
            if (i == 3) button.active &= view.contains("automaticColonization");
        }
        if (tab == 0) {
            action(label("unlimited", label(view.getBoolean("unlimited") ? "on" : "off")), 62, Action.UNLIMITED);
            action(label("territory", view.getInt("territory")), 85, Action.TERRITORY);
            var timed = action(label("timed_expansion", CityRules.EXPANSION_INTERVAL,
                    label(CityRules.readTimedExpansion(view) ? "on" : "off")), 108, Action.TIMED_EXPANSION);
            timed.active &= view.contains("timedExpansion");
            timed.setTooltip(Tooltip.of(label("timed_expansion_help")));
            var admissions = action(label("automatic_admissions", label(view.getBoolean("automaticAdmissions") ? "on" : "off")),
                    131, Action.AUTO_ADMISSIONS);
            admissions.active &= view.contains("automaticAdmissions");
            admissions.setTooltip(Tooltip.of(label("automatic_admissions_help", CityAdmissions.COOLDOWN_TICKS / 20)));
        } else if (tab == 1) {
            action(label("entities", view.getInt("entities")), 65, Action.ENTITIES);
            action(label("construction", view.getInt("construction")), 90, Action.CONSTRUCTION);
        } else if (tab == 2) {
            boolean paused = view.getInt("speed") == 0;
            action(label(paused ? "resume" : "pause"), 59, Action.PAUSE);
            var step = action(label("step"), 83, Action.STEP);
            step.active &= paused;
            action(label("speed", view.getInt("speed"), String.format(Locale.ROOT, "%.1f", view.getDouble("effective"))),
                    107, Action.SPEED);
        } else {
            var colonization = action(label("auto_colonization", label(view.getBoolean("automaticColonization") ? "on" : "off")),
                    60, Action.AUTO_COLONIZATION);
            colonization.setTooltip(Tooltip.of(label("auto_colonization_help")));
        }
        addDrawableChild(ButtonWidget.builder(label("refresh"), ignored -> send(Action.REFRESH))
                .dimensions(left + 12, top + 194, 135, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions(left + 155, top + 194, 135, 20).build());
    }

    private ButtonWidget action(Text text, int y, Action action) {
        ButtonWidget widget = addDrawableChild(ButtonWidget.builder(text, ignored -> send(action))
                .dimensions(left + 22, top + y, WIDTH - 44, 20).build());
        widget.active = view.getBoolean("editable");
        return widget;
    }

    private static void send(Action action) {
        if (!ClientPlayNetworking.canSend(CityHandbook.ACT)) return;
        var packet = PacketByteBufs.create();
        packet.writeByte(action.ordinal());
        ClientPlayNetworking.send(CityHandbook.ACT, packet);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void tick() {
        if ((tab == 2 || tab == 3) && ++ticks % 20 == 0) send(Action.REFRESH);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.fill(left, top, left + WIDTH, top + 222, 0xff202927);
        context.drawBorder(left, top, WIDTH, 222, 0xff516a63);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 13, 0xffe5eee8);
        if (tab == 0) {
            drawLine(context, label("territory_help", view.getInt("territoryUsed")), 157);
            drawLine(context, label("manual_help"), 169);
        } else if (tab == 1) {
            drawLine(context, label("loaded_help"), 123);
            drawLine(context, label("mspt", String.format(Locale.ROOT, "%.1f", view.getFloat("mspt"))), 139);
        } else if (tab == 2) {
            drawLine(context, label("city", view.getInt("city"), view.getLong("cycle")), 134);
            drawLine(context, label("site", view.getString("status")), 148);
            drawLine(context, label("progress", view.getInt("roadCursor"), view.getInt("roadLength"),
                    view.getInt("projectCursor")), 162);
        } else {
            renderNation(context);
        }
        if (!view.getBoolean("editable")) drawLine(context, label("read_only"), 181);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderNation(DrawContext context) {
        int nation = view.getInt("nationId");
        if (nation == 0) {
            drawLine(context, label("nation_empty"), 94);
            return;
        }
        String name = view.getString("nationName");
        drawLine(context, name.isEmpty() ? label("nation_default", nation) : label("nation_named", nation, name), 92);
        drawLine(context, label("nation_totals", nation, view.getInt("nationCities"), view.getLong("nationResidents")), 106);
        int city = view.getInt("city"), mother = view.getInt("foundedFrom");
        drawLine(context, mother > 0 ? label("nation_child", city, mother) : label("nation_home", city), 120);
        if (!view.contains("colonization")) {
            long remaining = view.getLong("nationCooldown");
            drawLine(context, remaining > 0 ? label("nation_retry", remaining) : label("nation_idle"), 148);
            return;
        }
        NbtCompound team = view.getCompound("colonization");
        long remaining = team.getLong("remaining");
        drawLine(context, label("nation_team", team.getInt("source"), team.getInt("settlers"),
                team.getInt("x"), team.getInt("z")), 141);
        drawLine(context, remaining == 0 ? label("nation_arrived") : label("nation_eta", remaining), 154);
        int span = WIDTH - 44;
        long duration = Math.max(1, team.getLong("duration"));
        int progress = (int) (span * Math.max(0, duration - remaining) / duration);
        context.fill(left + 22, top + 170, left + 22 + span, top + 175, 0xff405650);
        context.fill(left + 22, top + 170, left + 22 + progress, top + 175, 0xff8db797);
    }

    private void drawLine(DrawContext context, Text text, int y) {
        context.drawText(textRenderer, textRenderer.trimToWidth(text.getString(), WIDTH - 44),
                left + 22, top + y, 0xffd5e2da, false);
    }
}
