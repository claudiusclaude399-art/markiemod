package dev.markie.lyrics.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Drawing helpers: rounded boxes, smooth colours and the UI fonts. */
public final class Ui {
    public static final StyleSpriteSource FONT = new StyleSpriteSource.Font(Identifier.of("markielyrics", "ui"));
    public static final StyleSpriteSource FONT_BIG = new StyleSpriteSource.Font(Identifier.of("markielyrics", "ui_big"));

    // palette
    public static final int BG = 0xF0151219;
    public static final int SIDEBAR = 0xF01B1722;
    public static final int CARD = 0xFF221D2B;
    public static final int CARD_HOVER = 0xFF2B2537;
    public static final int STROKE = 0x30FFFFFF;
    public static final int TEXT = 0xFFF7F5FB;
    public static final int MUTED = 0xFFB9B2C8;
    public static final int ACCENT = 0xFF8B5CF6;
    public static final int ACCENT_SOFT = 0xFF6D44D1;
    public static final int GREEN = 0xFF4ADE80;

    private Ui() {}

    public static Text t(String s) { return Text.literal(s).setStyle(Style.EMPTY.withFont(FONT)); }
    public static Text big(String s) { return Text.literal(s).setStyle(Style.EMPTY.withFont(FONT_BIG)); }
    public static Text in(String s, StyleSpriteSource font) { return Text.literal(s).setStyle(Style.EMPTY.withFont(font)); }

    public static TextRenderer tr() { return MinecraftClient.getInstance().textRenderer; }

    public static void text(DrawContext ctx, Text text, int x, int y, int color) {
        ctx.drawText(tr(), text, x, y, color, false);
    }

    public static void textCentered(DrawContext ctx, Text text, int cx, int y, int color) {
        ctx.drawText(tr(), text, cx - tr().getWidth(text) / 2, y, color, false);
    }

    /** Filled rectangle with rounded corners (radius in GUI pixels). */
    public static void round(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { ctx.fill(x, y, x + w, y + h, color); return; }
        ctx.fill(x, y + r, x + w, y + h - r, color);
        for (int j = 0; j < r; j++) {
            double dy = r - j - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            ctx.fill(x + inset, y + j, x + w - inset, y + j + 1, color);
            ctx.fill(x + inset, y + h - j - 1, x + w - inset, y + h - j, color);
        }
    }

    /** Rounded box with a 1px border. */
    public static void roundOutlined(DrawContext ctx, int x, int y, int w, int h, int r, int fill, int border) {
        round(ctx, x, y, w, h, r, border);
        round(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    public static int mix(int c1, int c2, float f) {
        f = Math.max(0, Math.min(1, f));
        int a = (int) (((c1 >>> 24) & 255) + ((((c2 >>> 24) & 255) - ((c1 >>> 24) & 255)) * f));
        int r = (int) (((c1 >> 16) & 255) + ((((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * f));
        int g = (int) (((c1 >> 8) & 255) + ((((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * f));
        int b = (int) ((c1 & 255) + (((c2 & 255) - (c1 & 255)) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    /** Frame-rate independent smoothing towards a target. */
    public static float approach(float cur, float target, float speed, float dtSec) {
        return cur + (target - cur) * (1f - (float) Math.exp(-speed * dtSec));
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    public static String ellipsize(Text sample, String s, int maxWidth, StyleSpriteSource font) {
        TextRenderer tr = tr();
        if (tr.getWidth(in(s, font)) <= maxWidth) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (tr.getWidth(in(s.substring(0, mid) + e, font)) <= maxWidth) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + e;
    }
}
