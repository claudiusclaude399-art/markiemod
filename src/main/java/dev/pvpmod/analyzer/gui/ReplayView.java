package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.CheckType;
import dev.pvpmod.analyzer.FightEvent;
import dev.pvpmod.analyzer.FightRecord;
import dev.pvpmod.analyzer.Flag;
import dev.pvpmod.analyzer.PState;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.GFont;
import dev.pvpmod.glass.Glass;

/**
 * A small software 3D renderer for the replay: projects the recorded players, their hitboxes, look rays, trails,
 * hits and flag pins onto the GUI with an orbit camera, a top camera, or the opponent's own eyes.
 */
final class ReplayView {
    static final int ORBIT = 0, EYES = 1, TOP = 2;

    final FightRecord rec;
    final Analyzer an;
    int mode = ORBIT;
    float yaw = 35f, pitch = 28f, dist = 9f;
    double tx = Double.NaN, ty, tz;     // smoothed orbit target

    // camera basis
    private double cx, cy, cz, fx, fy, fz, rx, ry, rz, ux, uy, uz, focal;
    private float vx, vy, vw, vh;

    ReplayView(FightRecord rec, Analyzer an) {
        this.rec = rec;
        this.an = an;
    }

    PState me(float ph) { return state(ph, true); }

    PState op(float ph) { return state(ph, false); }

    private PState state(float ph, boolean mine) {
        int n = rec.frames.size();
        if (n == 0) return null;
        int i = Math.max(0, Math.min(n - 1, (int) Math.floor(ph)));
        int j = Math.min(n - 1, i + 1);
        float t = Math.max(0, Math.min(1, ph - i));
        var a = rec.frames.get(i);
        var b = rec.frames.get(j);
        return PState.lerp(mine ? a.me : a.op, mine ? b.me : b.op, t);
    }

    // ------------------------------------------------------------------ camera
    private void setup(float ph, float x, float y, float w, float h, float dt) {
        vx = x; vy = y; vw = w; vh = h;
        PState me = me(ph), op = op(ph);
        double mx = (me.x + op.x) / 2, my = (Math.min(me.y, op.y)) + 0.9, mz = (me.z + op.z) / 2;
        if (Double.isNaN(tx) || Math.abs(tx - mx) > 30 || Math.abs(tz - mz) > 30) { tx = mx; ty = my; tz = mz; }
        double k = 1 - Math.exp(-dt * 6);
        tx += (mx - tx) * k; ty += (my - ty) * k; tz += (mz - tz) * k;

        double yawR, pitchR;
        if (mode == EYES) {
            cx = op.x; cy = op.eyeY(); cz = op.z;
            yawR = Math.toRadians(op.yw);
            pitchR = Math.toRadians(op.pt);
        } else {
            float p = mode == TOP ? 89.5f : pitch;
            float d = mode == TOP ? Math.max(dist, 10) * 1.4f : dist;
            yawR = Math.toRadians(yaw);
            pitchR = Math.toRadians(p);
            double[] f = Analyzer.look((float) Math.toDegrees(yawR), (float) Math.toDegrees(pitchR));
            cx = tx - f[0] * d; cy = ty - f[1] * d; cz = tz - f[2] * d;
        }
        double[] f = Analyzer.look((float) Math.toDegrees(yawR), (float) Math.toDegrees(pitchR));
        fx = f[0]; fy = f[1]; fz = f[2];
        // right = forward x up, up' = right x forward
        rx = -fz; ry = 0; rz = fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
        rx /= rl; rz /= rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;
        double fov = Math.toRadians(mode == EYES ? 70 : 60);
        focal = (vh / 2.0) / Math.tan(fov / 2);
    }

    /** Camera-space coordinates {x, y, depth}. */
    private double[] cam(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return new double[]{dx * rx + dy * ry + dz * rz, dx * ux + dy * uy + dz * uz, dx * fx + dy * fy + dz * fz};
    }

    private float[] screen(double[] c) {
        return new float[]{(float) (vx + vw / 2 + focal * c[0] / c[2]), (float) (vy + vh / 2 - focal * c[1] / c[2])};
    }

    /** Projected point or null when behind the camera. */
    float[] project(double x, double y, double z) {
        double[] c = cam(x, y, z);
        return c[2] < 0.1 ? null : screen(c);
    }

