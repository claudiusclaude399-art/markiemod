package dev.markie.lyrics.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.music.MusicPulse;
import dev.markie.lyrics.render.EnvColors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Real 3D objects in the sky, rebuilt every frame on top of the sky backdrop: lit planets with rings and
 * moons, waving aurora curtains, volumetric nebula and galaxy clouds, a drifting cloud layer with parallax
 * (fly and it moves past you), shooting stars, twinkling stars and hyperspace streaks.
 * Everything is in "sky space": the camera sits at the origin, y is up, units are roughly blocks, and
 * objects stay inside the 100-unit backdrop cube. Drawn back to front (the sky has no depth buffer).
 */
public final class Sky3D {
    private static final Identifier ATLAS_ID = Identifier.of("markielyrics", "dynamic/sky_atlas");
    private static final int CELL = 64, ATLAS = 256;
    // atlas cells
    private static final int BLOB = 0, STAR = 1, CURTAIN = 2, STREAK = 3, PUFF = 4, WHITE = 5, DISC = 6;

    private static NativeImageBackedTexture atlas;
    private static BufferAllocator alloc;
    private static BufferBuilder bb;
    private static int quads;

    private Sky3D() {}

    public static NativeImageBackedTexture atlas() {
        if (atlas == null) buildAtlas();
        return atlas;
    }

    /** Builds this frame's geometry for the given sky. Returns null if there's nothing to draw. */
    private static float spinRad;

    public static BuiltBuffer build(int sky, float spin) {
        spinRad = spin;
        if (!LyricsConfig.get().sky3d) return null;
        if (alloc == null) alloc = new BufferAllocator(1 << 18);
        bb = new BufferBuilder(alloc, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        quads = 0;
        double t = (System.currentTimeMillis() % 3_600_000L) / 1000.0;
        float beat = MusicPulse.strength() * MusicPulse.beat();
        float energy = MusicPulse.energy();
        switch (sky) {
            case 0 -> { stars(t, 520, 1, beat); galaxyBand(t, 0.25f, 0.55f, 0.80f, 1f, beat); nebula(t, 40, 0.5f, 3, beat); shootingStars(t, 5.0); }
            case 1 -> { stars(t, 380, 2, beat); aurora(t, energy, beat); shootingStars(t, 9.0); }
            case 2 -> { stars(t, 420, 3, beat); nebula(t, 150, 1f, 4, beat); shootingStars(t, 8.0); }
            case 3 -> { stars(t, 90, 4, beat); sun(t, 0f, 0.06f, 1f, 0xFFD08A, 0xFF8A4A, beat); clouds(t, 0xFFC2C2, 0x9A4A7A, 0.55f, 41); }
            case 4 -> { sun(t, 0.5f, 0.12f, 0.85f, 0xFFF0B0, 0xFFC060, beat); clouds(t, 0xFFF0D0, 0xD69A5A, 0.5f, 42); }
            case 5 -> { clouds(t, 0xFFF3FA, 0xF0A8D8, 0.75f, 43); floaters(t, 90, beat); }
            case 6 -> { stars(t, 240, 5, beat); moon(t, -0.4f, 0.5f, 0.75f, 9f, 0xD0303A, -0.6f, 0.4f, 0.5f, 0x9A1020); mist(t, 50, 0x7A1018); }
            case 7 -> { stars(t, 500, 6, beat); nebula(t, 30, 0.35f, 5, beat); planet(t); shootingStars(t, 7.0); }
            case 8 -> hyperspace(t, energy, beat);
            case 9 -> { sun(t, -0.3f, 0.7f, 0.6f, 0xFFFFF0, 0xFFF6D0, beat); clouds(t, 0xFFFFFF, 0xB8C8DC, 0.6f, 44); }
            case 10 -> { stars(t, 820, 7, beat); galaxyBand(t, 0.7f, 0.6f, 0.38f, 0.55f, beat); shootingStars(t, 3.5); }
            case 11 -> { stars(t, 240, 8, beat); moon(t, 0.3f, 0.55f, -0.78f, 6f, 0xFFF4E0, 0.95f, 0.25f, 0.9f, 0xE0C0FF); clouds(t, 0xFFC8E6, 0x7A5AA8, 0.35f, 45); }
            default -> {}
        }
        if (quads == 0) { bb.endNullable(); return null; }
        return bb.end();
    }

    public static int indexCount() { return quads * 6; }

    // ------------------------------------------------------------------ scene pieces

    private static void stars(double t, int n, int seed, float beat) {
        for (int i = 0; i < n; i++) {
            float[] d = randDir(i, seed);
            if (d[1] < -0.15f) continue;
            float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(t * (1.2 + h(i, seed, 3) * 3) + h(i, seed, 4) * 40), 3);
            float a = (0.25f + 0.75f * tw) * (0.5f + 0.5f * h(i, seed, 5)) * (1f + beat * 0.8f);
            float size = 0.25f + 0.6f * h(i, seed, 6) * h(i, seed, 6);
            int col = h(i, seed, 7) < 0.2f ? 0xBFD8FF : h(i, seed, 7) > 0.85f ? 0xFFE7B0 : 0xFFFFFF;
            float r = 92f;
            billboard(d[0] * r, d[1] * r, d[2] * r, size * 2.2f, size * 2.2f, 0, STAR, argb(Math.min(1, a), tint(col, 0.3f)));
        }
    }

