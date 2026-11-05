package dev.pvpmod.mixin;

import dev.pvpmod.PvpTint;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Step 2 of the tint: stamp the colour into the layer's tint array right before it is submitted. */
@Mixin(ItemRenderState.LayerRenderState.class)
public abstract class LayerRenderStateMixin {
    @Shadow private int[] tints;

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V",
            at = @At("HEAD"), require = 0)
    private void pvpmod$stamp(MatrixStack matrices, OrderedRenderCommandQueue queue,
                              int light, int overlay, int outline, CallbackInfo ci) {
        int c = PvpTint.current;
        if (c != PvpTint.NONE) {
            this.tints = new int[] { PvpTint.MAGIC, c };
        } else if (this.tints != null && this.tints.length == 2 && this.tints[0] == PvpTint.MAGIC) {
            this.tints = new int[0];
        }
    }
}
