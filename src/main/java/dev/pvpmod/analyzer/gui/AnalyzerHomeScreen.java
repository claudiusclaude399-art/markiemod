package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.CheckType;
import dev.pvpmod.analyzer.FightRecord.FightMeta;
import dev.pvpmod.analyzer.FightStore;
import dev.pvpmod.analyzer.FightTracker;
import dev.pvpmod.config.PvpConfig;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.GFont;
import dev.pvpmod.glass.Glass;
import dev.pvpmod.glass.GlassScreen;
import dev.pvpmod.glass.Spring;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Util;

import java.util.List;

/** Home of the analyzer: live status and the list of recorded fights. */
public class AnalyzerHomeScreen extends GlassScreen {
    private List<FightMeta> fights;
    private final Spring scroll = new Spring(0f, 200f, 26f);
    private final Spring sheet = new Spring(0f, 190f, 22f);
    private boolean settings = false;
    private String confirmDelete = null;

    public AnalyzerHomeScreen(Screen parent) {
        super("Fight Analyzer", parent);
    }

    @Override
    protected void init() {
        fights = FightStore.list();
    }

    @Override
    protected boolean onKey(int key) {
        if (key == 256 && settings) { settings = false; PvpConfig.save(); return true; }
        return false;
    }

    @Override
    protected void draw(DrawContext ctx) {
        float W = this.width, H = this.height;
        float pw = Math.min(580, W - 24), ph = H - 20;
        float a = appear.get();
        float x0 = (W - pw) / 2f, y0 = 10 + (1f - a) * 18f;
        sheet.to(settings ? 1f : 0f).update(dt);
        fights = FightStore.list();
        modalOpen = settings;
        inModal = false;

        Glass.panel(x0, y0, pw, ph, 22);

        // header
        GFont.draw("Fight Analyzer", x0 + 18, y0 + 15, 17, Col.LABEL, true);
        GFont.draw("Records your 1v1s and shows exactly where the other player cheats", x0 + 18, y0 + 36, 7.5f, Col.LABEL2, false);
        boolean blocked = settings;
        if (button("done", "Done", x0 + pw - 68, y0 + 14, 52, 20, Col.BLUE, 0.55f) && !blocked) close();
        if (button("settings", "Settings", x0 + pw - 140, y0 + 14, 64, 20) && !blocked) settings = true;

        // live card
        float ly = y0 + 54, lh = 42;
        drawLive(x0 + 14, ly, pw - 28, lh);

        // list
        float listTop = ly + lh + 22, listBottom = y0 + ph - 12;
        GFont.draw("RECENT FIGHTS", x0 + 22, listTop - 13, 7, Col.LABEL3, true);
        GFont.drawRight(fights.size() + " saved", x0 + pw - 22, listTop - 13, 7, Col.LABEL3, false);
        float cardH = 50, gap = 8;
        float content = fights.size() * (cardH + gap);
        float maxScroll = Math.max(0, content - (listBottom - listTop));
        if (!blocked) {
            float sc = takeScroll();
            if (sc != 0) scroll.to(Math.max(0, Math.min(maxScroll, scroll.target() - sc * 40)));
        }
        scroll.to(Math.max(0, Math.min(maxScroll, scroll.target()))).update(dt);

        if (fights.isEmpty()) {
            float cy = (listTop + listBottom) / 2f - 20;
            Glass.ring(W / 2f, cy, 14, 2.5f, Col.a(0xFFFFFF, 0.25f));
            GFont.drawCentered("No fights yet", W / 2f, cy + 22, 10, Col.LABEL, true);
            GFont.drawCentered("Hit a player (or get hit) and a recording starts automatically.", W / 2f, cy + 36, 7.5f, Col.LABEL2, false);
            if (button("demo", "Try a demo fight", W / 2f - 60, cy + 54, 120, 20, Col.INDIGO, 0.45f)) {
                go(new FightReportScreen(this, dev.pvpmod.analyzer.DemoFight.saveDemo()));
            }
        } else {
            ctx.enableScissor((int) x0 + 2, (int) listTop - 2, (int) (x0 + pw) - 2, (int) listBottom);
            for (int i = 0; i < fights.size(); i++) {
                float cy = listTop + i * (cardH + gap) - scroll.get();
                if (cy > listBottom || cy + cardH < listTop - 4) continue;
                Spring enter = spring("enter" + i, 0f, 150f, 18f);
                if (age > i * 0.04f) enter.to(1f);
                float e = enter.get();
                float ox = (1f - e) * 30f;
                float old = Glass.alpha;
                Glass.alpha *= Math.max(0, Math.min(1, e));
                drawCard(fights.get(i), x0 + 14 + ox, cy, pw - 28, cardH, !blocked && my >= listTop && my < listBottom);
                Glass.alpha = old;
            }
            ctx.disableScissor();
            if (maxScroll > 0) {
                float trackH = listBottom - listTop, thumb = Math.max(20, trackH * trackH / (content));
                float ty = listTop + (trackH - thumb) * (scroll.get() / maxScroll);
                Glass.round(x0 + pw - 7, ty, 3, thumb, 1.5f, Col.a(0xFFFFFF, 0.3f));
            }
        }

        if (sheet.get() > 0.01f) {
            inModal = true;
            drawSettings(W, H);
            inModal = false;
        }
    }

