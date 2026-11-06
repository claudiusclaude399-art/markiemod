package dev.markie.lyrics.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * "Liquid glass" drawing. The shapes are textures generated at startup (smooth, anti-aliased rounded
 * rectangles with a frosted body, a bright refracted rim and a soft shadow) and drawn as 9-slices,
 * so any size and corner radius stays crisp. The frosting itself comes from Minecraft's menu blur
 * showing through the translucent body.
 */
public final class Glass {
    private static final int T = 128;   // texture size
    private static final int C = 32;    // corner size in texels (= radius of the shape)

    private static final Identifier MASK = id("mask");
    private static final Identifier BODY = id("body");
    private static final Identifier RIM = id("rim");
    private static final Identifier SHADOW = id("shadow");
    private static final Identifier SHEEN = id("sheen");
    private static boolean ready = false;

    private Glass() {}

    private static Identifier id(String s) { return Identifier.of("markielyrics", "dynamic/glass_" + s); }

    // ------------------------------------------------------------------ public drawing API

    /** Big frosted panel: shadow, dark base (keeps text readable), tint, frosted body, glossy rim. */
    public static void panel(DrawContext ctx, int x, int y, int w, int h, int r, int tintRgb, float a) {
        shadow(ctx, x, y, w, h, r, a * 0.9f);
        nine(ctx, MASK, x, y, w, h, r, argb(0.32f * a, 0x0C0A14));
        nine(ctx, MASK, x, y, w, h, r, argb(0.10f * a, tintRgb));
        nine(ctx, BODY, x, y, w, h, r, argb(0.95f * a, 0xFFFFFF));
        nine(ctx, RIM, x, y, w, h, r, argb(0.95f * a, 0xFFFFFF));
    }

    /** A lighter glass layer for cards and rows sitting on a panel. {@code lift} 0..1 brightens it (hover). */
    public static void card(DrawContext ctx, int x, int y, int w, int h, int r, float lift, float a) {
        nine(ctx, MASK, x, y, w, h, r, argb((0.05f + 0.06f * lift) * a, 0xFFFFFF));
        nine(ctx, BODY, x, y, w, h, r, argb((0.35f + 0.35f * lift) * a, 0xFFFFFF));
        nine(ctx, RIM, x, y, w, h, r, argb((0.35f + 0.35f * lift) * a, 0xFFFFFF));
    }

    /** Coloured glass (buttons, selected chips, toggle tracks). */
    public static void tinted(DrawContext ctx, int x, int y, int w, int h, int r, int rgb, float strength, float a) {
        nine(ctx, MASK, x, y, w, h, r, argb(strength * a, rgb));
        nine(ctx, BODY, x, y, w, h, r, argb(0.7f * a, 0xFFFFFF));
        nine(ctx, RIM, x, y, w, h, r, argb(0.8f * a, 0xFFFFFF));
    }

    /** Recessed glass (slider tracks, text boxes, segmented control trough). */
    public static void well(DrawContext ctx, int x, int y, int w, int h, int r, float a) {
        nine(ctx, MASK, x, y, w, h, r, argb(0.30f * a, 0x05040A));
        nine(ctx, RIM, x, y, w, h, r, argb(0.30f * a, 0xFFFFFF));
    }

    /** Glossy white bead (toggle knobs, slider handles). */
    public static void bead(DrawContext ctx, int x, int y, int w, int h, float a) {
        int r = Math.min(w, h) / 2;
        shadow(ctx, x, y + 1, w, h, r, a * 0.6f);
        nine(ctx, MASK, x, y, w, h, r, argb(0.96f * a, 0xFFFFFF));
        nine(ctx, RIM, x, y, w, h, r, argb(0.5f * a, 0xFFFFFF));
    }

    /** Plain solid rounded shape (smooth edges). */
    public static void solid(DrawContext ctx, int x, int y, int w, int h, int r, int argb) {
        nine(ctx, MASK, x, y, w, h, r, argb);
    }

    public static void shadow(DrawContext ctx, int x, int y, int w, int h, int r, float a) {
        int s = Math.max(4, Math.min(14, Math.min(w, h) / 4));
        nine(ctx, SHADOW, x - s, y - s + s / 3, w + 2 * s, h + 2 * s, r + s, argb(0.55f * a, 0x000000));
    }

