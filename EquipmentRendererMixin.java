package dev.pvpmod.mixin;

import dev.pvpmod.PvpTint;
import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom armor colour: remember the piece being drawn, then force that colour for its layers. */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererMixin {
    private static final String HEAD_ARGS =
        "Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;"
        + "Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
        + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;"
        + "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;";
    private static final String R9 = "render(" + HEAD_ARGS + "II)V";
    private static final String R11 = "render(" + HEAD_ARGS + "ILnet/minecraft/util/Identifier;II)V";
    private static final String DYE =
        "getDyeColor(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$Layer;I)I";

    @Inject(method = R9, at = @At("HEAD"), require = 0)
    private void pvpmod$start9(EquipmentModel.LayerType type, RegistryKey<?> key, Model<?> model, Object state,
                               ItemStack stack, MatrixStack matrices, OrderedRenderCommandQueue queue,
                               int light, int outline, CallbackInfo ci) {
        PvpTint.armor = PvpConfig.get().colorFor(stack);
    }

    @Inject(method = R9, at = @At("RETURN"), require = 0)
    private void pvpmod$end9(EquipmentModel.LayerType type, RegistryKey<?> key, Model<?> model, Object state,
                             ItemStack stack, MatrixStack matrices, OrderedRenderCommandQueue queue,
                             int light, int outline, CallbackInfo ci) {
        PvpTint.armor = PvpTint.NONE;
    }

    @Inject(method = R11, at = @At("HEAD"), require = 0)
    private void pvpmod$start11(EquipmentModel.LayerType type, RegistryKey<?> key, Model<?> model, Object state,
                                ItemStack stack, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                int light, Identifier id, int a, int b, CallbackInfo ci) {
        PvpTint.armor = PvpConfig.get().colorFor(stack);
    }

    @Inject(method = R11, at = @At("RETURN"), require = 0)
    private void pvpmod$end11(EquipmentModel.LayerType type, RegistryKey<?> key, Model<?> model, Object state,
                              ItemStack stack, MatrixStack matrices, OrderedRenderCommandQueue queue,
                              int light, Identifier id, int a, int b, CallbackInfo ci) {
        PvpTint.armor = PvpTint.NONE;
    }

    @Inject(method = DYE, at = @At("RETURN"), cancellable = true, require = 0)
    private static void pvpmod$dye(EquipmentModel.Layer layer, int dyeColor, CallbackInfoReturnable<Integer> cir) {
        if (PvpTint.armor != PvpTint.NONE) cir.setReturnValue(0xFF000000 | PvpTint.armor);
    }
}