    private void drawLive(float x, float y, float w, float h) {
        FightTracker.Live l = FightTracker.live;
        boolean on = PvpConfig.get().analyzerEnabled;
        Glass.round(x, y, w, h, 14, Col.a(0xFFFFFF, 0.07f), Col.a(0xFFFFFF, 0.03f));
        Glass.rim(x, y, w, h, 14, Col.a(0xFFFFFF, 0.22f), Col.a(0xFFFFFF, 0.05f));
        float pulse = 0.5f + 0.5f * (float) Math.sin(Glass.time * 5f);
        if (l != null) {
            Glass.glow(x + 18, y + h / 2f, 12 + pulse * 4, Col.a(Col.RED, 0.45f));
            Glass.dot(x + 18, y + h / 2f, 4.5f, Col.a(Col.RED, 1f));
            GFont.draw("Recording vs " + l.rec.opName, x + 32, y + 9, 10, Col.LABEL, true);
            float sc = l.analyzer.score();
            String sub = Ui.time(l.ticks()) + "  •  " + l.analyzer.flags.size() + " flags  •  " + l.analyzer.hitsGiven + " hits given / " + l.analyzer.hitsTaken + " taken";
            GFont.draw(sub, x + 32, y + 24, 7.5f, Col.LABEL2, false);
            float bw = 90;
            GFont.drawRight(Math.round(sc) + "%", x + w - 14, y + 8, 12, Col.a(Col.score(sc), 1f), true);
            Glass.round(x + w - 14 - bw, y + 27, bw, 4, 2, Col.a(0xFFFFFF, 0.12f));
            Glass.round(x + w - 14 - bw, y + 27, bw * sc / 100f, 4, 2, Col.a(Col.score(sc), 1f));
            if (button("stop", "Stop", x + w - 160, y + 11, 44, 20, Col.RED, 0.35f) && !settings) FightTracker.end("END");
        } else {
            int dotCol = on ? Col.GREEN : Col.GRAY;
            Glass.glow(x + 18, y + h / 2f, 11, Col.a(dotCol, 0.35f));
            Glass.dot(x + 18, y + h / 2f, 4, Col.a(dotCol, 1f));
            GFont.draw(on ? "Ready" : "Analyzer is off", x + 32, y + 9, 10, Col.LABEL, true);
            String sub = on ? (FightTracker.forcedTarget != null ? "Waiting for a fight with " + FightTracker.forcedTarget
                : "Waiting for a fight. Recording starts on the first hit.") : "Turn it on in Settings to record fights.";
            GFont.draw(sub, x + 32, y + 24, 7.5f, Col.LABEL2, false);
        }
    }

    private void drawCard(FightMeta m, float x, float y, float w, float h, boolean interactive) {
        boolean hov = interactive && hover(x, y, w, h);
        Spring hs = spring("card" + m.id, 0f);
        hs.to(hov ? 1f : 0f);
        float k = hs.get();
        Glass.button(x - k, y - k * 0.5f, w + 2 * k, h + k, 15, 0x1C1C1E, 0.30f, k * 0.6f);

        Ui.head(m.opUuid, x + 12, y + (h - 26) / 2f, 26);
        float tx = x + 48;
        GFont.draw(GFont.fit(m.opName, w * 0.35f, 10.5f, true), tx, y + 9, 10.5f, Col.LABEL, true);
        String res = m.result == null ? "END" : m.result;
        int resCol = res.equals("WIN") ? Col.GREEN : res.equals("LOSS") ? Col.RED : Col.GRAY;
        float nameW = GFont.width(GFont.fit(m.opName, w * 0.35f, 10.5f, true), 10.5f, true);
        chip(res.equals("END") ? "ENDED" : res, tx + nameW + 6, y + 9, resCol);
        String sub = Ui.date(m.start) + "  •  " + Ui.duration(m.ticks) + (m.server == null || m.server.isEmpty() ? "" : "  •  " + m.server);
        GFont.draw(GFont.fit(sub, w * 0.45f, 7.5f, false), tx, y + 25, 7.5f, Col.LABEL3, false);
        float cx = tx;
        for (String t : m.top) {
            try {
                CheckType c = CheckType.valueOf(t);
                cx += chip(c.title, cx, y + 35, c.color) + 4;
            } catch (IllegalArgumentException ignored) {
            }
        }

        int col = Col.score(m.score);
        float rx = x + w - 14;
        GFont.drawRight(Math.round(m.score) + "%", rx, y + 9, 15, Col.a(col, 1f), true);
        GFont.drawRight(Analyzer.verdict(m.score), rx, y + 30, 7.5f, Col.LABEL2, false);
        Ui.sparkline(m.spark, rx - 150, y + 12, 70, 22, col);

        if (hov) {
            boolean confirm = m.id.equals(confirmDelete);
            float bx = rx - 236, by = y + (h - 18) / 2f;
            if (button("del" + m.id, confirm ? "Sure?" : "Delete", bx, by, 52, 18, Col.RED, confirm ? 0.6f : 0.25f)) {
                if (confirm) { FightStore.delete(m.id); confirmDelete = null; }
                else confirmDelete = m.id;
                return;
            }
        }
        if (interactive && clicked(x, y, w, h)) go(new FightReportScreen(this, m));
    }

