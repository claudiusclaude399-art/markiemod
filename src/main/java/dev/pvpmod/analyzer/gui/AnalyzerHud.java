package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.FightTracker;
import dev.pvpmod.analyzer.Flag;
import dev.pvpmod.config.PvpConfig;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.GFont;
import dev.pvpmod.glass.Glass;
import dev.pvpmod.glass.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** "Dynamic island" at the top of the screen: grows while a fight records, expands to show new flags and the result. */
public final class AnalyzerHud {
    private AnalyzerHud() {}

    private static final Spring W = new Spring(0f, 190f, 19f), H = new Spring(22f, 190f, 19f), A = new Spring(0f, 120f, 18f);
    private static long last = 0;

    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!PvpConfig.get().analyzerHud || mc.options.hudHidden) return;
        long now = System.nanoTime();
        float dt = last == 0 ? 1f / 60f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        Glass.tick();

        FightTracker.Live l = FightTracker.live;
        long ms = System.currentTimeMillis();
        boolean showFlag = l != null && l.lastFlag != null && ms - l.lastFlagAt < 3500;
        boolean showResult = l == null && FightTracker.lastFinished != null && ms - FightTracker.lastFinishedAt < 6000;
        boolean visible = l != null || showResult;

        float tw = showFlag ? 250 : showResult ? 230 : 168;
        float th = showFlag || showResult ? 40 : 22;
        W.to(visible ? tw : 60).update(dt);
        H.to(visible ? th : 18).update(dt);
        A.to(visible ? 1f : 0f).update(dt);
        float a = A.get();
        if (a < 0.02f) return;

        Glass.begin(ctx);
        float w = W.get(), h = H.get();
        float x = (ctx.getScaledWindowWidth() - w) / 2f, y = 5;
        float old = Glass.alpha;
        Glass.alpha = Math.max(0, Math.min(1, a));
        Glass.shadow(x, y, w, h, h / 2f, 8, Col.a(0, 0.35f));
        Glass.round(x, y, w, h, Math.min(h / 2f, 14), Col.a(0x000000, 0.82f), Col.a(0x0A0A0C, 0.86f));
        Glass.rim(x, y, w, h, Math.min(h / 2f, 14), Col.a(0xFFFFFF, 0.22f), Col.a(0xFFFFFF, 0.06f));

        ctx.enableScissor((int) x + 1, (int) y, (int) (x + w) - 1, (int) (y + h));
        if (l != null) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(Glass.time * 5f);
            Glass.glow(x + 13, y + 11, 7 + 3 * pulse, Col.a(Col.RED, 0.5f));
            Glass.dot(x + 13, y + 11, 3.2f, Col.a(Col.RED, 1f));
            GFont.draw(GFont.fit(l.rec.opName, w - 110, 8, true), x + 22, y + 7, 8, Col.LABEL, true);
            float sc = l.analyzer.score();
            int col = Col.score(sc);
            String s = Math.round(sc) + "%";
            float sw = GFont.drawRight(s, x + w - 10, y + 7, 8, Col.a(col, 1f), true);
            GFont.drawRight(timer(l.ticks()), x + w - 16 - sw, y + 7, 7.5f, Col.LABEL3, false);
            if (showFlag) {
                Flag f = l.lastFlag;
                float k = Math.min(1f, (ms - l.lastFlagAt) / 250f);
                float yy = y + 22 + (1 - k) * 6;
                float cw = GFont.width(f.c.title, 7, true) + 10;
                Glass.round(x + 9, yy, cw, 12, 6, Col.a(f.c.color, 0.30f * k));
                GFont.draw(f.c.title, x + 14, yy + 2.2f, 7, Col.a(0xFFFFFF, k), true);
                GFont.draw(GFont.fit(f.d, w - cw - 26, 7, false), x + 14 + cw, yy + 2.2f, 7, Col.a(0xEBEBF5, 0.8f * k), false);
            }
        } else if (FightTracker.lastFinished != null) {
            var m = FightTracker.lastFinished;
            int col = Col.score(m.score);
            Glass.dot(x + 14, y + 13, 4, Col.a(col, 1f));
            GFont.draw("Fight vs " + GFont.fit(m.opName, w - 120, 8, true) + " analysed", x + 24, y + 8, 8, Col.LABEL, true);
            GFont.drawRight(Math.round(m.score) + "%", x + w - 12, y + 8, 8, Col.a(col, 1f), true);
            GFont.draw(Analyzer.verdict(m.score) + "  •  " + m.flags + " flags  •  press J to review", x + 24, y + 23, 7, Col.LABEL2, false);
        }
        ctx.disableScissor();
        Glass.alpha = old;
    }

    private static String timer(int ticks) {
        int s = ticks / 20;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
