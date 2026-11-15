package dev.pvpmod.glass;

/** iOS-style dark palette and ARGB helpers. */
public final class Col {
    private Col() {}

    public static final int RED = 0xFF453A, ORANGE = 0xFF9F0A, YELLOW = 0xFFD60A, GREEN = 0x30D158,
        MINT = 0x66D4CF, TEAL = 0x40C8E0, CYAN = 0x64D2FF, BLUE = 0x0A84FF, INDIGO = 0x5E5CE6,
        PURPLE = 0xBF5AF2, PINK = 0xFF375F, GRAY = 0x8E8E93;

    public static final int LABEL = 0xFFFFFFFF;
    public static final int LABEL2 = 0xB3EBEBF5;   // secondary
    public static final int LABEL3 = 0x66EBEBF5;   // tertiary

    /** rgb + alpha (0..1) to ARGB. */
    public static int a(int rgb, float alpha) {
        int al = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    /** Multiply the alpha of an ARGB colour. */
    public static int fade(int argb, float k) {
        int al = Math.max(0, Math.min(255, Math.round((argb >>> 24) * k)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((c1 >>> 24) & 255) + ((((c2 >>> 24) & 255) - ((c1 >>> 24) & 255)) * t));
        int r = Math.round(((c1 >> 16) & 255) + ((((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t));
        int g = Math.round(((c1 >> 8) & 255) + ((((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t));
        int b = Math.round((c1 & 255) + (((c2 & 255) - (c1 & 255)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Colour for a suspicion score 0..100. */
    public static int score(float s) {
        if (s < 20) return GREEN;
        if (s < 45) return YELLOW;
        if (s < 75) return ORANGE;
        return RED;
    }
}
