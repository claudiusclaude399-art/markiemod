package com.macroforge.bridge.mixin;

import com.macroforge.bridge.BridgeMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the combat assist at the exact moment Minecraft reads the attack key (fresh crosshair target, same-tick swing). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"), require = 0)
    private void macroforge$beforeKeybinds(CallbackInfo ci) {
        BridgeMod.keybindHook = true;
        BridgeMod.runAssist();
    }
}
