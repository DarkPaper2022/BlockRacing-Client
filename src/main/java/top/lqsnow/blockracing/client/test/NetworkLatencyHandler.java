package top.lqsnow.blockracing.client.test;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;

/**
 * Netty duplex handler that injects simulated network latency and jitter
 * into outbound and inbound Minecraft packets for testing and profiling.
 */
public final class NetworkLatencyHandler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "blockracing_latency_simulator";

    private static volatile long inboundLatencyMs = 0;
    private static volatile long outboundLatencyMs = 0;
    private static volatile long jitterMs = 0;

    public static void setConfig(long latencyMs, long jitter) {
        inboundLatencyMs = Math.max(0, latencyMs);
        outboundLatencyMs = Math.max(0, latencyMs);
        jitterMs = Math.max(0, jitter);
    }

    public static void clear() {
        inboundLatencyMs = 0;
        outboundLatencyMs = 0;
        jitterMs = 0;
    }

    public static boolean isEnabled() {
        return inboundLatencyMs > 0 || outboundLatencyMs > 0;
    }

    public static long getInboundLatencyMs() {
        return inboundLatencyMs;
    }

    public static long getOutboundLatencyMs() {
        return outboundLatencyMs;
    }

    public static long getJitterMs() {
        return jitterMs;
    }

    private long calculateDelay(long baseMs) {
        if (baseMs <= 0) return 0;
        if (jitterMs <= 0) return baseMs;
        long variance = (long) ((Math.random() * 2.0 - 1.0) * jitterMs);
        return Math.max(0, baseMs + variance);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        long delay = calculateDelay(inboundLatencyMs);
        if (delay <= 0) {
            super.channelRead(ctx, msg);
        } else {
            ctx.executor().schedule(() -> {
                try {
                    super.channelRead(ctx, msg);
                } catch (Exception ex) {
                    ctx.fireExceptionCaught(ex);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        long delay = calculateDelay(outboundLatencyMs);
        if (delay <= 0) {
            super.write(ctx, msg, promise);
        } else {
            ctx.executor().schedule(() -> {
                try {
                    super.write(ctx, msg, promise);
                } catch (Exception ex) {
                    promise.setFailure(ex);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }
}
