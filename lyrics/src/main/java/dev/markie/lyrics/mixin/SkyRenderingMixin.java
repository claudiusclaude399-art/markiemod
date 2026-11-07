package dev.markie.lyrics.mixin;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.sky.Skybox;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the overworld sky for the chosen custom sky. */
@Mixin(SkyRendering.class)
public class SkyRenderingMixin {
    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$topSky(int color, CallbackInfo ci) {
        dev.markie.lyrics.render.Colors.vanillaSky = color & 0xFFFFFF;
        if (Skybox.active()) {
            Skybox.render();
            ci.cancel();
        }
    }

    /** Vanilla sky colour pulses with the beat too. */
    @ModifyVariable(method = "renderTopSky", at = @At("HEAD"), argsOnly = true, require = 0)
    private int markielyrics$pulseSky(int color) {
        if (!LyricsConfig.get().reactSky) return color;
        float k = 1f + dev.markie.lyrics.music.MusicPulse.strength() * 0.18f * dev.markie.lyrics.music.MusicPulse.beat();
        if (k <= 1.001f) return color;
        int r = Math.min(255, Math.round(((color >> 16) & 255) * k)), g = Math.min(255, Math.round(((color >> 8) & 255) * k)),
            b = Math.min(255, Math.round((color & 255) * k));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    @Inject(method = "renderGlowingSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$glow(MatrixStack matrices, float angle, int color, CallbackInfo ci) {
        if (Skybox.active()) ci.cancel();
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$dark(CallbackInfo ci) {
        if (Skybox.active()) ci.cancel();
    }

    @Inject(method = "renderCelestialBodies", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$celestial(MatrixStack matrices, float a, float b, float c, MoonPhase phase, float d, float e, CallbackInfo ci) {
        if (Skybox.active() && !LyricsConfig.get().skySunMoon) ci.cancel();
    }
}