    /** A band of soft clouds along a great circle, at several depths, slowly turning. */
    private static void galaxyBand(double t, float nx, float ny, float nz, float strength, float beat) {
        float[] n = norm(nx, ny, nz);
        float[] e1 = norm(cross(n, new float[]{0, 1, 0}));
        float[] e2 = cross(n, e1);
        int count = Math.round(240 * strength);
        for (int i = 0; i < count; i++) {
            double th = h(i, 20, 1) * Math.PI * 2 + t * 0.004;
            float off = (float) (gauss(i, 21) * 0.11);
            float[] d = norm(e1[0] * (float) Math.cos(th) + e2[0] * (float) Math.sin(th) + n[0] * off,
                e1[1] * (float) Math.cos(th) + e2[1] * (float) Math.sin(th) + n[1] * off,
                e1[2] * (float) Math.cos(th) + e2[2] * (float) Math.sin(th) + n[2] * off);
            if (d[1] < -0.2f) continue;
            float r = 62f + 30f * h(i, 20, 2);
            float size = 5f + 11f * h(i, 20, 3);
            float k = h(i, 20, 4);
            int col = k < 0.33f ? 0xFF7AC8 : k < 0.66f ? 0x8A5CF6 : 0x6FA8FF;
            float a = (0.05f + 0.08f * h(i, 20, 5)) * strength * (1f + beat * 0.5f);
            billboard(d[0] * r, d[1] * r, d[2] * r, size, size, h(i, 20, 6) * 6.28f, PUFF, argb(a, tint(col, 0.55f)));
        }
    }

    /** Clustered coloured cloud puffs at different depths that slowly swirl. */
    private static void nebula(double t, int n, float strength, int seed, float beat) {
        float[][] centers = {randDir(1, seed * 7), randDir(2, seed * 7), randDir(3, seed * 7)};
        for (float[] c : centers) c[1] = Math.abs(c[1]) * 0.7f + 0.2f;
        int[] cols = {0xFF4FA3, 0x3A7BFF, 0x8A3CFF, 0xFF8AD0};
        for (int i = 0; i < n; i++) {
            float[] c = centers[i % 3];
            double sw = t * 0.01 * (0.5 + h(i, seed, 9));
            float jx = (float) (gauss(i, seed + 1) * 0.35), jy = (float) (gauss(i, seed + 2) * 0.25), jz = (float) (gauss(i, seed + 3) * 0.35);
            float cs = (float) Math.cos(sw), sn = (float) Math.sin(sw);
            float[] d = norm(c[0] + jx * cs - jz * sn, c[1] + jy, c[2] + jx * sn + jz * cs);
            float r = 52f + 40f * h(i, seed, 4);
            float size = 12f + 22f * h(i, seed, 5);
            float a = (0.05f + 0.09f * h(i, seed, 6)) * strength * (1f + beat * 0.6f);
            billboard(d[0] * r, d[1] * r, d[2] * r, size, size, (float) (h(i, seed, 7) * 6.28 + t * 0.02), PUFF,
                argb(a, tint(cols[(int) (h(i, seed, 8) * cols.length) % cols.length], 0.6f)));
        }
    }

