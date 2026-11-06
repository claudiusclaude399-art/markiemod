package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.joml.Matrix3x2fStack;

/** Draws one animated lyric line flat on the screen (subtitle mode and the menu's live preview). */
public final class Lyrics2D {
    private static final WordFx.Pose POSE = new WordFx.Pose();
    private static final WordFx.Spark[] SPARKS = WordFx.newSparkBuffer();

    private Lyrics2D() {}

    /** @param base text scale; @param cx,cy centre of the line on screen */
    public static void draw(DrawContext ctx, LyricsAnimator.Shown s, long pos, float cx, float cy, float base, float extraAlpha) {
        LyricsConfig cfg = LyricsConfig.get();
        float lineAlpha = s.lineAlpha() * extraAlpha;
        if (lineAlpha <= 0.02f) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        float leave = LyricsAnimator.easeOutCubic(s.leaveProgress());
        cy -= leave * 14f;
        Matrix3x2fStack m = ctx.getMatrices();
        int n = s.wordText.length;
        long seed = s.line.startMs();
        float x = -s.totalWidth / 2f;
        for (int i = 0; i < n; i++) {
            float w = s.wordWidth[i];
            float wcx = x + w / 2f;
            x += w + s.spaceWidth;
            float p = s.wordProgress(i, pos);
            if (p <= 0f) continue;
            long since = s.wordSince(i, pos);
            WordFx.pose(cfg, p, since, i, POSE);
            float alpha = POSE.alpha * lineAlpha * cfg.opacity;
            if (alpha < 0.03f) continue;
            float xFrac = s.totalWidth <= 0 ? 0 : (wcx + s.totalWidth / 2f) / s.totalWidth;

            m.pushMatrix();
            m.translate(cx + (wcx + POSE.dx) * base, cy + POSE.dy * base * 0.6f);
            m.scale(base, base);

            int sparks = WordFx.sparks(cfg, seed, i, since, w, SPARKS);
            for (int k = 0; k < sparks; k++) {
                WordFx.Spark sp = SPARKS[k];
                int col = LyricsAnimator.argb(sp.alpha * lineAlpha * cfg.opacity, sp.rgb);
                if ((col >>> 24) < 6) continue;
                Text g = WordFx.glyph(cfg, seed, i, k);
                m.pushMatrix();
                m.translate(sp.x, sp.y);
                m.rotate(sp.rot);
                m.scale(sp.scale, sp.scale);
                ctx.drawText(tr, g, -tr.getWidth(g) / 2, -4, col, false);
                m.popMatrix();
            }

            if (POSE.rot != 0) m.rotate(POSE.rot);
            int tx = Math.round(-w / 2f), ty = -4;
            if (cfg.echo && since >= 0 && since < 450) {
                float e = since / 450f;
                int ec = LyricsAnimator.argb((1f - e) * 0.45f * lineAlpha * cfg.opacity, Colors.glow());
                if ((ec >>> 24) >= 6) {
                    m.pushMatrix();
                    float es = POSE.scale * (1f + LyricsAnimator.easeOutCubic(e) * 0.7f);
                    m.scale(es, es);
                    ctx.drawText(tr, s.wordText[i], tx, ty, ec, false);
                    m.popMatrix();
                }
            }
            m.scale(POSE.scale, POSE.scale);
            if (cfg.glow) {
                int gc = LyricsAnimator.glowColor(alpha * 0.3f);
                if ((gc >>> 24) >= 4) {
                    ctx.drawText(tr, s.wordText[i], tx + 1, ty, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx - 1, ty, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx, ty + 1, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx, ty - 1, gc, false);
                }
            }
            ctx.drawText(tr, s.wordText[i], tx, ty, LyricsAnimator.argb(alpha, Colors.word(i, n, xFrac)), cfg.shadow);
            m.popMatrix();
        }
    }
}
