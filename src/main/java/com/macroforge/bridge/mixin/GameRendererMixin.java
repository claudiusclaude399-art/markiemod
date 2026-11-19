package com.macroforge.bridge.mixin;

import com.macroforge.bridge.Aim;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the camera every rendered frame (smooth aiming at any fps). Falls back to once per tick if this hook is missing. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void macroforge$frame(CallbackInfo ci) {
        Aim.frameHook = true;
        Aim.frame();
    }
}