    /** Three waving curtains of light made of real vertical ribbons. */
    private static void aurora(double t, float energy, float beat) {
        int[][] palette = {{0x39FFB0, 0x9B6BFF}, {0x6BFFD6, 0xFF6BD6}, {0x39FF7A, 0x5AA8FF}};
        for (int c = 0; c < 3; c++) {
            float az0 = (float) Math.toRadians(-40 + c * 75), span = (float) Math.toRadians(150);
            float y0 = 10f + c * 5f, hgt = 26f + c * 7f;
            int seg = 90;
            float bright = (0.45f + 0.35f * energy + 0.5f * beat) * (c == 0 ? 1f : 0.7f);
            int bottom = tint(palette[c][0], 0.5f), top = tint(palette[c][1], 0.5f);
            for (int i = 0; i < seg; i++) {
                float a0 = az0 + span * (i / (float) seg - 0.5f), a1 = az0 + span * ((i + 1) / (float) seg - 0.5f);
                float[] p0 = curtainPoint(a0, t, c), p1 = curtainPoint(a1, t, c);
                float h0 = hgt * (0.75f + 0.25f * (float) Math.sin(a0 * 5 + t * 0.5 + c));
                float h1 = hgt * (0.75f + 0.25f * (float) Math.sin(a1 * 5 + t * 0.5 + c));
                float r0 = rays(a0, t, c), r1 = rays(a1, t, c);
                float edge0 = edge(i / (float) seg), edge1 = edge((i + 1) / (float) seg);
                int cb0 = argb(bright * r0 * edge0, bottom), cb1 = argb(bright * r1 * edge1, bottom);
                int ct0 = argb(bright * r0 * edge0 * 0.6f, top), ct1 = argb(bright * r1 * edge1 * 0.6f, top);
                float[] uv = cellUV(CURTAIN);
                quad(p0[0], y0, p0[1], uv[0], uv[3], cb0,
                     p1[0], y0, p1[1], uv[2], uv[3], cb1,
                     p1[0], y0 + h1, p1[1], uv[2], uv[1], ct1,
                     p0[0], y0 + h0, p0[1], uv[0], uv[1], ct0);
            }
        }
    }

    private static float[] curtainPoint(float az, double t, int c) {
        float r = 80f + (float) (Math.sin(az * 3 + t * 0.35 + c) * 7 + Math.sin(az * 7 - t * 0.6 + c * 2) * 2.5);
        return new float[]{(float) Math.sin(az) * r, (float) Math.cos(az) * r};
    }

    private static float rays(float az, double t, int c) {
        float v = (float) (0.5 + 0.5 * Math.sin(az * 46 + t * 1.3 + c * 3 + Math.sin(az * 9 + t * 0.4) * 2));
        return 0.35f + 0.65f * v * v * v;
    }

    private static float edge(float f) { return Math.min(1f, Math.min(f, 1 - f) * 6f); }

    /** Sun disc with a soft corona, a wide glow and a little lens streak. */
    private static void sun(double t, float x, float y, float z, int core, int halo, float beat) {
        float[] d = norm(x, y, z);
        float r = 90f;
        float px = d[0] * r, py = d[1] * r, pz = d[2] * r;
        billboard(px, py, pz, 60f, 60f, 0, BLOB, argb(0.18f, tint(halo, 0.4f)));
        billboard(px, py, pz, 22f * (1f + beat * 0.15f), 22f * (1f + beat * 0.15f), 0, BLOB, argb(0.5f, tint(core, 0.3f)));
        billboard(px, py, pz, 40f, 1.6f, 0, STREAK, argb(0.35f, 0xFFFFFF));
        billboard(px, py, pz, 5.5f, 5.5f, 0, DISC, argb(1f, 0xFFFBEA));
    }

    /** Puffy clouds on a layer above you. The layer is anchored in the world (with parallax), so it moves past as you travel. */
    private static void clouds(double t, int topCol, int shadeCol, float cover, int seed) {
        Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
        Vec3d cp = cam.getCameraPos();
        float tile = 26f, view = 96f;
        // world-anchored: rotate the camera offset into sky space (the sky slowly spins)
        double wx = cp.x * 0.3 + t * 1.1, wz = cp.z * 0.3;
        double cs = Math.cos(-spinRad), sn = Math.sin(-spinRad);
        double ox = wx * cs + wz * sn, oz = -wx * sn + wz * cs;
        float cy = 26f - (float) Math.max(-10, Math.min(16, (cp.y - 90) * 0.15));
        int ix0 = (int) Math.floor((ox - view) / tile), ix1 = (int) Math.floor((ox + view) / tile);
        int iz0 = (int) Math.floor((oz - view) / tile), iz1 = (int) Math.floor((oz + view) / tile);
        int top = tint(topCol, 0.35f), shade = tint(shadeCol, 0.5f);
        // far cells first (back to front)
        java.util.List<float[]> puffs = new java.util.ArrayList<>();
        for (int ix = ix0; ix <= ix1; ix++) for (int iz = iz0; iz <= iz1; iz++) {
            float hc = hh(ix, iz, seed);
            if (hc > cover) continue;
            int count = 1 + (int) (hh(ix, iz, seed + 1) * 3);
            for (int k = 0; k < count; k++) {
                float x = (float) (ix * tile + hh(ix * 3 + k, iz, seed + 2) * tile - ox);
                float z = (float) (iz * tile + hh(ix, iz * 3 + k, seed + 3) * tile - oz);
                float dist = (float) Math.sqrt(x * x + z * z);
                if (dist > view) continue;
                float y = cy + hh(ix + k, iz - k, seed + 4) * 5f;
                float size = 9f + 9f * hh(ix - k, iz + k, seed + 5);
                puffs.add(new float[]{x, y, z, size, dist});
            }
        }
        puffs.sort((a, b) -> Float.compare(b[4], a[4]));
        for (float[] p : puffs) {
            float fade = (1f - smooth(view * 0.65f, view, p[4]));
            billboard(p[0], p[1] - 1.2f, p[2], p[3] * 1.15f, p[3] * 0.8f, 0, PUFF, argb(0.55f * fade, shade));
            billboard(p[0], p[1] + 0.8f, p[2], p[3], p[3] * 0.75f, 1.3f, PUFF, argb(0.75f * fade, top));
        }
    }

