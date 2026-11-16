package dev.markiesdetector.analyzer.gui;

import dev.markiesdetector.analyzer.Analyzer;
import dev.markiesdetector.analyzer.CheckType;
import dev.markiesdetector.analyzer.FightEvent;
import dev.markiesdetector.analyzer.FightRecord;
import dev.markiesdetector.analyzer.Flag;
import dev.markiesdetector.analyzer.PState;
import dev.markiesdetector.glass.Col;
import dev.markiesdetector.glass.GFont;
import dev.markiesdetector.glass.Glass;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.SkinTextures;


/**
 * A small software 3D renderer for the replay: projects the recorded players, their hitboxes, look rays, trails,
 * hits and flag pins onto the GUI with an orbit camera, a top camera, or the opponent's own eyes.
 */
final class ReplayView extends Scene3D {
    static final int ORBIT = 0, EYES = 1, TOP = 2;

    final FightRecord rec;
    final Analyzer an;
    int mode = ORBIT;
    float yaw = 35f, pitch = 22f, dist = 6.5f;
    double tx = Double.NaN, ty, tz;     // smoothed orbit target


    ReplayView(FightRecord rec, Analyzer an) {
        this.rec = rec;
        this.an = an;
    }

    PState me(float ph) { return state(ph, true); }

    // ------------------------------------------------------------------ world map
    boolean showWorld = dev.markiesdetector.config.PvpConfig.get().analyzerReplayWorld;

    boolean hasWorld() { return rec.wb != null && rec.wb.length >= dev.markiesdetector.analyzer.WorldSnapshot.STRIDE; }

