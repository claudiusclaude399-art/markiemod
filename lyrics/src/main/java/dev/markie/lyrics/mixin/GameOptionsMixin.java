package dev.markie.lyrics.mixin;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.sky.Skybox;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides vanilla clouds while a custom sky is showing (without touching the player's saved setting). */
@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Inject(method = "getCloudRenderModeValue", at = @At("HEAD"), cancellable = true, require = 0)
    private void markielyrics$clouds(CallbackInfoReturnable<CloudRenderMode> cir) {
        if (LyricsConfig.get().skyHideClouds && LyricsConfig.get().skyEnabled && Skybox.active()) cir.setReturnValue(CloudRenderMode.OFF);
    }
}
