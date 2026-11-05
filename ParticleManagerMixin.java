package dev.pvpmod.mixin;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    private static final String ADD =
        "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;";

    @Inject(method = ADD, at = @At("HEAD"), cancellable = true, require = 0)
    private void pvpmod$filter(ParticleEffect effect, double x, double y, double z,
                               double vx, double vy, double vz,
                               CallbackInfoReturnable<Particle> cir) {
        if (!PvpConfig.get().shouldSpawn(effect)) cir.setReturnValue(null);
    }

    @Inject(method = ADD, at = @At("RETURN"), require = 0)
    private void pvpmod$style(ParticleEffect effect, double x, double y, double z,
                              double vx, double vy, double vz,
                              CallbackInfoReturnable<Particle> cir) {
        Particle p = cir.getReturnValue();
        if (p != null) PvpConfig.get().stylise(effect, p);
    }
}
