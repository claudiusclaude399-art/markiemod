package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.CheckType;
import dev.pvpmod.analyzer.FightEvent;
import dev.pvpmod.analyzer.FightRecord;
import dev.pvpmod.analyzer.Flag;
import dev.pvpmod.analyzer.Frame;
import dev.pvpmod.analyzer.PState;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.Glass;
import dev.pvpmod.glass.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.SkinTextures;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3D Counter Lab: a holographic arena where your opponent (their skin) and a ghost of you (your skin) act out
 * how to beat each cheat, on top of data from your real fight (where you stood, where they hit you, their CPS...).
 * The opponent always stands at the centre of the arena.
 */
final class CounterLab extends Scene3D {
    private final FightRecord rec;
    private final Analyzer an;
    CheckType lesson;   // null = general spacing lesson
    float yaw = 30f, pitch = 30f, zoom = 1f;
    boolean dragging = false;
    private final Spring swoop = new Spring(0f, 60f, 11f);
    private float time = 0f;

    // fight data, in the opponent's frame (they stand at the origin facing +z)
    private final List<double[]> myPositions = new ArrayList<>();
    private final List<double[]> noLookSpots = new ArrayList<>();
    private final float[] cpsBars = new float[48];
    private double reachEdge = 3.6, peakSpeed = 0;
    // speed lesson state
    private double runAngle = 0;

    private static final int GHOST = 0xC0A8E6FF;     // ghost tint: translucent, cool blue

    CounterLab(FightRecord rec, Analyzer an) {
        this.rec = rec;
        this.an = an;
        int n = rec.frames.size();
        int step = Math.max(1, n / 600);
        for (int i = 0; i < n; i += step) {
            Frame f = rec.frames.get(i);
            if (f.op == null || f.op.has(PState.ABSENT) || f.me == null) continue;
            myPositions.add(rel(f.op, f.me));
        }
        for (FightEvent e : rec.events) {
            if (e.k != FightEvent.Kind.HIT_ME || e.t < 0 || e.t >= n) continue;
            Frame f = rec.frames.get(e.t);
            if (f.op == null || f.me == null) continue;
            if (Analyzer.aimError(f.op, f.me) > 60) noLookSpots.add(rel(f.op, f.me));
        }
        for (int b = 0; b < cpsBars.length; b++) {
            int i0 = b * n / cpsBars.length, i1 = Math.max(i0 + 1, (b + 1) * n / cpsBars.length);
            float m = 0;
            for (int i = i0; i < i1; i++) { float v = an.cps.get(i); if (!Float.isNaN(v)) m = Math.max(m, v); }
            cpsBars[b] = m;
        }
        for (Flag f : an.flags) if (f.c == CheckType.REACH) reachEdge = Math.max(reachEdge, f.v + 0.1);
        float ps = an.speed.max();
        peakSpeed = Float.isNaN(ps) ? 0 : ps;
    }

    /** Position of {@code me} relative to {@code op}, rotated so the opponent faces +z. */
    private static double[] rel(PState op, PState me) {
        double dx = me.x - op.x, dz = me.z - op.z;
        double a = Math.toRadians(op.yw);
        double c = Math.cos(a), s = Math.sin(a);
        // inverse of the yaw rotation used for models
        return new double[]{dx * c + dz * s, -dx * s + dz * c};
    }

    void setLesson(CheckType c) {
        if (c == lesson) return;
        lesson = c;
        swoop.snap(1f).to(0f);
        time = 0;
    }

    // ------------------------------------------------------------------ helpers
    private static PState pose(double x, double y, double z, double yaw, double pitch) {
        PState p = new PState();
        p.x = x; p.y = y; p.z = z; p.yw = (float) yaw; p.pt = (float) pitch;
        p.h = 1.8f; p.w = 0.6f; p.e = 1.62f;
        return p;
    }