    /** Small glowing sparkles floating around (dreamy skies). */
    private static void floaters(double t, int n, float beat) {
        for (int i = 0; i < n; i++) {
            float[] d = randDir(i, 30);
            d[1] = Math.abs(d[1]) * 0.6f + 0.05f;
            float r = 35f + 45f * h(i, 30, 2);
            float bob = (float) Math.sin(t * 0.6 + i) * 1.5f;
            float a = (0.3f + 0.4f * (float) Math.pow(0.5 + 0.5 * Math.sin(t * 2 + i * 7), 2)) * (1f + beat);
            billboard(d[0] * r, d[1] * r + bob, d[2] * r, 0.9f, 0.9f, (float) t, STAR, argb(Math.min(1, a), tint(0xFFFFFF, 0.4f)));
        }
    }

    /** Low drifting mist near the horizon. */
    private static void mist(double t, int n, int col) {
        for (int i = 0; i < n; i++) {
            double az = h(i, 40, 1) * Math.PI * 2 + t * 0.01;
            float y = 2f + 8f * h(i, 40, 2);
            float r = 60f + 30f * h(i, 40, 3);
            billboard((float) Math.sin(az) * r, y, (float) Math.cos(az) * r, 18f + 14f * h(i, 40, 4), 7f, 0, PUFF,
                argb(0.12f, tint(col, 0.5f)));
        }
    }

    /** A lit sphere moon with craters; lighting from (lx,ly,lz). A light from behind makes a crescent. */
    private static void moon(double t, float x, float y, float z, float radius, int col, float lx, float ly, float lz, int glowCol) {
        float[] d = norm(x, y, z);
        float dist = 80f;
        float cx = d[0] * dist, cy = d[1] * dist, cz = d[2] * dist;
        billboard(cx, cy, cz, radius * 3.2f, radius * 3.2f, 0, BLOB, argb(0.3f, tint(glowCol, 0.4f)));
        float[] l = norm(lx, ly, lz);
        int base = tint(col, 0.25f);
        sphere(cx, cy, cz, radius, 18, 32, 0, (n, lat, lon) -> {
            float crater = SkyLibrary.fbm(n[0] * 3f + 7, n[1] * 3f, n[2] * 3f, 4, 90);
            float shade = 0.65f + 0.45f * crater;
            float light = Math.max(0f, n[0] * l[0] + n[1] * l[1] + n[2] * l[2]);
            float v = Math.min(1.2f, 0.04f + light * 1.05f) * shade;
            return argb(Math.min(1f, 0.15f + light * 1.2f), scale(base, v));
        });
    }

