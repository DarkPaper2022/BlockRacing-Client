package top.lqsnow.blockracing.client.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.lqsnow.blockracing.client.test.NetworkLatencyHandler;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private Channel channel;

    @Inject(method = "configurePacketHandler", at = @At("RETURN"))
    private void injectLatencySimulator(ChannelPipeline pipeline, CallbackInfo ci) {
        if (pipeline != null && pipeline.get(NetworkLatencyHandler.HANDLER_NAME) == null) {
            pipeline.addBefore("packet_handler", NetworkLatencyHandler.HANDLER_NAME, new NetworkLatencyHandler());
        }
    }
}
