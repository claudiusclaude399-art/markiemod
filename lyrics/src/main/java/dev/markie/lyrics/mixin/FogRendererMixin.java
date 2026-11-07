package dev.markie.lyrics.mixin;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.sky.Skybox;
import net.minecraft.client.render.Camera;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.World;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tints the horizon fog towards the custom sky so the land fades into it instead of into blue. */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @Inject(method = "getFogColor", at = @At("RETURN"), require = 0)
    private void markielyrics$fogColor(Camera camera, float tickProgress, ClientWorld world, int viewDistance, float skyDarkness,
                                       CallbackInfoReturnable<Vector4f> cir) {
        if (world.getRegistryKey() != World.OVERWORLD || camera.getSubmersionType() != CameraSubmersionType.NONE) return;
        Vector4f v = cir.getReturnValue();
        if (dev.markie.lyrics.render.EnvColors.active()) {
            int fc = (Math.round(Math.min(1, v.x) * 255) << 16) | (Math.round(Math.min(1, v.y) * 255) << 8) | Math.round(Math.min(1, v.z) * 255);
            int t = dev.markie.lyrics.render.EnvColors.tint(fc, 0.7f);
            v.set(((t >> 16) & 255) / 255f, ((t >> 8) & 255) / 255f, (t & 255) / 255f, v.w);
        }
        if (LyricsConfig.get().reactSky) {
            float k = 1f + dev.markie.lyrics.music.MusicPulse.strength() * 0.15f * dev.markie.lyrics.music.MusicPulse.beat();
            v.set(Math.min(1f, v.x * k), Math.min(1f, v.y * k), Math.min(1f, v.z * k), v.w);
        }
        if (!LyricsConfig.get().skyFog || !Skybox.active()) return;
        int h = Skybox.horizonColor();
        float b = LyricsConfig.get().skyBrightness;
        float r = Math.min(1f, ((h >> 16) & 255) / 255f * b), g = Math.min(1f, ((h >> 8) & 255) / 255f * b), bl = Math.min(1f, (h & 255) / 255f * b);
        float k = 0.85f;
        v.set(v.x + (r - v.x) * k, v.y + (g - v.y) * k, v.z + (bl - v.z) * k, v.w);
    }
}
