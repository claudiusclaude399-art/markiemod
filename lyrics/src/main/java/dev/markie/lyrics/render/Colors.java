package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.gui.Themes;

/** Works out lyric colours for the chosen colour mode (theme, custom, rainbow, album cover). */
public final class Colors {
    private Colors() {}

    public static int colorA() {
        LyricsConfig c = LyricsConfig.get();
        return switch (c.colorMode) {
            case THEME -> Themes.current().a();
            case CUSTOM -> c.customA;
            case RAINBOW -> hsv(hueNow(0), 0.55f, 1f);
            case ALBUM -> CoverArt.hasPalette() ? CoverArt.paletteA() : Themes.current().a();
        };
    }

    public static int colorB() {
        LyricsConfig c = LyricsConfig.get();
        return switch (c.colorMode) {
            case THEME -> Themes.current().b();
            case CUSTOM -> c.customB;
            case RAINBOW -> hsv(hueNow(0.5f), 0.55f, 1f);
            case ALBUM -> CoverArt.hasPalette() ? CoverArt.paletteB() : Themes.current().b();
        };
    }

    public static int glow() {
        LyricsConfig c = LyricsConfig.get();
        return switch (c.colorMode) {
            case THEME -> Themes.current().glow();
            case CUSTOM -> c.customGlow;
            case RAINBOW -> hsv(hueNow(0.25f), 0.8f, 0.85f);
            case ALBUM -> CoverArt.hasPalette() ? scale(CoverArt.paletteA(), 0.6f) : Themes.current().glow();
        };
    }

    /** RGB for word i of n. {@code xFrac} is the word's position across the line (0..1). */
    public static int word(int i, int n, float xFrac) {
        LyricsConfig c = LyricsConfig.get();
        int rgb;
        if (c.colorMode == LyricsConfig.ColorMode.RAINBOW) {
            rgb = hsv(hueNow(xFrac * c.rainbowSpread), 0.5f, 1f);
        } else {
            rgb = LyricsAnimator.lerpRgb(colorA(), colorB(), n <= 1 ? 0f : i / (float) (n - 1));
        }
        if (c.shimmer) {
            // a band of light sweeping across the line every couple of seconds
            float t = (System.currentTimeMillis() % 2600L) / 2600f * 1.8f - 0.4f;
            float f = Math.max(0f, 1f - Math.abs(t - xFrac) * 4f);
            if (f > 0) rgb = LyricsAnimator.lerpRgb(rgb, 0xFFFFFF, f * 0.55f);
        }
        return rgb;
    }

    private static float hueNow(float offset) {
        float speed = LyricsConfig.get().rainbowSpeed;
        float t = (System.currentTimeMillis() % 1_000_000L) / 1000f * speed * 0.12f;
        float h = (t + offset) % 1f;
        return h < 0 ? h + 1 : h;
    }

    public static int hsv(float h, float s, float v) {
        h = (h % 1f + 1f) % 1f;
        float r, g, b;
        int i = (int) (h * 6f);
        float f = h * 6f - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** {h, s, v} of an RGB colour. */
    public static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) / 6f;
        else if (max == g) h = ((b - r) / d + 2) / 6f;
        else h = ((r - g) / d + 4) / 6f;
        if (h < 0) h += 1;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    private static int scale(int rgb, float f) {
        int r = Math.round(((rgb >> 16) & 255) * f), g = Math.round(((rgb >> 8) & 255) * f), b = Math.round((rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
