package dev.pvpmod.mixin;

import dev.pvpmod.PvpTint;
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

/** Step 1 of the tint: remember the colour of the item being drawn in your hand. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemTintMixin {
    private static final String RENDER_ITEM =
        "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
        + "Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;"
        + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";

    @Inject(method = RENDER_ITEM, at = @At("HEAD"))
    private void pvpmod$setTint(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                CallbackInfo ci) {
        PvpTint.current = PvpConfig.get().colorFor(stack);
    }

    @Inject(method = RENDER_ITEM, at = @At("RETURN"))
    private void pvpmod$clearTint(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                  MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                  CallbackInfo ci) {
        PvpTint.current = PvpTint.NONE;
    }
}
