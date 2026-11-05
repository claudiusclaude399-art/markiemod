package dev.pvpmod.mixin;

import dev.pvpmod.PvpTint;
import dev.pvpmod.render.TintingVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Step 3 of the tint: when the marker is present, draw the item through a colour-multiplying consumer. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderItem(Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;"
                   + "Lnet/minecraft/client/render/VertexConsumerProvider;II[ILjava/util/List;"
                   + "Lnet/minecraft/client/render/RenderLayer;"
                   + "Lnet/minecraft/client/render/item/ItemRenderState$Glint;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void pvpmod$tint(ItemDisplayContext displayContext, MatrixStack matrices,
                                    VertexConsumerProvider vertexConsumers, int light, int overlay,
                                    int[] tints, List<BakedQuad> quads, RenderLayer layer,
                                    ItemRenderState.Glint glint, CallbackInfo ci) {
        if (tints != null && tints.length == 2 && tints[0] == PvpTint.MAGIC) {
            int rgb = tints[1];
            VertexConsumerProvider wrapped = l -> new TintingVertexConsumer(vertexConsumers.getBuffer(l), rgb);
            ItemRenderer.renderItem(displayContext, matrices, wrapped, light, overlay,
                                    new int[] { -1, -1 }, quads, layer, glint);
            ci.cancel();
        }
    }
}
