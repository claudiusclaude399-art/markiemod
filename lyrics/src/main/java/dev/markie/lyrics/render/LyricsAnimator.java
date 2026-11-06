package dev.markie.lyrics.render;

import dev.markie.lyrics.Sounds;
import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.gui.Fonts;
import dev.markie.lyrics.lyrics.LyricLine;
import dev.markie.lyrics.lyrics.LyricsManager;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Random;

/** Keeps track of which line is on screen, the one fading away, and how each word animates in. */
public final class LyricsAnimator {
    public static final long WORD_IN_MS = 280;
    public static final long LINE_OUT_MS = 520;

    public static final class Shown {
        public final LyricLine line;
        public final long appearReal;
        public long leaveReal = -1;
        public net.minecraft.util.math.Vec3d origin;
        // placement, picked randomly when the line appears so every line lands somewhere new
        public final float baseYaw, basePitch;
        /** Random 0..1 picks; turned into angles at draw time so Position settings apply instantly. */
        public final float rYaw, rPitch;
        public final float roll, twist;
        // cached layout
        public final Text[] wordText;
        public final float[] wordWidth;
        public final float spaceWidth, totalWidth;

        public Shown(LyricLine line, float camYaw, float camPitch, LyricsConfig.Where where, long seed) {
            this.line = line;
            this.appearReal = System.currentTimeMillis();
            this.baseYaw = camYaw;
            this.basePitch = camPitch;
            Random r = new Random(seed);
            if (where == LyricsConfig.Where.SKY) {
                rYaw = r.nextFloat();
                rPitch = r.nextFloat();
                roll = (r.nextFloat() - 0.5f) * 20f;
                twist = (r.nextFloat() - 0.5f) * 44f;
            } else {
                rYaw = r.nextFloat();
                rPitch = r.nextFloat();
                roll = (r.nextFloat() - 0.5f) * 8f;
                twist = (r.nextFloat() - 0.5f) * 20f;
            }
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            Style style = Style.EMPTY.withFont(Fonts.current());
            List<LyricLine.Word> words = line.words();
            wordText = new Text[words.size()];
            wordWidth = new float[words.size()];
            float total = 0;
            for (int i = 0; i < words.size(); i++) {
                wordText[i] = Text.literal(words.get(i).text()).setStyle(style);
                wordWidth[i] = tr.getWidth(wordText[i]);
                total += wordWidth[i];
            }
            spaceWidth = tr.getWidth(Text.literal(" ").setStyle(style)) * 1.15f;
            totalWidth = total + spaceWidth * Math.max(0, words.size() - 1);
        }

        /** 0..1, how far word i has animated in. */
        public float wordProgress(int i, long songPos) {
            LyricsConfig cfg = LyricsConfig.get();
            long t0 = cfg.reveal == LyricsConfig.Reveal.WORD
                ? line.words().get(i).startMs()
                : line.startMs() + i * 45L;
            float p = (songPos - t0) / (float) WORD_IN_MS;
            return clamp01(p);
        }

        /** Milliseconds since word i started appearing (negative = not yet). */
        public long wordSince(int i, long songPos) {
            long t0 = LyricsConfig.get().reveal == LyricsConfig.Reveal.WORD
                ? line.words().get(i).startMs()
                : line.startMs() + i * 45L;
            return songPos - t0;
        }

        /** 0..1 overall line opacity (fade in, fade out). */
        public float lineAlpha() {
            long now = System.currentTimeMillis();
            float in = clamp01((now - appearReal) / 220f);
            if (leaveReal < 0) return in;
            return in * (1f - clamp01((now - leaveReal) / (float) LINE_OUT_MS));
        }

        /** 0..1 progress of the leaving animation. */
        public float leaveProgress() {
            return leaveReal < 0 ? 0 : clamp01((System.currentTimeMillis() - leaveReal) / (float) LINE_OUT_MS);
        }
    }

    private static Shown active, leaving;
    private static int activeIndex = -1;
    private static List<LyricLine> activeList;
    private static int fontAtBuild = -1;
    private static long seedCounter = 1;

    private LyricsAnimator() {}

    public static Shown active() { return active; }
    public static Shown leaving() { return leaving != null && leaving.leaveProgress() >= 1f ? null : leaving; }

    /** Call once per frame before drawing. */
    public static void update(float camYaw, float camPitch) {
        LyricsConfig cfg = LyricsConfig.get();
        if (!cfg.showLyrics || SpotifyClient.current() == null || LyricsManager.state() != LyricsManager.State.SYNCED) {
            if (active != null) { active.leaveReal = System.currentTimeMillis(); leaving = active; active = null; }
            activeIndex = -1;
            return;
        }
        List<LyricLine> lines = LyricsManager.lines();
        long pos = LyricsManager.position();
        int idx = LyricsManager.currentIndex(pos);
        boolean rebuild = cfg.font != fontAtBuild;
        if (idx != activeIndex || lines != activeList || rebuild) {
            boolean newLine = idx != activeIndex || lines != activeList;
            if (active != null && newLine) {
                active.leaveReal = System.currentTimeMillis();
                leaving = active;
            }
            activeIndex = idx;
            activeList = lines;
            fontAtBuild = cfg.font;
            if (idx >= 0) {
                Shown s = new Shown(lines.get(idx), camYaw, camPitch, cfg.where, seedCounter++ * 0x9E3779B97F4A7C15L);
                active = s;
                if (newLine && cfg.lineChime) Sounds.play(Sounds.LINE, 1f);
            } else {
                active = null;
            }
        }
    }

    /** Left/right offset in degrees for this line. */
    public static float yawOffset(Shown s, boolean sky) {
        return (s.rYaw - 0.5f) * (sky ? 56f : 16f) * LyricsConfig.get().spread;
    }

    /** Up/down offset in degrees (negative = up, Minecraft style). */
    public static float pitchOffset(Shown s, boolean sky) {
        LyricsConfig c = LyricsConfig.get();
        return sky ? -(c.skyHeight + s.rPitch * 18f) : -(c.floatHeight + s.rPitch * 6f * Math.min(1f, c.spread));
    }

    /** Colour for word i of n with alpha applied (ARGB). */
    public static int wordColor(int i, int n, float alpha) {
        return argb(alpha, Colors.word(i, n, n <= 1 ? 0f : i / (float) (n - 1)));
    }

    public static int glowColor(float alpha) {
        return argb(alpha, Colors.glow());
    }

    public static int argb(float alpha, int rgb) {
        int a = Math.round(clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static int lerpRgb(int c1, int c2, float f) {
        int r = (int) (((c1 >> 16) & 255) + ((((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * f));
        int g = (int) (((c1 >> 8) & 255) + ((((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * f));
        int b = (int) ((c1 & 255) + (((c2 & 255) - (c1 & 255)) * f));
        return (r << 16) | (g << 8) | b;
    }

    public static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    public static float easeOutCubic(float t) { float u = 1 - t; return 1 - u * u * u; }

    /** Overshoots a little, for the "pop". */
    public static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
