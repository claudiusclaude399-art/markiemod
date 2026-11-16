package dev.markiesdetector.analyzer.gui;

import dev.markiesdetector.analyzer.Analyzer;
import dev.markiesdetector.analyzer.Counter;
import dev.markiesdetector.analyzer.FightTracker;
import dev.markiesdetector.analyzer.Flag;
import dev.markiesdetector.config.PvpConfig;
import dev.markiesdetector.glass.Col;
import dev.markiesdetector.glass.GFont;
import dev.markiesdetector.glass.Glass;
import dev.markiesdetector.glass.Spring;
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

        java.util.List<FightTracker.Live> ls = new java.util.ArrayList<>(FightTracker.lives.values());
        FightTracker.Live l = null;   // the one with the newest flag
        for (FightTracker.Live c : ls) if (c.lastFlag != null && (l == null || c.lastFlagAt > l.lastFlagAt)) l = c;
        long ms = System.currentTimeMillis();
        boolean recording = !ls.isEmpty();
        boolean showFlag = l != null && ms - l.lastFlagAt < 6000;
        boolean showTip = showFlag && ms - l.lastFlagAt > 2000;
        boolean showResult = !recording && FightTracker.lastFinished != null && ms - FightTracker.lastFinishedAt < 6000;
        boolean visible = recording || showResult;
        int rows = Math.max(1, ls.size());

        float tw = showFlag ? 290 : showResult ? 230 : rows > 1 ? 190 : 168;
        float th = recording ? 22 + (rows - 1) * 15 + (showFlag ? 18 : 0) : showResult ? 40 : 22;
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
        Glass.shadow(x, y, w, h, Math.min(h / 2f, 14), 8, Col.a(0, 0.35f));
        Glass.round(x, y, w, h, Math.min(h / 2f, 14), Col.a(0x000000, 0.82f), Col.a(0x0A0A0C, 0.86f));
        Glass.rim(x, y, w, h, Math.min(h / 2f, 14), Col.a(0xFFFFFF, 0.22f), Col.a(0xFFFFFF, 0.06f));

        ctx.enableScissor((int) x + 1, (int) y, (int) (x + w) - 1, (int) (y + h));
        if (recording) {
            // one row per opponent: name, fight time, live suspicion
            float pulse = 0.5f + 0.5f * (float) Math.sin(Glass.time * 5f);
            for (int i = 0; i < ls.size(); i++) {
                FightTracker.Live r = ls.get(i);
                float ry = y + 7 + i * 15;
                if (i == 0) {
                    Glass.glow(x + 13, ry + 4, 7 + 3 * pulse, Col.a(Col.RED, 0.5f));
                    Glass.dot(x + 13, ry + 4, 3.2f, Col.a(Col.RED, 1f));
                } else {
                    Glass.dot(x + 13, ry + 4, 2.2f, Col.a(Col.RED, 0.8f));
                }
                GFont.draw(GFont.fit(r.rec.opName, w - 110, 8, true), x + 22, ry, 8, Col.LABEL, true);
                float sc = r.analyzer.score();
                float sw = GFont.drawRight(Math.round(sc) + "%", x + w - 10, ry, 8, Col.a(Col.score(sc), 1f), true);
                GFont.drawRight(timer(r.ticks()), x + w - 16 - sw, ry, 7.5f, Col.LABEL3, false);
            }
            if (showFlag) {
                // first what they did, then how to counter it
                Flag f = l.lastFlag;
                long age = ms - l.lastFlagAt - (showTip ? 2000 : 0);
                float k = Math.min(1f, age / 250f);
                float yy = y + 22 + (rows - 1) * 15 + (1 - k) * 6;
                String label = showTip ? "Counter" : f.c.title;
                int chipCol = showTip ? Col.GREEN : f.c.color;
                String text = (rows > 1 && !showTip ? l.rec.opName + ": " : "") + (showTip ? Counter.shortTip(f.c) : f.d);
                float cw = GFont.width(label, 7, true) + 10;
                Glass.round(x + 9, yy, cw, 12, 6, Col.a(chipCol, 0.30f * k));
                GFont.draw(label, x + 14, yy + 2.2f, 7, Col.a(0xFFFFFF, k), true);
                GFont.draw(GFont.fit(text, w - cw - 26, 7, false), x + 14 + cw, yy + 2.2f, 7, Col.a(0xEBEBF5, 0.8f * k), false);
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
