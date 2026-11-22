package io.doppel.adapter.fabric.v1_21.mixin;

import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.doppel.adapter.fabric.v1_21.FabricPacketVault;

/**
 * Outbound interception for the packet vault: every C2S gameplay packet crosses
 * {@code ClientCommonNetworkHandler#sendPacket} (movement, input, attacks, keepalive).
 *
 * HEAD + cancellable = hold at the source. {@link FabricPacketVault} re-sends held
 * packets straight through {@code ClientConnection.send} on release, which does NOT
 * re-enter this handler — no recursion, no re-hold.
 *
 * This mixin lives on ClientCommonNetworkHandler (not ClientPlayNetworkHandler)
 * because the 1.20.2+ channel refactor moved sendPacket up: intercepting here also
 * covers configuration-phase traffic.
 *
 * Registration: doppel.mixins.json → "client".
 */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {

    @Inject(
        method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void doppel$onOutbound(Packet<?> packet, CallbackInfo ci) {
        if (FabricPacketVault.INSTANCE.onOutbound(packet)) {
            ci.cancel();
        }
    }
}
