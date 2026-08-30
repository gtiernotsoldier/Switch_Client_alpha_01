package io.doppel.adapter.fabric.v1_21.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.doppel.adapter.fabric.v1_21.FabricVelocityInterceptor;

/**
 * Mixin into ClientPlayNetworkHandler to intercept velocity packets at HEAD.
 *
 * If FabricVelocityInterceptor.process() returns true, the packet is cancelled
 * (ci.cancel()) and vanilla motion application is skipped entirely.
 *
 * Registration: add to doppel.mixins.json:
 *   "client": ["ClientPlayNetworkHandlerMixin"]
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(
        method = "onEntityVelocityUpdate(Lnet/minecraft/network/packet/s2c/play/EntityVelocityUpdateS2CPacket;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void doppel$onVelocity(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        boolean cancel = FabricVelocityInterceptor.INSTANCE.process(
            packet.getId(),
            packet.getVelocityX(),
            packet.getVelocityY(),
            packet.getVelocityZ()
        );
        if (cancel) {
            ci.cancel();
        }
    }
}
