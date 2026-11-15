package dev.pvpmod.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Immediate-mode drawing for the "liquid glass" look. Coordinates are GUI units (floats); shapes are snapped to
 * physical pixels so rounded corners stay crisp at every GUI scale.
 */
public final class Glass {
    private Glass() {}

    /** Global opacity, used for fade-in/out of whole screens. */
    public static float alpha = 1f;
    public static float time = 0f;
    private static final long T0 = System.nanoTime();

    /** Refresh the animation clock (seconds since start). */
    public static void tick() { time = (System.nanoTime() - T0) / 1e9f; }
    private static DrawContext ctx;
    private static float s = 2f;

    public static void begin(DrawContext c) {
        ctx = c;
        s = Math.max(1, MinecraftClient.getInstance().getWindow().getScaleFactor());
    }

    public static DrawContext ctx() { return ctx; }

    public static float scale() { return s; }

    static int c(int argb) { return alpha >= 0.999f ? argb : Col.fade(argb, alpha); }

    // ------------------------------------------------------------------ physical pixel helpers
    private static int px(float v) { return Math.round(v * s); }

    private static void pushPhys() {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(1f / s, 1f / s);
    }

    private static void pop() { ctx.getMatrices().popMatrix(); }

    private static void tex(Identifier id, int x, int y, int u, int v, int w, int h, int tw, int th, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, w, h, tw, th, color);
    }

    /** Draw a texture region stretched to an arbitrary float rectangle (GUI units). */
    public static void texStretch(Identifier id, float x, float y, float w, float h,
                                  int u, int v, int rw, int rh, int tw, int th, int color) {
        if (w <= 0 || h <= 0) return;
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(w / rw, h / rh);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, rw, rh, rw, rh, tw, th, c(color));
        m.popMatrix();
    }

    // ------------------------------------------------------------------ rectangles
    public static void rect(float x, float y, float w, float h, int color) {
        pushPhys();
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h);
        if (X2 > X && Y2 > Y) ctx.fill(X, Y, X2, Y2, c(color));
        pop();
    }

    public static void rectGradient(float x, float y, float w, float h, int top, int bottom) {
        pushPhys();
        int X = px(x), Y = px(y), X2 = px(x + w), Y2 = px(y + h);
        if (X2 > X && Y2 > Y) ctx.fillGradient(X, Y, X2, Y2, c(top), c(bottom));
        pop();
    }

    public static final int TL = 1, TR = 2, BL = 4, BR = 8, ALL = 15, TOP = TL | TR, BOTTOM = BL | BR;

    public static void round(float x, float y, float w, float h, float r, int color) {
        round(x, y, w, h, r, color, color, ALL);
    }

    public static void round(float x, float y, float w, float h, float r, int top, int bottom) {
        round(x, y, w, h, r, top, bottom, ALL);
    }

    /** Rounded rectangle with a vertical gradient; {@code corners} picks which corners are rounded. */
    public static void round(float x, float y, float w, float h, float r, int top, int bottom, int corners) {
        int X = px(x), Y = px(y), W = px(x + w) - X, H = px(y + h) - Y;
        if (W <= 0 || H <= 0) return;
        int R = Math.min(px(r), Math.min(W / 2, H / 2));
        top = c(top);
        bottom = c(bottom);
        pushPhys();
        if (R < 1) {
            ctx.fillGradient(X, Y, X + W, Y + H, top, bottom);
            pop();
            return;
        }
        int cTop = Col.lerp(top, bottom, R * 0.5f / H), cBot = Col.lerp(top, bottom, 1f - R * 0.5f / H);
        int eTop = Col.lerp(top, bottom, R / (float) H), eBot = Col.lerp(top, bottom, (H - R) / (float) H);
        Identifier d = GlassTex.disc(R);
        int D = R * 2;
        corner(d, X, Y, 0, 0, R, D, cTop, (corners & TL) != 0);
        corner(d, X + W - R, Y, R, 0, R, D, cTop, (corners & TR) != 0);
        corner(d, X, Y + H - R, 0, R, R, D, cBot, (corners & BL) != 0);
        corner(d, X + W - R, Y + H - R, R, R, R, D, cBot, (corners & BR) != 0);
        ctx.fillGradient(X + R, Y, X + W - R, Y + H, top, bottom);
        if (H - 2 * R > 0) {
            ctx.fillGradient(X, Y + R, X + R, Y + H - R, eTop, eBot);
            ctx.fillGradient(X + W - R, Y + R, X + W, Y + H - R, eTop, eBot);
        }
        pop();
    }

    private static void corner(Identifier d, int x, int y, int u, int v, int R, int D, int color, boolean rounded) {
        if (rounded) tex(d, x, y, u, v, R, R, D, D, color);
        else ctx.fill(x, y, x + R, y + R, color);
    }

    /** One physical pixel outline of a rounded rectangle, brighter at the top like a glass edge. */
    public static void rim(float x, float y, float w, float h, float r, int top, int bottom) {
        int X = px(x), Y = px(y), W = px(x + w) - X, H = px(y + h) - Y;
        if (W <= 2 || H <= 2) return;
        int R = Math.min(px(r), Math.min(W / 2, H / 2));
        top = c(top);
        bottom = c(bottom);
        pushPhys();
        if (R < 2) {
            ctx.fill(X, Y, X + W, Y + 1, top);
            ctx.fill(X, Y + H - 1, X + W, Y + H, bottom);
            ctx.fillGradient(X, Y + 1, X + 1, Y + H - 1, top, bottom);
            ctx.fillGradient(X + W - 1, Y + 1, X + W, Y + H - 1, top, bottom);
            pop();
            return;
        }
        Identifier ring = GlassTex.ring(R);
        int D = R * 2;
        int eTop = Col.lerp(top, bottom, R / (float) H), eBot = Col.lerp(top, bottom, (H - R) / (float) H);
        tex(ring, X, Y, 0, 0, R, R, D, D, top);
        tex(ring, X + W - R, Y, R, 0, R, R, D, D, top);
        tex(ring, X, Y + H - R, 0, R, R, R, D, D, bottom);
        tex(ring, X + W - R, Y + H - R, R, R, R, R, D, D, bottom);
        ctx.fill(X + R, Y, X + W - R, Y + 1, top);
        ctx.fill(X + R, Y + H - 1, X + W - R, Y + H, bottom);
        if (H - 2 * R > 0) {
            ctx.fillGradient(X, Y + R, X + 1, Y + H - R, eTop, eBot);
            ctx.fillGradient(X + W - 1, Y + R, X + W, Y + H - R, eTop, eBot);
        }
        pop();
    }

    /** Soft drop shadow around a rectangle (9-slice of a blurred square, centre left empty). */
    public static void shadow(float x, float y, float w, float h, float r, float blur, int color) {
        Identifier t = GlassTex.shadow();
        int S = GlassTex.SHADOW_SIZE, H2 = S / 2;
        float x0 = x - blur, y0 = y - blur * 0.6f, w0 = w + blur * 2, h0 = h + blur * 2;
        float cs = Math.min(blur + r, Math.min(w0 / 2, h0 / 2));
        texStretch(t, x0, y0, cs, cs, 0, 0, H2, H2, S, S, color);
        texStretch(t, x0 + w0 - cs, y0, cs, cs, H2, 0, H2, H2, S, S, color);
        texStretch(t, x0, y0 + h0 - cs, cs, cs, 0, H2, H2, H2, S, S, color);
        texStretch(t, x0 + w0 - cs, y0 + h0 - cs, cs, cs, H2, H2, H2, H2, S, S, color);
        texStretch(t, x0 + cs, y0, w0 - 2 * cs, cs, H2 - 1, 0, 2, H2, S, S, color);
        texStretch(t, x0 + cs, y0 + h0 - cs, w0 - 2 * cs, cs, H2 - 1, H2, 2, H2, S, S, color);
        texStretch(t, x0, y0 + cs, cs, h0 - 2 * cs, 0, H2 - 1, H2, 2, S, S, color);
        texStretch(t, x0 + w0 - cs, y0 + cs, cs, h0 - 2 * cs, H2, H2 - 1, H2, 2, S, S, color);
    }

    // ------------------------------------------------------------------ liquid glass
    /** The signature panel: shadow, dark tint for legibility, frosted gradient, specular top, moving sheen, rim. */
    public static void panel(float x, float y, float w, float h, float r) {
        panel(x, y, w, h, r, 0x101218, 1f);
    }

    public static void panel(float x, float y, float w, float h, float r, int tint, float strength) {
        shadow(x, y, w, h, r, 14, Col.a(0x000000, 0.32f * strength));
        round(x, y, w, h, r, Col.a(tint, 0.42f), Col.a(tint, 0.55f));
        round(x, y, w, h, r, Col.a(0xFFFFFF, 0.13f * strength), Col.a(0xFFFFFF, 0.04f * strength));
        float sh = Math.min(h * 0.5f, 28f);
        round(x + 1, y + 1, w - 2, sh, Math.max(0, r - 1), Col.a(0xFFFFFF, 0.09f * strength), Col.a(0xFFFFFF, 0f), TOP);
        sheen(x, y, w, h, r);
        rim(x, y, w, h, r, Col.a(0xFFFFFF, 0.50f * strength), Col.a(0xFFFFFF, 0.10f * strength));
        rim(x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), Col.a(0xFFFFFF, 0.07f * strength), Col.a(0xFFFFFF, 0.02f * strength));
    }

    /** Smaller, brighter glass for buttons and chips. {@code hover} 0..1 brightens it. */
    public static void button(float x, float y, float w, float h, float r, int tint, float tintAlpha, float hover) {
        shadow(x, y, w, h, r, 6, Col.a(0x000000, 0.18f));
        round(x, y, w, h, r, Col.a(tint, tintAlpha + 0.08f * hover), Col.a(tint, tintAlpha * 0.8f + 0.06f * hover));
        round(x, y, w, h, r, Col.a(0xFFFFFF, 0.12f + 0.10f * hover), Col.a(0xFFFFFF, 0.04f + 0.05f * hover));
        rim(x, y, w, h, r, Col.a(0xFFFFFF, 0.45f + 0.25f * hover), Col.a(0xFFFFFF, 0.10f));
    }

    /** A diagonal highlight that slides across the glass every few seconds. */
    public static void sheen(float x, float y, float w, float h, float r) {
        float period = 7f;
        float phase = ((time + (x + y) * 0.002f) % period) / period;    // 0..1
        float travel = w + h * 1.5f;
        float cx = x - h + phase * (travel + h);
        if (cx < x - h || cx > x + w + h) return;
        int x1 = (int) Math.ceil(x + r * 0.3f), y1 = (int) Math.ceil(y + 1), x2 = (int) Math.floor(x + w - r * 0.3f), y2 = (int) Math.floor(y + h - 1);
        if (x2 <= x1 || y2 <= y1) return;
        ctx.enableScissor(x1, y1, x2, y2);
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, y + h / 2f);
        m.rotate((float) Math.toRadians(22));
        float bw = Math.max(18f, w * 0.12f), bh = h * 2.4f;
        texStretch(GlassTex.glow(), -bw / 2f, -bh / 2f, bw, bh, 0, 0, GlassTex.GLOW_SIZE, GlassTex.GLOW_SIZE,
            GlassTex.GLOW_SIZE, GlassTex.GLOW_SIZE, Col.a(0xFFFFFF, 0.09f));
        m.popMatrix();
        ctx.disableScissor();
    }

    // ------------------------------------------------------------------ primitives
    public static void dot(float cx, float cy, float radius, int color) {
        int S = GlassTex.DOT_SIZE;
        float pad = radius * (S / (S - 3f));
        texStretch(GlassTex.dot(), cx - pad, cy - pad, pad * 2, pad * 2, 0, 0, S, S, S, S, color);
    }

    public static void glow(float cx, float cy, float radius, int color) {
        int S = GlassTex.GLOW_SIZE;
        texStretch(GlassTex.glow(), cx - radius, cy - radius, radius * 2, radius * 2, 0, 0, S, S, S, S, color);
    }

    /** Anti-aliased line of the given width (GUI units). */
    public static void line(float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float th = width + 1.6f / s;   // room for the soft edge
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x1, y1);
        m.rotate((float) Math.atan2(dy, dx));
        texStretch(GlassTex.line(), 0, -th / 2f, len, th, 0, 0, GlassTex.LINE_W, GlassTex.LINE_H,
            GlassTex.LINE_W, GlassTex.LINE_H, color);
        m.popMatrix();
    }

    public static void dashed(float x1, float y1, float x2, float y2, float width, float dash, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        float ux = dx / len, uy = dy / len;
        for (float d = 0; d < len; d += dash * 2) {
            float e = Math.min(len, d + dash);
            line(x1 + ux * d, y1 + uy * d, x1 + ux * e, y1 + uy * e, width, color);
        }
    }

    /** Thick arc with round caps (stamped dots). Angles in degrees, 0 = up, clockwise. */
    public static void arc(float cx, float cy, float radius, float thick, float startDeg, float sweepDeg, int color) {
        if (sweepDeg <= 0.01f) return;
        float circumference = (float) (Math.PI * 2 * radius) * sweepDeg / 360f;
        int n = Math.max(2, (int) (circumference * s / Math.max(1f, thick * s * 0.18f)));
        n = Math.min(n, 720);
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(startDeg + sweepDeg * i / n);
            dot(cx + (float) Math.sin(a) * radius, cy - (float) Math.cos(a) * radius, thick / 2f, color);
        }
    }

    public static void ring(float cx, float cy, float radius, float thick, int color) {
        arc(cx, cy, radius, thick, 0, 360, color);
    }
}
