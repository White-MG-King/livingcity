package com.livingcity.client;

import com.livingcity.item.CityInspector;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;

public final class CityInspectorScreen extends Screen {
    private final NbtCompound city;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int spacing;

    private CityInspectorScreen(NbtCompound city) {
        super(label("title", city.getInt("id")));
        this.city = city;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CityInspector.VIEW, (client, handler, buffer, sender) -> {
            NbtCompound city = buffer.readNbt();
            if (city != null) client.execute(() -> client.setScreen(new CityInspectorScreen(city)));
        });
    }

    private static Text label(String key, Object... args) {
        return Text.translatable("screen.livingcity.inspector." + key, args);
    }

    private static Text need(int id) { return label("need." + id); }
    private static Text kind(String name) { return label("building." + name); }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 16, 370);
        spacing = Math.max(10, Math.min(17, (height - 132) / 7));
        panelHeight = 97 + 7 * spacing + 26;
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - panelHeight) / 2);
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(width / 2 - 45, top + panelHeight - 23, 90, 18).build());
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.fill(left, top, left + panelWidth, top + panelHeight, 0xff1e2825);
        context.drawBorder(left, top, panelWidth, panelHeight, 0xff53786c);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 7, 0xfff1e9cf);
        line(context, label("metrics", city.getInt("population"), city.getInt("beds"), city.getInt("territory"), city.getLong("cycle")), 20, 0xffd8e7db);
        line(context, label("stock", (long) city.getDouble("food"), Math.round(city.getDouble("wood")), Math.round(city.getDouble("stone"))), 32, 0xffd8e7db);
        line(context, label("site", city.getInt("pendingBuildings"), city.getInt("pendingBeds"), city.getString("siteStatus")), 45, 0xffd8e7db);
        int urgent = city.getInt("priority");
        Text priority = urgent < 0 ? label("none") : need(urgent);
        String candidate = city.getString("candidate");
        line(context, candidate.isEmpty() ? label("priority", priority)
                : label("candidate", priority, kind(candidate), city.getInt("candidateCount")), 58, 0xffefc573);
        String project = city.getString("project");
        line(context, project.isEmpty() ? label("idle")
                : label(city.getBoolean("paused") ? "paused" : "project", kind(project),
                (long) city.getDouble("paid"), (long) Math.ceil(city.getDouble("required"))), 71, 0xffd8e7db);
        line(context, label("legend"), 84, 0xffa9bdb5);
        var needs = city.getList("needs", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < needs.size(); i++) {
            var row = needs.getCompound(i);
            int y = 97 + i * spacing;
            int id = row.getInt("id");
            int color = id == urgent ? 0xffefc573 : 0xffd8e7db;
            line(context, need(id), y, color);
            if (!row.getBoolean("tracked")) {
                context.drawText(textRenderer, label("reserved"), left + 55, top + y, 0xffa9bdb5, false);
                continue;
            }
            int bar = Math.min(100, panelWidth / 4);
            int percent = (int) Math.round(Math.max(0, Math.min(1, row.getDouble("satisfaction"))) * 100);
            context.fill(left + 55, top + y + 1, left + 55 + bar, top + y + 7, 0xff3e504b);
            context.fill(left + 55, top + y + 1, left + 55 + bar * percent / 100, top + y + 7, color);
            String gap = String.format(java.util.Locale.ROOT, "%.1f", row.getDouble("gap"));
            context.drawText(textRenderer, label("row", percent, gap, label("unit." + id)),
                    left + 61 + bar, top + y, color, false);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void line(DrawContext context, Text text, int y, int color) {
        context.drawText(textRenderer, textRenderer.trimToWidth(text.getString(), panelWidth - 20),
                left + 10, top + y, color, false);
    }
}
