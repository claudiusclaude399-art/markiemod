package dev.markie.lyrics.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;

/** Something the lyric line can be drawn onto: the 3D world or the 2D screen. Units are font pixels, y down. */
public interface Canvas {
    void push();
    void pop();
    void translate(float x, float y);
    void rotate(float radians);
    void scale(float s);
    /** Move towards (+) or away from (-) the viewer; only matters in the world, where text is depth-sorted. */
    default void depth(float dz) {}
    void text(Text t, float x, float y, int argb, boolean shadow);
    /** A tinted soft sprite centred on (cx, cy). */
    void sprite(Identifier tex, float cx, float cy, float halfW, float halfH, int argb);

    /** Draws into the world with the text render layers (so it blends like name tags). */
    final class World implements Canvas {
        private final MatrixStack ms;
        private final VertexConsumerProvider consumers;
        private final TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        private final boolean seeThrough;
        private final int light;

        public World(MatrixStack ms, VertexConsumerProvider consumers, boolean seeThrough, int light) {
            this.ms = ms; this.consumers = consumers; this.seeThrough = seeThrough; this.light = light;
        }

        public void push() { ms.push(); }
        public void pop() { ms.pop(); }
        public void translate(float x, float y) { ms.translate(x, y, 0); }
        public void rotate(float r) { if (r != 0) ms.multiply(RotationAxis.POSITIVE_Z.rotation(r)); }
        public void scale(float s) { ms.scale(s, s, s); }
        public void depth(float dz) { ms.translate(0, 0, dz); }

        public void text(Text t, float x, float y, int argb, boolean shadow) {
            if ((argb >>> 24) < 6) return;
            tr.draw(t, x, y, argb, shadow, ms.peek().getPositionMatrix(), consumers,
                seeThrough ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL, 0, light);
        }

        public void sprite(Identifier tex, float cx, float cy, float hw, float hh, int argb) {
            if ((argb >>> 24) < 3) return;
            FxTextures.ensure();
            VertexConsumer vc = consumers.getBuffer(seeThrough ? RenderLayers.textSeeThrough(tex) : RenderLayers.text(tex));
            Matrix4f m = ms.peek().getPositionMatrix();
            float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh;
            vc.vertex(m, x0, y0, 0).color(argb).texture(0, 0).light(light);
            vc.vertex(m, x0, y1, 0).color(argb).texture(0, 1).light(light);
            vc.vertex(m, x1, y1, 0).color(argb).texture(1, 1).light(light);
            vc.vertex(m, x1, y0, 0).color(argb).texture(1, 0).light(light);
        }
    }

    /** Draws on the HUD / in menus. */
    final class Screen implements Canvas {
        private final DrawContext ctx;
        private final Matrix3x2fStack m;
        private final TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        public Screen(DrawContext ctx) { this.ctx = ctx; this.m = ctx.getMatrices(); }

        public void push() { m.pushMatrix(); }
        public void pop() { m.popMatrix(); }
        public void translate(float x, float y) { m.translate(x, y); }
        public void rotate(float r) { if (r != 0) m.rotate(r); }
        public void scale(float s) { m.scale(s, s); }

        public void text(Text t, float x, float y, int argb, boolean shadow) {
            if ((argb >>> 24) < 6) return;
            m.pushMatrix();
            m.translate(x, y);
            ctx.drawText(tr, t, 0, 0, argb, shadow);
            m.popMatrix();
        }

        public void sprite(Identifier tex, float cx, float cy, float hw, float hh, int argb) {
            if ((argb >>> 24) < 3) return;
            FxTextures.ensure();
            m.pushMatrix();
            m.translate(cx - hw, cy - hh);
            m.scale(hw * 2f / FxTextures.S, hh * 2f / FxTextures.S);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, FxTextures.S, FxTextures.S,
                FxTextures.S, FxTextures.S, FxTextures.S, FxTextures.S, argb);
            m.popMatrix();
        }
    }
}
