package dev.pvpmod.gui;

public final class ColorUtil {
    public static final float[] HUES = {0f, 28f, 52f, 120f, 170f, 215f, 265f, 320f};
    public static final float[][] ROWS = {{0.35f, 1f}, {0.65f, 1f}, {1f, 1f}, {1f, 0.7f}, {1f, 0.4f}};
    public static final int PAL_COLS = HUES.length + 1;
    public static final int PAL_ROWS = ROWS.length;

    private ColorUtil() {}

    public static int hsv(float h, float s, float v) {
        float c = v * s;
        float hh = ((h % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs(hh % 2f - 1f));
        float m = v - c;
        float r = 0, g = 0, b = 0;
        if (hh < 1) { r = c; g = x; }
        else if (hh < 2) { r = x; g = c; }
        else if (hh < 3) { g = c; b = x; }
        else if (hh < 4) { g = x; b = c; }
        else if (hh < 5) { r = x; b = c; }
        else { r = c; b = x; }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    public static float hueOf(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d == 0) return 0f;
        float h;
        if (max == r) h = ((g - b) / d) % 6f;
        else if (max == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    /** Palette colour: column 0 is greys, the other columns are hues; rows go light to dark. */
    public static int palette(int col, int row) {
        if (col == 0) return hsv(0f, 0f, 1f - row / (float) (PAL_ROWS - 1));
        float[] sv = ROWS[row];
        return hsv(HUES[col - 1], sv[0], sv[1]);
    }
}