    /** Queues the saved blocks around the camera target, fading out at the edge. */
    private void queueWorld(double gy) {
        int[] b = rec.wb;
        double R = 15, fadeFrom = 10;
        int S = dev.markiesdetector.analyzer.WorldSnapshot.STRIDE;
        // cube() face order: south, north, west, east, up, down -> snapshot face bits
        for (int i = 0; i + S <= b.length; i += S) {
            int bx = b[i], by = b[i + 1], bz = b[i + 2], rgb = b[i + 3], info = b[i + 4];
            double dx = bx + 0.5 - tx, dz = bz + 0.5 - tz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > R || by < gy - 7 || by > gy + 12) continue;
            float a = d < fadeFrom ? 1f : (float) (1 - (d - fadeFrom) / (R - fadeFrom));
            boolean water = (info & dev.markiesdetector.analyzer.WorldSnapshot.WATER) != 0;
            if (water) a *= 0.55f;
            double h = ((info >> 6) & 31) / 16.0;
            int faces = info & 63;
            int mask = ((faces & 8) != 0 ? 1 : 0) | ((faces & 4) != 0 ? 2 : 0) | ((faces & 16) != 0 ? 4 : 0)
                | ((faces & 32) != 0 ? 8 : 0) | ((faces & 2) != 0 ? 16 : 0) | ((faces & 1) != 0 ? 32 : 0);
            // faint checker so individual blocks are visible, like in game
            float shade = ((bx + bz + by) & 1) == 0 ? 0.95f : 0.88f;
            cube(bx, by, bz, bx + 1, by + h, bz + 1, shadeRgb(rgb, shade), a, mask);
        }
    }

    /** Draw players with the game's own renderer (armor, items, animations), or the built-in box models. */
    boolean useGameModels = true;

    private List<PState> extrasAt(float ph) {
        List<PState> extras = new ArrayList<>();
        for (int k = 0; k < rec.others.size(); k++) extras.add(extra(ph, k));
        return extras;
    }

    /** Was this player hit in the last half second (red hurt flash)? */
    private boolean hurt(float ph, boolean mine) {
        FightEvent.Kind k = mine ? FightEvent.Kind.HIT_ME : FightEvent.Kind.HIT_OP;
        for (FightEvent e : rec.events) if (e.k == k && e.t <= ph && ph - e.t < 10) return true;
        return false;
    }

    /** Real game models, far to near so nearer players are drawn on top. */
    private void drawGamePlayers(float ph, PState me, PState op, List<PState> extras) {
        record Actor(PState p, SkinTextures skin, String uuid, String name, float phase, float amt, float swing, boolean hurt, double depth) {}
        List<Actor> actors = new ArrayList<>();
        String myId = rec.meUuid == null || rec.meUuid.isEmpty() ? MinecraftClient.getInstance().getGameProfile().id().toString() : rec.meUuid;
        double[] dm = dist(true), dop = dist(false);
        actors.add(new Actor(me, mySkin(), myId, rec.meName, (float) (distAt(dm, ph) * 2.66),
            (float) clamp((distAt(dm, ph) - distAt(dm, ph - 1)) * 4.5, 0, 1), swing(ph, true), hurt(ph, true), cam(me.x, me.y + 0.9, me.z)[2]));
        if (mode != EYES) {
            actors.add(new Actor(op, Ui.skin(rec.opUuid, rec.opName, rec.opTex, rec.opSig), rec.opUuid, rec.opName,
                (float) (distAt(dop, ph) * 2.66), (float) clamp((distAt(dop, ph) - distAt(dop, ph - 1)) * 4.5, 0, 1),
                swing(ph, false), hurt(ph, false), cam(op.x, op.y + 0.9, op.z)[2]));
        }
        for (int k = 0; k < extras.size(); k++) {
            PState o = extras.get(k);
            if (o == null || o.has(PState.ABSENT)) continue;
            FightRecord.Other ot = rec.others.get(k);
            PState prev = extra(ph - 1, k);
            float amt = prev == null ? 0 : (float) clamp(Math.hypot(o.x - prev.x, o.z - prev.z) * 4.5, 0, 1);
            actors.add(new Actor(o, Ui.skin(ot.uuid, ot.name, ot.tex, ot.sig), ot.uuid, ot.name, ph * 0.9f, amt, -1, false, cam(o.x, o.y + 0.9, o.z)[2]));
        }
        actors.sort((a, b) -> Double.compare(b.depth, a.depth));
        for (Actor a : actors) {
            if (a.depth < 0.6) continue;   // too close to (or behind) the camera
            float[] feet = project(a.p.x, a.p.y, a.p.z);
            if (feet == null) continue;
            RealPlayers.draw(Glass.ctx(), a.p, a.skin, a.uuid, a.name, feet[0], feet[1], scaleAt(a.p.x, a.p.y + 0.9, a.p.z, 1.0),
                camYaw, camPitch, a.phase, a.amt, a.swing, a.hurt);
        }
    }

    /** Queues the skinned models of everyone in the fight; returns the other fighters (group fights). */
    private List<PState> queuePlayers(float ph, PState me, PState op) {
        modelFaces(me, mySkin(), ph, true);
        if (mode != EYES) modelFaces(op, Ui.skin(rec.opUuid, rec.opName, rec.opTex, rec.opSig), ph, false);
        List<PState> extras = new ArrayList<>();
        for (int k = 0; k < rec.others.size(); k++) {
            PState o = extra(ph, k);
            extras.add(o);
            if (o == null || o.has(PState.ABSENT)) continue;
            FightRecord.Other ot = rec.others.get(k);
            PState prev = extra(ph - 1, k);
            double amt = prev == null ? 0 : clamp(Math.hypot(o.x - prev.x, o.z - prev.z) * 4.5, 0, 1);
            player(o, Ui.skin(ot.uuid, ot.name, ot.tex, ot.sig), 0xE6FFFFFF, ph * 0.9, amt, -1);
        }
        return extras;
    }

    /** Another fighter of a group fight at the playhead (null if not recorded then). */
    PState extra(float ph, int k) {
        int n = rec.frames.size();
        if (n == 0) return null;
        int i = Math.max(0, Math.min(n - 1, (int) Math.floor(ph)));
        int j = Math.min(n - 1, i + 1);
        List<PState> a = rec.frames.get(i).x, b = rec.frames.get(j).x;
        if (a == null || a.size() <= k) return null;
        PState pa = a.get(k), pb = b == null || b.size() <= k ? pa : b.get(k);
        if (pa == null) return null;
        if (pb == null || pb.has(PState.ABSENT)) return pa;
        return PState.lerp(pa, pb, Math.max(0, Math.min(1, ph - i)));
    }

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
        PState me = me(ph), op = op(ph);
        double mx = (me.x + op.x) / 2, my = (Math.min(me.y, op.y)) + 0.9, mz = (me.z + op.z) / 2;
        if (Double.isNaN(tx) || Math.abs(tx - mx) > 30 || Math.abs(tz - mz) > 30) { tx = mx; ty = my; tz = mz; }
        double k = 1 - Math.exp(-dt * 6);
        tx += (mx - tx) * k; ty += (my - ty) * k; tz += (mz - tz) * k;

        if (mode == EYES) {
            setCamera(op.x, op.eyeY(), op.z, op.yw, op.pt, 70, x, y, w, h);
        } else {
            float p = mode == TOP ? 89.5f : pitch;
            float d = mode == TOP ? Math.max(dist, 10) * 1.4f : dist;
            orbit(tx, ty, tz, yaw, p, d, 60, x, y, w, h);
        }
    }

    private void box(PState p, int color, float width) {
        double hw = p.w / 2;
        wireBox(p.x - hw, p.y, p.z - hw, p.x + hw, p.y + p.h, p.z + hw, width, color);
    }

    // ------------------------------------------------------------------ drawing
    void draw(float ph, float x, float y, float w, float h, float dt, Flag selected) {
        if (rec.frames.isEmpty()) return;
        setup(ph, x, y, w, h, dt);
        PState me = me(ph), op = op(ph);
        Glass.ctx().enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));

        // ground grid, or the real map when it was saved
        double gy = Math.floor(Math.min(me.y, op.y) + 0.01);
        boolean map = showWorld && hasWorld();
        int gx = (int) Math.floor(tx), gz = (int) Math.floor(tz), R = 12;
        for (int i = -R; i <= R && !map; i++) {
            float fade = 1f - Math.abs(i) / (float) (R + 1);
            int col = Col.a(0xFFFFFF, 0.10f * fade);
            line3(gx + i, gy, gz - R, gx + i, gy, gz + R, 0.7f, col);
            line3(gx - R, gy, gz + i, gx + R, gy, gz + i, 0.7f, col);
        }
        if (map) queueWorld(gy);
        boolean real = useGameModels && RealPlayers.available();
        List<PState> extras = real ? extrasAt(ph) : queuePlayers(ph, me, op);
        flush();
        if (real) drawGamePlayers(ph, me, op, extras);

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
        for (PState s : map ? new PState[0] : new PState[]{me, op}) {
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

        // hitbox, look ray and name tag on top of the models
        drawPlayer(me, Col.CYAN, "You", mode != EYES);
        if (mode != EYES) drawPlayer(op, Col.RED, rec.opName, true);
        for (int k = 0; k < extras.size(); k++) {
            PState o = extras.get(k);
            if (o != null && !o.has(PState.ABSENT)) drawPlayer(o, Col.PURPLE, rec.others.get(k).name, true);
        }

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
        box(p, Col.a(rgb, 0.55f * a), 1.0f);
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

    // ------------------------------------------------------------------ skinned player models
    private double[] meDist, opDist;

    private SkinTextures mySkin() {
        String id = rec.meUuid;
        if (id == null || id.isEmpty()) id = MinecraftClient.getInstance().getGameProfile().id().toString();
        return Ui.skin(id, rec.meName, rec.meTex, rec.meSig);
    }

    /** Cumulative horizontal distance walked, which drives the arm and leg swing like the real game. */
    private double[] dist(boolean mine) {
        if (mine && meDist != null) return meDist;
        if (!mine && opDist != null) return opDist;
        int n = rec.frames.size();
        double[] d = new double[n];
        for (int i = 1; i < n; i++) {
            PState a = mine ? rec.frames.get(i - 1).me : rec.frames.get(i - 1).op;
            PState b = mine ? rec.frames.get(i).me : rec.frames.get(i).op;
            double dx = b.x - a.x, dz = b.z - a.z, step = Math.sqrt(dx * dx + dz * dz);
            d[i] = d[i - 1] + (step > 2 ? 0 : step);
        }
        if (mine) meDist = d; else opDist = d;
        return d;
    }

    private double distAt(double[] d, float ph) {
        int i = Math.max(0, Math.min(d.length - 1, (int) ph)), j = Math.min(d.length - 1, i + 1);
        return d[i] + (d[j] - d[i]) * Math.max(0, Math.min(1, ph - i));
    }

    /** 0..1 progress of an attack swing at the playhead, or -1. */
    private float swing(float ph, boolean mine) {
        int t = (int) ph;
        if (mine) {
            for (FightEvent e : rec.events)
                if (e.k == FightEvent.Kind.HIT_OP && e.t <= ph && ph - e.t < 6) return (ph - e.t) / 6f;
            return -1;
        }
        for (int i = t; i >= Math.max(0, t - 5); i--) {
            if (i < rec.frames.size() && rec.frames.get(i).op.s > 0) return Math.min(1f, (ph - i) / 6f);
        }
        return -1;
    }

    private void modelFaces(PState p, SkinTextures skin, float ph, boolean mine) {
        if (p == null || rec.frames.isEmpty()) return;
        double[] d = dist(mine);
        double phase = distAt(d, ph) * 2.66;
        double amt = clamp((distAt(d, ph) - distAt(d, ph - 1)) * 4.5, 0, 1);
        int a = Math.round((p.has(PState.ABSENT) ? 0.3f : 1f) * 255);
        player(p, skin, (a << 24) | 0xFFFFFF, phase, amt, swing(ph, mine));
    }

}
