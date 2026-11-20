package com.macroforge.bridge.mixin;

import com.macroforge.bridge.Pvp;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees entity events on the client (35 = totem of undying used) to count other players' totem pops. */
@Mixin(LivingEntity.class)
public abstract class EntityEventMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"), require = 0)
    private void macroforge$entityEvent(byte id, CallbackInfo ci) {
        try { if (((LivingEntity) (Object) this).level().isClientSide()) Pvp.onEntityEvent((LivingEntity) (Object) this, id); } catch (Throwable ignored) {}
    }
}
