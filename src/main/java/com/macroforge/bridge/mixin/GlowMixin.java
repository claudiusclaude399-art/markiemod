package com.macroforge.bridge.mixin;

import com.macroforge.bridge.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Outline the current aim / auto-hit target (vanilla glowing outline, only on your screen). */
@Mixin(Minecraft.class)
public abstract class GlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
    private void macroforge$glow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Hud.shouldGlow(entity)) cir.setReturnValue(true);
    }
}