    private void drawSettings(float W, float H) {
        float k = sheet.get();
        Glass.rect(0, 0, W, H, Col.a(0x000000, 0.35f * Math.min(1, k)));
        float sw = Math.min(330, W - 30), sh = 250;
        float sx = (W - sw) / 2f, sy = H - sh - 14 + (1f - k) * (sh + 30);
        Glass.panel(sx, sy, sw, sh, 24, 0x1C1C1E, 1.1f);
        GFont.draw("Settings", sx + 18, sy + 16, 14, Col.LABEL, true);
        if (button("sheetdone", "Done", sx + sw - 66, sy + 14, 50, 20, Col.BLUE, 0.55f)) {
            settings = false;
            PvpConfig.save();
        }
        PvpConfig c = PvpConfig.get();
        float ry = sy + 46, rh = 26;
        String[] labels = {"Analyze my fights", "Live island at top of screen", "Save fights to disk", "Summary in chat after a fight"};
        for (int i = 0; i < labels.length; i++) {
            float y = ry + i * rh;
            GFont.draw(labels[i], sx + 18, y + 5, 8.5f, Col.LABEL, false);
            boolean v = switch (i) { case 0 -> c.analyzerEnabled; case 1 -> c.analyzerHud; case 2 -> c.analyzerSave; default -> c.analyzerChat; };
            if (toggle("t" + i, sx + sw - 48, y + 1, v)) {
                switch (i) {
                    case 0 -> c.analyzerEnabled = !v;
                    case 1 -> c.analyzerHud = !v;
                    case 2 -> c.analyzerSave = !v;
                    default -> c.analyzerChat = !v;
                }
            }
            if (i < labels.length - 1) Glass.rect(sx + 18, y + rh - 3, sw - 36, 0.5f, Col.a(0xFFFFFF, 0.08f));
        }
        float y = ry + labels.length * rh + 4;
        String sensName = c.analyzerSensitivity < 0.92f ? "Strict" : c.analyzerSensitivity > 1.12f ? "Relaxed" : "Normal";
        GFont.draw("Sensitivity", sx + 18, y, 8.5f, Col.LABEL, false);
        GFont.drawRight(sensName, sx + sw - 18, y, 8.5f, Col.LABEL2, false);
        float v = slider("sens", sx + 22, y + 17, sw - 44, (c.analyzerSensitivity - 0.8f) / 0.5f);
        c.analyzerSensitivity = 0.8f + Math.round(v * 10) / 10f * 0.5f;
        y += 30;
        GFont.draw("End fight after no hits for", sx + 18, y, 8.5f, Col.LABEL, false);
        GFont.drawRight(c.analyzerTimeout + " s", sx + sw - 18, y, 8.5f, Col.LABEL2, false);
        float tv = slider("timeout", sx + 22, y + 17, sw - 44, (c.analyzerTimeout - 5) / 55f);
        c.analyzerTimeout = 5 + Math.round(tv * 55);
        y += 30;
        if (button("folder", "Open fights folder", sx + 18, y, sw - 36, 20)) {
            try {
                java.nio.file.Files.createDirectories(FightStore.dir());
                Util.getOperatingSystem().open(FightStore.dir());
            } catch (Exception ignored) {
            }
        }
        consumeClick();   // the sheet is modal
    }

    @Override
    protected void onClosed() {
        PvpConfig.save();
    }
}
