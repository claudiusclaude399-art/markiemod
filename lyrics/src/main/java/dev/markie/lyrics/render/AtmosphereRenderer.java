package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Mood around the music: drifting particles in the world (fireflies, stardust, snow, petals, embers)
 * and soft screen effects (tinted vignette, haze, a glow pulse on each new line, cinematic bars).
 * The particle field is computed from time and position only: it is anchored in the world and wraps
 * around you, so there's nothing to simulate or store.
 */
public final class AtmosphereRenderer {
    private static float presence = 0f;      // fades in/out with the music
    private static float bars = 0f;
    private static long lastNs = System.nanoTime();

    private AtmosphereRenderer() {}

    private static boolean wanted(LyricsConfig c) {
        if (!c.atmoOnlyMusic) return true;
        NowPlaying np = SpotifyClient.current();
        return np != null && np.playing();
    }

    private static void tickPresence(LyricsConfig c) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float target = wanted(c) ? 1f : 0f;
        presence += (target - presence) * (1f - (float) Math.exp(-2.5f * dt));
        bars += ((c.cinemaBars ? target : 0f) - bars) * (1f - (float) Math.exp(-4f * dt));
    }

    // ------------------------------------------------------------------ world particles

    public static void renderWorld(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        LyricsConfig c = LyricsConfig.get();
        if (mc.world == null || c.atmo == LyricsConfig.Atmo.NONE || presence < 0.01f) return;
        MatrixStack ms = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        if (ms == null || consumers == null) return;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Canvas.World cv = new Canvas.World(ms, consumers, false, LightmapTextureManager.MAX_LIGHT_COORDINATE);

        LyricsConfig.Atmo kind = c.atmo;
        float boxW = 22f, boxH = 14f;
        double cx = cam.x, cy = cam.y + 3, cz = cam.z;
        int n = Math.round((kind == LyricsConfig.Atmo.SNOW ? 240 : kind == LyricsConfig.Atmo.PETALS ? 90 : 120) * c.atmoDensity);
        dev.markie.lyrics.music.MusicPulse.update();
        boolean react = c.reactParticles && c.react;
        // with "react" on, particles drift on music time: faster when the song is busy, still when paused
        double t = react ? dev.markie.lyrics.music.MusicPulse.musicTime() * 1.4 + 1000 : (System.currentTimeMillis() % 10_000_000L) / 1000.0;
        float str = dev.markie.lyrics.music.MusicPulse.strength();
        float beatGrow = react ? 1f + 0.7f * str * dev.markie.lyrics.music.MusicPulse.beat() : 1f;
        float beatGlow = react ? 0.75f + 0.25f * dev.markie.lyrics.music.MusicPulse.energy() + 0.5f * str * dev.markie.lyrics.music.MusicPulse.beat() : 1f;

        // two passes: soft halos first, then the bright cores (keeps texture switches low)
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < n; k++) {
                float h1 = h(k, 1), h2 = h(k, 2), h3 = h(k, 3), h4 = h(k, 4), h5 = h(k, 5), h6 = h(k, 6);
                double px = h1 * boxW, py = h2 * boxH, pz = h3 * boxW;
                float alpha = 1f, size = 0.08f, spin = 0f, flip = 1f;
                int rgb;
                Identifier core;
                boolean halo;
                switch (kind) {
                    case FIREFLIES -> {
                        px += Math.sin(t * 0.3 * (0.5 + h4) + h5 * 6.28) * 1.6;
                        py += Math.sin(t * 0.45 + h6 * 6.28) * 0.7;
                        pz += Math.cos(t * 0.27 * (0.5 + h5) + h4 * 6.28) * 1.6;
                        double blink = Math.max(0, Math.sin(t * (0.8 + h4 * 1.6) + h5 * 20));
                        alpha = (float) (0.15 + 0.85 * blink * blink);
                        rgb = c.atmoLyricColors ? ((k & 1) == 0 ? Colors.colorA() : Colors.colorB()) : ((k & 1) == 0 ? 0xD8FF6B : 0xFFE680);
                        size = 0.10f + h6 * 0.06f;
                        core = FxTextures.BLOB;
                        halo = true;
                    }
                    case STARDUST -> {
                        py += t * 0.25 * (0.5 + h4);
                        px += Math.sin(t * 0.5 + h5 * 6.28) * 0.4;
                        alpha = (float) (0.35 + 0.65 * Math.pow(Math.max(0, Math.sin(t * (1.5 + h6 * 2) + h4 * 30)), 4));
                        rgb = c.atmoLyricColors ? ((k & 1) == 0 ? Colors.colorA() : Colors.colorB()) : ((k % 3) == 0 ? 0xFFFFFF : (k & 1) == 0 ? 0xD7C4FF : 0xBFE8FF);
                        size = 0.14f + h6 * 0.12f;
                        spin = (float) (t * (h5 - 0.5) * 2);
                        core = FxTextures.SPARKLE;
                        halo = true;
                    }
                    case SNOW -> {
                        py -= t * 1.3 * (0.7 + h4 * 0.6);
                        px += Math.sin(t * 0.8 + h5 * 6.28) * 0.6;
                        pz += Math.cos(t * 0.6 + h6 * 6.28) * 0.4;
                        alpha = 0.85f;
                        rgb = c.atmoLyricColors ? LyricsAnimator.lerpRgb(Colors.colorA(), 0xFFFFFF, 0.6f) : 0xFFFFFF;
                        size = 0.10f + h6 * 0.12f;
                        spin = (float) (t * (h5 - 0.5));
                        core = h6 > 0.6f ? FxTextures.FLAKE : FxTextures.BLOB;
                        halo = false;
                    }
                    case PETALS -> {
                        py -= t * 0.7 * (0.7 + h4 * 0.6);
                        px += Math.sin(t * 0.7 + h5 * 6.28) * 1.2 + t * 0.3;
                        pz += Math.cos(t * 0.5 + h6 * 6.28) * 0.6;
                        alpha = 0.95f;
                        rgb = c.atmoLyricColors ? ((k & 1) == 0 ? Colors.colorA() : Colors.colorB()) : ((k & 1) == 0 ? 0xFFB7D5 : 0xFF8FB8);
                        size = 0.20f + h6 * 0.12f;
                        spin = (float) (t * (1 + h4 * 2) + h5 * 6.28);
                        flip = (float) Math.cos(t * (1.5 + h6 * 2) + h4 * 6.28);
                        core = FxTextures.PETAL;
                        halo = false;
                    }
                    default -> { // EMBERS
                        py += t * 1.0 * (0.5 + h4);
                        px += Math.sin(t * 1.3 + h5 * 6.28) * 0.5;
                        pz += Math.cos(t * 1.1 + h6 * 6.28) * 0.5;
                        alpha = (float) (0.4 + 0.6 * Math.abs(Math.sin(t * (4 + h6 * 6) + h4 * 40)));
                        rgb = c.atmoLyricColors ? Colors.colorA() : ((k & 1) == 0 ? 0xFF8A3D : 0xFFC05A);
                        size = 0.08f + h6 * 0.06f;
                        core = FxTextures.BLOB;
                        halo = true;
                    }
                }
                if (pass == 0 && !halo) continue;
                if (!c.atmoLyricColors) rgb = (k & 1) == 0 ? EnvColors.tint(rgb, 0.85f) : EnvColors.tint2(rgb, 0.85f);

                // wrap into the box around the camera
                double rx = wrap(px - cx, boxW), ry = wrap(py - cy, boxH), rz = wrap(pz - cz, boxW);
                float edge = fadeEdge((float) rx, boxW / 2) * fadeEdge((float) ry, boxH / 2) * fadeEdge((float) rz, boxW / 2);
                double ox = cx + rx - cam.x, oy = cy + ry - cam.y, oz = cz + rz - cam.z;
                double d = Math.sqrt(ox * ox + oy * oy + oz * oz);
                float near = (float) Math.min(1, Math.max(0, (d - 0.8) / 1.2));
                float a = Math.min(1f, alpha * edge * near * presence * beatGlow);
                if (a < 0.02f) continue;

                ms.push();
                ms.translate(ox, oy, oz);
                ms.multiply(camera.getRotation());
                float sc = size * c.atmoSize * beatGrow;
                ms.scale(sc, -sc, sc);
                if (spin != 0) ms.multiply(RotationAxis.POSITIVE_Z.rotation(spin));
                if (pass == 0) {
                    cv.sprite(FxTextures.BLOB, 0, 0, 4.5f, 4.5f, LyricsAnimator.argb(a * 0.35f, rgb));
                } else {
                    cv.sprite(core, 0, 0, Math.max(0.15f, Math.abs(flip)), 1f, LyricsAnimator.argb(a, rgb));
                }
                ms.pop();
            }
        }
    }

    private static double wrap(double v, double size) {
        double r = v % size;
        if (r < -size / 2) r += size;
        if (r > size / 2) r -= size;
        return r;
    }

    private static float fadeEdge(float v, float half) {
        float f = Math.abs(v) / half;
        return f < 0.7f ? 1f : Math.max(0f, 1f - (f - 0.7f) / 0.3f);
    }

    private static float h(int k, int salt) {
        int x = k * 0x9E3779B1 + salt * 0x85EBCA6B;
        x ^= x >>> 15; x *= 0x2C1B3C6D; x ^= x >>> 12; x *= 0x297A2D39; x ^= x >>> 15;
        return (x & 0xFFFFFF) / 16777215f;
    }

    // ------------------------------------------------------------------ screen overlays

    public static void renderOverlay(DrawContext ctx) {
        LyricsConfig c = LyricsConfig.get();
        tickPresence(c);
        if (presence < 0.01f && bars < 0.01f) return;
        FxTextures.ensure();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        float pulse = 0f;
        LyricsAnimator.Shown active = LyricsAnimator.active();
        if (c.linePulse && active != null && c.showLyrics) {
            float since = System.currentTimeMillis() - active.appearReal;
            pulse = since < 0 ? 0 : (float) Math.exp(-since / 380f);
        }

        if (c.haze > 0) {
            int top = LyricsAnimator.argb(c.haze * 0.22f * presence * (1f + pulse * 0.8f), Colors.colorA());
            ctx.fillGradient(0, 0, sw, Math.round(sh * 0.5f), top, 0x00000000);
        }
        if (c.reactScreen && c.react) {
            dev.markie.lyrics.music.MusicPulse.update();
            pulse = Math.max(pulse, dev.markie.lyrics.music.MusicPulse.strength() * (0.35f * dev.markie.lyrics.music.MusicPulse.beat() + 0.45f * dev.markie.lyrics.music.MusicPulse.bar()));
            if (c.vignette < 0.05f && pulse > 0.01f) {
                // even with no vignette set, flash a soft coloured edge on the beat
                int tint = LyricsAnimator.lerpRgb(Colors.glow(), 0x000000, 0.2f);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FxTextures.VIGNETTE, 0, 0, 0, 0, sw, sh, 128, 128, 128, 128,
                    LyricsAnimator.argb(Math.min(0.6f, pulse * 0.45f * presence), tint));
            }
        }
        float vig = c.vignette * presence + pulse * 0.35f * presence;
        if (vig > 0.01f) {
            int tint = c.vignetteTint ? LyricsAnimator.lerpRgb(Colors.glow(), 0x000000, 0.35f) : 0x000000;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FxTextures.VIGNETTE, 0, 0, 0, 0, sw, sh, 128, 128, 128, 128,
                LyricsAnimator.argb(Math.min(1f, vig), tint));
        }
        if (bars > 0.01f) {
            int bh = Math.round(sh * 0.085f * LyricsAnimator.easeOutCubic(bars));
            ctx.fill(0, 0, sw, bh, 0xFF000000);
            ctx.fill(0, sh - bh, sw, sh, 0xFF000000);
        }
    }
}