    /** A banded gas giant with a tilted ring passing in front of and behind it, and a small orbiting moon. */
    private static void planet(double t) {
        float[] d = norm(0.55f, 0.38f, 0.74f);
        float dist = 78f, R = 15f;
        float cx = d[0] * dist, cy = d[1] * dist, cz = d[2] * dist;
        float[] light = norm(-0.7f, 0.35f, 0.3f);
        float spin = (float) (t * 0.05);
        int c1 = tint(0xE8C48A, 0.45f), c2 = tint(0xB9824E, 0.45f), storm = tint(0xD9653A, 0.3f);

        // ring geometry: tilted plane around the planet
        float tilt = 0.45f;
        float[] up = norm(0.15f, (float) Math.cos(tilt), (float) Math.sin(tilt));
        float[] e1 = norm(cross(up, new float[]{0, 0, 1}));
        float[] e2 = cross(up, e1);
        float ringIn = R * 1.35f, ringOut = R * 2.3f;
        int segs = 96, bands = 10;
        float centerDist = dist;

        // pass 1: the ring half behind the planet
        ring(cx, cy, cz, e1, e2, ringIn, ringOut, segs, bands, light, true, centerDist, t);
        // the planet
        billboard(cx, cy, cz, R * 2.6f, R * 2.6f, 0, BLOB, argb(0.22f, tint(0xFFD9A0, 0.4f)));
        sphere(cx, cy, cz, R, 22, 40, spin, (n, lat, lon) -> {
            float bandsV = 0.5f + 0.5f * (float) Math.sin(lat * 13f + SkyLibrary.fbm(lon * 2f, lat * 3f, 1f, 3, 91) * 3f);
            int col = mixRgb(c1, c2, bandsV);
            // a storm spot that rotates with the planet
            float dl = (float) Math.atan2(Math.sin(lon - 1.0), Math.cos(lon - 1.0));
            float sp = (float) Math.exp(-(dl * dl * 9 + (lat + 0.35f) * (lat + 0.35f) * 60));
            col = mixRgb(col, storm, Math.min(1, sp * 1.4f));
            float lit = Math.max(0f, n[0] * light[0] + n[1] * light[1] + n[2] * light[2]);
            return argb(1f, scale(col, Math.min(1.15f, 0.06f + lit * 1.1f)));
        });
        // pass 2: the ring half in front
        ring(cx, cy, cz, e1, e2, ringIn, ringOut, segs, bands, light, false, centerDist, t);
        // a little moon orbiting in the ring plane
        double ma = t * 0.12;
        float mr = R * 3.1f;
        float mx = cx + (float) (e1[0] * Math.cos(ma) + e2[0] * Math.sin(ma)) * mr;
        float my = cy + (float) (e1[1] * Math.cos(ma) + e2[1] * Math.sin(ma)) * mr;
        float mz = cz + (float) (e1[2] * Math.cos(ma) + e2[2] * Math.sin(ma)) * mr;
        int mc = tint(0xCFCFD8, 0.3f);
        sphere(mx, my, mz, R * 0.18f, 10, 16, 0, (n, lat, lon) -> {
            float lit = Math.max(0f, n[0] * light[0] + n[1] * light[1] + n[2] * light[2]);
            return argb(1f, scale(mc, 0.08f + lit));
        });
    }

    private static void ring(float cx, float cy, float cz, float[] e1, float[] e2, float rin, float rout, int segs, int bands,
                             float[] light, boolean back, float centerDist, double t) {
        float[] uv = cellUV(WHITE);
        int col = tint(0xD9C3A0, 0.45f);
        for (int b = 0; b < bands; b++) {
            float r0 = rin + (rout - rin) * b / bands, r1 = rin + (rout - rin) * (b + 1) / bands;
            float mid = (r0 + r1) / 2 / rin;
            float alpha = (0.35f + 0.4f * (float) Math.abs(Math.sin(mid * 21f))) * (mid > 1.3f && mid < 1.37f ? 0.15f : 1f);
            for (int s = 0; s < segs; s++) {
                double a0 = s * Math.PI * 2 / segs, a1 = (s + 1) * Math.PI * 2 / segs;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                float mx = cx + (e1[0] * (c0 + c1) + e2[0] * (s0 + s1)) * 0.5f * (r0 + r1) * 0.5f;
                float my = cy + (e1[1] * (c0 + c1) + e2[1] * (s0 + s1)) * 0.5f * (r0 + r1) * 0.5f;
                float mz = cz + (e1[2] * (c0 + c1) + e2[2] * (s0 + s1)) * 0.5f * (r0 + r1) * 0.5f;
                boolean behind = Math.sqrt(mx * mx + my * my + mz * mz) > centerDist;
                if (behind != back) continue;
                // the planet's shadow falls across the ring on the far side from the light
                float sd = -((e1[0] * c0 + e2[0] * s0) * light[0] + (e1[1] * c0 + e2[1] * s0) * light[1] + (e1[2] * c0 + e2[2] * s0) * light[2]);
                float shadow = sd > 0.75f ? 0.35f : 1f;
                int cc = argb(alpha, scale(col, 0.85f * shadow));
                quad(cx + (e1[0] * c0 + e2[0] * s0) * r0, cy + (e1[1] * c0 + e2[1] * s0) * r0, cz + (e1[2] * c0 + e2[2] * s0) * r0, uv[0], uv[1], cc,
                     cx + (e1[0] * c1 + e2[0] * s1) * r0, cy + (e1[1] * c1 + e2[1] * s1) * r0, cz + (e1[2] * c1 + e2[2] * s1) * r0, uv[0], uv[1], cc,
                     cx + (e1[0] * c1 + e2[0] * s1) * r1, cy + (e1[1] * c1 + e2[1] * s1) * r1, cz + (e1[2] * c1 + e2[2] * s1) * r1, uv[0], uv[1], cc,
                     cx + (e1[0] * c0 + e2[0] * s0) * r1, cy + (e1[1] * c0 + e2[1] * s0) * r1, cz + (e1[2] * c0 + e2[2] * s0) * r1, uv[0], uv[1], cc);
            }
        }
    }