    private void line3(double x1, double y1, double z1, double x2, double y2, double z2, float width, int color) {
        double[] a = cam(x1, y1, z1), b = cam(x2, y2, z2);
        double near = 0.1;
        if (a[2] < near && b[2] < near) return;
        if (a[2] < near || b[2] < near) {
            double t = (near - a[2]) / (b[2] - a[2]);
            double[] p = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, near};
            if (a[2] < near) a = p; else b = p;
        }
        float[] sa = screen(a), sb = screen(b);
        if (Math.abs(sa[0]) > 1e5 || Math.abs(sb[0]) > 1e5) return;
        Glass.line(sa[0], sa[1], sb[0], sb[1], width, color);
    }

    private void box(PState p, int color, float width) {
        double hw = p.w / 2, x0 = p.x - hw, x1 = p.x + hw, y0 = p.y, y1 = p.y + p.h, z0 = p.z - hw, z1 = p.z + hw;
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : e) line3(c[ed[0]][0], c[ed[0]][1], c[ed[0]][2], c[ed[1]][0], c[ed[1]][1], c[ed[1]][2], width, color);
    }

    // ------------------------------------------------------------------ drawing
    void draw(float ph, float x, float y, float w, float h, float dt, Flag selected) {
        if (rec.frames.isEmpty()) return;
        setup(ph, x, y, w, h, dt);
        PState me = me(ph), op = op(ph);
        Glass.ctx().enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));

        // ground grid
        double gy = Math.floor(Math.min(me.y, op.y) + 0.01);
        int gx = (int) Math.floor(tx), gz = (int) Math.floor(tz), R = 12;
        for (int i = -R; i <= R; i++) {
            float fade = 1f - Math.abs(i) / (float) (R + 1);
            int col = Col.a(0xFFFFFF, 0.10f * fade);
            line3(gx + i, gy, gz - R, gx + i, gy, gz + R, 0.7f, col);
            line3(gx - R, gy, gz + i, gx + R, gy, gz + i, 0.7f, col);
        }

        // trails
        int iph = (int) ph;
        for (int k = 0; k < 2; k++) {
            int col = k == 0 ? Col.CYAN : Col.RED;
            PState prev = null;
            for (int i = Math.max(0, iph - 60); i <= Math.min(rec.frames.size() - 1, iph); i++) {
                PState s = k == 0 ? rec.frames.get(i).me : rec.frames.get(i).op;
                if (s.has(PState.ABSENT)) { prev = null; continue; }
                if (prev != null) {
                    float a = 0.08f + 0.6f * (1f - (iph - i) / 60f);
                    line3(prev.x, prev.y + 0.05, prev.z, s.x, s.y + 0.05, s.z, 1.4f, Col.a(col, a));
                }
                prev = s;
            }
        }

        // ground shadows under players
        for (PState s : new PState[]{me, op}) {
            float[] a = project(s.x, gy, s.z);
            if (a != null) {
                double[] c = cam(s.x, gy, s.z);
                float r = (float) (focal * 0.45 / c[2]);
                Glass.glow(a[0], a[1], r * 1.6f, Col.a(0x000000, 0.45f));
            }
        }

        // flag pins
        for (Flag f : an.flags) {
            float near = Math.abs(f.t - ph);
            boolean sel = f == selected;
            if (near > 200 && !sel) continue;
            float[] p = project(f.x, f.y + 2.9, f.z);
            float[] base = project(f.x, f.y + 0.1, f.z);
            if (p == null) continue;
            float pulse = 0.5f + 0.5f * (float) Math.sin(Glass.time * 6 + f.t);
            float act = sel ? 1f : Math.max(0f, 1f - near / 25f);
            float a = 0.25f + 0.75f * act;
            if (base != null && act > 0.05f) Glass.dashed(base[0], base[1], p[0], p[1], 1f, 2.5f, Col.a(f.c.color, 0.5f * act));
            if (act > 0.05f) Glass.glow(p[0], p[1], 10 + 10 * act * pulse, Col.a(f.c.color, 0.45f * act));
            float r = 4.5f + 2.5f * act;
            Glass.dot(p[0], p[1], r + 1, Col.a(0xFFFFFF, a));
            Glass.dot(p[0], p[1], r, Col.a(f.c.color, a));
            GFont.drawCentered(f.c.letter, p[0], p[1] - 3.6f, 7, Col.a(0xFFFFFF, a), true);
            if (sel) {
                String lbl = f.c.title;
                float lw = GFont.width(lbl, 7, true) + 10;
                Glass.round(p[0] - lw / 2, p[1] - r - 16, lw, 12, 6, Col.a(f.c.color, 0.85f));
                GFont.drawCentered(lbl, p[0], p[1] - r - 13.6f, 7, 0xFFFFFFFF, true);
            }
        }

        // events near the playhead
        for (FightEvent e : rec.events) {
            float age = ph - e.t;
            if (age < -1 || age > 14) continue;
            float fade = Math.max(0, 1f - Math.max(0, age) / 14f);
            if (e.k == FightEvent.Kind.HIT_ME || e.k == FightEvent.Kind.HIT_OP) {
                int t = Math.max(0, Math.min(rec.frames.size() - 1, e.t));
                PState atk = e.k == FightEvent.Kind.HIT_ME ? rec.frames.get(t).op : rec.frames.get(t).me;
                PState vic = e.k == FightEvent.Kind.HIT_ME ? rec.frames.get(t).me : rec.frames.get(t).op;
                double hw = vic.w / 2;
                double px = clamp(atk.x, vic.x - hw, vic.x + hw), py = clamp(atk.eyeY(), vic.y, vic.y + vic.h), pz = clamp(atk.z, vic.z - hw, vic.z + hw);
                boolean flagged = false;
                for (Flag f : an.flags) if (f.t == e.t && (f.c == CheckType.REACH || f.c == CheckType.KILLAURA || f.c == CheckType.WALL_HIT)) flagged = true;
                int col = flagged ? Col.RED : (e.k == FightEvent.Kind.HIT_ME ? 0xFFFFFF : Col.CYAN);
                line3(atk.x, atk.eyeY(), atk.z, px, py, pz, flagged ? 2.2f : 1.4f, Col.a(col, 0.9f * fade));
                float[] mid = project((atk.x + px) / 2, (atk.eyeY() + py) / 2 + 0.25, (atk.z + pz) / 2);
                if (mid != null && e.k == FightEvent.Kind.HIT_ME) {
                    double d = Analyzer.boxDistance(atk.x, atk.eyeY(), atk.z, vic);
                    String s = String.format("%.2fm", d);
                    float sw = GFont.width(s, 7, true) + 8;
                    Glass.round(mid[0] - sw / 2, mid[1] - 6, sw, 11, 5.5f, Col.a(flagged ? Col.RED : 0x2C2C2E, 0.85f * fade));
                    GFont.drawCentered(s, mid[0], mid[1] - 3.8f, 7, Col.a(0xFFFFFF, fade), true);
                }
            } else if (e.k == FightEvent.Kind.POP_OP || e.k == FightEvent.Kind.POP_ME) {
                float[] p = project(e.x, e.y + 1.2, e.z);
                if (p != null) Glass.glow(p[0], p[1], 30 * (0.4f + Math.max(0, age) / 14f), Col.a(Col.YELLOW, 0.6f * fade));
            } else if (e.k == FightEvent.Kind.CRYSTAL_OP) {
                float[] p = project(e.x, e.y + 0.5, e.z);
                if (p != null) {
                    Glass.glow(p[0], p[1], 14, Col.a(Col.PURPLE, 0.6f * fade));
                    Glass.dot(p[0], p[1], 3, Col.a(Col.PINK, fade));
                }
            }
        }

        // players
        drawPlayer(me, Col.CYAN, "You", mode != EYES);
        if (mode != EYES) drawPlayer(op, Col.RED, rec.opName, true);

        if (mode == EYES) {
            float cxs = x + w / 2, cys = y + h / 2;
            double err = Analyzer.aimError(op, me);
            int col = err < 1 ? Col.GREEN : Col.a(0xFFFFFF, 0.9f);
            Glass.line(cxs - 6, cys, cxs + 6, cys, 1.2f, col);
            Glass.line(cxs, cys - 6, cxs, cys + 6, 1.2f, col);
            GFont.drawCentered(String.format("%.0f° off you", err), cxs, cys + 12, 7, Col.LABEL2, true);
        }
        Glass.ctx().disableScissor();
    }

    private void drawPlayer(PState p, int rgb, String name, boolean showLook) {
        if (p == null) return;
        boolean absent = p.has(PState.ABSENT);
        float a = absent ? 0.3f : 1f;
        box(p, Col.a(rgb, 0.9f * a), 1.5f);
        if (showLook && !absent) {
            double[] l = Analyzer.look(p.yw, p.pt);
            double len = 4.5;
            line3(p.x, p.eyeY(), p.z, p.x + l[0] * len, p.eyeY() + l[1] * len, p.z + l[2] * len, 1.6f, Col.a(rgb, 0.75f));
            float[] eye = project(p.x, p.eyeY(), p.z);
            if (eye != null) Glass.dot(eye[0], eye[1], 2.4f, Col.a(0xFFFFFF, 0.95f));
        }
        float[] top = project(p.x, p.y + p.h + 0.45, p.z);
        if (top != null) {
            String s = name + "  " + Math.round(p.hp + p.ab) + "❤";
            float sw = GFont.width(s, 7, true) + 10;
            Glass.round(top[0] - sw / 2, top[1] - 6, sw, 12, 6, Col.a(0x1C1C1E, 0.75f * a));
            Glass.rim(top[0] - sw / 2, top[1] - 6, sw, 12, 6, Col.a(rgb, 0.8f * a), Col.a(rgb, 0.3f * a));
            GFont.drawCentered(s, top[0], top[1] - 3.6f, 7, Col.a(0xFFFFFF, a), true);
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
