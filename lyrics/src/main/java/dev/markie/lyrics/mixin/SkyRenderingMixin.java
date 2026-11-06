package dev.markie.lyrics.mixin;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.sky.Skybox;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the overworld sky for the chosen custom sky. */
@Mixin(SkyRendering.class)
public class SkyRenderingMixin {
    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$topSky(int color, CallbackInfo ci) {
        if (Skybox.active()) {
            Skybox.render();
            ci.cancel();
        }
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
