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

    private static final WordFx.Pose POSE = new WordFx.Pose();
    private static final WordFx.Spark[] SPARKS = WordFx.newSparkBuffer();

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
        float lineAlpha = s.lineAlpha();
        if (lineAlpha <= 0.02f) return;
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
        float leave = LyricsAnimator.easeOutCubic(s.leaveProgress());
        if (sky) pitch -= leave * 6f;          // drift up while leaving
        else dist += leave * 1.5f;             // drift away while leaving

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

        TextRenderer tr = mc.textRenderer;
        TextRenderer.TextLayerType layer = cfg.throughWalls ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int n = s.wordText.length;
        float radius = s.totalWidth * 1.25f + 70f;
        float x = -s.totalWidth / 2f;
        long seed = s.line.startMs();
        for (int i = 0; i < n; i++) {
            float w = s.wordWidth[i];
            float cx = x + w / 2f;
            x += w + s.spaceWidth;
            float p = s.wordProgress(i, pos);
            if (p <= 0f) continue;
            long since = s.wordSince(i, pos);
            WordFx.pose(cfg, p, since, i, POSE);
            float alpha = POSE.alpha * lineAlpha * cfg.opacity;
            if (alpha < 0.03f) continue;
            float xFrac = s.totalWidth <= 0 ? 0 : (cx + s.totalWidth / 2f) / s.totalWidth;

            ms.push();
            if (cfg.curved) {
                float theta = cx / radius;
                ms.translate(radius * (float) Math.sin(theta) + POSE.dx, radius * (1f - (float) Math.cos(theta)) + POSE.dy, 0);
                ms.multiply(RotationAxis.POSITIVE_Z.rotation(theta));
            } else {
                ms.translate(cx + POSE.dx, POSE.dy, 0);
            }

            // particle burst (drawn before the word so the word sits on top)
            int sparks = WordFx.sparks(cfg, seed, i, since, w, SPARKS);
            for (int k = 0; k < sparks; k++) {
                WordFx.Spark sp = SPARKS[k];
                int col = LyricsAnimator.argb(sp.alpha * lineAlpha * cfg.opacity, sp.rgb);
                if ((col >>> 24) < 6) continue;
                net.minecraft.text.Text g = WordFx.glyph(cfg, seed, i, k);
                ms.push();
                ms.translate(sp.x, sp.y, 0);
                ms.multiply(RotationAxis.POSITIVE_Z.rotation(sp.rot));
                ms.scale(sp.scale, sp.scale, sp.scale);
                tr.draw(g, -tr.getWidth(g) / 2f, -4f, col, false, ms.peek().getPositionMatrix(), consumers, layer, 0, light);
                ms.pop();
            }

            if (POSE.rot != 0) ms.multiply(RotationAxis.POSITIVE_Z.rotation(POSE.rot));
            float sc = POSE.scale * (1f + leave * 0.15f);
            float tx = -w / 2f, ty = -4.5f;

            // echo: a bigger, fading copy that flashes out from the word as it lands
            if (cfg.echo && since >= 0 && since < 450) {
                float e = since / 450f;
                int ec = LyricsAnimator.argb((1f - e) * 0.45f * lineAlpha * cfg.opacity, Colors.glow());
                if ((ec >>> 24) >= 6) {
                    ms.push();
                    float es = sc * (1f + LyricsAnimator.easeOutCubic(e) * 0.7f);
                    ms.scale(es, es, es);
                    tr.draw(s.wordText[i], tx, ty, ec, false, ms.peek().getPositionMatrix(), consumers, layer, 0, light);
                    ms.pop();
                }
            }

            ms.scale(sc, sc, sc);
            Matrix4f m = ms.peek().getPositionMatrix();
            if (cfg.glow) {
                int gc = LyricsAnimator.glowColor(alpha * 0.28f);
                if ((gc >>> 24) >= 4) {
                    for (int k = 0; k < 8; k++) {
                        float a = k * (float) Math.PI / 4f;
                        tr.draw(s.wordText[i], tx + (float) Math.cos(a) * 0.9f, ty + (float) Math.sin(a) * 0.9f, gc, false, m, consumers, layer, 0, light);
                    }
                }
            }
            tr.draw(s.wordText[i], tx, ty, LyricsAnimator.argb(alpha, Colors.word(i, n, xFrac)), cfg.shadow, m, consumers, layer, 0, light);
            ms.pop();
        }
        ms.pop();
    }
}
