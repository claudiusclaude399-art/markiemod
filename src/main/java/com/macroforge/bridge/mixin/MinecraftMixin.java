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

    /** Right after Minecraft handled the keys (hotbar switches applied): run the stun-web step that needs them. */
    @Inject(method = "handleKeybinds", at = @At("TAIL"), require = 0)
    private void macroforge$afterKeybinds(CallbackInfo ci) {
        BridgeMod.afterKeybinds();
    }
}
