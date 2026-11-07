package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * Draws one lyric line with all its animation and effects onto a {@link Canvas}:
 * entrance pose (per word or per letter), karaoke highlight, glow, aura, bloom flash, shockwave ring,
 * echo, particle burst, the light streak when a line appears, and the exit animation.
 * The canvas origin is the centre of the line.
 */
public final class LineRenderer {
    private static final WordFx.Pose POSE = new WordFx.Pose();
    private static final WordFx.Pose LETTER = new WordFx.Pose();
    private static final WordFx.Spark[] SPARKS = WordFx.newSparkBuffer();

    private LineRenderer() {}

    public static void draw(Canvas cv, LyricsAnimator.Shown s, long pos, boolean curved, float extraAlpha) {
        LyricsConfig cfg = LyricsConfig.get();
        float lineAlpha = s.lineAlpha() * extraAlpha * cfg.opacity;
        if (lineAlpha <= 0.02f) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int n = s.wordText.length;
        long seed = s.line.startMs();
        float radius = s.totalWidth * 1.25f + 70f;
        int glow = Colors.glow();
        int cur = cfg.highlight ? s.currentWord(pos) : -1;
        boolean leaving = s.leaveElapsed() >= 0;

        // light streak sweeping across the line as it appears
        if (cfg.streak && !leaving) {
            float t = (System.currentTimeMillis() - s.appearReal) / 700f;
            if (t > 0 && t < 1) {
                float x = -s.totalWidth / 2f - 20f + (s.totalWidth + 40f) * LyricsAnimator.easeOutCubic(t);
                float a = (float) Math.sin(t * Math.PI) * lineAlpha;
                cv.sprite(FxTextures.FLARE, x, 0, 46f, 9f, LyricsAnimator.argb(a * 0.9f, LyricsAnimator.lerpRgb(glow, 0xFFFFFF, 0.6f)));
            }
        }

        float x = -s.totalWidth / 2f;
        for (int i = 0; i < n; i++) {
            float w = s.wordWidth[i];
            float cx = x + w / 2f;
            x += w + s.spaceWidth;
            long since = s.wordSince(i, pos);
            float p = s.wordProgress(i, pos);
            if (p <= 0f) continue;

            WordFx.pose(cfg, Math.max(p, 0f), since, i, POSE);
            float exit = s.wordExit(i);
            applyExit(cfg, exit, i, POSE);
            float alpha = POSE.alpha * lineAlpha;
            // karaoke: the word being sung is bigger and brighter, words not reached yet are dim
            float hl = 0;
            if (cur >= 0) {
                long sung = s.sungSince(i, pos);
                if (i == cur) hl = Math.min(1f, Math.max(0, sung) / 120f);
                else if (i == cur - 1) hl = Math.max(0f, 1f - s.sungSince(cur, pos) / 160f);
                if (sung < 0) alpha *= 0.55f;   // not sung yet (whole-line mode)
            }
            if (alpha < 0.02f && exit <= 0) continue;
            float xFrac = s.totalWidth <= 0 ? 0 : (cx + s.totalWidth / 2f) / s.totalWidth;

            cv.push();
            if (curved) {
                float theta = cx / radius;
                cv.translate(radius * (float) Math.sin(theta) + POSE.dx, radius * (1f - (float) Math.cos(theta)) + POSE.dy);
                cv.rotate(theta);
            } else {
                cv.translate(cx + POSE.dx, POSE.dy);
            }

            // ---- effects behind the word
            if (cfg.aura && alpha > 0.03f) {
                cv.sprite(FxTextures.BLOB, 0, 0, w * 0.55f + 9f, 11f, LyricsAnimator.argb(alpha * (0.16f + 0.14f * hl), glow));
            }
            if (cfg.bloom && since >= 0 && since < 800 && exit <= 0) {
                float b = since < 110 ? since / 110f : 1f - (since - 110) / 690f;
                cv.sprite(FxTextures.BLOB, 0, 0, w * 0.75f + 12f, 15f, LyricsAnimator.argb(b * 0.5f * lineAlpha, glow));
                float f = 1f - since / 320f;
                if (f > 0) cv.sprite(FxTextures.FLARE, 0, 0, w * 0.9f + 14f, 6f, LyricsAnimator.argb(f * 0.85f * lineAlpha, 0xFFFFFF));
            }
            if (cfg.shockwave && since >= 0 && since < 560 && exit <= 0) {
                float t = since / 560f;
                float r = 5f + LyricsAnimator.easeOutCubic(t) * (w * 0.55f + 16f);
                cv.sprite(FxTextures.RING, 0, 0, r, r * 0.62f, LyricsAnimator.argb((1 - t) * (1 - t) * 0.7f * lineAlpha, Colors.colorA()));
            }

            // ---- particles: entrance burst, or dissolving into glitter on exit
            int sparks = WordFx.sparks(cfg, seed, i, since, w, SPARKS);
            drawSparks(cv, tr, cfg, cfg.vfx, seed, i, sparks, lineAlpha);
            if (cfg.exit == LyricsConfig.Exit.DISSOLVE && exit > 0) {
                long es = s.leaveElapsed() - i * LyricsAnimator.EXIT_STAGGER_MS;
                LyricsConfig.Vfx was = cfg.vfx;
                float amt = cfg.vfxAmount;
                cfg.vfx = LyricsConfig.Vfx.GLITTER;
                cfg.vfxAmount = Math.max(1.2f, amt);
                int ds = WordFx.sparks(cfg, seed ^ 0x5DEECE66DL, i, es, w, SPARKS);
                drawSparks(cv, tr, cfg, LyricsConfig.Vfx.SPARKLES, seed ^ 0x5DEECE66DL, i, ds, lineAlpha);
                cfg.vfx = was;
                cfg.vfxAmount = amt;
            }

            cv.rotate(POSE.rot);
            cv.rotateY(POSE.rotY);
            cv.rotateX(POSE.rotX);
            float sc = POSE.scale * (1f + 0.1f * hl);
            if (cfg.reactLyrics && cfg.react && p >= 1f && exit <= 0) {
                // words that are already in bounce gently to the beat
                sc *= 1f + 0.07f * dev.markie.lyrics.music.MusicPulse.strength() * dev.markie.lyrics.music.MusicPulse.beat();
            }
            float ty = -4.5f;
            int rgb = Colors.word(i, n, xFrac);
            if (hl > 0) rgb = LyricsAnimator.lerpRgb(rgb, 0xFFFFFF, 0.35f * hl);
            if (POSE.bright > 0) rgb = LyricsAnimator.lerpRgb(rgb, 0xFFFFFF, POSE.bright);

            // echo: bigger fading copy flashing out of the word
            if (cfg.echo && since >= 0 && since < 450 && exit <= 0) {
                float e = since / 450f;
                cv.push();
                cv.scale(sc * (1f + LyricsAnimator.easeOutCubic(e) * 0.7f));
                cv.text(s.wordText[i], -w / 2f, ty, LyricsAnimator.argb((1f - e) * 0.45f * lineAlpha, glow), false);
                cv.pop();
            }

            cv.scale(sc);
            long inMs = LyricsAnimator.wordInMs();
            if (cfg.perLetter && exit <= 0 && since < inMs + s.letters[i].length * LyricsAnimator.LETTER_STAGGER_MS + 50) {
                // letters pop in one after another with the chosen style
                Text[] ls = s.letters[i];
                for (int k = 0; k < ls.length; k++) {
                    long lsince = since - k * LyricsAnimator.LETTER_STAGGER_MS;
                    float lp = LyricsAnimator.clamp01(lsince / (float) inMs);
                    if (lp <= 0) continue;
                    WordFx.pose(cfg, lp, lsince, i * 7 + k, LETTER);
                    float la = LETTER.alpha * lineAlpha;
                    float lcx = -w / 2f + s.letterX[i][k] + s.letterW[i][k] / 2f;
                    cv.push();
                    cv.translate(lcx + LETTER.dx, LETTER.dy);
                    cv.rotate(LETTER.rot);
                    cv.rotateY(LETTER.rotY);
                    cv.rotateX(LETTER.rotX);
                    cv.scale(LETTER.scale);
                    drawGlyph(cv, cfg, ls[k], -s.letterW[i][k] / 2f, ty, la, rgb, glow);
                    cv.pop();
                }
            } else {
                drawGlyph(cv, cfg, s.wordText[i], -w / 2f, ty, alpha, rgb, glow);
            }
            cv.pop();
        }
    }

