package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.text.Text;

/**
 * How a word moves as it appears (pop, bounce, zoom, spin, slide, fade), the idle wave, and the
 * particle burst around it. Everything is computed from time alone, so there is no state to keep.
 */
public final class WordFx {
    /** Per-word transform, in text units (one unit = one font pixel). */
    public static final class Pose { public float dx, dy, scale, rot, alpha; }

    /** One particle of a burst. */
    public static final class Spark { public float x, y, scale, rot, alpha; public int rgb; }

    private static final Text[][] GLYPHS = {
        {},
        {Text.literal("✦"), Text.literal("✧"), Text.literal("·")},
        {Text.literal("★"), Text.literal("☆")},
        {Text.literal("❤"), Text.literal("♥")},
        {Text.literal("♪"), Text.literal("♫"), Text.literal("♬")},
        {Text.literal("•"), Text.literal("·"), Text.literal("✦")},
    };

    public static final long BURST_MS = 900;

    private WordFx() {}

    /** p = 0..1 entrance progress, sinceMs = time since the word started, i = word index. */
    public static void pose(LyricsConfig c, float p, long sinceMs, int i, Pose out) {
        float e = LyricsAnimator.easeOutCubic(p);
        out.dx = 0; out.dy = 0; out.scale = 1; out.rot = 0; out.alpha = e;
        switch (c.popStyle) {
            case POP -> {
                out.scale = 0.55f + 0.45f * LyricsAnimator.easeOutBack(p);
                out.dy = (1f - e) * 7f;
            }
            case BOUNCE -> {
                out.dy = -(1f - bounce(p)) * 16f;
                out.alpha = Math.min(1f, p * 3f);
                out.scale = 1f + (p < 0.75f ? 0 : (float) Math.sin((p - 0.75f) / 0.25f * Math.PI) * 0.08f);
            }
            case ZOOM -> {
                out.scale = 1f + (1f - e) * 1.9f;
                out.alpha = p * p;
            }
            case SPIN -> {
                out.rot = (1f - LyricsAnimator.easeOutBack(p)) * -1.4f;
                out.scale = 0.3f + 0.7f * LyricsAnimator.easeOutBack(p);
            }
            case SLIDE -> {
                out.dx = -(1f - e) * 18f;
                out.rot = (1f - e) * 0.15f;
            }
            case FADE -> {
                out.alpha = p;
                out.dy = (1f - e) * 2f;
            }
        }
        if (c.wave && p >= 1f) {
            float t = sinceMs / 1000f;
            float settle = Math.min(1f, (sinceMs - LyricsAnimator.WORD_IN_MS) / 400f);
            out.dy += (float) Math.sin(t * 3.2f + i * 0.8f) * 1.3f * Math.max(0, settle);
        }
    }

    /**
     * Particles for word i, {@code sinceMs} after it appeared. Returns how many were written to {@code out}.
     * Positions are relative to the word's centre.
     */
    public static int sparks(LyricsConfig c, long seed, int i, long sinceMs, float wordWidth, Spark[] out) {
        if (c.vfx == LyricsConfig.Vfx.NONE || sinceMs < 0 || sinceMs > BURST_MS + 400) return 0;
        int n = Math.min(out.length, Math.round((3 + wordWidth / 9f) * c.vfxAmount));
        int count = 0;
        for (int k = 0; k < n; k++) {
            long h = mix(seed * 31 + i * 977 + k * 7919);
            float r1 = (h & 0xFFFF) / 65535f, r2 = ((h >> 16) & 0xFFFF) / 65535f, r3 = ((h >> 32) & 0xFFFF) / 65535f, r4 = ((h >> 48) & 0xFFFF) / 65535f;
            long life = (long) (BURST_MS * (0.6f + 0.6f * r3));
            long t0 = (long) (r4 * 120);   // stagger
            float t = (sinceMs - t0) / (float) life;
            if (t <= 0f || t >= 1f) continue;
            float ang = (float) (r1 * Math.PI * 2);
            float speed = 10f + r2 * 16f;
            float go = LyricsAnimator.easeOutCubic(t);
            Spark s = out[count++];
            s.x = (float) Math.cos(ang) * (wordWidth * 0.35f + speed * go);
            s.y = (float) Math.sin(ang) * (5f + speed * 0.6f * go) + t * t * 9f;   // a little gravity
            s.scale = (0.45f + r3 * 0.5f) * (1f - t * 0.6f) * (t < 0.15f ? t / 0.15f : 1f);
            s.rot = (r2 - 0.5f) * 3f * t;
            s.alpha = 1f - t * t;
            s.rgb = (k & 1) == 0 ? Colors.colorA() : Colors.colorB();
        }
        return count;
    }

    public static Text glyph(LyricsConfig c, long seed, int i, int k) {
        Text[] set = GLYPHS[c.vfx.ordinal()];
        return set[(int) Math.floorMod(mix(seed + i * 131 + k * 17), set.length)];
    }

    public static Spark[] newSparkBuffer() {
        Spark[] a = new Spark[24];
        for (int i = 0; i < a.length; i++) a[i] = new Spark();
        return a;
    }

    private static float bounce(float t) {
        float n = 7.5625f, d = 2.75f;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) { t -= 1.5f / d; return n * t * t + 0.75f; }
        if (t < 2.5 / d) { t -= 2.25f / d; return n * t * t + 0.9375f; }
        t -= 2.625f / d;
        return n * t * t + 0.984375f;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
