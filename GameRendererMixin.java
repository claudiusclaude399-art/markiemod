package dev.pvpmod.mixin;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "showFloatingItem(Lnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void pvpmod$hideTotem(ItemStack stack, CallbackInfo ci) {
        if (!PvpConfig.get().totemOverlay && stack.isOf(Items.TOTEM_OF_UNDYING)) ci.cancel();
    }
}
