package dev.pvpmod.render;

import net.minecraft.client.render.VertexConsumer;

/** Wraps a vertex consumer and multiplies every vertex colour by a fixed RGB tint. */
public class TintingVertexConsumer implements VertexConsumer {
    private final VertexConsumer d;
    private final int tr, tg, tb;

    public TintingVertexConsumer(VertexConsumer delegate, int rgb) {
        this.d = delegate;
        this.tr = (rgb >> 16) & 255;
        this.tg = (rgb >> 8) & 255;
        this.tb = rgb & 255;
    }

    private int mul(int argb) {
        int a = (argb >>> 24) & 255, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return (a << 24) | ((r * tr / 255) << 16) | ((g * tg / 255) << 8) | (b * tb / 255);
    }

    public VertexConsumer vertex(float x, float y, float z) { d.vertex(x, y, z); return this; }
    public VertexConsumer color(int r, int g, int b, int a) { d.color(r * tr / 255, g * tg / 255, b * tb / 255, a); return this; }
    public VertexConsumer color(int argb) { d.color(mul(argb)); return this; }
    public VertexConsumer texture(float u, float v) { d.texture(u, v); return this; }
    public VertexConsumer overlay(int u, int v) { d.overlay(u, v); return this; }
    public VertexConsumer light(int u, int v) { d.light(u, v); return this; }
    public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }
    public VertexConsumer lineWidth(float w) { d.lineWidth(w); return this; }

    public void vertex(float x, float y, float z, int color, float u, float v,
                       int overlay, int light, float nx, float ny, float nz) {
        d.vertex(x, y, z, mul(color), u, v, overlay, light, nx, ny, nz);
    }
}
