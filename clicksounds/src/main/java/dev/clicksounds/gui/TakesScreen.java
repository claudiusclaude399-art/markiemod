package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Take;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/** Listen to each of the 10 takes and redo just the bad ones. */
public class TakesScreen extends Screen {
    private final Screen parent;
    private final boolean mouse;
    private final SampleBank bank;
    private String status = "";
    private int statusColor = GRAY;
    private int top;
    private boolean confirmDelete;

    public TakesScreen(Screen parent, boolean mouse) {
        super(Text.literal(mouse ? "Mouse takes" : "Keyboard takes"));
        this.parent = parent;
        this.mouse = mouse;
        this.bank = mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
    }

    public void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    private float volume() {
        return mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume;
    }

    @Override
    protected void init() {
        confirmDelete = false;
        int cx = this.width / 2;
        top = Math.max(36, this.height / 2 - 84);
        List<Take> takes = bank.takes();
        for (int i = 0; i < SampleBank.TAKES; i++) {
            int x = i < 5 ? cx - 155 : cx + 5;
            int y = top + (i % 5) * 24;
            final int idx = i;
            boolean has = i < takes.size();
            ButtonWidget play = ButtonWidget.builder(Text.literal(has ? "Play take " + (i + 1) : "Take " + (i + 1) + " (empty)"),
                b -> bank.preview(idx, Math.max(0.3f, volume()))).dimensions(x, y, 100, 20).build();
            play.active = has;
            if (has) play.setTooltip(Tooltip.of(Text.literal(takes.get(i).release() != null
                ? "Press + release" : "Press only (no release sound)")));
            addDrawableChild(play);
            int slot = has ? i : takes.size();
            addDrawableChild(ButtonWidget.builder(Text.literal(has ? "Redo" : "Record"),
                b -> client.setScreen(new RecordScreen(this, mouse, slot, this::setStatus)))
                .dimensions(x + 104, y, 46, 20).build());
        }
        int y = top + 5 * 24 + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Re-record all 10"),
            b -> client.setScreen(new RecordScreen(this, mouse, -1, this::setStatus)))
            .dimensions(cx - 155, y, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete all"), b -> {
            if (!confirmDelete) {
                confirmDelete = true;
                b.setMessage(Text.literal("Click again to delete"));
                return;
            }
            bank.clear();
            setStatus("All " + (mouse ? "mouse" : "keyboard") + " takes deleted", YELLOW);
            clearAndInit();
        }).dimensions(cx + 5, y, 150, 20).build()).active = bank.size() > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(cx - 100, y + 24, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, Math.max(4, top - 26), WHITE);
        String sub = bank.size() > SampleBank.TAKES
            ? "+" + (bank.size() - SampleBank.TAKES) + " more takes from the folder are used too"
            : summary(bank, mouse ? "mouse" : "key");
        ctx.drawCenteredTextWithShadow(this.textRenderer, sub, cx, top - 13, GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, Math.min(this.height - 12, top + 7 * 24 + 4), statusColor);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
