package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.lyrics.LyricsManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Draws lyrics as real 3D text in the world: huge in the sky, or floating in front of you. */
public final class SkyLyricsRenderer {
    private static final float RAD = (float) (Math.PI / 180.0);

    private SkyLyricsRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        Camera camera = mc.gameRenderer.getCamera();
        LyricsConfig cfg = LyricsConfig.get();
        LyricsAnimator.update(camera.getYaw(), camera.getPitch());
        if (cfg.where == LyricsConfig.Where.SCREEN) return;

        MatrixStack ms = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        if (ms == null || consumers == null) return;
        long pos = LyricsManager.position();

        LyricsAnimator.Shown leaving = LyricsAnimator.leaving();
        if (leaving != null) drawLine(mc, camera, ms, consumers, leaving, pos, cfg);
        LyricsAnimator.Shown active = LyricsAnimator.active();
        if (active != null) drawLine(mc, camera, ms, consumers, active, pos, cfg);
    }

    private static void drawLine(MinecraftClient mc, Camera camera, MatrixStack ms, VertexConsumerProvider consumers,
                                 LyricsAnimator.Shown s, long pos, LyricsConfig cfg) {
        if (s.lineAlpha() <= 0.02f) return;
        boolean sky = cfg.where == LyricsConfig.Where.SKY;

        // where the line lives
        float yaw = (cfg.followView ? camera.getYaw() : s.baseYaw) + LyricsAnimator.yawOffset(s, sky);
        float pitchOff = LyricsAnimator.pitchOffset(s, sky);
        float pitch = sky ? pitchOff : (cfg.followView ? camera.getPitch() : s.basePitch) * 0.6f + pitchOff;
        pitch = Math.max(-89f, Math.min(89f, pitch));
        float wanted = sky ? cfg.skyDistance : cfg.floatDistance;
        float dist = wanted;
        if (sky && cfg.fitFog) {
            // past ~60% of the render distance fog would swallow the words
            float maxDist = mc.options.getClampedViewDistance() * 16f * 0.6f;
            dist = Math.min(wanted, Math.max(16f, maxDist));
        }
        if (cfg.exit == LyricsConfig.Exit.FADE_UP) {
            float leave = LyricsAnimator.easeOutCubic(s.leaveProgress());
            if (sky) pitch -= leave * 6f;          // drift up while leaving
            else dist += leave * 1.5f;             // drift away while leaving
        }

        Vec3d dir = Vec3d.fromPolar(pitch, yaw);
        Vec3d cam = camera.getCameraPos();
        // sky lines move with you like the sky does; floating lines stay where they popped up (unless Follow view is on)
        if (s.origin == null) s.origin = cam;
        Vec3d base = sky || cfg.followView ? cam : s.origin;
        Vec3d anchor = base.add(dir.multiply(dist));
        Vec3d rel = anchor.subtract(cam);

        // face the player, plus a little twist so it looks painted onto the sky at an angle
        double horiz = Math.sqrt(rel.x * rel.x + rel.z * rel.z);
        float faceYaw = (float) (Math.atan2(-rel.x, rel.z) / RAD) + s.twist;
        float facePitch = (float) (-Math.atan2(rel.y, horiz) / RAD);
        // By default words keep the same size on screen at any distance (distance changes depth and parallax);
        // with "Shrink with distance" they get smaller the farther away they are, like real objects.
        float ref = sky ? 46f : 7f;
        float unit = sky ? 0.0068f : 0.0056f;
        float scale = (cfg.scaleWithDistance ? ref : (float) rel.length()) * unit * cfg.size;

        ms.push();
        ms.translate(rel.x, rel.y, rel.z);
        ms.multiply(new Quaternionf().rotationYXZ((float) Math.PI - faceYaw * RAD, -facePitch * RAD, s.roll * RAD));
        ms.scale(scale, -scale, scale);

        Canvas canvas = new Canvas.World(ms, consumers, cfg.throughWalls, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        LineRenderer.draw(canvas, s, pos, cfg.curved, 1f);
        ms.pop();
    }
}