    private static double yawTo(double fx, double fz, double tx, double tz) {
        return Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /** A floor point {@code r} blocks to the camera's right (negative = left), so labels never stack up. */
    private double[] side(double r, double y) { return new double[]{rx * r, y, rz * r}; }

    private void sideLabel(String text, double r, double y, int rgb, float a) {
        double[] p = side(r, y);
        label(text, p[0], p[1], p[2], rgb, a);
    }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** 0..1 progress of t through [a, b]. */
    private static double seg(double t, double a, double b) { return smooth((t - a) / (b - a)); }

    private static float swing(double t, double a, double b) { return t >= a && t <= b ? (float) ((t - a) / (b - a)) : -1f; }

    private SkinTextures opSkin() { return Ui.skin(rec.opUuid, rec.opName, rec.opTex, rec.opSig); }

    private SkinTextures mySkin() {
        String id = rec.meUuid == null || rec.meUuid.isEmpty() ? MinecraftClient.getInstance().getGameProfile().id().toString() : rec.meUuid;
        return Ui.skin(id, rec.meName, rec.meTex, rec.meSig);
    }

    private void ghost(double x, double z, double y, boolean moving, float swing) {
        PState g = pose(x, y, z, yawTo(x, z, 0, 0), 5);
        player(g, mySkin(), GHOST, time * 9, moving ? 1 : 0, swing);
    }

    private void opponent(double yaw, float swing) {
        player(pose(0, 0, 0, yaw, 8), opSkin(), 0xFFFFFFFF, 0, 0, swing);
    }

    private void hitLine(double x1, double y1, double z1, double x2, double y2, double z2, int rgb, float a) {
        line3(x1, y1, z1, x2, y2, z2, 2.2f, Col.a(rgb, 0.9f * a));
        float[] p = project(x2, y2, z2);
        if (p != null) Glass.glow(p[0], p[1], 14 * a, Col.a(rgb, 0.6f * a));
    }

    // ------------------------------------------------------------------ frame
    void draw(float x, float y, float w, float h, float dt) {
        time += dt;
        if (!dragging) yaw += dt * 7f;
        swoop.update(dt);
        float sw = swoop.get();
        float dist = (lesson == CheckType.FLIGHT || lesson == CheckType.SPEED || lesson == CheckType.CRYSTAL_AURA ? 7.8f : 6.4f) * zoom + sw * 4f;
        orbit(0, lesson == CheckType.FLIGHT ? 1.4 : 0.8, 0, yaw - sw * 70f, pitch + sw * 10f, dist, 50, x, y, w, h);

        Glass.ctx().enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
        float old = Glass.alpha;
        Glass.alpha *= (1f - sw * 0.8f);
        holoFloor();
        if (lesson == null) general();
        else switch (lesson) {
            case REACH -> reach();
            case KILLAURA -> killaura();
            case AIM -> aim();
            case AUTOCLICKER -> clicker();
            case VELOCITY -> velocity();
            case SPEED -> speed(dt);
            case FLIGHT -> flight();
            case AUTO_TOTEM -> totem();
            case CRYSTAL_AURA -> crystals();
            case WALL_HIT -> walls();
        }
        Glass.alpha = old;
        Glass.ctx().disableScissor();
    }

    private void holoFloor() {
        for (int r = 1; r <= 7; r++) circle(0, 0, 0, r, 0.7f, Col.a(Col.CYAN, 0.10f - r * 0.008f));
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            line3(Math.sin(a) * 0.6, 0, Math.cos(a) * 0.6, Math.sin(a) * 7, 0, Math.cos(a) * 7, 0.6f, Col.a(Col.CYAN, 0.06f));
        }
        // scanning ring
        double sr = (time * 1.6) % 7;
        circle(0, 0.01, 0, sr, 1.2f, Col.a(Col.CYAN, 0.25f * (1 - (float) sr / 7)));
        float[] c = project(0, 0, 0);
        if (c != null) Glass.glow(c[0], c[1], scaleAt(0, 0, 0, 1.6), Col.a(Col.RED, 0.25f));
    }

    private void heatDots(double r0, double r1, int rgb) {
        for (double[] p : myPositions) {
            double d = Math.sqrt(p[0] * p[0] + p[1] * p[1]);
            int col = d < r0 ? Col.GREEN : d <= r1 ? rgb : Col.GRAY;
            dot3(p[0], 0.02, p[1], 0.05, Col.a(col, 0.55f), false);
        }
    }

    // ------------------------------------------------------------------ lessons
    private void general() {
        double edge = 3.6;
        ring(0, 0.005, 0, 0, 3.0, Col.a(Col.GREEN, 0.14f));
        ring(0, 0.005, 0, 3.0, edge, Col.a(Col.YELLOW, 0.14f));
        heatDots(3.0, edge, Col.YELLOW);
        double t = time % 6;
        double a = t / 6 * Math.PI * 2;
        double gx = Math.sin(a) * 2.4, gz = Math.cos(a) * 2.4;
        opponent(yawTo(0, 0, gx, gz), -1);
        ghost(gx, gz, 0, true, swing(t % 1.5, 0.6, 0.9));
        flush();
        sideLabel("Close: under 3 blocks", -1.6, 0.1, Col.GREEN, 1);
        sideLabel("Edge of reach", 3.3, 0.1, Col.YELLOW, 1);
        sideLabel("Each dot = where you stood", -2.6, 2.4, Col.CYAN, 1);
    }