    /** A streak of light now and then, sliding across the sky. */
    private static void shootingStars(double t, double every) {
        long slot = (long) Math.floor(t / every);
        for (long s = slot - 1; s <= slot; s++) {
            double start = s * every + h((int) s, 50, 1) * every * 0.6;
            double life = 0.9;
            double p = (t - start) / life;
            if (p < 0 || p > 1) continue;
            float[] d0 = randDir((int) s, 51);
            d0[1] = Math.abs(d0[1]) * 0.6f + 0.25f;
            d0 = norm(d0[0], d0[1], d0[2]);
            float[] dir = norm(cross(d0, randDir((int) s, 52)));
            if (dir[1] > 0) { dir[0] = -dir[0]; dir[1] = -dir[1]; dir[2] = -dir[2]; }
            float r = 88f;
            float travel = 0.45f;
            float hx = (d0[0] + dir[0] * travel * (float) p) * r, hy = (d0[1] + dir[1] * travel * (float) p) * r, hz = (d0[2] + dir[2] * travel * (float) p) * r;
            float tl = 0.12f * (float) Math.min(1, p * 4);
            float tx = hx - dir[0] * tl * r, ty = hy - dir[1] * tl * r, tz = hz - dir[2] * tl * r;
            float a = (float) Math.sin(p * Math.PI);
            streak(tx, ty, tz, hx, hy, hz, 0.55f, STREAK, argb(a * 0.9f, 0xFFFFFF));
            billboard(hx, hy, hz, 1.6f, 1.6f, 0, BLOB, argb(a, tint(0xDDE8FF, 0.3f)));
        }
    }

