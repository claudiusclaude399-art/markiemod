package dev.pvpmod.glass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Textures generated at runtime for the glass UI: anti-aliased discs and rings (rounded corners),
 * a soft line profile, a radial glow and a blurred shadow. Everything is white; colour comes from the tint.
 */
public final class GlassTex {
    private GlassTex() {}

    public static final int DOT_SIZE = 64, GLOW_SIZE = 128, SHADOW_SIZE = 64, LINE_W = 4, LINE_H = 16;

    private static final Map<Integer, Identifier> DISCS = new HashMap<>();
    private static final Map<Integer, Identifier> RINGS = new HashMap<>();
    private static Identifier dot, glow, shadow, line, white;
    private static int counter = 0;

    /** Texture with a configurable sampler, so scaled textures (dots, glows, lines) stay smooth. */
    private static final class Tex extends NativeImageBackedTexture {
        Tex(String name, NativeImage img, boolean linear) {
            super(() -> name, img);
            if (linear) this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }

    private static Identifier register(String name, NativeImage img, boolean linear) {
        Identifier id = Identifier.of("pvpmod", "glass/" + name + "_" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new Tex("pvpmod:" + name, img, linear));
        return id;
    }

    private static int white(float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | 0xFFFFFF;
    }

    /** Coverage of a disc of radius r centred at (cx, cy) over pixel (x, y), 4x4 supersampled. */
    private static float discCoverage(int x, int y, float cx, float cy, float rOuter, float rInner) {
        int hit = 0;
        for (int sy = 0; sy < 4; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                float px = x + (sx + 0.5f) / 4f - cx, py = y + (sy + 0.5f) / 4f - cy;
                float d2 = px * px + py * py;
                if (d2 <= rOuter * rOuter && d2 >= rInner * rInner) hit++;
            }
        }
        return hit / 16f;
    }

    /** Full disc of radius r (size 2r x 2r). Quadrants are used as the corners of rounded rectangles. */
    public static Identifier disc(int r) {
        return DISCS.computeIfAbsent(r, rr -> {
            int s = rr * 2;
            NativeImage img = new NativeImage(s, s, true);
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++)
                    img.setColorArgb(x, y, white(discCoverage(x, y, rr, rr, rr, -1)));
            return register("disc" + rr, img, false);
        });
    }

    /** One-pixel ring of radius r (size 2r x 2r), the rim of a rounded rectangle. */
    public static Identifier ring(int r) {
        return RINGS.computeIfAbsent(r, rr -> {
            int s = rr * 2;
            NativeImage img = new NativeImage(s, s, true);
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++)
                    img.setColorArgb(x, y, white(discCoverage(x, y, rr, rr, rr, rr - 1.1f)));
            return register("ring" + rr, img, false);
        });
    }

    /** Plain white 4x4, tinted for solid 3D faces. */
    public static Identifier white() {
        if (white == null) {
            NativeImage img = new NativeImage(4, 4, true);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setColorArgb(x, y, 0xFFFFFFFF);
            white = register("white", img, false);
        }
        return white;
    }

    public static Identifier dot() {
        if (dot == null) {
            int s = DOT_SIZE;
            NativeImage img = new NativeImage(s, s, true);
            float c = s / 2f;
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++)
                    img.setColorArgb(x, y, white(discCoverage(x, y, c, c, c - 1.5f, -1)));
            dot = register("dot", img, true);
        }
        return dot;
    }

    public static Identifier glow() {
        if (glow == null) {
            int s = GLOW_SIZE;
            NativeImage img = new NativeImage(s, s, true);
            float c = s / 2f;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    float dx = (x + 0.5f - c) / c, dy = (y + 0.5f - c) / c;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float a = d >= 1f ? 0f : (float) Math.exp(-d * d * 4.5f) * (1f - d * d);
                    img.setColorArgb(x, y, white(a));
                }
            }
            glow = register("glow", img, true);
        }
        return glow;
    }

    /** Blurred square: corners hold the soft falloff, used as a 9-slice drop shadow. */
    public static Identifier shadow() {
        if (shadow == null) {
            int s = SHADOW_SIZE;
            NativeImage img = new NativeImage(s, s, true);
            float half = s / 2f;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    float dx = Math.max(0, Math.abs(x + 0.5f - half) - 2f) / (half - 2f);
                    float dy = Math.max(0, Math.abs(y + 0.5f - half) - 2f) / (half - 2f);
                    float fx = smooth(1f - dx), fy = smooth(1f - dy);
                    img.setColorArgb(x, y, white(fx * fy));
                }
            }
            shadow = register("shadow", img, true);
        }
        return shadow;
    }

    /** Soft-edged line profile across the texture height. */
    public static Identifier line() {
        if (line == null) {
            NativeImage img = new NativeImage(LINE_W, LINE_H, true);
            for (int y = 0; y < LINE_H; y++) {
                float d = Math.abs(y + 0.5f - LINE_H / 2f) / (LINE_H / 2f);   // 0 centre .. 1 edge
                float a = d < 0.6f ? 1f : Math.max(0f, 1f - (d - 0.6f) / 0.4f);
                for (int x = 0; x < LINE_W; x++) img.setColorArgb(x, y, white(a));
            }
            line = register("line", img, true);
        }
        return line;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
