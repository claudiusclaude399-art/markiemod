package dev.pvpmod.mixin;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EndCrystalEntityModel;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalEntityModel.class)
public abstract class EndCrystalEntityModelMixin {
    @Shadow @Final public ModelPart outerGlass;
    @Shadow @Final public ModelPart innerGlass;
    @Shadow @Final public ModelPart base;

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;)V",
            at = @At("RETURN"), require = 0)
    private void pvpmod$parts(EndCrystalEntityRenderState state, CallbackInfo ci) {
        PvpConfig c = PvpConfig.get();
        this.outerGlass.visible = c.crystal3d;
        this.innerGlass.visible = c.crystal3d;
        if (!c.crystalBase) this.base.visible = false;
    }
}
