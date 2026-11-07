package dev.markie.lyrics.mixin;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.music.MusicPulse;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional tiny zoom "kick" on each beat. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void markielyrics$beatFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!changingFov || !LyricsConfig.get().reactCamera) return;
        float kick = MusicPulse.strength() * (1.4f * MusicPulse.beat() + 0.8f * MusicPulse.bar());
        if (kick > 0.01f) cir.setReturnValue(cir.getReturnValue() - kick);
    }
}
