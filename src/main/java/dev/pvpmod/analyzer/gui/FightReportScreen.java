package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.CheckType;
import dev.pvpmod.analyzer.Counter;
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
    private static final String[] TABS = {"Overview", "Graphs", "Replay", "Flags", "Counter"};

    private FightMeta meta;
    private FightRecord rec;
    private Analyzer an;
    /** Everyone in this fight (one entry for a 1v1), with their loaded recordings and analyses. */
    private final List<FightMeta> group;
    private final FightRecord[] recs;
    private final Analyzer[] ans;
    private int sel = 0;
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
    private final Spring counterScroll = new Spring(0f, 200f, 26f);
    private float counterContentH = 0;
    private long copiedAt = 0;
    private CounterLab lab;
    private boolean dragLab = false;
    private float labX, labY, labW, labH;

    // drag state
    private boolean dragView = false, dragTimeline = false;
    private float viewX, viewY, viewW, viewH, tlX, tlW, tlY = -100;

    public FightReportScreen(Screen parent, FightMeta meta) {
        this(parent, List.of(meta));
    }

    /** A report for a group fight: switch between opponents with the pills under the header. */
    public FightReportScreen(Screen parent, List<FightMeta> group) {
        super("Fight vs " + group.get(0).opName, parent);
        this.group = List.copyOf(group);
        this.meta = group.get(0);
        this.recs = new FightRecord[group.size()];
        this.ans = new Analyzer[group.size()];
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < this.group.size(); i++) {
                FightRecord r = FightStore.load(this.group.get(i).id);
                if (r == null) { if (i == 0) failed = true; continue; }
                Analyzer a = Analyzer.run(r);
                synchronized (this) { ans[i] = a; recs[i] = r; }
            }
        });
    }

    private boolean ready() {
        if (rec == null) {
            synchronized (this) {
                if (recs[sel] != null && ans[sel] != null) { rec = recs[sel]; an = ans[sel]; }
            }
        }
        return rec != null && an != null;
    }

    /** Show another opponent of a group fight. */
    private void select(int i) {
        if (i == sel) return;
        sel = i;
        meta = group.get(i);
        rec = null;
        an = null;
        view = null;
        lab = null;
        selected = null;
        playing = false;
        playhead = 0;
        ringFill.snap(0f);
        reveal.snap(0f);
        tabFade.snap(0f).to(1f);
        flagScroll.snap(0f);
        counterScroll.snap(0f);
    }

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
        dragLab = tab == 4 && lab != null && x >= labX && y >= labY && x < labX + labW && y < labY + labH;
        if (tab == 2 && ready()) {
            dragView = x >= viewX && y >= viewY && x < viewX + viewW && y < viewY + viewH;
            dragTimeline = x >= tlX - 6 && x < tlX + tlW + 6 && Math.abs(y - tlY) < 9 && !dragView;
        }
    }

    @Override
    protected void onMouseRelease(float x, float y) {
        dragLab = false;
        dragView = false;
        dragTimeline = false;
    }

    @Override
    protected void onDrag(float x, float y, float dx, float dy) {
        if (dragLab && lab != null) {
            lab.yaw += dx * 0.6f;
            lab.pitch = Math.max(5f, Math.min(80f, lab.pitch + dy * 0.6f));
        }
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
        float hx = x0 + 90;
        if (rec != null) {
            String myId = rec.meUuid == null || rec.meUuid.isEmpty() ? client.getGameProfile().id().toString() : rec.meUuid;
            Ui.head(Ui.skin(myId, rec.meName, rec.meTex, rec.meSig), hx, y0 + 11, 22);
            GFont.drawCentered("vs", hx + 33, y0 + 17, 7.5f, Col.LABEL3, true);
            hx += 44;
        }
        Ui.head(Ui.skin(meta.opUuid, meta.opName, meta.opTex, meta.opSig), hx, y0 + 11, 22);
        GFont.draw(GFont.fit(meta.opName, 150, 12, true), hx + 28, y0 + 11, 12, Col.LABEL, true);
        GFont.draw(Ui.date(meta.start) + "  \u2022  " + Ui.duration(meta.ticks), hx + 28, y0 + 25, 7, Col.LABEL3, false);

        boolean wide = pw >= 720;
        float segW = Math.min(375, pw - 24);
        float segX = wide ? x0 + pw - segW - 14 : x0 + (pw - segW) / 2;
        float segY = wide ? y0 + 12 : y0 + 40;
        setTab(segmented("tabs", TABS, tab, segX, segY, segW, 22));

        float cx = x0 + 12, cy = segY + 32, cw = pw - 24, ch = y0 + ph - 12 - cy;
        if (group.size() > 1) {
            float py = cy - 2;
            GFont.draw("OPPONENTS", x0 + 16, py + 6, 6.5f, Col.LABEL3, true);
            float px = x0 + 70;
            for (int i = 0; i < group.size(); i++) {
                FightMeta m = group.get(i);
                String label = m.opName + "  " + Math.round(m.score) + "%";
                float pwid = GFont.width(label, 7.5f, true) + 32;
                boolean on = i == sel;
                Spring hs = spring("pill" + i, 0f);
                hs.to(hover(px, py, pwid, 18) ? 1f : 0f);
                int col = Col.score(m.score);
                Glass.button(px, py, pwid, 18, 9, on ? col : 0x2C2C2E, on ? 0.45f : 0.3f, hs.get());
                Ui.head(Ui.skin(m.opUuid, m.opName, m.opTex, m.opSig), px + 4, py + 3, 12);
                GFont.draw(label, px + 22, py + 5, 7.5f, Col.LABEL, true);
                if (clicked(px, py, pwid, 18)) select(i);
                px += pwid + 6;
            }
            cy += 24;
            ch -= 24;
        }

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
            case 3 -> drawFlags(cx, cy, cw, ch);
            default -> drawCounter(cx, cy, cw, ch);
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
        GFont.drawCentered(pct, rcx, rcy - r * 0.42f, r * 0.55f, Col.LABEL, true);
        GFont.drawCentered("suspicion", rcx, rcy + r * 0.30f, 7, Col.LABEL3, false);
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
                if (th >= 30) {
                    GFont.draw(GFont.fit(stats[i][0], tw - 14, 6.5f, false), tx + 8, tyy + 5, 6.5f, Col.LABEL3, false);
                    GFont.draw(stats[i][1], tx + 8, tyy + th - 15, Math.min(11, th * 0.35f), Col.LABEL, true);
                } else {
                    float vw = GFont.drawRight(stats[i][1], tx + tw - 8, tyy + (th - 8) / 2f, 8, Col.LABEL, true);
                    GFont.draw(GFont.fit(stats[i][0], tw - vw - 20, 6.5f, false), tx + 8, tyy + (th - 6.5f) / 2f, 6.5f, Col.LABEL2, false);
                }
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
            if (rowH >= 25) {
                GFont.draw(c.title, rx + 33, yy + rowH / 2 - 8, 8.5f, Col.a(0xFFFFFF, dim), true);
                GFont.draw(n == 0 ? "clean" : n + (n == 1 ? " flag" : " flags"), rx + 33, yy + rowH / 2 + 2, 6.5f, Col.a(0xEBEBF5, 0.55f * dim), false);
            } else {
                float tw2 = GFont.draw(c.title, rx + 33, yy + rowH / 2 - 4.5f, 8, Col.a(0xFFFFFF, dim), true);
                if (n > 0) GFont.draw("×" + n, rx + 37 + tw2, yy + rowH / 2 - 3.5f, 6.5f, Col.a(0xEBEBF5, 0.55f), false);
            }
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
        if (second != null) {
            float lx = x + w - 12 - GFont.width("them   you", 6.5f, true) - 14;
            Glass.dot(lx, py + 4, 2.5f, Col.a(rgb, 1f));
            float tw0 = GFont.draw("them", lx + 5, py + 1, 6.5f, Col.LABEL2, true);
            Glass.dot(lx + tw0 + 12, py + 4, 2.5f, Col.a(Col.CYAN, 1f));
            GFont.draw("you", lx + tw0 + 17, py + 1, 6.5f, Col.LABEL2, true);
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
                Glass.rect(x - 0.4f, y, 0.8f, py + ph - y, Col.a(rgb, 0.12f));
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
        // the real map around the fight (optional)
        float mw = 78, mxp = viewX + viewW - mw - 8;
        if (view.hasWorld()) {
            if (button("worldmap", view.showWorld ? "Map: On" : "Map: Off", mxp, viewY + 8, mw, 18,
                view.showWorld ? Col.GREEN : 0x2C2C2E, view.showWorld ? 0.40f : 0.3f)) {
                view.showWorld = !view.showWorld;
                dev.pvpmod.config.PvpConfig.get().analyzerReplayWorld = view.showWorld;
                dev.pvpmod.config.PvpConfig.save();
            }
        } else {
            GFont.drawRight("no map saved for this fight", viewX + viewW - 10, viewY + 13, 6.5f, Col.LABEL3, false);
        }
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
            int lines = Math.min(2, wrapLines(f.d, w - 28, 7.5f, true).size());
            float ch = 36 + lines * 10.5f;
            if (yy + ch > y + h - 4) break;
            boolean sel = f == selected;
            Glass.round(x + 8, yy, w - 16, ch, 12, Col.a(f.c.color, sel ? 0.20f : 0.10f), Col.a(f.c.color, sel ? 0.10f : 0.04f));
            Glass.rim(x + 8, yy, w - 16, ch, 12, Col.a(f.c.color, sel ? 0.8f : 0.4f), Col.a(f.c.color, 0.15f));
            chip(f.c.title, x + 14, yy + 6, f.c.color);
            GFont.drawRight(Ui.time(f.t), x + w - 14, yy + 8, 7, Col.LABEL2, true);
            wrap(f.d, x + 14, yy + 21, w - 28, 7.5f, Col.LABEL, true, 2);
            Glass.round(x + 14, yy + ch - 10, w - 28, 3, 1.5f, Col.a(0xFFFFFF, 0.1f));
            Glass.round(x + 14, yy + ch - 10, Math.max(3, (w - 28) * f.sev), 3, 1.5f, Col.a(f.c.color, 1f));
            if (clicked(x + 8, yy, w - 16, ch)) { selected = f; playhead = Math.max(0, f.t - 12); playing = true; }
            yy += ch + 6;
        }
    }

    // ------------------------------------------------------------------ counter coach
    private static List<String> wrapLines(String text, float w, float size, boolean bold) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (GFont.width(test, size, bold) > w && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(test);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Draws wrapped text and returns its height. */
    private float para(String text, float x, float y, float w, float size, int color, boolean bold) {
        List<String> lines = wrapLines(text, w, size, bold);
        float lh = size + 3.5f;
        for (int i = 0; i < lines.size(); i++) GFont.draw(lines.get(i), x, y + i * lh, size, color, bold);
        return lines.size() * lh;
    }

    private float paraHeight(String text, float w, float size, boolean bold) {
        return wrapLines(text, w, size, bold).size() * (size + 3.5f);
    }

    private void drawCounter(float x, float y, float w, float h) {
        card(x, y, w, h);
        float pad = 14;
        GFont.draw("How to beat " + GFont.fit(rec.opName, w * 0.4f, 12, true), x + pad, y + 12, 12, Col.LABEL, true);
        GFont.draw("Legit ways to win against what they used, based on this fight", x + pad, y + 28, 7.5f, Col.LABEL2, false);
        boolean copied = System.currentTimeMillis() - copiedAt < 2000;
        float bw = 132;
        if (button("copyreport", copied ? "Copied!" : "Copy report for staff", x + w - bw - pad, y + 12, bw, 20,
            copied ? Col.GREEN : Col.INDIGO, 0.45f)) {
            client.keyboard.setClipboard(Counter.staffReport(rec, an));
            copiedAt = System.currentTimeMillis();
        }

        float top = y + 44, bottom = y + h - 8;
        List<CheckType> found = new ArrayList<>();
        for (CheckType c : CheckType.values()) if (an.flagCount(c) > 0) found.add(c);
        found.sort((a, b) -> Float.compare(an.checkScore(b) * b.weight, an.checkScore(a) * a.weight));
        if (lab == null) {
            lab = new CounterLab(rec, an);
            lab.setLesson(found.isEmpty() ? null : found.get(0));
        }

        // the 3D lab
        boolean side = w >= 520;
        labX = x + pad;
        labY = top;
        labW = side ? (w - pad * 2) * 0.56f : w - pad * 2;
        labH = side ? bottom - top : (bottom - top) * 0.52f;
        Glass.round(labX, labY, labW, labH, 16, Col.a(0x05060A, 0.6f), Col.a(0x0B0D14, 0.7f));
        if (hover(labX, labY, labW, labH)) {
            float sc = takeScroll();
            if (sc != 0) lab.zoom = Math.max(0.5f, Math.min(1.8f, lab.zoom * (float) Math.pow(0.9, sc)));
        }
        lab.dragging = dragLab;
        lab.draw(labX, labY, labW, labH, dt);
        Glass.rim(labX, labY, labW, labH, 16, Col.a(0xFFFFFF, 0.28f), Col.a(0xFFFFFF, 0.06f));
        String lt = lab.lesson == null ? "Your spacing" : lab.lesson.title;
        int lc = lab.lesson == null ? Col.CYAN : lab.lesson.color;
        float chipW = chip("3D COUNTER LAB", labX + 10, labY + 10, Col.CYAN);
        GFont.draw(lt, labX + 16 + chipW, labY + 11, 9, Col.a(lc, 1f), true);
        GFont.drawRight("drag to rotate \u2022 scroll to zoom", labX + labW - 10, labY + labH - 13, 6.5f, Col.LABEL3, false);

        float listX = side ? labX + labW + 10 : x + pad, listW = side ? x + w - pad - listX : w - pad * 2;
        if (!side) top = labY + labH + 8;
        float maxScroll = Math.max(0, counterContentH - (bottom - top));
        if (hover(listX, top, listW, bottom - top)) {
            float sc = takeScroll();
            if (sc != 0) counterScroll.to(Math.max(0, Math.min(maxScroll, counterScroll.target() - sc * 40)));
        }
        counterScroll.to(Math.max(0, Math.min(maxScroll, counterScroll.target()))).update(dt);

        Glass.ctx().enableScissor((int) listX - 2, (int) top, (int) (listX + listW) + 2, (int) bottom);
        float cy = top - counterScroll.get();
        float cw = listW, inner = cw - 28;
        float cardX = listX;
        if (found.isEmpty()) {
            String msg = "Nothing in this fight looked like cheating, so this one comes down to skill. Your fight breakdown is below.";
            float ch = 32 + paraHeight(msg, inner, 7.5f, false);
            Glass.round(cardX, cy, cw, ch, 12, Col.a(Col.GREEN, 0.14f), Col.a(Col.GREEN, 0.05f));
            Glass.rim(cardX, cy, cw, ch, 12, Col.a(Col.GREEN, 0.5f), Col.a(Col.GREEN, 0.15f));
            GFont.draw("Nothing to counter", cardX + 14, cy + 9, 10, Col.LABEL, true);
            para(msg, cardX + 14, cy + 25, inner, 7.5f, Col.LABEL2, false);
            cy += ch + 10;
        }
        for (CheckType c : found) {
            String mine = Counter.personal(c, rec, an);
            List<String> tips = Counter.tips(c);
            float ch = 30 + paraHeight(mine, inner, 8, true) + 16;
            for (String t : tips) ch += paraHeight(t, inner - 14, 7.5f, false) + 5;
            ch += 6;
            if (cy + ch > top - 4 && cy < bottom + 4) {
                boolean sel = lab.lesson == c;
                boolean hov = hover(cardX, Math.max(cy, top), cw, Math.min(ch, bottom - cy));
                Glass.round(cardX, cy, cw, ch, 14, Col.a(c.color, sel ? 0.22f : hov ? 0.16f : 0.10f), Col.a(c.color, sel ? 0.08f : 0.03f));
                Glass.rim(cardX, cy, cw, ch, 14, Col.a(c.color, sel ? 1f : 0.5f), Col.a(c.color, sel ? 0.35f : 0.12f));
                if (sel) GFont.draw("\u25B6 in 3D", cardX + 20 + GFont.width(c.title, 7, true) + 10, cy + 12, 6.5f, Col.a(c.color, 1f), true);
                if (my >= top && my < bottom && clicked(cardX, cy, cw, ch)) lab.setLesson(c);
                float tx = cardX + 14, ty = cy + 10;
                chip(c.title, tx, ty, c.color);
                GFont.drawRight(an.flagCount(c) + (an.flagCount(c) == 1 ? " flag" : " flags") + "  \u2022  " + Math.round(an.checkScore(c)) + "%",
                    cardX + cw - 14, ty + 2, 7, Col.LABEL2, true);
                ty += 20;
                ty += para(mine, tx, ty, inner, 8, Col.LABEL, true) + 4;
                GFont.draw("HOW TO BEAT IT", tx, ty, 6.5f, Col.LABEL3, true);
                ty += 12;
                for (int i = 0; i < tips.size(); i++) {
                    Glass.dot(tx + 4, ty + 4, 4.5f, Col.a(c.color, 0.9f));
                    GFont.drawCentered(String.valueOf(i + 1), tx + 4, ty + 1.2f, 6, 0xFFFFFFFF, true);
                    ty += para(tips.get(i), tx + 14, ty, inner - 14, 7.5f, Col.a(0xEBEBF5, 0.9f), false) + 5;
                }
            }
            cy += ch + 8;
        }
        // general breakdown
        List<String> gen = Counter.general(rec, an);
        float gh = 30;
        for (String g : gen) gh += paraHeight(g, inner, 7.5f, false) + 4;
        if (cy + gh > top - 4 && cy < bottom + 4) {
            if (my >= top && my < bottom && clicked(cardX, cy, cw, gh)) lab.setLesson(null);
            Glass.round(cardX, cy, cw, gh, 14, Col.a(0xFFFFFF, lab.lesson == null ? 0.12f : 0.07f), Col.a(0xFFFFFF, 0.03f));
            Glass.rim(cardX, cy, cw, gh, 14, Col.a(0xFFFFFF, 0.25f), Col.a(0xFFFFFF, 0.05f));
            GFont.draw("Your fight", cardX + 14, cy + 10, 10, Col.LABEL, true);
            float ty = cy + 26;
            for (String g : gen) ty += para(g, cardX + 14, ty, inner, 7.5f, Col.LABEL2, false) + 4;
        }
        cy += gh + 8;
        counterContentH = cy + counterScroll.get() - top;
        Glass.ctx().disableScissor();
        if (maxScroll > 0) {
            float trackH = bottom - top, thumb = Math.max(20, trackH * trackH / counterContentH);
            Glass.round(listX + listW + 3, top + (trackH - thumb) * (counterScroll.get() / maxScroll), 3, thumb, 1.5f, Col.a(0xFFFFFF, 0.3f));
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
