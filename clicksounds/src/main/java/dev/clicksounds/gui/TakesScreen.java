package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Take;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
            boolean has = i < takes.size();
            addDrawableChild(new WaveformButton(x, y, 100, 20, bank, i, this::volume));
            int slot = has ? i : takes.size();
            addDrawableChild(ButtonWidget.builder(Text.literal(has ? "Redo" : "Record"),
                b -> client.setScreen(new RecordScreen(this, mouse, slot, this::setStatus)))
                .dimensions(x + 104, y, 46, 20).build());
        }
        int y = top + 5 * 24 + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Play all"), b -> {
            int ms = bank.previewAll(Math.max(0.3f, volume()));
            setStatus("Playing all " + bank.size() + " takes (" + String.format(java.util.Locale.ROOT, "%.1f", ms / 1000f) + " s)", GRAY);
        }).dimensions(cx - 155, y, 100, 20).build()).active = bank.size() > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("Re-record all"),
            b -> client.setScreen(new RecordScreen(this, mouse, -1, this::setStatus)))
            .dimensions(cx - 50, y, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete all"), b -> {
            if (!confirmDelete) {
                confirmDelete = true;
                b.setMessage(Text.literal("Sure? Click again"));
                return;
            }
            bank.clear();
            setStatus("All " + (mouse ? "mouse" : "keyboard") + " takes deleted", YELLOW);
            clearAndInit();
        }).dimensions(cx + 55, y, 100, 20).build()).active = bank.size() > 0;
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
        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, Math.min(this.height - 22, top + 7 * 24 + 4), statusColor);
        // legend
        int ly = Math.min(this.height - 11, top + 7 * 24 + 16);
        String a = "\u25A0 press", b2 = "\u25A0 release";
        int wa = this.textRenderer.getWidth(a), wb = this.textRenderer.getWidth(b2), lx = cx - (wa + 12 + wb) / 2;
        ctx.drawTextWithShadow(this.textRenderer, a, lx, ly, Waveforms.PRESS);
        ctx.drawTextWithShadow(this.textRenderer, b2, lx + wa + 12, ly, Waveforms.RELEASE);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
