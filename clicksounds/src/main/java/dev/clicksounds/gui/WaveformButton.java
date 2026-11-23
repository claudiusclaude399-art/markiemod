package dev.clicksounds.gui;

import dev.clicksounds.audio.Genre;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Take;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Supplier;

/** A take's sound wave; click it to hear the take, with a playhead moving across. */
public class WaveformButton extends ClickableWidget {
    private static final int LABEL_W = 14;

    private final SampleBank bank;
    private final String scope;
    private final Genre genre;
    private final int index;
    private final Supplier<Float> volume;
    private final Waveforms.TakeShape shape;
    private final float gain;

    public WaveformButton(int x, int y, int w, int h, SampleBank bank, String scope, Genre genre, int index, Supplier<Float> volume) {
        super(x, y, w, h, Text.literal("Take " + (index + 1)));
        this.bank = bank;
        this.scope = scope;
        this.genre = genre;
        this.index = index;
        this.volume = volume;
        java.util.List<Take> takes = bank.takes(scope, genre);
        Take t = index < takes.size() ? takes.get(index) : null;
        int longest = 0; // same time scale for every take in the bank, so they're comparable
        for (Take other : takes) longest = Math.max(longest, other.timelineLength());
        this.shape = t == null ? null : new Waveforms.TakeShape(t, w - LABEL_W - 3, longest);
        float peak = bank.peak();
        this.gain = peak > 0 ? 0.95f / peak : 1f;
        this.active = t != null;
        if (t != null) {
            String rel = t.release() != null ? "click + release"
                : bank.releaseCount(scope, genre) > 0 || bank.releaseCount(scope, Genre.NORMAL) > 0 || bank.releaseCount(Genre.NORMAL) > 0 ? "click (+ a random release in game)" : "click only";
            setTooltip(Tooltip.of(Text.literal(String.format(Locale.ROOT, "%s: %s, %.0f ms\nClick to play",
                t.name() == null ? "Take " + (index + 1) : t.name(), rel, t.lengthMs()))));
        }
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = active && (isHovered() || isFocused());
        ctx.fill(x, y, x + w, y + h, hot ? 0xFF1C2433 : 0xFF0E0E12);
        ctx.drawStrokedRectangle(x, y, w, h, hot ? 0xFFFFFFFF : 0xFF555555);
        var tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
        String n = Integer.toString(index + 1);
        ctx.drawTextWithShadow(tr, n, x + 1 + (LABEL_W - tr.getWidth(n)) / 2, y + (h - 8) / 2, active ? 0xFFDDDDDD : 0xFF666666);
        ctx.fill(x + LABEL_W, y + 2, x + LABEL_W + 1, y + h - 2, 0xFF333333);
        int wx = x + LABEL_W + 2;
        if (shape == null) {
            ctx.drawTextWithShadow(tr, "empty", wx + 4, y + (h - 8) / 2, 0xFF666666);
            return;
        }
        ctx.fill(wx, y + h / 2, wx + (w - LABEL_W - 3), y + h / 2 + 1, 0xFF262626);
        shape.draw(ctx, wx, y + 2, h - 4, gain, SampleBank.previewPosition(shape.take));
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        bank.preview(scope, genre, index, Math.max(0.3f, volume.get()), 0);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // no UI click: the take itself is the sound
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
