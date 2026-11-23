package dev.clicksounds.gui;

import dev.clicksounds.audio.Take;
import net.minecraft.client.gui.DrawContext;

/** Draws sound waves as mirrored bars (one per pixel column). */
public final class Waveforms {
    public static final int PRESS = 0xFF5FB4FF, PRESS_DIM = 0xFF2E5C85;
    public static final int RELEASE = 0xFFFFB050, RELEASE_DIM = 0xFF85592A;
    public static final int PLAYHEAD = 0xFFFFFFFF;

    private Waveforms() {}

    /** Per-column loudest sample of {@code data[from, from+len)}, mapped onto {@code cols} columns. */
    public static float[] envelope(float[] data, int from, int len, int cols) {
        float[] out = new float[Math.max(0, cols)];
        if (data == null || len <= 0) return out;
        for (int c = 0; c < cols; c++) {
            int a = from + (int) ((long) c * len / cols);
            int b = Math.max(a + 1, from + (int) ((long) (c + 1) * len / cols));
            float m = 0f;
            for (int i = Math.max(0, a); i < Math.min(data.length, b); i++) m = Math.max(m, Math.abs(data[i]));
            out[c] = m;
        }
        return out;
    }

    /** Clicks are spiky; a gentle curve keeps their quiet tails visible. */
    public static int barHeight(float v, float gain, int half) {
        float n = Math.min(1f, v * gain);
        return Math.round((float) Math.pow(n, 0.6) * half);
    }

    /** Draws mirrored bars around the vertical centre of the box. */
    public static void bars(DrawContext ctx, float[] env, int x, int y, int h, float gain, int color) {
        int half = Math.max(1, h / 2 - 1), mid = y + h / 2;
        for (int c = 0; c < env.length; c++) {
            int bh = barHeight(env[c], gain, half);
            if (bh > 0) ctx.fill(x + c, mid - bh, x + c + 1, mid + bh, color);
        }
    }

    /**
     * Precomputed drawing of a take on a timeline: press (blue) at 0, release (orange) where it
     * plays in a preview. Columns before {@code playedCols} are drawn bright, the rest dimmer.
     */
    public static final class TakeShape {
        public final Take take;
        private final int w;
        private final float[] env;
        private final boolean[] isRelease;

        private final int total;

        /** Scaled to at least 250 ms, so short and long takes look comparable. */
        public TakeShape(Take take, int w) {
            this(take, w, (int) (dev.clicksounds.audio.AudioEngine.RATE * 0.25f));
        }

        /** @param scaleFrames how many frames the full width shows (grows to fit the take if needed) */
        public TakeShape(Take take, int w, int scaleFrames) {
            this.take = take;
            this.w = w;
            this.env = new float[w];
            this.isRelease = new boolean[w];
            int total = this.total = Math.max(scaleFrames, take.timelineLength());
            float[] p = envelope(take.press(), 0, total, w); // past the end reads as silence
            for (int c = 0; c < w; c++) env[c] = p[c];
            if (take.release() != null) {
                int off = take.releaseOffset();
                for (int c = 0; c < w; c++) {
                    int a = (int) ((long) c * total / w), b = Math.max(a + 1, (int) ((long) (c + 1) * total / w));
                    if (b <= off) continue;
                    float m = 0f;
                    for (int i = Math.max(a, off); i < b && i - off < take.release().length; i++)
                        m = Math.max(m, Math.abs(take.release()[i - off]));
                    if (m > env[c]) { env[c] = m; isRelease[c] = true; }
                }
            }
        }

        /** @param playFrame current preview position in frames, or -1 when not playing */
        public void draw(DrawContext ctx, int x, int y, int h, float gain, long playFrame) {
            int half = Math.max(1, h / 2 - 1), mid = y + h / 2;
            int head = playFrame < 0 ? -1 : (int) (playFrame * w / Math.max(1, total));
            for (int c = 0; c < w; c++) {
                int bh = barHeight(env[c], gain, half);
                if (bh <= 0) continue;
                boolean bright = head < 0 || c <= head;
                int col = isRelease[c] ? (bright ? RELEASE : RELEASE_DIM) : (bright ? PRESS : PRESS_DIM);
                ctx.fill(x + c, mid - bh, x + c + 1, mid + bh, col);
            }
            if (head >= 0 && head < w) ctx.fill(x + head, y, x + head + 1, y + h, PLAYHEAD);
        }
    }
}
