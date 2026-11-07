package dev.markie.lyrics.sky;

/**
 * The built-in skies. Each one is a function from a view direction (x, y, z; y is up) to a colour,
 * so skyboxes are generated seamlessly at any resolution, and preview thumbnails come for free.
 */
public final class SkyLibrary {
    public interface Fn { int color(float x, float y, float z); }
    public record Sky(String name, Fn fn) {}

    public static final Sky[] ALL = {
        new Sky("Galaxy", SkyLibrary::galaxy),
        new Sky("Aurora", SkyLibrary::aurora),
        new Sky("Nebula", SkyLibrary::nebula),
        new Sky("Sunset", SkyLibrary::sunset),
        new Sky("Golden Hour", SkyLibrary::golden),
        new Sky("Cotton Candy", SkyLibrary::cotton),
        new Sky("Blood Moon", SkyLibrary::bloodMoon),
        new Sky("Ringed Planet", SkyLibrary::planet),
        new Sky("Hyperspace", SkyLibrary::hyperspace),
        new Sky("Clear Day", SkyLibrary::clearDay),
        new Sky("Starry Night", SkyLibrary::starry),
        new Sky("Lavender Dusk", SkyLibrary::lavender),
    };

    /** When true, things that the 3D sky draws as real objects are left out of the painted backdrop. */
    static volatile boolean threeD = true;

    private SkyLibrary() {}

    // ------------------------------------------------------------------ skies

