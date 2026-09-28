package top.lqsnow.blockracing.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.lqsnow.blockracing.client.test.ClientProfiler;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void onPlayerTeleportPacket(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        int x = (int) packet.change().position().x();
        int z = (int) packet.change().position().z();
        ClientProfiler.recordTeleportPacketReceived(x, z);
    }
}
