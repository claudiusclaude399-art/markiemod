package dev.clicksounds.gui;

import dev.clicksounds.audio.MicRecorder;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Scrolling live sound wave of the mic with input marks and captured clicks highlighted. */
public class LiveScope {
    private record Mark(long frame, int color) {}
    private record Region(long start, long end, int color) {}

    private final List<Mark> marks = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private float peak = 0.05f;
    private final float seconds;

    public LiveScope(float seconds) {
        this.seconds = seconds;
    }

    public void mark(long frame, int color) {
        marks.add(new Mark(frame, color));
    }

    public void region(long start, long end, int color) {
        regions.add(new Region(start, end, color));
    }

    /** @return bottom y */
    public int draw(DrawContext ctx, TextRenderer tr, MicRecorder mic, int x, int y, int w, int h) {
        float rate = mic.rate();
        long now = mic.now();
        int frames = (int) (rate * seconds);
        long from = now - frames;

        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF333333);
        ctx.fill(x, y, x + w, y + h, 0xFF0A0A0D);
        for (Region r : regions) {
            int a = (int) ((r.start() - from) * w / frames), b = (int) ((r.end() - from) * w / frames);
            if (b < 0 || a > w) continue;
            ctx.fill(x + Math.max(0, a), y, x + Math.min(w, Math.max(a + 1, b)), y + h, r.color());
        }
        float[] env = mic.envelope(now, frames, w);
        float visible = 0f;
        for (float v : env) visible = Math.max(visible, v);
        peak = Math.max(0.02f, Math.max(visible, peak * 0.985f)); // auto-zoom, slow release
        ctx.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0xFF222222);
        Waveforms.bars(ctx, env, x, y + 1, h - 2, 0.9f / peak, 0xFF7CE07C);
        for (Mark m : marks) {
            int a = (int) ((m.frame() - from) * w / frames);
            if (a < 0 || a >= w) continue;
            ctx.fill(x + a, y, x + a + 1, y + 4, m.color());
            ctx.fill(x + a, y + h - 4, x + a + 1, y + h, m.color());
        }
        marks.removeIf(m -> m.frame() < from - rate);
        regions.removeIf(r -> r.end() < from - rate);
        if (Math.sqrt(mic.level()) > 0.95)
            ctx.drawTextWithShadow(tr, "TOO LOUD", x + w - tr.getWidth("TOO LOUD") - 3, y + 3, 0xFFFF5555);
        return y + h + 1;
    }
}
