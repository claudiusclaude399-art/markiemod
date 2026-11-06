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
    public static final long PAIR_MOVE_MS = 450;
    public static final float LINE_GAP = 13.5f;   // distance between the two lines of a pair, in font pixels

    /** Entrance length: the smooth style takes a little longer so it feels soft. */
    public static long wordInMs() {
        return LyricsConfig.get().popStyle == LyricsConfig.PopStyle.SMOOTH ? 460 : WORD_IN_MS;
    }
    public static final long LINE_OUT_MS = 650;   // each word's exit
    public static final long EXIT_STAGGER_MS = 45;  // delay between words leaving
    public static final long LETTER_STAGGER_MS = 35;

    public static final class Shown {
        public final LyricLine line;
        public final long appearReal;
        /** First line of the pair this line belongs to (itself if it starts a pair), and its slot (0 top, 1 bottom). */
        public Shown leader = this;
        public int slot = 0;
        /** When the second line of this line's pair arrived (-1 = none yet). */
        public long partnerAt = -1;
        public long leaveReal = -1;
        public net.minecraft.util.math.Vec3d origin;
        // placement, picked randomly when the line appears so every line lands somewhere new
        public float baseYaw, basePitch;
        /** Random 0..1 picks; turned into angles at draw time so Position settings apply instantly. */
        public float rYaw, rPitch;
        public float roll, twist;
        /** Direction the line slowly drifts while it's showing (cinematic motion). */
        public float driftYaw, driftRoll;
        // cached layout
        public final Text[] wordText;
        public final float[] wordWidth;
        public final float spaceWidth, totalWidth;
        /** Per-letter layout for the "letter by letter" mode. */
        public final Text[][] letters;
        public final float[][] letterX, letterW;

        public Shown(LyricLine line, float camYaw, float camPitch, LyricsConfig.Where where, long seed) {
            this.line = line;
            this.appearReal = System.currentTimeMillis();
            this.baseYaw = camYaw;
            this.basePitch = camPitch;
            Random r = new Random(seed);
            if (where == LyricsConfig.Where.SKY) {
                rYaw = r.nextFloat();
                rPitch = r.nextFloat();
                roll = (r.nextFloat() - 0.5f) * 18f;
                // strongly angled, so a line runs from far to near like in a music video
                twist = (r.nextFloat() < 0.5f ? -1 : 1) * (18f + r.nextFloat() * 30f);
            } else {
                rYaw = r.nextFloat();
                rPitch = r.nextFloat();
                roll = (r.nextFloat() - 0.5f) * 8f;
                twist = (r.nextFloat() - 0.5f) * 30f;
            }
            driftYaw = (r.nextFloat() - 0.5f) * 14f;
            driftRoll = (r.nextFloat() - 0.5f) * 6f;
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
            letters = new Text[words.size()][];
            letterX = new float[words.size()][];
            letterW = new float[words.size()][];
            for (int i = 0; i < words.size(); i++) {
                int[] cps = words.get(i).text().codePoints().toArray();
                letters[i] = new Text[cps.length];
                letterX[i] = new float[cps.length];
                letterW[i] = new float[cps.length];
                float lx = 0;
                for (int k = 0; k < cps.length; k++) {
                    letters[i][k] = Text.literal(new String(Character.toChars(cps[k]))).setStyle(style);
                    letterW[i][k] = tr.getWidth(letters[i][k]);
                    letterX[i][k] = lx;
                    lx += letterW[i][k];
                }
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
            float p = (songPos - t0) / (float) wordInMs();
            return clamp01(p);
        }

        /** Milliseconds since word i started appearing (negative = not yet). */
        public long wordSince(int i, long songPos) {
            long t0 = LyricsConfig.get().reveal == LyricsConfig.Reveal.WORD
                ? line.words().get(i).startMs()
                : line.startMs() + i * 45L;
            return songPos - t0;
        }

        /** Vertical offset of this line inside its pair (font pixels), animated when the partner arrives. */
        public float slotOffset() {
            if (slot == 1) return LINE_GAP / 2f;
            if (partnerAt < 0) return 0f;
            float k = clamp01((System.currentTimeMillis() - partnerAt) / (float) PAIR_MOVE_MS);
            return -LINE_GAP / 2f * easeOutCubic(k);
        }

        /** Seconds since the pair started (drives the slow cinematic drift). */
        public float groupAge() {
            return (System.currentTimeMillis() - leader.appearReal) / 1000f;
        }

        /** Copy placement from the first line so both lines of a pair sit together. */
        void joinPair(Shown first) {
            leader = first;
            slot = 1;
            baseYaw = first.baseYaw; basePitch = first.basePitch;
            rYaw = first.rYaw; rPitch = first.rPitch;
            roll = first.roll; twist = first.twist;
            driftYaw = first.driftYaw; driftRoll = first.driftRoll;
            origin = first.origin;
            first.partnerAt = System.currentTimeMillis();
        }

        /** 0..1 fade-in of the whole line (leaving is handled per word, see {@link #wordExit}). */
        public float lineAlpha() {
            return clamp01((System.currentTimeMillis() - appearReal) / 220f);
        }

        /** Milliseconds since the line started leaving, or -1. */
        public long leaveElapsed() {
            return leaveReal < 0 ? -1 : System.currentTimeMillis() - leaveReal;
        }

        /** 0..1 exit progress of word i (words leave one after another). */
        public float wordExit(int i) {
            long e = leaveElapsed();
            if (e < 0) return 0;
            return clamp01((e - i * EXIT_STAGGER_MS) / (float) LINE_OUT_MS);
        }

        public boolean exitDone() {
            return leaveReal >= 0 && leaveElapsed() > LINE_OUT_MS + EXIT_STAGGER_MS * wordText.length;
        }

        /** Overall leave progress (0..1), used for drifting the whole line. */
        public float leaveProgress() {
            long e = leaveElapsed();
            if (e < 0) return 0;
            return clamp01(e / (float) (LINE_OUT_MS + EXIT_STAGGER_MS * wordText.length));
        }

        /** Milliseconds since word i is actually sung (its real timing, whatever the reveal mode). */
        public long sungSince(int i, long songPos) {
            return songPos - line.words().get(i).startMs();
        }

        /** Index of the word being sung right now, or -1. */
        public int currentWord(long songPos) {
            if (leaveReal >= 0) return -1;
            int cur = -1;
            for (int i = 0; i < wordText.length; i++) if (sungSince(i, songPos) >= 0) cur = i;
            return cur;
        }
    }

    private static Shown active, partnerTop;
    private static final java.util.ArrayList<Shown> leavingLines = new java.util.ArrayList<>();
    private static int activeIndex = -1;
    private static List<LyricLine> activeList;
    private static int fontAtBuild = -1;
    private static long seedCounter = 1;

    private LyricsAnimator() {}

    public static Shown active() { return active; }

    /** Every line to draw this frame: lines on their way out, then the current pair. */
    public static List<Shown> drawList() {
        leavingLines.removeIf(Shown::exitDone);
        java.util.ArrayList<Shown> out = new java.util.ArrayList<>(leavingLines);
        if (partnerTop != null) out.add(partnerTop);
        if (active != null) out.add(active);
        return out;
    }

    private static void leave(Shown s) {
        if (s == null) return;
        s.leaveReal = System.currentTimeMillis();
        leavingLines.add(s);
    }

    /** Call once per frame before drawing. */
    public static void update(float camYaw, float camPitch) {
        LyricsConfig cfg = LyricsConfig.get();
        if (!cfg.showLyrics || SpotifyClient.current() == null || LyricsManager.state() != LyricsManager.State.SYNCED) {
            leave(partnerTop); partnerTop = null;
            leave(active); active = null;
            activeIndex = -1;
            return;
        }
        List<LyricLine> lines = LyricsManager.lines();
        long pos = LyricsManager.position();
        int idx = LyricsManager.currentIndex(pos);
        boolean rebuild = cfg.font != fontAtBuild;
        if (idx != activeIndex || lines != activeList || rebuild) {
            boolean newLine = idx != activeIndex || lines != activeList;
            Shown prev = active;
            // pairs: the next line joins the current one (underneath) if it follows straight on
            boolean pair = newLine && cfg.linePairs && prev != null && partnerTop == null && idx == activeIndex + 1
                && lines == activeList && idx >= 0 && lines.get(idx).startMs() - prev.line.endMs() < 1500;
            if (rebuild && !newLine) {
                // font changed: rebuild in place, keeping the pair
                if (idx >= 0) {
                    Shown s = new Shown(lines.get(idx), camYaw, camPitch, cfg.where, seedCounter++ * 0x9E3779B97F4A7C15L);
                    if (partnerTop != null) s.joinPair(partnerTop);
                    active = s;
                }
            } else if (pair) {
                partnerTop = prev;
                Shown s = new Shown(lines.get(idx), camYaw, camPitch, cfg.where, seedCounter++ * 0x9E3779B97F4A7C15L);
                s.joinPair(prev);
                active = s;
            } else {
                leave(partnerTop); partnerTop = null;
                leave(active); active = null;
                if (idx >= 0) active = new Shown(lines.get(idx), camYaw, camPitch, cfg.where, seedCounter++ * 0x9E3779B97F4A7C15L);
            }
            if (newLine && idx >= 0 && cfg.lineChime) Sounds.play(Sounds.LINE, 1f);
            activeIndex = idx;
            activeList = lines;
            fontAtBuild = cfg.font;
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
    public static float easeInCubic(float t) { return t * t * t; }

    public static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
