package dev.markiesdetector.mixin;

import dev.markiesdetector.analyzer.FightTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds combat packets to the fight analyzer. Injected after the thread check, so this runs on the main thread. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    private static final String FORCE_MAIN =
        "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V";

    @Inject(method = "onEntityDamage", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER))
    private void markiesdetector$damage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        try { FightTracker.onDamage(packet); } catch (Throwable t) { System.err.println("[MarkiesDetector] analyzer: " + t); }
    }

    @Inject(method = "onEntityAnimation", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER))
    private void markiesdetector$animation(EntityAnimationS2CPacket packet, CallbackInfo ci) {
        try { FightTracker.onAnimation(packet); } catch (Throwable t) { System.err.println("[MarkiesDetector] analyzer: " + t); }
    }

    @Inject(method = "onEntityStatus", at = @At(value = "INVOKE", target = FORCE_MAIN, shift = At.Shift.AFTER))
    private void markiesdetector$status(EntityStatusS2CPacket packet, CallbackInfo ci) {
        try { FightTracker.onStatus(packet); } catch (Throwable t) { System.err.println("[MarkiesDetector] analyzer: " + t); }
    }
}