    private void reach() {
        double R = reachEdge;
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4);
        ring(0, 0.005, 0, 0, 2.0, Col.a(Col.GREEN, 0.18f));
        ring(0, 0.005, 0, 2.0, 3.0, Col.a(0xFFFFFF, 0.05f));
        ring(0, 0.005, 0, 3.0, R, Col.a(Col.RED, 0.16f + 0.12f * pulse));
        circle(0, 0.01, 0, R, 1.5f, Col.a(Col.RED, 0.8f));
        circle(0, 0.01, 0, 2.0, 1.2f, Col.a(Col.GREEN, 0.8f));
        heatDots(3.0, R, Col.RED);

        double t = time % 6;
        double r = t < 1.4 ? 4.8 - 3.2 * seg(t, 0, 1.4) : t < 2.2 ? 1.6 : t < 3.4 ? 1.6 + 3.2 * seg(t, 2.2, 3.4) : 4.8;
        double ang = t > 3.4 ? Math.toRadians(25 * Math.sin((t - 3.4) * 1.6)) : 0;
        double gx = Math.sin(ang) * r, gz = Math.cos(ang) * r;
        boolean inRed = r > 3.0 && r < R;
        opponent(yawTo(0, 0, gx, gz), inRed ? (float) ((time * 3) % 1) : -1);
        ghost(gx, gz, 0, t < 3.4 && (t < 1.4 || t > 2.2), swing(t, 1.6, 2.0));
        flush();
        if (inRed) {
            hitLine(0, 1.62, 0, gx * 0.92, 1.1, gz * 0.92, Col.RED, 1);
            label("Free hit for them", gx, 2.3, gz, Col.RED, 1);
        }
        if (t >= 1.6 && t <= 2.0) label("Your hit", gx * 0.5, 2.1, gz * 0.5, Col.GREEN, 1);
        sideLabel("Inside 2: safe", -1.0, 0.1, Col.GREEN, 1);
        sideLabel(String.format("3 to %.1f: their free hits", R), -(3 + R) / 2 - 0.6, 0.1, Col.RED, 1);
        sideLabel("Out of range", R + 1.4, 0.1, 0xFFFFFF, 0.8f);
    }

    private void killaura() {
        double R = 3.6;
        ring(0, 0.005, 0, 0, R, Col.a(Col.RED, 0.10f));
        circle(0, 0.01, 0, R, 1.5f, Col.a(Col.RED, 0.8f));
        double beam = time * 2.6;
        for (int i = 0; i < 10; i++) {
            double a = beam - i * 0.06;
            line3(0, 0.03, 0, Math.sin(a) * R, 0.03, Math.cos(a) * R, 2.2f, Col.a(Col.RED, 0.5f * (1 - i / 10f)));
        }
        for (double[] p : noLookSpots) {
            line3(p[0] - 0.18, 0.02, p[1] - 0.18, p[0] + 0.18, 0.02, p[1] + 0.18, 2f, Col.a(Col.RED, 0.9f));
            line3(p[0] - 0.18, 0.02, p[1] + 0.18, p[0] + 0.18, 0.02, p[1] - 0.18, 2f, Col.a(Col.RED, 0.9f));
        }
        double t = time % 6;
        double r = t < 2 ? 4.3 : t < 2.5 ? 4.3 - 1.9 * seg(t, 2, 2.5) : t < 2.8 ? 2.4 : t < 3.5 ? 2.4 + 1.9 * seg(t, 2.8, 3.5) : 4.3;
        double gx = 0, gz = r;
        opponent(Math.toDegrees(beam) % 360, (float) ((time * 2.5) % 1));
        ghost(gx, gz, 0, (t > 2 && t < 2.5) || (t > 2.8 && t < 3.5), swing(t, 2.5, 2.8));
        boolean shieldUp = !(t > 2.4 && t < 2.9);
        if (shieldUp) {
            // shield in front of the ghost, facing the opponent
            double sz = r - 0.45;
            quad(new double[]{-0.62, 1.6, sz}, new double[]{0.22, 1.6, sz}, new double[]{-0.62, 0.5, sz}, Col.a(0x7A5230, 0.95f), false);
            quad(new double[]{-0.62, 1.6, sz - 0.01}, new double[]{0.22, 1.6, sz - 0.01}, new double[]{-0.62, 0.5, sz - 0.01}, Col.a(0x5C3D22, 0.95f), false);
        }
        flush();
        if (shieldUp && r < R + 0.8) {
            float[] p = project(-0.07, 1.1, r - 0.5);
            if (p != null && ((time * 2.5) % 1) < 0.3) Glass.glow(p[0], p[1], 16, Col.a(Col.YELLOW, 0.8f));
        }
        if (r < R) hitLine(0, 1.62, 0, 0, 1.0, r - 0.3, Col.RED, 0.7f);
        sideLabel("Aura hits all around them", -R - 0.4, 0.1, Col.RED, 1);
        if (!noLookSpots.isEmpty()) sideLabel("X = hit without looking", R + 0.9, 0.1, Col.RED, 0.9f);
        label(shieldUp ? "Shield up, just outside" : "Short burst in", 0, 2.4, r, Col.CYAN, 1);
    }

    private void aim() {
        double t = time % 6;
        double ang = Math.toRadians(70 * Math.sin(t / 6 * Math.PI * 2));
        double R = 3.4, gx = Math.sin(ang) * R, gz = Math.cos(ang) * R;
        circle(0, 0.01, 0, R, 1.2f, Col.a(Col.CYAN, 0.35f));
        boolean blocked = Math.abs(Math.toDegrees(ang)) < 13;
        opponent(yawTo(0, 0, gx, gz), -1);
        ghost(gx, gz, 0, true, -1);
        for (int i = 0; i < 3; i++) cube(-0.5, i, 1.25, 0.5, i + 1, 2.25, 0x2B1B44, 1f);
        flush();
        if (blocked) {
            double s = 1.25 / Math.max(0.01, gz);
            double hx = gx * s, hy = 1.62 + (1.1 - 1.62) * s;
            hitLine(0, 1.62, 0, hx, hy, 1.25, Col.ORANGE, 1);
            label("Line broken", 0, 3.4, 1.7, Col.GREEN, 1);
        } else {
            hitLine(0, 1.62, 0, gx, 1.1, gz, Col.RED, 1);
            label("Locked on", gx, 2.3, gz, Col.RED, 1);
        }
        sideLabel("Strafing won't shake it", -4.2, 0.1, Col.ORANGE, 0.9f);
        sideLabel("Pillars and height do", 4.2, 0.1, Col.GREEN, 0.9f);
    }

    private void clicker() {
        float max = 1;
        for (float v : cpsBars) max = Math.max(max, v);
        double R = 2.6, sweep = (time * 0.6) % (Math.PI * 2);
        for (int i = 0; i < cpsBars.length; i++) {
            double a = Math.PI * 2 * i / cpsBars.length;
            double bx = Math.sin(a) * R, bz = Math.cos(a) * R;
            double hgt = 0.05 + cpsBars[i] / Math.max(20, max) * 2.6;
            double d = Math.abs(((a - sweep) % (Math.PI * 2) + Math.PI * 3) % (Math.PI * 2) - Math.PI);
            int col = cpsBars[i] >= 20 ? Col.RED : Col.YELLOW;
            cube(bx - 0.09, 0, bz - 0.09, bx + 0.09, hgt, bz + 0.09, col, d < 0.3 ? 1f : 0.65f);
        }
        float avg = 0; int n = 0;
        for (float v : cpsBars) if (v > 0) { avg += v; n++; }
        avg = n == 0 ? 10 : avg / n;
        double beat = (time * avg / 2.5) % 1;
        circle(0, 0.02, 0, 0.3 + beat * 1.8, 1.4f, Col.a(Col.YELLOW, 0.7f * (1 - (float) beat)));
        double t = time % 5;
        double r = t < 1 ? 4.4 - 2.4 * seg(t, 0, 1) : t < 1.6 ? 2.0 : t < 2.6 ? 2.0 + 2.4 * seg(t, 1.6, 2.6) : 4.4;
        double jump = t > 1.4 && t < 1.9 ? Math.sin((t - 1.4) / 0.5 * Math.PI) * 0.5 : 0;
        opponent(yawTo(0, 0, 0, r), (float) beat);
        ghost(0, r, jump, t < 1 || (t > 1.6 && t < 2.6), swing(t, 1.0, 1.35));
        flush();
        label(String.format("Their CPS over the fight (peak %.0f)", an.peakCps), 0, 3.3, 0, Col.YELLOW, 1);
        if (jump > 0) label("Jump-reset out of the combo", 0, 2.6, r, Col.CYAN, 1);
    }

    private void velocity() {
        double t = time % 6;
        opponent(0, -1);
        ghost(0, 2.0, 0, false, swing(t, 0.4, 0.8));
        // where they should have flown
        if (t > 0.8 && t < 2.6) {
            double k = seg(t, 0.8, 1.8);
            PState fly = pose(0, Math.sin(k * Math.PI) * 0.7, -2.6 * k, 0, 8);
            player(fly, opSkin(), 0x70FF6060, 0, 0, -1);
        }
        // the trap: cubes rise around them
        if (t > 2.6) {
            double k = seg(t, 2.6, 3.4);
            double[][] spots = {{-1, 0}, {1, 0}, {0, -1}};
            for (double[] s : spots) cube(s[0] - 0.5, 0, s[1] - 0.5, s[0] + 0.5, k, s[1] + 0.5, 0x2B1B44, 1f);
        }
        flush();
        if (t > 0.8 && t < 2.6) {
            line3(0, 0.05, 0, 0, 0.05, -2.6, 1.6f, Col.a(Col.RED, 0.6f));
            label("Where your hit should send them", 0, 2.6, -2.6, Col.RED, 1);
            label("Where they stayed", 0.9, 2.3, 0, Col.PURPLE, 1);
        }
        if (t > 3.0) {
            wireBox(-0.45, 0, -0.45, 0.45, 1.9, 0.45, 1f, Col.a(0xFFFFFF, 0.5f));
            for (int i = 0; i < 4; i++) line3(-0.45 + i * 0.3, 0, -0.45, 0.45 - i * 0.3, 1.9, 0.45, 0.8f, Col.a(0xFFFFFF, 0.35f));
            label("They don't move: trap them", 0, 2.6, 0, Col.GREEN, 1);
        }
    }

    private void speed(float dt) {
        double[] webs = {0.6, 2.6, 4.6};
        double near = 9;
        for (double wAng : webs) near = Math.min(near, Math.abs(((runAngle - wAng) % (Math.PI * 2) + Math.PI * 3) % (Math.PI * 2) - Math.PI));
        boolean slowed = near < 0.25;
        runAngle += dt * (slowed ? 0.25 : 1.7);
        double R = 4.2;
        for (int i = 1; i < 26; i++) {
            double a0 = runAngle - i * 0.06, a1 = runAngle - (i - 1) * 0.06;
            line3(Math.sin(a0) * R, 0.1, Math.cos(a0) * R, Math.sin(a1) * R, 0.1, Math.cos(a1) * R, 2.4f,
                Col.a(Col.lerp(0xFF64D2FF, 0xFFFF453A, i / 26f) & 0xFFFFFF, 0.7f * (1 - i / 26f)));
        }
        for (double wAng : webs) {
            double wx = Math.sin(wAng) * R, wz = Math.cos(wAng) * R;
            cube(wx - 0.5, 0, wz - 0.5, wx + 0.5, 1, wz + 0.5, 0xE8E8E8, 0.22f);
        }
        double ox = Math.sin(runAngle) * R, oz = Math.cos(runAngle) * R;
        PState op = pose(ox, 0, oz, Math.toDegrees(-runAngle) - 90, 8);
        player(op, opSkin(), 0xFFFFFFFF, time * (slowed ? 4 : 16), 1, -1);
        PState me = pose(0, 0, 0, yawTo(0, 0, ox, oz), 5);
        player(me, mySkin(), GHOST, 0, 0, -1);
        flush();
        label(slowed ? "Cobweb: speed gone" : String.format("They hit %.1f b/s", Math.max(peakSpeed, 11)), ox, 2.4, oz, slowed ? Col.GREEN : Col.CYAN, 1);
        label("Hold your ground", 0, 2.4, 0, Col.CYAN, 0.9f);
    }

    private void flight() {
        double hover = 2.4 + Math.sin(time * 2) * 0.15;
        PState op = pose(0, hover, 0, yawTo(0, 0, 0, 3.6), 20);
        player(op, opSkin(), 0xFFFFFFFF, 0, 0, -1);
        ghost(0, 3.6, 0, false, -1);
        flush();
        line3(0, 0.02, 0, 0, hover, 0, 1.2f, Col.a(Col.TEAL, 0.5f));
        circle(0, 0.02, 0, 0.6, 1.4f, Col.a(Col.TEAL, 0.8f));
        label("Nothing under them", 0.9, 0.3, 0, Col.TEAL, 1);
        double t = time % 1.4;
        double k = t / 1.0;
        if (k <= 1) {
            double sx = 0, sy = 1.5, sz = 3.3, ex = 0, ey = hover + 1.0, ez = 0.2;
            double px = sx + (ex - sx) * k, pz = sz + (ez - sz) * k, py = sy + (ey - sy) * k + Math.sin(k * Math.PI) * 0.8;
            double k2 = Math.max(0, k - 0.06);
            double qx = sx + (ex - sx) * k2, qz = sz + (ez - sz) * k2, qy = sy + (ey - sy) * k2 + Math.sin(k2 * Math.PI) * 0.8;
            line3(qx, qy, qz, px, py, pz, 2.2f, Col.a(0xFFFFFF, 0.95f));
            for (int i = 0; i < 20; i++) {
                double a = i / 20.0 * k, b = (i + 1) / 20.0 * k;
                line3(sx + (ex - sx) * a, sy + (ey - sy) * a + Math.sin(a * Math.PI) * 0.8, sz + (ez - sz) * a,
                    sx + (ex - sx) * b, sy + (ey - sy) * b + Math.sin(b * Math.PI) * 0.8, sz + (ez - sz) * b, 0.8f, Col.a(0xFFFFFF, 0.25f));
            }
        } else {
            float[] p = project(0, hover + 1.0, 0.2);
            if (p != null) Glass.glow(p[0], p[1], 20, Col.a(Col.RED, 0.7f));
        }
        label("Bow / crossbow from the ground", 0, 2.6, 3.6, Col.CYAN, 1);
    }

    private void totem() {
        double t = time % 6;
        boolean popped1 = t > 0.6 && t < 1.5, popped2 = t > 3.5 && t < 4.4;
        opponent(yawTo(0, 0, 0, 2.2), -1);
        ghost(0, 2.2, 0, false, t < 2.5 ? swing(t, 0.3, 0.6) : swing(t, 3.2, 3.5));
        // the instant refill: totem back in their off hand
        boolean hasTotem = !(t > 0.6 && t < 0.72) && !(t > 3.5 && t < 3.6);
        if (hasTotem) cube(0.33, 0.75, 0.05, 0.53, 0.95, 0.25, Col.YELLOW, 1f);
        // the counter: crystal then axe in the same second
        if (t > 2.4 && t < 3.0) {
            double spin = time * 3;
            double s = 0.25;
            double cxp = 1.2, czp = 0.9, cyp = 0.6 + Math.sin(time * 4) * 0.08;
            cube(cxp - s, cyp - s, czp - s, cxp + s, cyp + s, czp + s, Col.PINK, 0.95f);
            float[] p = project(cxp, cyp, czp);
            if (p != null) Glass.glow(p[0], p[1], 18 + (float) Math.sin(spin) * 3, Col.a(Col.PURPLE, 0.7f));
        }
        flush();
        if (popped1 || popped2) {
            double k = popped1 ? (t - 0.6) / 0.9 : (t - 3.5) / 0.9;
            circle(0, 1.0, 0, 0.3 + k * 2.2, 2f, Col.a(Col.YELLOW, 0.9f * (1 - (float) k)));
            float[] p = project(0, 1.0, 0);
            if (p != null) Glass.glow(p[0], p[1], 30 * (1 - (float) k) + 8, Col.a(Col.YELLOW, 0.6f * (1 - (float) k)));
        }
        if (t > 3.0 && t < 3.5) {
            double k = (t - 3.0) / 0.5;
            circle(1.2, 0.6, 0.9, 0.3 + k * 2.4, 2.4f, Col.a(0xFFFFFF, 0.9f * (1 - (float) k)));
        }
        // floating timeline ribbon
        double y = 3.0, L = 2.2;
        double[] a0 = side(-L, y), a1 = side(L, y);
        line3(a0[0], y, a0[2], a1[0], y, a1[2], 1.6f, Col.a(0xFFFFFF, 0.4f));
        double[][] marks = {{0.6, Col.YELLOW}, {0.72, Col.YELLOW}, {3.0, Col.RED}, {3.5, Col.RED}};
        for (double[] mk : marks) {
            double[] m = side(-L + 2 * L * mk[0] / 6, y);
            line3(m[0], y - 0.14, m[2], m[0], y + 0.14, m[2], 2.4f, Col.a((int) mk[1], 1f));
        }
        double[] cur = side(-L + 2 * L * t / 6, y);
        dot3(cur[0], y, cur[2], 0.07, Col.a(0xFFFFFF, 1f), true);
        double[] l1 = side(-L + 2 * L * 0.66 / 6, y + 0.45), l2 = side(-L + 2 * L * 3.25 / 6, y - 0.45);
        label("pop + instant refill", l1[0], l1[1], l1[2], Col.YELLOW, 1);
        label("crystal + axe < 1 s", l2[0], l2[1], l2[2], Col.RED, 1);
        if (t > 2.4 && t < 3.6) label("Two big hits, back to back", 0, 2.3, 2.2, Col.CYAN, 1);
    }

    private void crystals() {
        double t = time % 7;
        boolean safe = t > 3.2;
        // platform of obsidian for the high ground, with a hole
        for (int dx = -1; dx <= 1; dx++) for (int dz = 2; dz <= 4; dz++) cube(dx - 0.5, 0, dz - 0.5, dx + 0.5, 1, dz + 0.5, 0x2B1B44, 1f);
        if (safe) {
            double[][] hole = {{-1, 3}, {1, 3}, {0, 2}, {0, 4}};
            for (double[] hb : hole) cube(hb[0] - 0.5, 1, hb[1] - 0.5, hb[0] + 0.5, 2, hb[1] + 0.5, 0x3A2A55, 1f);
        }
        double gx = safe ? 0 : 2.6, gz = safe ? 3 : 1.2, gy = safe ? 1 : 0;
        opponent(yawTo(0, 0, gx, gz), -1);
        PState me = pose(gx, gy, gz, yawTo(gx, gz, 0, 0), 5);
        player(me, mySkin(), GHOST, 0, 0, -1);
        // crystals popping next to the ghost
        double ct = (time * 2.2) % 1;
        double[] c = safe ? new double[]{1.6, 0.5, 1.4} : new double[]{2.0, 0.5, 0.4};
        if (ct < 0.55) {
            double s = 0.25;
            cube(c[0] - s, c[1] - s, c[2] - s, c[0] + s, c[1] + s, c[2] + s, Col.PINK, 0.95f);
        }
        flush();
        if (ct >= 0.55) {
            double k = (ct - 0.55) / 0.45;
            circle(c[0], c[1], c[2], 0.3 + k * (safe ? 1.2 : 2.6), 2.2f, Col.a(0xFFFFFF, 0.9f * (1 - (float) k)));
            float[] p = project(c[0], c[1], c[2]);
            if (p != null) Glass.glow(p[0], p[1], 26 * (1 - (float) k) + 6, Col.a(safe ? Col.YELLOW : Col.RED, 0.7f));
        }
        label(safe ? "Hole + high ground: crystals below hurt much less" : "Open ground: crystals all around you",
            gx, gy + 2.5, gz, safe ? Col.GREEN : Col.RED, 1);
    }

    private void walls() {
        double t = time % 6;
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 2; dy++) cube(dx - 0.5, dy, 1.0, dx + 0.5, dy + 1, 1.8, 0x6E6E73, 1f);
        double gz = t < 3 ? 2.8 : 2.8 + 3.0 * seg(t, 3, 4.5);
        opponent(yawTo(0, 0, 0, gz), (float) ((time * 1.25) % 1));
        ghost(0, gz, 0, t > 3 && t < 4.5, -1);
        flush();
        if (gz < 3.6 && ((time * 1.25) % 1) < 0.4) {
            hitLine(0, 1.62, 0, 0, 1.1, gz - 0.3, Col.RED, 1);
            label("Hit through the wall", 0, 2.6, 1.4, Col.RED, 1);
        }
        label(gz > 4.5 ? "Out of range = actually safe" : "Thin cover isn't safe", 0, 2.4, gz, gz > 4.5 ? Col.GREEN : Col.CYAN, 1);
    }
}