    /** Streaks of light rushing at you from where you're looking. */
    private static void hyperspace(double t, float energy, float beat) {
        Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
        float[] f0 = vec(Vec3d.fromPolar(cam.getPitch(), cam.getYaw()));
        float cs = (float) Math.cos(-spinRad), sn = (float) Math.sin(-spinRad);
        float[] f = {f0[0] * cs + f0[2] * sn, f0[1], -f0[0] * sn + f0[2] * cs};
        float[] e1 = norm(cross(f, Math.abs(f[1]) > 0.95f ? new float[]{1, 0, 0} : new float[]{0, 1, 0}));
        float[] e2 = cross(f, e1);
        float speed = 30f * (0.6f + energy + beat);
        billboard(f[0] * 90, f[1] * 90, f[2] * 90, 30f, 30f, 0, BLOB, argb(0.35f, tint(0x3A6BFF, 0.5f)));
        billboard(f[0] * 90, f[1] * 90, f[2] * 90, 8f, 8f, 0, BLOB, argb(0.8f, 0xFFFFFF));
        for (int i = 0; i < 300; i++) {
            double ang = h(i, 60, 1) * Math.PI * 2;
            float lateral = 4f + 40f * h(i, 60, 2);
            float z = 95f - (float) ((t * speed * (0.6 + h(i, 60, 3)) + h(i, 60, 4) * 95) % 95);
            float len = 4f + 10f * (0.6f + energy);
            float ox = (float) Math.cos(ang) * lateral, oy = (float) Math.sin(ang) * lateral;
            float[] head = {f[0] * z + e1[0] * ox + e2[0] * oy, f[1] * z + e1[1] * ox + e2[1] * oy, f[2] * z + e1[2] * ox + e2[2] * oy};
            float z2 = Math.min(95f, z + len);
            float[] tail = {f[0] * z2 + e1[0] * ox + e2[0] * oy, f[1] * z2 + e1[1] * ox + e2[1] * oy, f[2] * z2 + e1[2] * ox + e2[2] * oy};
            float a = smooth(95f, 70f, z) * smooth(2f, 14f, z);
            int col = tint(h(i, 60, 5) < 0.3f ? 0x9FD0FF : 0xFFFFFF, 0.4f);
            streak(tail[0], tail[1], tail[2], head[0], head[1], head[2], 0.35f, STREAK, argb(a, col));
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    private interface ColorFn { int at(float[] n, float lat, float lon); }

    /** Lat/long sphere; {@code spin} turns the surface around the vertical axis. */
    private static void sphere(float cx, float cy, float cz, float r, int latN, int lonN, float spin, ColorFn fn) {
        float[] uv = cellUV(WHITE);
        for (int i = 0; i < latN; i++) {
            float la0 = (float) (-Math.PI / 2 + Math.PI * i / latN), la1 = (float) (-Math.PI / 2 + Math.PI * (i + 1) / latN);
            for (int j = 0; j < lonN; j++) {
                float lo0 = (float) (Math.PI * 2 * j / lonN), lo1 = (float) (Math.PI * 2 * (j + 1) / lonN);
                float[] n00 = sph(la0, lo0), n01 = sph(la0, lo1), n11 = sph(la1, lo1), n10 = sph(la1, lo0);
                // only the side facing the camera (the camera is at the origin)
                float[] nm = norm(n00[0] + n11[0], n00[1] + n11[1], n00[2] + n11[2]);
                // a point on the sphere faces us when n . centre < -r
                if (nm[0] * cx + nm[1] * cy + nm[2] * cz > -r * 0.97f) continue;
                quad(cx + n00[0] * r, cy + n00[1] * r, cz + n00[2] * r, uv[0], uv[1], fn.at(n00, la0, lo0 + spin),
                     cx + n01[0] * r, cy + n01[1] * r, cz + n01[2] * r, uv[0], uv[1], fn.at(n01, la0, lo1 + spin),
                     cx + n11[0] * r, cy + n11[1] * r, cz + n11[2] * r, uv[0], uv[1], fn.at(n11, la1, lo1 + spin),
                     cx + n10[0] * r, cy + n10[1] * r, cz + n10[2] * r, uv[0], uv[1], fn.at(n10, la1, lo0 + spin));
            }
        }
    }

    private static float[] sph(float lat, float lon) {
        return new float[]{(float) (Math.cos(lat) * Math.sin(lon)), (float) Math.sin(lat), (float) (Math.cos(lat) * Math.cos(lon))};
    }

    /** Camera-facing sprite. */
    private static void billboard(float x, float y, float z, float hw, float hh, float rot, int cell, int argb) {
        if ((argb >>> 24) < 2) return;
        float[] f = norm(x, y, z);
        float[] up = Math.abs(f[1]) > 0.98f ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
        float[] r = norm(cross(f, up));
        float[] u = cross(r, f);
        if (rot != 0) {
            float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
            float[] r2 = {r[0] * c + u[0] * s, r[1] * c + u[1] * s, r[2] * c + u[2] * s};
            float[] u2 = {u[0] * c - r[0] * s, u[1] * c - r[1] * s, u[2] * c - r[2] * s};
            r = r2; u = u2;
        }
        float[] uv = cellUV(cell);
        quad(x - r[0] * hw - u[0] * hh, y - r[1] * hw - u[1] * hh, z - r[2] * hw - u[2] * hh, uv[0], uv[3], argb,
             x + r[0] * hw - u[0] * hh, y + r[1] * hw - u[1] * hh, z + r[2] * hw - u[2] * hh, uv[2], uv[3], argb,
             x + r[0] * hw + u[0] * hh, y + r[1] * hw + u[1] * hh, z + r[2] * hw + u[2] * hh, uv[2], uv[1], argb,
             x - r[0] * hw + u[0] * hh, y - r[1] * hw + u[1] * hh, z - r[2] * hw + u[2] * hh, uv[0], uv[1], argb);
    }

    /** A strip from a to b, facing the camera. */
    private static void streak(float ax, float ay, float az, float bx, float by, float bz, float w, int cell, int argb) {
        if ((argb >>> 24) < 2) return;
        float[] dir = norm(bx - ax, by - ay, bz - az);
        float[] view = norm((ax + bx) / 2, (ay + by) / 2, (az + bz) / 2);
        float[] side = norm(cross(dir, view));
        float[] uv = cellUV(cell);
        quad(ax - side[0] * w, ay - side[1] * w, az - side[2] * w, uv[0], uv[3], argb,
             bx - side[0] * w, by - side[1] * w, bz - side[2] * w, uv[2], uv[3], argb,
             bx + side[0] * w, by + side[1] * w, bz + side[2] * w, uv[2], uv[1], argb,
             ax + side[0] * w, ay + side[1] * w, az + side[2] * w, uv[0], uv[1], argb);
    }

    /** One quad, written in both windings so it shows whatever way it faces. */
    private static void quad(float x0, float y0, float z0, float u0, float v0, int c0,
                             float x1, float y1, float z1, float u1, float v1, int c1,
                             float x2, float y2, float z2, float u2, float v2, int c2,
                             float x3, float y3, float z3, float u3, float v3, int c3) {
        bb.vertex(x0, y0, z0).texture(u0, v0).color(c0);
        bb.vertex(x1, y1, z1).texture(u1, v1).color(c1);
        bb.vertex(x2, y2, z2).texture(u2, v2).color(c2);
        bb.vertex(x3, y3, z3).texture(u3, v3).color(c3);
        bb.vertex(x3, y3, z3).texture(u3, v3).color(c3);
        bb.vertex(x2, y2, z2).texture(u2, v2).color(c2);
        bb.vertex(x1, y1, z1).texture(u1, v1).color(c1);
        bb.vertex(x0, y0, z0).texture(u0, v0).color(c0);
        quads += 2;
    }

    private static float[] cellUV(int cell) {
        float s = CELL / (float) ATLAS, pad = 1.5f / ATLAS;
        float u0 = (cell % 4) * s + pad, v0 = (cell / 4) * s + pad;
        return new float[]{u0, v0, u0 + s - 2 * pad, v0 + s - 2 * pad};
    }

    // ------------------------------------------------------------------ atlas

    private static void buildAtlas() {
        NativeImage img = new NativeImage(ATLAS, ATLAS, false);
        for (int cell = 0; cell < 16; cell++) {
            int ox = (cell % 4) * CELL, oy = (cell / 4) * CELL;
            for (int y = 0; y < CELL; y++) for (int x = 0; x < CELL; x++) {
                float u = (x + 0.5f) / CELL * 2 - 1, v = (y + 0.5f) / CELL * 2 - 1;
                float d = (float) Math.sqrt(u * u + v * v);
                float a = switch (cell) {
                    case BLOB -> (float) Math.exp(-d * d * 4.5f) * (1 - smooth(0.9f, 1f, d));
                    case STAR -> {
                        float au = Math.abs(u), av = Math.abs(v);
                        float star = Math.max(0, 1f - (float) Math.pow(Math.pow(au, 0.5) + Math.pow(av, 0.5), 2.2));
                        yield Math.min(1f, star * 1.3f + (float) Math.exp(-d * d * 25f));
                    }
                    case CURTAIN -> {
                        float vv = (y + 0.5f) / CELL;    // 0 top, 1 bottom
                        yield (float) Math.pow(vv, 1.6) * (1 - smooth(0.9f, 1f, vv)) * 0.95f + 0.05f * vv;
                    }
                    case STREAK -> (float) Math.exp(-v * v * 30f) * (float) Math.pow((u + 1) / 2, 2.2);
                    case PUFF -> {
                        float n = SkyLibrary.fbm(x * 0.09f + cell, y * 0.09f, 3.3f, 4, 77);
                        float edge = 1 - smooth(0.45f, 1f, d + (n - 0.5f) * 0.45f);
                        yield edge * (0.75f + 0.25f * n);
                    }
                    case WHITE -> 1f;
                    case DISC -> 1 - smooth(0.88f, 1f, d);
                    default -> 0f;
                };
                a = Math.max(0, Math.min(1, a));
                img.setColorArgb(ox + x, oy + y, (Math.round(a * 255) << 24) | 0xFFFFFF);
            }
        }
        atlas = new Smooth(img);
        MinecraftClient.getInstance().getTextureManager().registerTexture(ATLAS_ID, atlas);
    }

    private static final class Smooth extends NativeImageBackedTexture {
        Smooth(NativeImage img) {
            super(() -> "markielyrics sky atlas", img);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }

    // ------------------------------------------------------------------ math

    private static int tint(int rgb, float k) { return EnvColors.tint(rgb, k); }

    private static int argb(float a, int rgb) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int scale(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 255) * k)), g = Math.min(255, Math.round(((rgb >> 8) & 255) * k)),
            b = Math.min(255, Math.round((rgb & 255) * k));
        return (r << 16) | (g << 8) | b;
    }

    private static int mixRgb(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static float h(int i, int seed, int salt) { return SkyLibrary.hash(i, seed, salt, 1234); }
    private static float hh(int x, int z, int seed) { return SkyLibrary.hash(x, z, seed, 4321); }

    private static double gauss(int i, int seed) {
        double u1 = Math.max(1e-6, h(i, seed, 11)), u2 = h(i, seed, 12);
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    private static float[] randDir(int i, int seed) {
        float z = h(i, seed, 1) * 2 - 1;
        double a = h(i, seed, 2) * Math.PI * 2;
        float r = (float) Math.sqrt(1 - z * z);
        return new float[]{(float) Math.cos(a) * r, z, (float) Math.sin(a) * r};
    }

    private static float[] vec(Vec3d v) { return new float[]{(float) v.x, (float) v.y, (float) v.z}; }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] norm(float[] v) { return norm(v[0], v[1], v[2]); }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{x / l, y / l, z / l};
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
