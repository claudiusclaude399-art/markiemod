package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.gui.Glass;
import dev.markie.lyrics.gui.LyricsScreen;
import dev.markie.lyrics.gui.Themes;
import dev.markie.lyrics.gui.Ui;
import dev.markie.lyrics.lyrics.LyricsManager;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/** The little "now playing" pill at the top of the screen, and the subtitle-style lyrics mode. */
public final class HudRenderer {
    private static float pillAnim = 0f;
    private static long lastFrame = System.nanoTime();
    private static String lastTrack;
    private static long trackChangedAt;

    private HudRenderer() {}

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;

        LyricsConfig cfg = LyricsConfig.get();
        NowPlaying np = SpotifyClient.current();
        boolean showPill = cfg.hud && np != null && !(mc.currentScreen instanceof LyricsScreen);
        pillAnim = Ui.approach(pillAnim, showPill ? 1f : 0f, 10f, dt);
        if (pillAnim > 0.01f && np != null) drawPill(ctx, mc, np, cfg);

        if (cfg.where == LyricsConfig.Where.SCREEN && cfg.showLyrics) {
            // in world modes the world renderer updates the animator; in screen mode we do it here
            LyricsAnimator.update(mc.gameRenderer.getCamera().getYaw(), mc.gameRenderer.getCamera().getPitch());
            drawSubtitles(ctx, mc, cfg);
        }
    }

    private static void drawPill(DrawContext ctx, MinecraftClient mc, NowPlaying np, LyricsConfig cfg) {
        if (!np.id().equals(lastTrack)) { lastTrack = np.id(); trackChangedAt = System.currentTimeMillis(); }
        TextRenderer tr = mc.textRenderer;
        int w = 168, h = 18;
        int x = ctx.getScaledWindowWidth() / 2 - w / 2;
        float ease = LyricsAnimator.easeOutCubic(pillAnim);
        int y = Math.round(cfg.hudY - (1f - ease) * 24f);
        float a = pillAnim;

        // liquid glass pill (no blur in-game, so a darker base keeps it readable over bright skies)
        Glass.shadow(ctx, x, y, w, h, 9, a * 0.8f);
        Glass.solid(ctx, x, y, w, h, 9, Glass.argb(0.62f * a, 0x0C0A14));
        Glass.solid(ctx, x, y, w, h, 9, Glass.argb(0.12f * a, Themes.current().glow()));
        Glass.card(ctx, x, y, w, h, 9, 0.6f, a);

        // cover, spinning-record style ring while there's no cover yet
        Identifier cover = CoverArt.texture();
        int cx = x + 3, cy = y + 3;
        if (cover != null && a > 0.6f) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, cover, cx, cy, 0, 0, 12, 12, 12, 12, 12, 12);
        } else {
            // little spinning record
            Glass.solid(ctx, cx, cy, 12, 12, 6, Ui.alpha(0xFF2A2230, a));
            Glass.solid(ctx, cx + 3, cy + 3, 6, 6, 3, Ui.alpha(Themes.current().a() | 0xFF000000, a));
            double ang = np.playing() ? System.currentTimeMillis() / 400.0 : 0;
            int dx = (int) Math.round(Math.cos(ang) * 4), dy = (int) Math.round(Math.sin(ang) * 4);
            ctx.fill(cx + 6 + dx, cy + 6 + dy, cx + 7 + dx, cy + 7 + dy, Ui.alpha(0x66FFFFFF, a));
            ctx.fill(cx + 5, cy + 5, cx + 7, cy + 7, Ui.alpha(0xFF2A2230, a));
        }

        // scrolling title
        int textX = x + 19, textW = w - 19 - 26;
        String label = np.title() + "  ·  " + np.artist();
        Text t = Ui.t(label);
        int tw = tr.getWidth(t);
        int scroll = 0;
        if (tw > textW) {
            long since = System.currentTimeMillis() - trackChangedAt;
            int travel = tw - textW + 12;
            long period = 2500L + travel * 40L;
            long phase = Math.max(0, since - 1500) % (period * 2);
            float f = phase < period ? phase / (float) period : 1f - (phase - period) / (float) period;
            scroll = Math.round(travel * smooth(f));
        }
        ctx.enableScissor(textX, y, textX + textW, y + h);
        Ui.text(ctx, t, textX - scroll, y + 5, Ui.alpha(0xFFF2EEF8, a));
        ctx.disableScissor();

        // equaliser bars
        if (cfg.hudBars) {
            int bx = x + w - 22;
            int themeCol = Themes.current().a() | 0xFF000000;
            double time = System.currentTimeMillis() / 1000.0;
            for (int i = 0; i < 4; i++) {
                float v = np.playing()
                    ? (float) (0.35 + 0.65 * Math.abs(Math.sin(time * (3.1 + i * 1.7) + i * 1.3)))
                    : 0.2f;
                int bh = Math.max(2, Math.round(9 * v));
                ctx.fill(bx + i * 4, y + 13 - bh, bx + i * 4 + 2, y + 13, Ui.alpha(themeCol, a));
            }
        }

        // progress
        if (cfg.hudProgress && np.durationMs() > 0) {
            float prog = np.positionNow() / (float) np.durationMs();
            int px = x + 9, pw = w - 18;
            Glass.solid(ctx, px, y + h - 3, pw, 1, 1, Ui.alpha(0x26FFFFFF, a));
            Glass.solid(ctx, px, y + h - 3, Math.max(1, Math.round(pw * prog)), 1, 1, Ui.alpha(Themes.current().a() | 0xFF000000, a));
        }
    }

    private static float smooth(float f) { return f * f * (3 - 2 * f); }

    private static void drawSubtitles(DrawContext ctx, MinecraftClient mc, LyricsConfig cfg) {
        long pos = LyricsManager.position();
        LyricsAnimator.Shown leaving = LyricsAnimator.leaving();
        if (leaving != null) drawSubLine(ctx, mc, leaving, pos, cfg);
        LyricsAnimator.Shown active = LyricsAnimator.active();
        if (active != null) drawSubLine(ctx, mc, active, pos, cfg);
    }

    private static void drawSubLine(DrawContext ctx, MinecraftClient mc, LyricsAnimator.Shown s, long pos, LyricsConfig cfg) {
        float lineAlpha = s.lineAlpha();
        if (lineAlpha <= 0.02f) return;
        TextRenderer tr = mc.textRenderer;
        float leave = LyricsAnimator.easeOutCubic(s.leaveProgress());
        float base = 2.0f * cfg.size;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        float maxW = sw * 0.9f;
        if (s.totalWidth * base > maxW) base = maxW / s.totalWidth;
        float cy = sh * cfg.screenY - leave * 14f;

        Matrix3x2fStack m = ctx.getMatrices();
        int n = s.wordText.length;
        float x = -s.totalWidth / 2f;
        for (int i = 0; i < n; i++) {
            float w = s.wordWidth[i];
            float cx = x + w / 2f;
            x += w + s.spaceWidth;
            float p = s.wordProgress(i, pos);
            if (p <= 0f) continue;
            float alpha = LyricsAnimator.easeOutCubic(p) * lineAlpha;
            if (alpha < 0.03f) continue;
            float pop = 0.55f + 0.45f * LyricsAnimator.easeOutBack(p);
            float rise = (1f - LyricsAnimator.easeOutCubic(p)) * 6f;

            m.pushMatrix();
            m.translate(sw / 2f + cx * base, cy + rise * base / 2f);
            m.scale(base * pop, base * pop);
            int tx = Math.round(-w / 2f), ty = -4;
            if (cfg.glow) {
                int gc = LyricsAnimator.glowColor(alpha * 0.3f);
                if ((gc >>> 24) >= 4) {
                    ctx.drawText(tr, s.wordText[i], tx + 1, ty, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx - 1, ty, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx, ty + 1, gc, false);
                    ctx.drawText(tr, s.wordText[i], tx, ty - 1, gc, false);
                }
            }
            ctx.drawText(tr, s.wordText[i], tx, ty, LyricsAnimator.wordColor(i, n, alpha), cfg.shadow);
            m.popMatrix();
        }
    }
}
