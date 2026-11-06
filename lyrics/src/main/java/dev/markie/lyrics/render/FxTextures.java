package dev.markie.lyrics.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Soft white sprites generated at startup, tinted when drawn: glows, rings, flares, petals, vignette. */
public final class FxTextures {
    public static final int S = 64;
    public static final Identifier BLOB = id("blob");
    public static final Identifier RING = id("ring");
    public static final Identifier FLARE = id("flare");
    public static final Identifier SPARKLE = id("sparkle");
    public static final Identifier PETAL = id("petal");
    public static final Identifier FLAKE = id("flake");
    public static final Identifier VIGNETTE = id("vignette");
    private static boolean ready;

    private FxTextures() {}

    private static Identifier id(String s) { return Identifier.of("markielyrics", "dynamic/fx_" + s); }

    public static void ensure() {
        if (ready) return;
        ready = true;
        make(BLOB, S, (x, y) -> {
            float d = dist(x, y, S);
            float v = (float) Math.exp(-d * d * 4.5f) * (1f - smooth(0.9f, 1f, d));
            return v;
        });
        make(RING, S, (x, y) -> {
            float d = dist(x, y, S);
            float r = (d - 0.82f) / 0.08f;
            return (float) Math.exp(-r * r) + 0.25f * (float) Math.exp(-((d - 0.7f) / 0.2f) * ((d - 0.7f) / 0.2f)) * (1f - smooth(0.9f, 1f, d));
        });
        make(FLARE, S, (x, y) -> {
            // a bright core with a long horizontal streak and a short vertical one
            float u = (x + 0.5f) / S * 2f - 1f, v = (y + 0.5f) / S * 2f - 1f;
            float core = (float) Math.exp(-(u * u + v * v) * 18f);
            float hs = (float) Math.exp(-v * v * 900f) * (1f - Math.abs(u));
            float vs = (float) Math.exp(-u * u * 900f) * (1f - Math.abs(v)) * 0.5f;
            return Math.min(1f, core + hs + vs);
        });
        make(SPARKLE, S, (x, y) -> {
            float u = Math.abs((x + 0.5f) / S * 2f - 1f), v = Math.abs((y + 0.5f) / S * 2f - 1f);
            float star = Math.max(0, 1f - (float) Math.pow(Math.pow(u, 0.5) + Math.pow(v, 0.5), 2.2));
            float core = (float) Math.exp(-(u * u + v * v) * 30f);
            return Math.min(1f, star * 1.4f + core);
        });
        make(PETAL, S, (x, y) -> {
            float u = (x + 0.5f) / S * 2f - 1f, v = (y + 0.5f) / S * 2f - 1f;
            float e = (u * u) / 0.25f + (v * v) / 0.9f;     // an ellipse...
            float notch = (float) Math.exp(-((u) * (u)) * 60f) * smooth(0.7f, 1f, -v);  // ...with a little notch
            float a = (1f - smooth(0.8f, 1f, e)) * (1f - notch * 0.9f);
            return a * (0.75f + 0.25f * (1f - Math.abs(u) * 2f));
        });
        make(FLAKE, S, (x, y) -> {
            float u = (x + 0.5f) / S * 2f - 1f, v = (y + 0.5f) / S * 2f - 1f;
            float d = (float) Math.sqrt(u * u + v * v);
            float ang = (float) Math.atan2(v, u);
            float arms = (float) Math.pow(Math.abs(Math.cos(ang * 3)), 18) * (1f - smooth(0.6f, 0.95f, d));
            float core = (float) Math.exp(-d * d * 20f);
            return Math.min(1f, arms * 0.9f + core + (float) Math.exp(-d * d * 3f) * 0.25f);
        });
        make(VIGNETTE, 128, (x, y) -> {
            float u = (x + 0.5f) / 128f * 2f - 1f, v = (y + 0.5f) / 128f * 2f - 1f;
            float d = (float) Math.sqrt(u * u * 0.9f + v * v);
            return smooth(0.45f, 1.35f, d);
        });
    }

    private interface Alpha { float at(int x, int y); }

    private static void make(Identifier id, int size, Alpha fn) {
        NativeImage img = new NativeImage(size, size, false);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float a = Math.max(0, Math.min(1, fn.at(x, y)));
            img.setColorArgb(x, y, (Math.round(a * 255) << 24) | 0xFFFFFF);
        }
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new Smooth(id.toString(), img));
    }

    private static float dist(int x, int y, int s) {
        float u = (x + 0.5f) / s * 2f - 1f, v = (y + 0.5f) / s * 2f - 1f;
        return (float) Math.sqrt(u * u + v * v);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static final class Smooth extends NativeImageBackedTexture {
        Smooth(String name, NativeImage img) {
            super(() -> name, img);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }
}