    private static int galaxy(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0x2A1448, 0.35f, 0x120C30, 1f, 0x05060F);
        float b = x * 0.25f + y * 0.55f + z * 0.80f;
        b /= 1.0062f;
        float band = (float) Math.exp(-(b * b) / 0.05f);
        float f = fbm(x * 3.5f, y * 3.5f, z * 3.5f, 5, 11);
        float dust = fbm(x * 7f + 10, y * 7f, z * 7f, 4, 12);
        float[] bc = mix(rgb(0x5B3A9E), rgb(0xFF7AC8), f);
        add(c, bc, band * (0.25f + 1.1f * f * f));
        mul(c, 1f - band * smooth(0.55f, 0.75f, dust) * 0.6f);
        float st = stars(x, y, z, 90, 0.35f + band * 0.4f, 1) * 0.8f + stars(x, y, z, 38, 0.10f, 2) * 1.2f;
        add(c, rgb(0xFFFFFF), st);
        return below(c, y);
    }

    private static int aurora(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0x0E2A3A, 0.4f, 0x081A2B, 1f, 0x030A14);
        add(c, rgb(0xFFFFFF), stars(x, y, z, 90, 0.25f, 3) * 0.7f);
        if (y > 0 && !threeD) {
            float a = (float) Math.atan2(x, z);
            float el = (float) Math.asin(Math.min(1, y));
            for (int k = 0; k < 2; k++) {
                float wav = fbm((float) Math.cos(a) * 1.6f + k * 7, (float) Math.sin(a) * 1.6f, k * 3.1f, 3, 20 + k);
                float center = (k == 0 ? 0.42f : 0.75f) + (wav - 0.5f) * 0.6f;
                float d = (el - center) / (k == 0 ? 0.16f : 0.12f);
                float curtain = (float) Math.exp(-d * d) * smooth(0.0f, 0.12f, el);
                float rays = 0.5f + 0.5f * (float) Math.sin(a * 70f + wav * 24f);
                float inten = curtain * (0.35f + 0.65f * rays * rays * rays) * (k == 0 ? 1.1f : 0.6f);
                float[] col = mix(rgb(k == 0 ? 0x39FFB0 : 0xFF6BD6), rgb(0x9B6BFF), smooth(-1f, 1.2f, d));
                add(c, col, inten);
            }
        }
        return below(c, y);
    }

    private static int nebula(float x, float y, float z) {
        float[] c = rgb(0x07050F);
        float f1 = fbm(x * 2.2f, y * 2.2f, z * 2.2f, 5, 30);
        float f2 = fbm(x * 3f + 5, y * 3f, z * 3f, 5, 31);
        add(c, rgb(0xFF4FA3), pow3(f1) * 2.4f);
        add(c, rgb(0x3A7BFF), pow3(f2) * 2.4f);
        add(c, rgb(0x8A3CFF), (f1 * f2) * (f1 * f2) * 3.2f);
        add(c, rgb(0xFFFFFF), stars(x, y, z, 90, 0.4f, 4) * 0.8f + stars(x, y, z, 40, 0.1f, 5));
        return below(c, y);
    }

    private static int sunset(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0xFF9A3D, 0.08f, 0xFF6B6B, 0.25f, 0xC2458C, 0.6f, 0x4B2A7A, 1f, 0x1E1440);
        sun(c, x, y, z, 0f, 0.06f, 1f, 0xFFD08A, 0xFF8A4A);
        if (y > 0.01f && y < 0.35f && !threeD) {
            float cl = fbm(x * 2f, y * 11f, z * 2f, 5, 40);
            float a = smooth(0.52f, 0.72f, cl) * smooth(0.01f, 0.05f, y) * (1f - smooth(0.2f, 0.35f, y));
            blend(c, rgb(0x7A3A6A), a * 0.75f);
        }
        add(c, rgb(0xFFFFFF), stars(x, y, z, 80, 0.15f, 6) * smooth(0.4f, 0.9f, e) * 0.6f);
        return below(c, y);
    }

    private static int golden(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0xFFE0A0, 0.1f, 0xFFC46B, 0.35f, 0x9CC9FF, 1f, 0x3C7BD9);
        sun(c, x, y, z, 0.5f, 0.12f, 0.85f, 0xFFF0B0, 0xFFC060);
        clouds(c, x, y, z, 0xFFF4E0, 0.55f, 41);
        return below(c, y);
    }

    private static int cotton(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0xFFD1E8, 0.3f, 0xE6C6FF, 1f, 0xA9D8FF);
        clouds(c, x, y, z, 0xFFF3FA, 0.8f, 42);
        return below(c, y);
    }

    private static int bloodMoon(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0x4A0A12, 0.3f, 0x22060B, 1f, 0x0A0205);
        add(c, rgb(0xFFD0D0), stars(x, y, z, 90, 0.2f, 7) * 0.5f);
        float mx = -0.4f, my = 0.5f, mz = 0.75f;
        float ml = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        float ang = (float) Math.acos(Math.max(-1, Math.min(1, (x * mx + y * my + z * mz) / ml)));
        float r = 0.075f;
        if (threeD) return below(c, y);
        if (ang < r) {
            float tex = fbm(x * 30, y * 30, z * 30, 4, 50);
            float[] moon = mul(rgb(0xD0303A), 0.7f + 0.4f * tex);
            blend(c, moon, smooth(r, r * 0.92f, ang));
        }
        add(c, rgb(0x9A1020), (float) Math.exp(-Math.max(0, ang - r) * 18f) * 0.6f);
        return below(c, y);
    }

    private static int planet(float x, float y, float z) {
        float[] c = rgb(0x04040C);
        float f = fbm(x * 2f, y * 2f, z * 2f, 4, 60);
        add(c, rgb(0x2A1A5A), pow3(f) * 1.5f);
        add(c, rgb(0xFFFFFF), stars(x, y, z, 90, 0.4f, 8) * 0.8f + stars(x, y, z, 40, 0.1f, 9));
        // planet frame: P = centre, U/V perpendicular
        float px = 0.55f, py = 0.38f, pz = 0.74f;
        float pl = (float) Math.sqrt(px * px + py * py + pz * pz); px /= pl; py /= pl; pz /= pl;
        float ux = pz, uy = 0, uz = -px;
        float ul = (float) Math.sqrt(ux * ux + uz * uz); ux /= ul; uz /= ul;
        float vx = py * uz - pz * uy, vy = pz * ux - px * uz, vz = px * uy - py * ux;
        float w = x * px + y * py + z * pz;
        if (w > 0.2f && !threeD) {
            float u = (x * ux + y * uy + z * uz) / w, v = (x * vx + y * vy + z * vz) / w;
            float R = 0.2f;
            float tilt = 0.35f;
            float ru = u * (float) Math.cos(tilt) - v * (float) Math.sin(tilt);
            float rv = u * (float) Math.sin(tilt) + v * (float) Math.cos(tilt);
            float rr = (float) Math.sqrt(ru * ru + (rv / 0.3f) * (rv / 0.3f)) / R;
            float r = (float) Math.sqrt(u * u + v * v) / R;
            boolean ringFront = rv < 0;
            float ring = 0;
            if (rr > 1.3f && rr < 2.25f) {
                ring = (0.55f + 0.45f * (float) Math.sin(rr * 40f)) * smooth(1.3f, 1.4f, rr) * (1f - smooth(2.1f, 2.25f, rr));
                if (rr > 1.75f && rr < 1.82f) ring *= 0.2f;   // a gap
            }
            if (!ringFront && ring > 0 && r >= 1f) blend(c, rgb(0xD9C3A0), ring * 0.8f);
            if (r < 1f) {
                float zz = (float) Math.sqrt(1 - r * r);
                float light = Math.max(0.08f, (-u * 0.6f + v * 0.35f) / R * 0.5f + zz * 0.8f);
                float bands = 0.5f + 0.5f * (float) Math.sin(v / R * 13f + fbm(u * 8, v * 8, 1, 3, 61) * 3f);
                float[] pc = mix(rgb(0xE8C48A), rgb(0xB9824E), bands);
                c = mul(pc, Math.min(1.2f, light));
                if (!ringFront && ring > 0 && rv > 0) { /* hidden behind planet */ }
            }
            if (ringFront && ring > 0) blend(c, rgb(0xD9C3A0), ring * 0.85f);
        }
        return below(c, y);
    }

    private static int hyperspace(float x, float y, float z) {
        float[] c = rgb(0x02030A);
        float az = Math.abs(z);
        float theta = (float) Math.acos(Math.min(1, az));
        float ang = (float) Math.atan2(y, x);
        int bucket = (int) Math.floor((ang + Math.PI) * 120f / Math.PI);
        float h = hash(bucket, z > 0 ? 1 : 2, 0, 70);
        if (h > 0.55f && !threeD) {
            float start = hash(bucket, 3, 0, 71) * 1.2f;
            float len = 0.15f + hash(bucket, 4, 0, 72) * 0.6f;
            float t = (theta - start) / len;
            if (t > 0 && t < 1) {
                float within = 1f - Math.abs(((ang + (float) Math.PI) * 120f / (float) Math.PI) - bucket - 0.5f) * 2f;
                float br = (float) Math.sin(t * Math.PI) * within * within * (0.5f + h);
                add(c, mix(rgb(0x6FB8FF), rgb(0xFFFFFF), hash(bucket, 5, 0, 73)), br);
            }
        }
        add(c, rgb(0x3A6BFF), (float) Math.exp(-theta * 5f) * 0.8f);
        add(c, rgb(0xFFFFFF), (float) Math.exp(-theta * 30f));
        return below(c, y);
    }

    private static int clearDay(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0xCFE9FF, 0.3f, 0x8CC6FF, 1f, 0x3C8BE6);
        sun(c, x, y, z, -0.3f, 0.7f, 0.6f, 0xFFFFF0, 0xFFF6D0);
        clouds(c, x, y, z, 0xFFFFFF, 0.85f, 43);
        return below(c, y);
    }

    private static int starry(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0x1A2A5A, 0.3f, 0x0C1638, 1f, 0x040818);
        float b = (x * 0.7f + y * 0.6f + z * 0.38f) / 1.0f;
        float band = (float) Math.exp(-(b * b) / 0.06f) * fbm(x * 4, y * 4, z * 4, 4, 80);
        add(c, rgb(0x6A7BD0), band * 0.5f);
        add(c, rgb(0xFFFFFF), stars(x, y, z, 95, 0.55f, 10) * 0.8f + stars(x, y, z, 42, 0.16f, 11) * 1.2f);
        return below(c, y);
    }

    private static int lavender(float x, float y, float z) {
        float e = elev(y);
        float[] c = grad(e, 0f, 0xF2B5D4, 0.15f, 0xB57EDC, 0.5f, 0x5B3E96, 1f, 0x1F1745);
        add(c, rgb(0xFFFFFF), stars(x, y, z, 85, 0.2f, 12) * smooth(0.35f, 0.8f, e) * 0.8f);
        // crescent moon
        float mx = 0.3f, my = 0.55f, mz = -0.78f;
        float ml = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        float a1 = (float) Math.acos(Math.max(-1, Math.min(1, (x * mx + y * my + z * mz) / ml)));
        float ox = mx + 0.035f, oy = my + 0.02f;
        float ol = (float) Math.sqrt(ox * ox + oy * oy + mz * mz);
        float a2 = (float) Math.acos(Math.max(-1, Math.min(1, (x * ox + y * oy + z * mz) / ol)));
        float r = 0.05f;
        if (!threeD) {
            float moon = smooth(r, r * 0.9f, a1) * (1f - smooth(r * 0.95f, r * 0.85f, a2));
            blend(c, rgb(0xFFF4E0), moon);
            add(c, rgb(0xE0C0FF), (float) Math.exp(-Math.max(0, a1 - r) * 20f) * 0.3f);
        }
        return below(c, y);
    }

    // ------------------------------------------------------------------ building blocks

    private static void sun(float[] c, float x, float y, float z, float sx, float sy, float sz, int core, int halo) {
        float l = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        float g = Math.max(0, (x * sx + y * sy + z * sz) / l);
        add(c, rgb(halo), (float) Math.pow(g, 6) * 0.45f);
        add(c, rgb(core), (float) Math.pow(g, 60) * 1.1f);
        if (g > 0.9993f) blend(c, rgb(0xFFFBEA), 1f);
    }

    /** Clouds on a flat layer above you (perspective makes them bunch up at the horizon). */
    private static void clouds(float[] c, float x, float y, float z, int color, float strength, int seed) {
        if (y <= 0.03f || threeD) return;
        float px = x / y, pz = z / y;
        float f = fbm(px * 0.45f, pz * 0.45f, 0.5f, 5, seed);
        float a = smooth(0.5f, 0.72f, f) * smooth(0.03f, 0.2f, y) * strength;
        float[] cc = rgb(color);
        mul(cc, 0.88f + 0.12f * smooth(0.5f, 0.8f, f));
        blend(c, cc, a);
    }

    /** Point stars on a jittered grid. */
    private static float stars(float x, float y, float z, float scale, float density, int seed) {
        float px = x * scale, py = y * scale, pz = z * scale;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
        float h = hash(cx, cy, cz, seed);
        if (h > density) return 0;
        float ox = hash(cx, cy, cz, seed + 101) * 0.7f + 0.15f;
        float oy = hash(cx, cy, cz, seed + 202) * 0.7f + 0.15f;
        float oz = hash(cx, cy, cz, seed + 303) * 0.7f + 0.15f;
        float dx = px - cx - ox, dy = py - cy - oy, dz = pz - cz - oz;
        float size = 0.12f + 0.18f * hash(cx, cy, cz, seed + 404);
        float d2 = (dx * dx + dy * dy + dz * dz) / (size * size);
        return (float) Math.exp(-d2) * (0.35f + 0.65f * hash(cx, cy, cz, seed + 505));
    }

    static float hash(int x, int y, int z, int seed) {
        int h = x * 374761393 + y * 668265263 + z * 1440662683 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777215f;
    }

    static float noise(float x, float y, float z, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        float fx = x - x0, fy = y - y0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        float a = lerp(hash(x0, y0, z0, seed), hash(x0 + 1, y0, z0, seed), fx);
        float b = lerp(hash(x0, y0 + 1, z0, seed), hash(x0 + 1, y0 + 1, z0, seed), fx);
        float c = lerp(hash(x0, y0, z0 + 1, seed), hash(x0 + 1, y0, z0 + 1, seed), fx);
        float d = lerp(hash(x0, y0 + 1, z0 + 1, seed), hash(x0 + 1, y0 + 1, z0 + 1, seed), fx);
        return lerp(lerp(a, b, fy), lerp(c, d, fy), fz);
    }

    static float fbm(float x, float y, float z, int octaves, int seed) {
        float sum = 0, amp = 0.5f, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x, y, z, seed + i * 17) * amp;
            norm += amp;
            amp *= 0.5f;
            x *= 2.03f; y *= 2.03f; z *= 2.03f;
        }
        return sum / norm;
    }

    /** 0 at the horizon, 1 straight up. */
    private static float elev(float y) {
        return y <= 0 ? 0 : (float) (Math.asin(Math.min(1, y)) / (Math.PI / 2));
    }

    /** Multi-stop gradient: pairs of (position, rgb). */
    private static float[] grad(float t, Object... stops) {
        float prevPos = (float) stops[0];
        int prevCol = (int) stops[1];
        if (t <= prevPos) return rgb(prevCol);
        for (int i = 2; i < stops.length; i += 2) {
            float pos = (float) stops[i];
            int col = (int) stops[i + 1];
            if (t <= pos) {
                float f = (t - prevPos) / (pos - prevPos);
                f = f * f * (3 - 2 * f);
                return mix(rgb(prevCol), rgb(col), f);
            }
            prevPos = pos;
            prevCol = col;
        }
        return rgb(prevCol);
    }

    /** Below the horizon the sky fades darker (terrain normally covers it anyway). */
    private static int below(float[] c, float y) {
        if (y < 0) mul(c, Math.max(0.25f, 1f + y * 1.6f));
        return pack(c);
    }

    static float[] rgb(int c) { return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f}; }
    static float[] mix(float[] a, float[] b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
    static void add(float[] c, float[] col, float k) { c[0] += col[0] * k; c[1] += col[1] * k; c[2] += col[2] * k; }
    static void blend(float[] c, float[] col, float a) {
        a = Math.max(0, Math.min(1, a));
        c[0] += (col[0] - c[0]) * a; c[1] += (col[1] - c[1]) * a; c[2] += (col[2] - c[2]) * a;
    }
    static float[] mul(float[] c, float k) { c[0] *= k; c[1] *= k; c[2] *= k; return c; }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
    static float pow3(float v) { return v * v * v; }

    static int pack(float[] c) {
        int r = Math.round(Math.max(0, Math.min(1, c[0])) * 255);
        int g = Math.round(Math.max(0, Math.min(1, c[1])) * 255);
        int b = Math.round(Math.max(0, Math.min(1, c[2])) * 255);
        return (r << 16) | (g << 8) | b;
    }
}
