package dev.markiesdetector.analyzer.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;

/** A GUI quad with four arbitrary corners (optionally textured), so 3D faces get true perspective without seams. */
final class Quad4 implements SimpleGuiElementRenderState {
    private final RenderPipeline pipeline;
    private final TextureSetup textures;
    private final Matrix3x2f pose;
    private final float[] x, y, u, v;
    private final int color;
    private final ScreenRect scissor, bounds;

    Quad4(RenderPipeline pipeline, TextureSetup textures, Matrix3x2f pose, float[] x, float[] y, float[] u, float[] v,
          int color, ScreenRect scissor, ScreenRect bounds) {
        this.pipeline = pipeline;
        this.textures = textures;
        this.pose = pose;
        this.x = x;
        this.y = y;
        this.u = u;
        this.v = v;
        this.color = color;
        this.scissor = scissor;
        this.bounds = bounds;
    }

    @Override
    public void setupVertices(VertexConsumer vc) {
        for (int i = 0; i < 4; i++) {
            VertexConsumer c = vc.vertex(pose, x[i], y[i]);
            if (u != null) c = c.texture(u[i], v[i]);
            c.color(color);
        }
    }

    @Override public RenderPipeline pipeline() { return pipeline; }

    @Override public TextureSetup textureSetup() { return textures; }

    @Override public ScreenRect scissorArea() { return scissor; }

    @Override public ScreenRect bounds() { return bounds; }
}