    static void drawGlyph(Canvas cv, LyricsConfig cfg, Text t, float x, float y, float alpha, int rgb, int glow) {
        if (alpha < 0.02f) return;
        if (cfg.depth) {
            // extruded 3D edge: stacked copies stepping down-right, darkest at the back
            boolean world = cv instanceof Canvas.World;
            // in the world: many thin slices make a solid body you can see from the side
            int layers = world ? Math.max(6, Math.round(14 * cfg.depthAmount)) : Math.max(2, Math.round(5 * cfg.depthAmount));
            float step = 0.3f;
            int deep = Colors.depth(rgb);
            float da = alpha * alpha;   // edge fades in a bit later than the face, so the word "solidifies"
            for (int k = layers; k >= 1; k--) {
                int col = LyricsAnimator.lerpRgb(deep, LyricsAnimator.lerpRgb(deep, rgb, 0.35f), 1f - k / (float) layers);
                // each layer sits a little behind the letters, so the world's depth sorting keeps the face on top
                cv.push();
                if (cv instanceof Canvas.World) {
                    // in the world: a real extrusion going backwards, so the letters are always the closest
                    // thing to the camera (text there is depth-sorted) and angled lines show solid 3D sides
                    cv.depth(-0.15f * k);
                    cv.text(t, x + k * 0.02f, y + k * 0.06f, LyricsAnimator.argb(da * 0.97f, col), false);
                } else {
                    cv.text(t, x + k * step * 0.55f, y + k * step, LyricsAnimator.argb(da * 0.95f, col), false);
                }
                cv.pop();
            }
        }
        if (cfg.glow) {
            int gc = LyricsAnimator.argb(alpha * 0.28f, glow);
            cv.push();
            cv.depth(-0.04f);
            for (int k = 0; k < 8; k++) {
                float a = k * (float) Math.PI / 4f;
                cv.text(t, x + (float) Math.cos(a) * 0.9f, y + (float) Math.sin(a) * 0.9f, gc, false);
            }
            cv.pop();
        }
        cv.text(t, x, y, LyricsAnimator.argb(alpha, rgb), cfg.shadow);
    }

