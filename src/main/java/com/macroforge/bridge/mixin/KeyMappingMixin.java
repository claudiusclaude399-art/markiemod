package com.macroforge.bridge.mixin;

import com.macroforge.bridge.BridgeMod;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While MacroForge holds a key "let go" (W-tap), keep it up and remember what the real key is doing. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
    @Inject(method = "setDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void macroforge$setDown(boolean down, CallbackInfo ci) {
        if (BridgeMod.interceptSetDown((KeyMapping) (Object) this, down)) ci.cancel();
    }
}
