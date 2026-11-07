package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.music.MusicPulse;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Concert-style light beams far out around you. They sweep slowly with the music, flash on every beat,
 * and burst brighter at the start of each bar. Colours follow the lyric colours.
 */
public final class LightShow {
    private static final int BEAMS = 10;
    private static float presence = 0f;
    private static long lastNs;

    private LightShow() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        LyricsConfig c = LyricsConfig.get();
        if (mc.world == null) return;
        MusicPulse.update();
        NowPlaying np = SpotifyClient.current();
        boolean on = c.react && c.lightShow && np != null && np.playing();
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        presence += ((on ? 1f : 0f) - presence) * (1f - (float) Math.exp(-2f * dt));
        if (presence < 0.01f) return;
        MatrixStack ms = ctx.matrices();
        if (ms == null || ctx.consumers() == null) return;

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Canvas.World cv = new Canvas.World(ms, ctx.consumers(), false, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        float str = MusicPulse.strength();
        float beat = MusicPulse.beat(), bar = MusicPulse.bar(), energy = MusicPulse.energy();
        double t = MusicPulse.musicTime();
        float radius = Math.min(90f, Math.max(28f, mc.options.getClampedViewDistance() * 16f * 0.55f));
        float height = radius * 1.4f;

        for (int i = 0; i < BEAMS; i++) {
            double ang = i * (Math.PI * 2 / BEAMS) + t * 0.05;
            double bx = cam.x + Math.sin(ang) * radius, bz = cam.z + Math.cos(ang) * radius;
            double by = cam.y - 12;
            // each beam faces you around its own vertical axis, then sways
            float faceYaw = (float) Math.atan2(cam.x - bx, cam.z - bz);
            float sway = (float) (Math.sin(t * 0.7 + i * 1.7) * 0.45 + Math.sin(t * 1.9 + i) * 0.12 * energy);
            // stage colours: the lyric/sky colours pushed to full saturation so beams read as coloured light
            int base = switch (i % 3) { case 0 -> Colors.glow(); case 1 -> Colors.colorA(); default -> Colors.colorB(); };
            float[] hsv = Colors.toHsv(base);
            float hue = hsv[1] < 0.15f ? (i * 0.13f + (float) (t * 0.01)) % 1f : hsv[0] + (i % 3 - 1) * 0.06f;
            int rgb = Colors.hsv(hue, 0.65f, 1f);
            // alternate beams flash on alternate beats so the show feels alive
            float flash = (MusicPulse.beats() + i) % 2 == 0 ? beat : beat * 0.35f;
            float a = presence * Math.min(1f, (0.12f + 0.15f * energy) + str * (0.35f * flash + 0.3f * bar));
            if (a < 0.02f) continue;
            float w = (2.0f + 1.2f * bar * str) * (radius / 50f);

            ms.push();
            ms.translate(bx - cam.x, by - cam.y, bz - cam.z);
            ms.multiply(RotationAxis.POSITIVE_Y.rotation(faceYaw));
            ms.multiply(RotationAxis.POSITIVE_Z.rotation(sway));
            // sprite y runs downward, beam texture is brightest at the bottom: draw from 0 up to -height
            ms.scale(1f, -1f, 1f);
            cv.sprite(FxTextures.BEAM, 0, -height / 2f, w, height / 2f, LyricsAnimator.argb(a, rgb));
            cv.sprite(FxTextures.BLOB, 0, 0, w * 3f, w * 3f, LyricsAnimator.argb(Math.min(1f, a * 1.4f), rgb));
            ms.pop();
        }
    }
}
