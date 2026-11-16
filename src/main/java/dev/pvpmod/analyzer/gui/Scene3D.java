package dev.pvpmod.analyzer.gui;

import dev.pvpmod.analyzer.Analyzer;
import dev.pvpmod.analyzer.PState;
import dev.pvpmod.glass.Col;
import dev.pvpmod.glass.GFont;
import dev.pvpmod.glass.Glass;
import dev.pvpmod.glass.GlassTex;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny software 3D engine drawn with the GUI: perspective camera, anti-aliased 3D lines, solid shaded cubes and
 * floor rings, and skinned Minecraft player models. Solids are queued and painter-sorted by {@link #flush()}.
 */
class Scene3D {
    // camera basis
    protected double cx, cy, cz, fx, fy, fz, rx, ry, rz, ux, uy, uz, focal;
    protected float vx, vy, vw, vh;

    /** Camera at a position looking along yaw/pitch (Minecraft conventions). */
    protected void setCamera(double x, double y, double z, float yawDeg, float pitchDeg, float fovDeg,
                             float viewX, float viewY, float viewW, float viewH) {
        vx = viewX; vy = viewY; vw = viewW; vh = viewH;
        cx = x; cy = y; cz = z;
        double[] f = Analyzer.look(yawDeg, pitchDeg);
        fx = f[0]; fy = f[1]; fz = f[2];
        rx = -fz; ry = 0; rz = fx;          // right = forward x up
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
        rx /= rl; rz /= rl;
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;   // up = right x forward
        focal = (vh / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2);
    }

    /** Orbit camera around a target. */
    protected void orbit(double tx, double ty, double tz, float yawDeg, float pitchDeg, float dist, float fovDeg,
                         float viewX, float viewY, float viewW, float viewH) {
        double[] f = Analyzer.look(yawDeg, pitchDeg);
        setCamera(tx - f[0] * dist, ty - f[1] * dist, tz - f[2] * dist, yawDeg, pitchDeg, fovDeg, viewX, viewY, viewW, viewH);
    }

    /** Camera-space coordinates {x, y, depth}. */
    protected double[] cam(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return new double[]{dx * rx + dy * ry + dz * rz, dx * ux + dy * uy + dz * uz, dx * fx + dy * fy + dz * fz};
    }

    protected float[] screen(double[] c) {
        return new float[]{(float) (vx + vw / 2 + focal * c[0] / c[2]), (float) (vy + vh / 2 - focal * c[1] / c[2])};
    }

    /** Projected point or null when behind the camera. */
    float[] project(double x, double y, double z) {
        double[] c = cam(x, y, z);
        return c[2] < 0.1 ? null : screen(c);
    }

    /** Screen size (GUI units) of a world length at a point. */
    protected float scaleAt(double x, double y, double z, double len) {
        double[] c = cam(x, y, z);
        return c[2] < 0.1 ? 0 : (float) (focal * len / c[2]);
    }

    protected void line3(double x1, double y1, double z1, double x2, double y2, double z2, float width, int color) {
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

    protected void wireBox(double x0, double y0, double z0, double x1, double y1, double z1, float width, int color) {
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : e) line3(c[ed[0]][0], c[ed[0]][1], c[ed[0]][2], c[ed[1]][0], c[ed[1]][1], c[ed[1]][2], width, color);
    }

    // ------------------------------------------------------------------ queued solids
    protected record Face(float[] s0, float[] s1, float[] s3, double depth, Identifier tex, int u, int v, int w, int h,
                          int tw, int th, int color) {}

    protected final List<Face> queue = new ArrayList<>();

    /** Draw every queued solid, far to near. */
    protected void flush() {
        queue.sort((a, b) -> Double.compare(b.depth, a.depth));
        for (Face f : queue) drawFace(f);
        queue.clear();
    }

    protected void drawFace(Face f) {
        Matrix3x2fStack m = Glass.ctx().getMatrices();
        m.pushMatrix();
        m.mul(new Matrix3x2f(
            (f.s1[0] - f.s0[0]) / f.w, (f.s1[1] - f.s0[1]) / f.w,
            (f.s3[0] - f.s0[0]) / f.h, (f.s3[1] - f.s0[1]) / f.h,
            f.s0[0], f.s0[1]));
        Glass.ctx().drawTexture(RenderPipelines.GUI_TEXTURED, f.tex, 0, 0, f.u, f.v, f.w, f.h, f.w, f.h, f.tw, f.th, f.color);
        m.popMatrix();
    }

    /** A flat solid parallelogram p0 (top-left), p1 (top-right), p3 (bottom-left). Queued, or drawn now. */
    protected void quad(double[] p0, double[] p1, double[] p3, int argb, boolean now) {
        double[] c0 = cam(p0[0], p0[1], p0[2]), c1 = cam(p1[0], p1[1], p1[2]), c3 = cam(p3[0], p3[1], p3[2]);
        double[] c2 = cam(p1[0] + p3[0] - p0[0], p1[1] + p3[1] - p0[1], p1[2] + p3[2] - p0[2]);
        if (c0[2] < 0.1 || c1[2] < 0.1 || c2[2] < 0.1 || c3[2] < 0.1) return;
        int color = Col.fade(argb, Glass.alpha);
        Face f = new Face(screen(c0), screen(c1), screen(c3), (c0[2] + c1[2] + c2[2] + c3[2]) / 4, GlassTex.white(), 0, 0, 4, 4, 4, 4, color);
        if (now) drawFace(f); else queue.add(f);
    }

    /** Shaded solid cube (queued). */
    protected void cube(double x0, double y0, double z0, double x1, double y1, double z1, int rgb, float alpha) {
        double[][][] faces = {
            {{x0, y1, z1}, {x1, y1, z1}, {x0, y0, z1}, {0, 0, 1}},
            {{x1, y1, z0}, {x0, y1, z0}, {x1, y0, z0}, {0, 0, -1}},
            {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z0}, {-1, 0, 0}},
            {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z1}, {1, 0, 0}},
            {{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {0, 1, 0}},
            {{x0, y0, z1}, {x1, y0, z1}, {x0, y0, z0}, {0, -1, 0}}
        };
        float[] shade = {0.8f, 0.8f, 0.65f, 0.65f, 1f, 0.5f};
        for (int i = 0; i < 6; i++) {
            double[][] q = faces[i];
            double[] n = q[3];
            double mx = (q[1][0] + q[2][0]) / 2, my = (q[1][1] + q[2][1]) / 2, mz = (q[1][2] + q[2][2]) / 2;
            if (n[0] * (mx - cx) + n[1] * (my - cy) + n[2] * (mz - cz) >= 0) continue;
            quad(q[0], q[1], q[2], Col.a(shadeRgb(rgb, shade[i]), alpha), false);
        }
    }

    protected static int shadeRgb(int rgb, float k) {
        int r = Math.round(((rgb >> 16) & 255) * k), g = Math.round(((rgb >> 8) & 255) * k), b = Math.round((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** Filled ring on a horizontal plane, drawn immediately (use for floors before solids). */
    protected void ring(double ox, double y, double oz, double r0, double r1, int argb) {
        int segs = 48;
        for (int i = 0; i < segs; i++) {
            double a0 = Math.PI * 2 * i / segs, a1 = Math.PI * 2 * (i + 1) / segs;
            double[] p0 = {ox + Math.sin(a0) * r0, y, oz + Math.cos(a0) * r0};
            double[] p1 = {ox + Math.sin(a0) * r1, y, oz + Math.cos(a0) * r1};
            double[] p3 = {ox + Math.sin(a1) * r0, y, oz + Math.cos(a1) * r0};
            quad(p0, p1, p3, argb, true);
        }
    }

    protected void circle(double ox, double y, double oz, double r, float width, int argb) {
        int segs = 64;
        for (int i = 0; i < segs; i++) {
            double a0 = Math.PI * 2 * i / segs, a1 = Math.PI * 2 * (i + 1) / segs;
            line3(ox + Math.sin(a0) * r, y, oz + Math.cos(a0) * r, ox + Math.sin(a1) * r, y, oz + Math.cos(a1) * r, width, argb);
        }
    }

    /** Glowing dot with a world-space radius. */
    protected void dot3(double x, double y, double z, double r, int argb, boolean glow) {
        float[] p = project(x, y, z);
        if (p == null) return;
        float s = Math.max(1f, scaleAt(x, y, z, r));
        if (glow) Glass.glow(p[0], p[1], s * 3, Col.fade(argb, 0.45f));
        Glass.dot(p[0], p[1], s, argb);
    }

    /** A glass label floating at a 3D point. */
    protected void label(String text, double x, double y, double z, int rgb, float alpha) {
        float[] p = project(x, y, z);
        if (p == null || alpha < 0.02f) return;
        float w = GFont.width(text, 7, true) + 10;
        Glass.round(p[0] - w / 2, p[1] - 6, w, 12, 6, Col.a(0x1C1C1E, 0.8f * alpha));
        Glass.rim(p[0] - w / 2, p[1] - 6, w, 12, 6, Col.a(rgb, 0.85f * alpha), Col.a(rgb, 0.3f * alpha));
        GFont.drawCentered(text, p[0], p[1] - 3.6f, 7, Col.a(0xFFFFFF, alpha), true);
    }

    // ------------------------------------------------------------------ skinned player model
    /**
     * Queues a skinned player model. {@code phase}/{@code amt} drive the walk cycle, {@code swing} (0..1, or -1)
     * the attack swing, {@code tint} multiplies the colours (alpha = opacity).
     */
    protected void player(PState p, SkinTextures skin, int tint, double phase, double amt, float swing) {
        if (p == null) return;
        Identifier tex = skin.body().texturePath();
        boolean slim = skin.model() == PlayerSkinType.SLIM;
        double leg = Math.cos(phase) * 1.4 * amt * 0.6;
        double arm = Math.cos(phase + Math.PI) * amt * 0.8;
        double attack = swing >= 0 ? -Math.sin(swing * Math.PI) * 1.5 - 0.25 : 0;
        double headPitch = Math.toRadians(Math.max(-90, Math.min(90, p.pt)));
        int aw = slim ? 3 : 4;
        part(p, tex, tint, -4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8, 0, 24, headPitch);
        part(p, tex, tint, -4.5, 23.5, -4.5, 4.5, 32.5, 4.5, 32, 0, 8, 8, 8, 0, 24, headPitch);
        part(p, tex, tint, -4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4, 0, 12, 0);
        part(p, tex, tint, -4 - aw, 12, -2, -4, 24, 2, 40, 16, aw, 12, 4, -5, 22, arm + attack);
        part(p, tex, tint, 4, 12, -2, 4 + aw, 24, 2, 32, 48, aw, 12, 4, 5, 22, -arm);
        part(p, tex, tint, -4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4, -2, 12, leg);
        part(p, tex, tint, 0, 0, -2, 4, 12, 2, 16, 48, 4, 12, 4, 2, 12, -leg);
    }

    /** One box of the player model (Minecraft skin layout), as up to 6 textured faces facing the camera. */
    private void part(PState p, Identifier tex, int tint,
                      double x0, double y0, double z0, double x1, double y1, double z1,
                      int tu, int tv, int w, int h, int dd, double pivX, double pivY, double rot) {
        double[][][] faces = {
            {{x0, y1, z1}, {x1, y1, z1}, {x0, y0, z1}, {0, 0, 1}},
            {{x1, y1, z0}, {x0, y1, z0}, {x1, y0, z0}, {0, 0, -1}},
            {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z0}, {-1, 0, 0}},
            {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z1}, {1, 0, 0}},
            {{x0, y1, z0}, {x1, y1, z0}, {x0, y1, z1}, {0, 1, 0}},
            {{x0, y0, z1}, {x1, y0, z1}, {x0, y0, z0}, {0, -1, 0}}
        };
        int[][] uv = {
            {tu + dd, tv + dd, w, h}, {tu + 2 * dd + w, tv + dd, w, h}, {tu, tv + dd, dd, h},
            {tu + dd + w, tv + dd, dd, h}, {tu + dd, tv, w, dd}, {tu + dd + w, tv, w, dd}
        };
        float[] shade = {0.85f, 0.85f, 0.68f, 0.68f, 1f, 0.5f};
        double k = 1.8 / 32.0, yaw = Math.toRadians(p.yw), drop = p.has(PState.SNEAK) ? 0.15 : 0;
        double cr = Math.cos(rot), sr = Math.sin(rot), cyw = Math.cos(yaw), syw = Math.sin(yaw);
        float ta = ((tint >>> 24) & 255) / 255f * Glass.alpha;
        int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
        for (int f = 0; f < 6; f++) {
            double[][] q = new double[4][3];
            double[][] src = {faces[f][0], faces[f][1], faces[f][2],
                {faces[f][1][0] + faces[f][2][0] - faces[f][0][0], faces[f][1][1] + faces[f][2][1] - faces[f][0][1], faces[f][1][2] + faces[f][2][2] - faces[f][0][2]}};
            for (int c = 0; c < 4; c++) {
                double mx = src[c][0] - pivX, my = src[c][1] - pivY, mz = src[c][2];
                double ry2 = my * cr - mz * sr, rz2 = my * sr + mz * cr;          // pitch about the pivot
                double wx = (mx + pivX) * k, wy = (ry2 + pivY) * k, wz = rz2 * k;
                q[c][0] = p.x + wx * cyw - wz * syw;                              // body yaw
                q[c][1] = p.y - drop + wy;
                q[c][2] = p.z + wx * syw + wz * cyw;
            }
            double[] n = faces[f][3];
            double ny = n[1] * cr - n[2] * sr, nz = n[1] * sr + n[2] * cr, nx = n[0];
            double wnx = nx * cyw - nz * syw, wnz = nx * syw + nz * cyw;
            double fcx = (q[0][0] + q[3][0]) / 2, fcy = (q[0][1] + q[3][1]) / 2, fcz = (q[0][2] + q[3][2]) / 2;
            if (wnx * (fcx - cx) + ny * (fcy - cy) + wnz * (fcz - cz) >= 0) continue;   // back face
            double[] c0 = cam(q[0][0], q[0][1], q[0][2]), c1 = cam(q[1][0], q[1][1], q[1][2]),
                c2 = cam(q[2][0], q[2][1], q[2][2]), c3 = cam(q[3][0], q[3][1], q[3][2]);
            if (c0[2] < 0.1 || c1[2] < 0.1 || c2[2] < 0.1 || c3[2] < 0.1) continue;
            float sh = shade[f];
            int color = (Math.round(ta * 255) << 24) | (Math.round(tr * sh) << 16) | (Math.round(tg * sh) << 8) | Math.round(tb * sh);
            queue.add(new Face(screen(c0), screen(c1), screen(c2), cam(fcx, fcy, fcz)[2], tex, uv[f][0], uv[f][1], uv[f][2], uv[f][3], 64, 64, color));
        }
    }
}
