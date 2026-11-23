package dev.clicksounds.mixin;

import dev.clicksounds.ClickSoundsClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void clicksounds$onMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) ClickSoundsClient.onMouseButton(input.button(), true);
        else if (action == GLFW.GLFW_RELEASE) ClickSoundsClient.onMouseButton(input.button(), false);
    }
}