    private static void drawSparks(Canvas cv, TextRenderer tr, LyricsConfig cfg, LyricsConfig.Vfx kind, long seed, int i, int count, float lineAlpha) {
        for (int k = 0; k < count; k++) {
            WordFx.Spark sp = SPARKS[k];
            int col = LyricsAnimator.argb(sp.alpha * lineAlpha, sp.rgb);
            if ((col >>> 24) < 5) continue;
            if (kind == LyricsConfig.Vfx.SPARKLES || kind == LyricsConfig.Vfx.GLITTER) {
                // soft sprites look much nicer than font glyphs for these two
                float r = (kind == LyricsConfig.Vfx.SPARKLES ? 4.2f : 1.8f) * sp.scale;
                cv.push();
                cv.translate(sp.x, sp.y);
                cv.rotate(sp.rot);
                cv.sprite(kind == LyricsConfig.Vfx.SPARKLES ? FxTextures.SPARKLE : FxTextures.BLOB, 0, 0, r, r, col);
                if (kind == LyricsConfig.Vfx.SPARKLES) cv.sprite(FxTextures.BLOB, 0, 0, r * 1.6f, r * 1.6f, LyricsAnimator.argb(sp.alpha * lineAlpha * 0.3f, sp.rgb));
                cv.pop();
            } else {
                Text g = WordFx.glyph(cfg, seed, i, k);
                cv.push();
                cv.translate(sp.x, sp.y);
                cv.rotate(sp.rot);
                cv.scale(sp.scale);
                cv.text(g, -tr.getWidth(g) / 2f, -4f, col, false);
                cv.pop();
            }
        }
    }

    /** Exit animation for one word, layered on top of its pose. */
    private static void applyExit(LyricsConfig cfg, float t, int i, WordFx.Pose p) {
        if (t <= 0) return;
        float e = LyricsAnimator.easeOutCubic(t), in = LyricsAnimator.easeInCubic(t);
        switch (cfg.exit) {
            case FADE_UP -> { p.alpha *= 1f - t; p.dy -= e * 9f; p.scale *= 1f + t * 0.12f; }
            case DISSOLVE -> { p.alpha *= (1f - t) * (1f - t); p.scale *= 1f - t * 0.25f; }
            case BLOW -> { p.alpha *= 1f - t; p.dx += in * 46f; p.dy -= t * 10f; p.rot += t * 0.7f; }
            case SHRINK -> { p.alpha *= 1f - t * 0.6f; p.scale *= 1f - in; }
            case DROP -> { p.alpha *= 1f - t; p.dy += t * t * 44f; p.rot += t * 0.5f * ((i & 1) == 0 ? 1 : -1); }
        }
    }
}