    /** Soft round light, used for the specular highlight that follows the mouse. */
    public static void sheen(DrawContext ctx, int cx, int cy, int size, int argb) {
        ensure();
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SHEEN, cx - size / 2, cy - size / 2, 0, 0, size, size, 64, 64, 64, 64, argb);
    }

    public static int argb(float a, int rgb) {
        int al = Math.round(Math.max(0, Math.min(1, a)) * 255f);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ 9-slice

    private static void nine(DrawContext ctx, Identifier tex, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        ensure();
        r = Math.max(1, Math.min(r, Math.min(w, h) / 2));
        int mw = w - 2 * r, mh = h - 2 * r;
        int m = T - 2 * C;
        // corners
        part(ctx, tex, x, y, r, r, 0, 0, C, C, color);
        part(ctx, tex, x + w - r, y, r, r, T - C, 0, C, C, color);
        part(ctx, tex, x, y + h - r, r, r, 0, T - C, C, C, color);
        part(ctx, tex, x + w - r, y + h - r, r, r, T - C, T - C, C, C, color);
        // edges
        if (mw > 0) {
            part(ctx, tex, x + r, y, mw, r, C, 0, m, C, color);
            part(ctx, tex, x + r, y + h - r, mw, r, C, T - C, m, C, color);
        }
        if (mh > 0) {
            part(ctx, tex, x, y + r, r, mh, 0, C, C, m, color);
            part(ctx, tex, x + w - r, y + r, r, mh, T - C, C, C, m, color);
        }
        if (mw > 0 && mh > 0) part(ctx, tex, x + r, y + r, mw, mh, C, C, m, m, color);
    }

    private static void part(DrawContext ctx, Identifier tex, int x, int y, int w, int h, int u, int v, int rw, int rh, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, rw, rh, T, T, color);
    }

    // ------------------------------------------------------------------ texture generation

    private static void ensure() {
        if (ready) return;
        ready = true;
        register(MASK, T, (x, y) -> {
            float d = sdf(x, y, 0, C);
            return white(cover(d));
        });
        register(BODY, T, (x, y) -> {
            float d = sdf(x, y, 0, C);
            float in = cover(d);
            float depth = Math.max(0, -d);
            float fresnel = 0.20f * (float) Math.exp(-depth / 5.0);        // edges catch light
            float gloss = 0.09f * Math.max(0, 1f - y / 46f);                 // top is a bit brighter
            float base = 0.05f;
            return white(in * Math.min(1, base + fresnel + gloss));
        });
        register(RIM, T, (x, y) -> {
            float d = sdf(x, y, 0, C);
            float ring = cover(d) - cover(d + 2.2f);
            // light from the top-left, a fainter refracted bounce along the bottom
            float fy = y / (float) (T - 1), fx = x / (float) (T - 1);
            float light = 0.95f - 0.75f * fy + 0.45f * (float) Math.pow(fy, 6) - 0.15f * fx;
            return white(Math.max(0, ring) * Math.max(0.18f, Math.min(1, light)));
        });
        register(SHADOW, T, (x, y) -> {
            float d = sdf(x, y, 14, 18);
            float t = Math.max(0, Math.min(1, (d + 6f) / 20f));
            float s = 1f - t * t * (3 - 2 * t);
            return (Math.round(s * 255) << 24);
        });
        register(SHEEN, 64, (x, y) -> {
            float dx = (x + 0.5f - 32) / 32f, dy = (y + 0.5f - 32) / 32f;
            float d = Math.min(1, (float) Math.sqrt(dx * dx + dy * dy));
            float v = (1 - d) * (1 - d);
            return white(v);
        });
    }

    private interface Pixel { int at(int x, int y); }

    private static void register(Identifier id, int size, Pixel fn) {
        NativeImage img = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setColorArgb(x, y, fn.at(x, y));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new SmoothTexture(id.toString(), img));
    }

    /** Signed distance to a rounded box filling the texture, shrunk by {@code inset}, with corner radius {@code rad}. */
    private static float sdf(int x, int y, float inset, float rad) {
        float half = T / 2f - inset;
        float px = Math.abs(x + 0.5f - T / 2f) - (half - rad);
        float py = Math.abs(y + 0.5f - T / 2f) - (half - rad);
        float ox = Math.max(px, 0), oy = Math.max(py, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(px, py), 0) - rad;
    }

    private static float cover(float d) { return Math.max(0, Math.min(1, 0.5f - d)); }

    private static int white(float a) { return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | 0xFFFFFF; }

    /** Texture with linear filtering so scaled glass stays smooth. */
    private static final class SmoothTexture extends NativeImageBackedTexture {
        SmoothTexture(String name, NativeImage img) {
            super(() -> name, img);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }
}
