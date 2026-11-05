package dev.pvpmod.mixin;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    private static final String RENDER_ITEM =
        "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
        + "Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;"
        + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";

    @Inject(method = RENDER_ITEM, at = @At("HEAD"))
    private void pvpmod$pushScale(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                  MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                  CallbackInfo ci) {
        matrices.push();
        float s = PvpConfig.get().scaleFor(stack);
        if (s != 1.0f) matrices.scale(s, s, s);
    }

    @Inject(method = RENDER_ITEM, at = @At("RETURN"))
    private void pvpmod$popScale(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                 MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                 CallbackInfo ci) {
        matrices.pop();
    }
}
