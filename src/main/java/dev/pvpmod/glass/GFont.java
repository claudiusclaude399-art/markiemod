package dev.pvpmod.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/** The custom Inter font (assets/pvpmod/font). Size is in GUI units; 8 is the base size. */
public final class GFont {
    private GFont() {}

    public static final StyleSpriteSource REGULAR = new StyleSpriteSource.Font(Identifier.of("pvpmod", "inter"));
    public static final StyleSpriteSource BOLD = new StyleSpriteSource.Font(Identifier.of("pvpmod", "inter_bold"));
    public static final float BASE = 8f;

    public static MutableText text(String s, boolean bold) {
        return Text.literal(s).setStyle(Style.EMPTY.withFont(bold ? BOLD : REGULAR));
    }

    private static TextRenderer tr() { return MinecraftClient.getInstance().textRenderer; }

    public static float width(String s, float size, boolean bold) {
        return tr().getWidth(text(s, bold)) * size / BASE;
    }

    /** Draws text with its top-left at (x, y). Returns the width drawn. */
    public static float draw(String s, float x, float y, float size, int color, boolean bold) {
        if (s == null || s.isEmpty()) return 0;
        Text t = text(s, bold);
        Matrix3x2fStack m = Glass.ctx().getMatrices();
        float k = size / BASE;
        m.pushMatrix();
        m.translate(x, y);
        m.scale(k, k);
        Glass.ctx().drawText(tr(), t, 0, 0, Glass.c(color), false);
        m.popMatrix();
        return tr().getWidth(t) * k;
    }

    public static float drawCentered(String s, float cx, float y, float size, int color, boolean bold) {
        float w = width(s, size, bold);
        draw(s, cx - w / 2f, y, size, color, bold);
        return w;
    }

    public static float drawRight(String s, float rx, float y, float size, int color, boolean bold) {
        float w = width(s, size, bold);
        draw(s, rx - w, y, size, color, bold);
        return w;
    }

    /** Cuts the string with an ellipsis so it fits {@code maxW}. */
    public static String fit(String s, float maxW, float size, boolean bold) {
        if (width(s, size, bold) <= maxW) return s;
        String e = "…";
        int n = s.length();
        while (n > 0 && width(s.substring(0, n) + e, size, bold) > maxW) n--;
        return s.substring(0, n) + e;
    }

    /** Height of a line of text at this size. */
    public static float lineHeight(float size) { return 9f * size / BASE; }
}
