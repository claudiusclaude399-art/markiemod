package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.CheckType;
import dev.pvpmod.analyzer.FightEvent;
import dev.pvpmod.analyzer.FightRecord;
import dev.pvpmod.analyzer.FightRecord.FightMeta;
import dev.pvpmod.analyzer.FightStore;
import dev.pvpmod.analyzer.Flag;
import dev.pvpmod.analyzer.PState;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.GFont;
import dev.pvpmod.glass.Glass;
import dev.pvpmod.glass.GlassScreen;
import dev.pvpmod.glass.Spring;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Full report for one fight: overview, graphs, 3D replay and the list of flags. */
public class FightReportScreen extends GlassScreen {
    private static final String[] TABS = {"Overview", "Graphs", "Replay", "Flags"};

    private final FightMeta meta;
    private volatile FightRecord rec;
    private volatile Analyzer an;
    private volatile boolean failed = false;
    private ReplayView view;

    private int tab = 0;
    private final Spring tabFade = new Spring(1f, 160f, 20f);
    private final Spring ringFill = new Spring(0f, 40f, 11f);
    private final Spring reveal = new Spring(0f, 30f, 10f);
    private float playhead = 0;
    private boolean playing = false;
    private int speedIdx = 2;
    private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f};
    private Flag selected;
    private final Spring flagScroll = new Spring(0f, 200f, 26f);

    // drag state
    private boolean dragView = false, dragTimeline = false;
    private float viewX, viewY, viewW, viewH, tlX, tlW, tlY = -100;

    public FightReportScreen(Screen parent, FightMeta meta) {
        super("Fight vs " + meta.opName, parent);
        this.meta = meta;
        CompletableFuture.runAsync(() -> {
            FightRecord r = FightStore.load(meta.id);
            if (r == null) { failed = true; return; }
            Analyzer a = Analyzer.run(r);
            an = a;
            rec = r;
        });
    }

    private boolean ready() { return rec != null && an != null; }

    private void setTab(int t) {
        if (t == tab) return;
        tab = t;
        tabFade.snap(0f).to(1f);
        if (t == 1) reveal.snap(0f);
    }

    private void jumpTo(Flag f) {
        selected = f;
        playhead = Math.max(0, f.t - 12);
        playing = true;
        setTab(2);
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void onMousePress(float x, float y, int button) {
        if (tab == 2 && ready()) {
            dragView = x >= viewX && y >= viewY && x < viewX + viewW && y < viewY + viewH;
            dragTimeline = x >= tlX - 6 && x < tlX + tlW + 6 && Math.abs(y - tlY) < 9 && !dragView;
        }
    }

    @Override
    protected void onMouseRelease(float x, float y) {
        dragView = false;
        dragTimeline = false;
    }

    @Override
    protected void onDrag(float x, float y, float dx, float dy) {
        if (dragView && view != null && view.mode != ReplayView.EYES) {
            view.yaw += dx * 0.6f;
            view.pitch = Math.max(-5f, Math.min(89f, view.pitch + dy * 0.6f));
        }
    }

    @Override
    protected boolean onKey(int key) {
        if (!ready() || tab != 2) return false;
        boolean shift = GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS;
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> { togglePlay(); return true; }
            case GLFW.GLFW_KEY_LEFT -> { playing = false; playhead = Math.max(0, playhead - (shift ? 20 : 1)); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { playing = false; playhead = Math.min(rec.ticks() - 1, playhead + (shift ? 20 : 1)); return true; }
            case GLFW.GLFW_KEY_F -> { stepFlag(1); return true; }
            default -> { return false; }
        }
    }

    private void togglePlay() {
        if (!playing && playhead >= rec.ticks() - 1.01f) playhead = 0;
        playing = !playing;
    }

    private void stepFlag(int dir) {
        List<Flag> fl = sortedFlags();
        if (fl.isEmpty()) return;
        Flag best = null;
        for (Flag f : fl) {
            if (dir > 0 && f.t > playhead + 13 && (best == null || f.t < best.t)) best = f;
            if (dir < 0 && f.t < playhead + 11 && f != selected && (best == null || f.t > best.t)) best = f;
        }
        if (best == null) best = dir > 0 ? fl.get(0) : fl.get(fl.size() - 1);
        selected = best;
        playhead = Math.max(0, best.t - 12);
        playing = true;
    }

    private List<Flag> sortedFlags() {
        List<Flag> fl = new ArrayList<>(an.flags);
        fl.sort(Comparator.comparingInt(f -> f.t));
        return fl;
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void draw(DrawContext ctx) {
        float W = this.width, H = this.height;
        float x0 = 10, y0 = 8 + (1f - appear.get()) * 18f, pw = W - 20, ph = H - 16;
        Glass.panel(x0, y0, pw, ph, 22);

        if (backButton("Fights", x0 + 12, y0 + 12)) close();
        Ui.head(meta.opUuid, x0 + 90, y0 + 11, 22);
        GFont.draw(GFont.fit("vs " + meta.opName, 150, 12, true), x0 + 118, y0 + 11, 12, Col.LABEL, true);
        GFont.draw(Ui.date(meta.start) + "  •  " + Ui.duration(meta.ticks), x0 + 118, y0 + 25, 7, Col.LABEL3, false);

        boolean wide = pw >= 560;
        float segW = wide ? 300 : Math.min(300, pw - 24);
        float segX = wide ? x0 + pw - segW - 14 : x0 + (pw - segW) / 2;
        float segY = wide ? y0 + 12 : y0 + 40;
        setTab(segmented("tabs", TABS, tab, segX, segY, segW, 22));

        float cx = x0 + 12, cy = segY + 32, cw = pw - 24, ch = y0 + ph - 12 - cy;

        if (!ready()) {
            float mx = x0 + pw / 2, my = y0 + ph / 2;
            if (failed) {
                GFont.drawCentered("Could not load this fight", mx, my - 6, 11, Col.LABEL, true);
                return;
            }
            float a = Glass.time * 300;
            Glass.ring(mx, my, 14, 3, Col.a(0xFFFFFF, 0.12f));
            Glass.arc(mx, my, 14, 3, a, 90, Col.a(0xFFFFFF, 0.9f));
            GFont.drawCentered("Analysing fight…", mx, my + 24, 8, Col.LABEL2, false);
            return;
        }
        if (view == null) view = new ReplayView(rec, an);

        tabFade.update(dt);
        ringFill.to(1f).update(dt);
        reveal.to(1f).update(dt);
        if (playing) {
            playhead += dt * 20f * SPEEDS[speedIdx];
            if (playhead >= rec.ticks() - 1) { playhead = rec.ticks() - 1; playing = false; }
        }

        float f = tabFade.get();
        float old = Glass.alpha;
        Glass.alpha *= Math.max(0, Math.min(1, f));
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, (1f - f) * 10f);
        switch (tab) {
            case 0 -> drawOverview(cx, cy, cw, ch);
            case 1 -> drawGraphs(cx, cy, cw, ch);
            case 2 -> drawReplay(cx, cy, cw, ch);
            default -> drawFlags(cx, cy, cw, ch);
        }
        m.popMatrix();
        Glass.alpha = old;
    }

    // ------------------------------------------------------------------ overview
    private void card(float x, float y, float w, float h) {
        Glass.round(x, y, w, h, 16, Col.a(0xFFFFFF, 0.07f), Col.a(0xFFFFFF, 0.03f));
        Glass.rim(x, y, w, h, 16, Col.a(0xFFFFFF, 0.22f), Col.a(0xFFFFFF, 0.05f));
    }

    private void drawOverview(float x, float y, float w, float h) {
        float lw = Math.max(170, Math.min(250, w * 0.38f));
        float ringH = Math.min(h * 0.6f, 200);
        card(x, y, lw, ringH);
        float score = an.score();
        int col = Col.score(score);
        float r = Math.min(lw * 0.30f, ringH * 0.30f);
        float rcx = x + lw / 2, rcy = y + ringH * 0.42f;
        float fill = Spring.easeOutCubic(ringFill.get());
        Glass.glow(rcx, rcy, r * 2.0f, Col.a(col, 0.18f));
        Glass.ring(rcx, rcy, r, r * 0.20f, Col.a(0xFFFFFF, 0.10f));
        Glass.arc(rcx, rcy, r, r * 0.20f, 0, 360 * score / 100f * fill, Col.a(col, 1f));
        String pct = Math.round(score * fill) + "%";
        GFont.drawCentered(pct, rcx, rcy - r * 0.32f, r * 0.55f, Col.LABEL, true);
        GFont.drawCentered("suspicion", rcx, rcy + r * 0.22f, 7, Col.LABEL3, false);
        GFont.drawCentered(Analyzer.verdict(score), rcx, rcy + r + 12, 11, Col.a(col, 1f), true);
        int triggered = 0;
        for (CheckType c : CheckType.values()) if (an.flagCount(c) > 0) triggered++;
        GFont.drawCentered(an.flags.size() + " flags across " + triggered + (triggered == 1 ? " check" : " checks"), rcx, rcy + r + 27, 7.5f, Col.LABEL2, false);

        // stat tiles
        float ty = y + ringH + 8, th = (h - ringH - 8 - 6) / 3f;
        String[][] stats = {
            {"Max reach", an.reachCount == 0 ? "-" : String.format("%.2f", an.maxReach)},
            {"Avg reach", an.reachCount == 0 ? "-" : String.format("%.2f", an.reachSum / an.reachCount)},
            {"Peak CPS", String.format("%.0f", an.peakCps)},
            {"Hits given / taken", an.hitsGiven + " / " + an.hitsTaken},
            {"Totem pops (them / you)", an.popsOp + " / " + an.popsMe},
            {"Result", rec.result.equals("END") ? "Ended" : rec.result.equals("LEFT") ? "They left" : rec.result}
        };
        if (th >= 18) {
            float tw = (lw - 6) / 2f;
            for (int i = 0; i < stats.length; i++) {
                float tx = x + (i % 2) * (tw + 6), tyy = ty + (i / 2) * (th + 3);
                card(tx, tyy, tw, th);
                GFont.draw(GFont.fit(stats[i][0], tw - 14, 6.5f, false), tx + 8, tyy + 5, 6.5f, Col.LABEL3, false);
                GFont.draw(stats[i][1], tx + 8, tyy + th - 15, Math.min(11, th * 0.35f), Col.LABEL, true);
            }
        }

        // checks
        float rx = x + lw + 10, rw = w - lw - 10;
        card(rx, y, rw, h);
        GFont.draw("Checks", rx + 14, y + 12, 11, Col.LABEL, true);
        GFont.drawRight("score", rx + rw - 14, y + 14, 7, Col.LABEL3, false);
        List<CheckType> checks = new ArrayList<>(List.of(CheckType.values()));
        checks.sort((a, b) -> Float.compare(an.checkScore(b), an.checkScore(a)));
        float rowH = Math.min(30, (h - 36) / checks.size());
        for (int i = 0; i < checks.size(); i++) {
            CheckType c = checks.get(i);
            float yy = y + 32 + i * rowH;
            float s = an.checkScore(c);
            int n = an.flagCount(c);
            float dim = n == 0 ? 0.45f : 1f;
            boolean hov = hover(rx + 6, yy, rw - 12, rowH);
            if (hov) Glass.round(rx + 6, yy - 1, rw - 12, rowH, 8, Col.a(0xFFFFFF, 0.06f));
            Glass.dot(rx + 20, yy + rowH / 2 - 1, 7, Col.a(c.color, 0.9f * dim));
            GFont.drawCentered(c.letter, rx + 20, yy + rowH / 2 - 4.6f, 7, Col.a(0xFFFFFF, dim), true);
            GFont.draw(c.title, rx + 33, yy + rowH / 2 - 8, 8.5f, Col.a(0xFFFFFF, dim), true);
            GFont.draw(n == 0 ? "clean" : n + (n == 1 ? " flag" : " flags"), rx + 33, yy + rowH / 2 + 2, 6.5f, Col.a(0xEBEBF5, 0.55f * dim), false);
            float bx = rx + Math.max(120, rw * 0.42f), bw = rx + rw - 50 - bx;
            Spring bar = spring("bar" + c.name(), 0f, 90f, 14f);
            if (age > i * 0.05f) bar.to(s / 100f);
            Glass.round(bx, yy + rowH / 2 - 3, bw, 5, 2.5f, Col.a(0xFFFFFF, 0.08f));
            if (bar.get() > 0.005f) Glass.round(bx, yy + rowH / 2 - 3, Math.max(5, bw * bar.get()), 5, 2.5f, Col.a(c.color, 1f));
            GFont.drawRight(Math.round(s) + "%", rx + rw - 14, yy + rowH / 2 - 4, 8, Col.a(0xFFFFFF, dim), true);
            if (hov) {
                Glass.ctx().drawTooltip(GFont.text(c.description, false), (int) mx, (int) my);
                if (n > 0 && clicked(rx + 6, yy, rw - 12, rowH)) {
                    for (Flag fl : sortedFlags()) if (fl.c == c) { jumpTo(fl); break; }
                }
            }
        }
    }

    // ------------------------------------------------------------------ graphs
    private void drawGraphs(float x, float y, float w, float h) {
        int cols = w > 520 ? 3 : 2, rows = cols == 3 ? 2 : 3;
        float gw = (w - (cols - 1) * 8) / cols, gh = (h - (rows - 1) * 8) / rows;
        Object[][] g = {
            {"Reach", an.reach, Col.RED, 3.0f, "blocks", CheckType.REACH, 4.5f, true},
            {"Clicks per second", an.cps, Col.YELLOW, 20f, "CPS", CheckType.AUTOCLICKER, 0f, false},
            {"Aim error (lower = tighter lock)", an.aim, Col.ORANGE, Float.NaN, "°", CheckType.AIM, 60f, false},
            {"Speed", an.speed, Col.CYAN, 11.2f, "b/s", CheckType.SPEED, 0f, false},
            {"Health", an.opHp, Col.RED, Float.NaN, "hp", null, 0f, false},
            {"Suspicion over time", an.sus, Col.PURPLE, Float.NaN, "%", null, 100f, false}
        };
        for (int i = 0; i < g.length && i < cols * rows; i++) {
            float gx = x + (i % cols) * (gw + 8), gy = y + (i / cols) * (gh + 8);
            graph(gx, gy, gw, gh, (String) g[i][0], (Analyzer.Series) g[i][1], (int) g[i][2], (float) g[i][3],
                (String) g[i][4], (CheckType) g[i][5], (float) g[i][6], (boolean) g[i][7], i == 4 ? an.myHp : null);
        }
    }

    private void graph(float x, float y, float w, float h, String title, Analyzer.Series s, int rgb, float limit,
                       String unit, CheckType check, float fixedMax, boolean scatter, Analyzer.Series second) {
        card(x, y, w, h);
        GFont.draw(title, x + 12, y + 9, 8.5f, Col.LABEL, true);
        float peak = s.max();
        GFont.drawRight(Float.isNaN(peak) ? "-" : String.format("peak %.1f %s", peak, unit), x + w - 12, y + 10, 7, Col.LABEL3, false);
        float px = x + 30, py = y + 24, pw = w - 42, ph = h - 40;
        if (pw < 20 || ph < 10) return;
        int n = s.size();
        float max = fixedMax > 0 ? fixedMax : 1f;
        if (fixedMax <= 0) {
            if (!Float.isNaN(peak)) max = Math.max(max, peak * 1.15f);
            if (!Float.isNaN(limit)) max = Math.max(max, limit * 1.3f);
            if (second != null && !Float.isNaN(second.max())) max = Math.max(max, second.max() * 1.15f);
        }
        // grid
        for (int i = 0; i <= 3; i++) {
            float gy = py + ph * i / 3f;
            Glass.rect(px, gy, pw, 0.5f, Col.a(0xFFFFFF, 0.07f));
            GFont.drawRight(String.format(max >= 10 ? "%.0f" : "%.1f", max * (1 - i / 3f)), px - 4, gy - 3, 6, Col.LABEL3, false);
        }
        if (!Float.isNaN(limit) && limit < max) {
            float ly = py + ph * (1 - limit / max);
            Glass.dashed(px, ly, px + pw, ly, 1f, 3f, Col.a(Col.RED, 0.7f));
            GFont.drawRight("limit", px + pw, ly - 8, 6, Col.a(Col.RED, 0.9f), false);
        }
        float rev = Spring.easeOutCubic(reveal.get());
        float drawW = pw * rev;
        if (second != null) plot(second, n, px, py, pw, ph, drawW, max, Col.CYAN, false);
        plot(s, n, px, py, pw, ph, drawW, max, rgb, scatter);

        // flags
        if (check != null && n > 1) {
            for (Flag f : an.flags) {
                if (f.c != check) continue;
                float fx = px + pw * f.t / (n - 1f);
                if (fx > px + drawW) continue;
                float v = s.get(f.t);
                float fy = Float.isNaN(v) ? py + 4 : py + ph * (1 - Math.min(max, v) / max);
                Glass.glow(fx, fy, 9, Col.a(f.c.color, 0.5f));
                Glass.dot(fx, fy, 3.2f, 0xFFFFFFFF);
                Glass.dot(fx, fy, 2.3f, Col.a(f.c.color, 1f));
            }
        }
        // playhead
        if (n > 1) {
            float hx = px + pw * playhead / (n - 1f);
            Glass.rect(hx - 0.5f, py, 1f, ph, Col.a(0xFFFFFF, 0.35f));
        }
        // hover tooltip + click to replay
        if (hover(px, py, pw, ph) && n > 1) {
            int t = Math.max(0, Math.min(n - 1, Math.round((mx - px) / pw * (n - 1))));
            Glass.rect(mx - 0.5f, py, 1f, ph, Col.a(0xFFFFFF, 0.6f));
            float v = s.get(t);
            String tip = Ui.time(t) + "   " + (Float.isNaN(v) ? "-" : String.format("%.2f %s", v, unit));
            if (second != null) tip = Ui.time(t) + "   them " + fmt(s.get(t)) + "  you " + fmt(second.get(t));
            float tw = GFont.width(tip, 7, true) + 12;
            float tx = Math.min(mx + 6, x + w - tw - 4);
            Glass.round(tx, py + 2, tw, 14, 7, Col.a(0x2C2C2E, 0.92f));
            GFont.draw(tip, tx + 6, py + 5.5f, 7, Col.LABEL, true);
            if (clicked(px, py, pw, ph)) {
                playhead = t;
                playing = false;
                setTab(2);
            }
        }
        GFont.draw("0:00", px, py + ph + 4, 6, Col.LABEL3, false);
        GFont.drawRight(Ui.time(n), px + pw, py + ph + 4, 6, Col.LABEL3, false);
    }

    private static String fmt(float v) { return Float.isNaN(v) ? "-" : String.format("%.1f", v); }

    private void plot(Analyzer.Series s, int n, float px, float py, float pw, float ph, float drawW, float max, int rgb, boolean scatter) {
        if (n < 2) return;
        if (scatter) {
            for (int i = 0; i < n; i++) {
                float v = s.get(i);
                if (Float.isNaN(v)) continue;
                float x = px + pw * i / (n - 1f);
                if (x > px + drawW) break;
                float y = py + ph * (1 - Math.min(max, v) / max);
                Glass.rect(x - 0.5f, y, 1f, py + ph - y, Col.a(rgb, 0.25f));
                Glass.dot(x, y, 2.2f, Col.a(v > Analyzer.REACH_LIMIT + 0.12 ? Col.RED : 0xFFFFFF, 1f));
            }
            return;
        }
        int cols = Math.max(2, (int) (pw * Glass.scale() / 2));
        float prevX = 0, prevY = 0;
        boolean has = false;
        for (int c = 0; c < cols; c++) {
            int i0 = (int) ((long) c * n / cols), i1 = Math.max(i0 + 1, (int) ((long) (c + 1) * n / cols));
            float v = Float.NaN;
            for (int i = i0; i < i1 && i < n; i++) {
                float sv = s.get(i);
                if (!Float.isNaN(sv) && (Float.isNaN(v) || sv > v)) v = sv;
            }
            float x = px + pw * c / (cols - 1f);
            if (x > px + drawW) break;
            if (Float.isNaN(v)) { has = false; continue; }
            float y = py + ph * (1 - Math.min(max, Math.max(0, v)) / max);
            if (has) {
                Glass.rectGradient(prevX, Math.min(prevY, y), x - prevX + 0.01f, py + ph - Math.min(prevY, y), Col.a(rgb, 0.22f), Col.a(rgb, 0.0f));
                Glass.line(prevX, prevY, x, y, 1.4f, Col.a(rgb, 1f));
            }
            prevX = x;
            prevY = y;
            has = true;
        }
    }

    // ------------------------------------------------------------------ replay
    private void drawReplay(float x, float y, float w, float h) {
        float sideW = Math.min(210, w * 0.34f), tlH = 58;
        viewX = x; viewY = y; viewW = w - sideW - 8; viewH = h - tlH - 8;

        // viewport
        Glass.round(viewX, viewY, viewW, viewH, 16, Col.a(0x05060A, 0.55f), Col.a(0x0B0D14, 0.62f));
        if (hover(viewX, viewY, viewW, viewH)) {
            float sc = takeScroll();
            if (sc != 0) view.dist = Math.max(3, Math.min(40, view.dist * (float) Math.pow(0.88, sc)));
        }
        view.draw(playhead, viewX, viewY, viewW, viewH, dt, selected);
        Glass.rim(viewX, viewY, viewW, viewH, 16, Col.a(0xFFFFFF, 0.25f), Col.a(0xFFFFFF, 0.06f));
        int mode = segmented("cam", new String[]{"Orbit", "Their eyes", "Top"}, view.mode, viewX + 8, viewY + 8, Math.min(200, viewW - 16), 18);
        view.mode = mode;
        GFont.drawRight(view.mode == ReplayView.EYES ? "what they saw" : "drag to rotate • scroll to zoom", viewX + viewW - 10, viewY + viewH - 13, 6.5f, Col.LABEL3, false);

        drawSide(x + w - sideW, y, sideW, viewH);
        drawTimeline(x, y + h - tlH, w, tlH);
    }

    private void drawSide(float x, float y, float w, float h) {
        card(x, y, w, h);
        PState me = view.me(playhead), op = view.op(playhead);
        int t = Math.max(0, Math.min(rec.ticks() - 1, (int) playhead));
        GFont.draw("At " + Ui.time(playhead), x + 12, y + 10, 10, Col.LABEL, true);
        float yy = y + 28;
        String[][] rows = {
            {"Distance", String.format("%.2f m", Analyzer.boxDistance(op.x, op.eyeY(), op.z, me))},
            {"Their aim off you", String.format("%.0f°", Analyzer.aimError(op, me))},
            {"Their CPS", fmt(an.cps.get(t))},
            {"Their speed", fmt(an.speed.get(t)) + " b/s"},
            {"Health  them / you", Math.round(op.hp + op.ab) + " / " + Math.round(me.hp + me.ab)}
        };
        for (String[] r : rows) {
            GFont.draw(r[0], x + 12, yy, 7, Col.LABEL2, false);
            GFont.drawRight(r[1], x + w - 12, yy, 7, Col.LABEL, true);
            yy += 12;
        }
        yy += 4;
        Glass.rect(x + 12, yy, w - 24, 0.5f, Col.a(0xFFFFFF, 0.1f));
        yy += 8;

        // evidence: selected flag, or flags close to the playhead
        List<Flag> near = new ArrayList<>();
        if (selected != null && Math.abs(selected.t - playhead) < 60) near.add(selected);
        for (Flag f : sortedFlags()) if (f != selected && Math.abs(f.t - playhead) <= 20 && near.size() < 3) near.add(f);
        GFont.draw("EVIDENCE", x + 12, yy, 6.5f, Col.LABEL3, true);
        yy += 11;
        if (near.isEmpty()) {
            GFont.draw("Nothing flagged around here.", x + 12, yy, 7.5f, Col.LABEL2, false);
            GFont.draw("Use the arrows below to jump", x + 12, yy + 11, 7, Col.LABEL3, false);
            GFont.draw("to the next suspicious moment.", x + 12, yy + 21, 7, Col.LABEL3, false);
        }
        for (Flag f : near) {
            float ch = 58;
            if (yy + ch > y + h - 4) break;
            boolean sel = f == selected;
            Glass.round(x + 8, yy, w - 16, ch, 12, Col.a(f.c.color, sel ? 0.20f : 0.10f), Col.a(f.c.color, sel ? 0.10f : 0.04f));
            Glass.rim(x + 8, yy, w - 16, ch, 12, Col.a(f.c.color, sel ? 0.8f : 0.4f), Col.a(f.c.color, 0.15f));
            chip(f.c.title, x + 14, yy + 6, f.c.color);
            GFont.drawRight(Ui.time(f.t), x + w - 14, yy + 8, 7, Col.LABEL2, true);
            wrap(f.d, x + 14, yy + 22, w - 28, 7.5f, Col.LABEL, true, 2);
            Glass.round(x + 14, yy + ch - 10, w - 28, 3, 1.5f, Col.a(0xFFFFFF, 0.1f));
            Glass.round(x + 14, yy + ch - 10, Math.max(3, (w - 28) * f.sev), 3, 1.5f, Col.a(f.c.color, 1f));
            if (clicked(x + 8, yy, w - 16, ch)) { selected = f; playhead = Math.max(0, f.t - 12); playing = true; }
            yy += ch + 6;
        }
    }

    private void wrap(String text, float x, float y, float w, float size, int color, boolean bold, int maxLines) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (GFont.width(test, size, bold) > w && line.length() > 0) {
                GFont.draw(line.toString(), x, y + lines * (size + 3), size, color, bold);
                lines++;
                line = new StringBuilder(word);
                if (lines >= maxLines) return;
            } else line = new StringBuilder(test);
        }
        if (line.length() > 0 && lines < maxLines) GFont.draw(GFont.fit(line.toString(), w, size, bold), x, y + lines * (size + 3), size, color, bold);
    }

    private void drawTimeline(float x, float y, float w, float h) {
        card(x, y, w, h);
        int n = rec.ticks();
        // controls
        float bx = x + 10, by = y + 8;
        if (button("prev", "‹", bx, by, 22, 20)) { playing = false; stepFlag(-1); }
        Spring ps = spring("playbtn", 0f);
        ps.to(hover(bx + 26, by - 2, 24, 24) ? 1f : 0f);
        Glass.button(bx + 26, by - 2, 24, 24, 12, Col.BLUE, 0.55f, ps.get());
        float pcx = bx + 38, pcy = by + 10;
        if (playing) {
            Glass.round(pcx - 4, pcy - 4.5f, 2.6f, 9, 1.2f, 0xFFFFFFFF);
            Glass.round(pcx + 1.4f, pcy - 4.5f, 2.6f, 9, 1.2f, 0xFFFFFFFF);
        } else {
            for (float i = 0; i < 8; i += 0.5f) Glass.rect(pcx - 3 + i, pcy - 4.5f + i * 0.5625f, 0.6f, 9 - i * 1.125f, 0xFFFFFFFF);
        }
        if (clicked(bx + 26, by - 2, 24, 24)) togglePlay();
        if (button("next", "›", bx + 54, by, 22, 20)) { playing = false; stepFlag(1); }
        GFont.draw(Ui.time(playhead) + " / " + Ui.time(n - 1), bx + 84, by + 5, 8, Col.LABEL, true);
        String[] sp = {"¼×", "½×", "1×", "2×"};
        speedIdx = segmented("speed", sp, speedIdx, x + w - 150, by, 140, 18);
        GFont.drawRight("space play • ← → step • F next flag", x + w - 158, by + 6, 6.5f, Col.LABEL3, false);

        // track
        tlX = x + 14;
        tlW = w - 28;
        float ty = y + h - 18;
        tlY = ty;
        Glass.round(tlX, ty - 2, tlW, 4, 2, Col.a(0xFFFFFF, 0.12f));
        float prog = n > 1 ? playhead / (n - 1f) : 0;
        Glass.round(tlX, ty - 2, Math.max(4, tlW * prog), 4, 2, Col.a(Col.BLUE, 0.9f));
        if (rec.preroll > 0 && n > 1) {
            float pr = tlX + tlW * rec.preroll / (n - 1f);
            Glass.rect(pr, ty - 6, 0.7f, 12, Col.a(0xFFFFFF, 0.35f));
        }
        for (FightEvent e : rec.events) {
            if (n < 2) break;
            float ex = tlX + tlW * Math.min(n - 1, e.t) / (n - 1f);
            switch (e.k) {
                case HIT_ME -> Glass.rect(ex - 0.4f, ty + 3, 0.8f, 4, Col.a(0xFFFFFF, 0.5f));
                case HIT_OP -> Glass.rect(ex - 0.4f, ty + 3, 0.8f, 4, Col.a(Col.CYAN, 0.6f));
                case POP_OP, POP_ME -> Glass.dot(ex, ty + 6, 1.8f, Col.a(Col.YELLOW, 1f));
                case DEATH_OP, DEATH_ME -> Glass.dot(ex, ty, 3f, Col.a(e.k == FightEvent.Kind.DEATH_OP ? Col.GREEN : Col.RED, 1f));
                default -> {}
            }
        }
        Flag hovered = null;
        for (Flag f : an.flags) {
            if (n < 2) break;
            float fx = tlX + tlW * f.t / (n - 1f);
            float fh = 4 + 8 * f.sev;
            boolean sel = f == selected;
            Glass.round(fx - 1.2f, ty - 4 - fh, 2.4f, fh, 1.2f, Col.a(f.c.color, sel ? 1f : 0.85f));
            if (sel) Glass.glow(fx, ty - 4 - fh / 2, 8, Col.a(f.c.color, 0.6f));
            if (Math.abs(mx - fx) < 3 && my > ty - 16 && my < ty + 8) hovered = f;
        }
        // thumb
        float thx = tlX + tlW * prog;
        Spring th = spring("thumb", 0f);
        th.to(dragTimeline ? 1f : hover(thx - 8, ty - 8, 16, 16) ? 0.6f : 0f);
        float tr = 5 + 2 * th.get();
        Glass.shadow(thx - tr, ty - tr, tr * 2, tr * 2, tr, 4, Col.a(0, 0.4f));
        Glass.dot(thx, ty, tr, 0xFFFFFFFF);

        if (hovered != null) {
            String tip = hovered.c.title + " • " + Ui.time(hovered.t);
            float tw = GFont.width(tip, 7, true) + 12;
            float tx = Math.max(x + 4, Math.min(mx - tw / 2, x + w - tw - 4));
            Glass.round(tx, ty - 32, tw, 14, 7, Col.a(0x2C2C2E, 0.95f));
            GFont.draw(tip, tx + 6, ty - 28.5f, 7, Col.LABEL, true);
            if (clicked(tlX, ty - 16, tlW, 24)) { jumpTo(hovered); dragTimeline = false; }
        }
        if (dragTimeline && mouseDown && n > 1) {
            playhead = Math.max(0, Math.min(n - 1, (mx - tlX) / tlW * (n - 1)));
            playing = false;
        }
    }

    // ------------------------------------------------------------------ flags list
    private void drawFlags(float x, float y, float w, float h) {
        card(x, y, w, h);
        List<Flag> fl = sortedFlags();
        if (fl.isEmpty()) {
            Glass.glow(x + w / 2, y + h / 2 - 16, 40, Col.a(Col.GREEN, 0.25f));
            Glass.dot(x + w / 2, y + h / 2 - 16, 12, Col.a(Col.GREEN, 1f));
            Glass.line(x + w / 2 - 5, y + h / 2 - 16, x + w / 2 - 1, y + h / 2 - 12, 2f, 0xFFFFFFFF);
            Glass.line(x + w / 2 - 1, y + h / 2 - 12, x + w / 2 + 6, y + h / 2 - 20, 2f, 0xFFFFFFFF);
            GFont.drawCentered("No flags", x + w / 2, y + h / 2 + 4, 11, Col.LABEL, true);
            GFont.drawCentered("Nothing in this fight looked like cheating.", x + w / 2, y + h / 2 + 19, 7.5f, Col.LABEL2, false);
            return;
        }
        float rowH = 30, top = y + 8, bottom = y + h - 8;
        float content = fl.size() * rowH;
        float maxScroll = Math.max(0, content - (bottom - top));
        float sc = takeScroll();
        if (sc != 0) flagScroll.to(Math.max(0, Math.min(maxScroll, flagScroll.target() - sc * 36)));
        flagScroll.update(dt);
        Glass.ctx().enableScissor((int) x, (int) top, (int) (x + w), (int) bottom);
        for (int i = 0; i < fl.size(); i++) {
            Flag f = fl.get(i);
            float ry = top + i * rowH - flagScroll.get();
            if (ry + rowH < top || ry > bottom) continue;
            boolean hov = hover(x + 6, ry, w - 12, rowH - 2) && my >= top && my < bottom;
            if (hov) Glass.round(x + 6, ry, w - 12, rowH - 2, 10, Col.a(0xFFFFFF, 0.07f));
            GFont.draw(Ui.time(f.t), x + 16, ry + 10, 8, Col.LABEL2, true);
            float cw = chip(f.c.title, x + 66, ry + 8, f.c.color);
            GFont.draw(GFont.fit(f.d, w - 66 - cw - 110, 8, false), x + 72 + cw, ry + 10, 8, Col.LABEL, false);
            float bw = 60;
            Glass.round(x + w - 22 - bw, ry + 12, bw, 4, 2, Col.a(0xFFFFFF, 0.1f));
            Glass.round(x + w - 22 - bw, ry + 12, Math.max(4, bw * f.sev), 4, 2, Col.a(f.c.color, 1f));
            if (hov && clicked(x + 6, ry, w - 12, rowH - 2)) jumpTo(f);
        }
        Glass.ctx().disableScissor();
    }
}
