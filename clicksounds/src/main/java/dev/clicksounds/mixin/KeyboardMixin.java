package dev.clicksounds.mixin;

import dev.clicksounds.ClickSoundsClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    // PRESS + RELEASE only (not REPEAT): holding W doesn't machine-gun the sound, it plays once like a real key.
    @Inject(method = "onKey", at = @At("HEAD"))
    private void clicksounds$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) ClickSoundsClient.onKey(input, true);
        else if (action == GLFW.GLFW_RELEASE) ClickSoundsClient.onKey(input, false);
    }
}
