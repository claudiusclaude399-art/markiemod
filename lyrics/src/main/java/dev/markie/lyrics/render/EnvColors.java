package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.spotify.SpotifyClient;

/**
 * The album cover's main colours, smoothed over time, for tinting the world: sky, fog, skybox, 3D sky
 * objects, particles, light show and (in Sky colour mode) the lyrics. When the song changes the colours
 * fade over to the new cover instead of snapping.
 */
public final class EnvColors {
    private static final float[] main = {0.6f, 0.55f, 0.85f}, second = {0.85f, 0.6f, 0.8f};
    private static float presence = 0f;

    private EnvColors() {}

    public static void update(float dt) {
        LyricsConfig c = LyricsConfig.get();
        boolean has = c.coverEnv && SpotifyClient.current() != null && CoverArt.hasPalette();
        float k = 1f - (float) Math.exp(-1.6f * dt);
        presence += ((has ? 1f : 0f) - presence) * k;
        if (has) {
            approach(main, CoverArt.paletteA(), k);
            approach(second, CoverArt.paletteB(), k);
        }
    }

    private static void approach(float[] cur, int rgb, float k) {
        cur[0] += (((rgb >> 16) & 255) / 255f - cur[0]) * k;
        cur[1] += (((rgb >> 8) & 255) / 255f - cur[1]) * k;
        cur[2] += ((rgb & 255) / 255f - cur[2]) * k;
    }

    /** 0..1 how much the cover colours apply right now (fades in/out). */
    public static float amount() { return presence * LyricsConfig.get().coverEnvStrength; }

    public static boolean active() { return amount() > 0.01f; }

    public static int main() { return pack(main); }
    public static int second() { return pack(second); }

    /**
     * Shift a colour towards the cover's main colour while keeping its brightness, so a dark night sky stays
     * dark and a bright day sky stays bright, just in the cover's hue. {@code k} = how far (0..1).
     */
    public static int tint(int rgb, float k) {
        float amt = amount() * k;
        if (amt <= 0.001f) return rgb;
        return tintWith(rgb, main, amt);
    }

    /** Same, towards the second cover colour (for variety, e.g. alternate particles). */
    public static int tint2(int rgb, float k) {
        float amt = amount() * k;
        if (amt <= 0.001f) return rgb;
        return tintWith(rgb, second, amt);
    }

    private static int tintWith(int rgb, float[] target, float amt) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        float tl = Math.max(0.05f, 0.299f * target[0] + 0.587f * target[1] + 0.114f * target[2]);
        float s = lum / tl;
        float tr = Math.min(1f, target[0] * s), tg = Math.min(1f, target[1] * s), tb = Math.min(1f, target[2] * s);
        r += (tr - r) * amt; g += (tg - g) * amt; b += (tb - b) * amt;
        return (Math.round(Math.min(1, r) * 255) << 16) | (Math.round(Math.min(1, g) * 255) << 8) | Math.round(Math.min(1, b) * 255);
    }

    private static int pack(float[] c) {
        return (Math.round(Math.min(1, c[0]) * 255) << 16) | (Math.round(Math.min(1, c[1]) * 255) << 8) | Math.round(Math.min(1, c[2]) * 255);
    }
}
