package top.lqsnow.blockracing.client.test;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty duplex handler that injects simulated network RTT latency, jitter,
 * and bandwidth rate-limiting (Token Bucket / Traffic Shaping) into outbound and inbound Minecraft packets.
 */
public final class NetworkLatencyHandler extends ChannelDuplexHandler {
    public static final String HANDLER_NAME = "blockracing_latency_simulator";

    private static volatile long inboundLatencyMs = 0;
    private static volatile long outboundLatencyMs = 0;
    private static volatile long jitterMs = 0;

    // Bandwidth limit in Bytes per second (0 = unlimited). e.g. 3MB/s = 3 * 1024 * 1024 = 3,145,728 B/s
    private static volatile long maxBytesPerSecond = 0;

    // Token bucket state for inbound rate-limiting
    private static final AtomicLong tokenBucket = new AtomicLong(0);
    private static volatile long lastTokenRefreshNanos = System.nanoTime();
    private long nextInboundDeliveryNanos;
    private long nextOutboundDeliveryNanos;

    public static void setConfig(long rttMs, long jitter, long bytesPerSec) {
        // RTT is round-trip time, so one-way latency is RTT / 2
        long oneWay = Math.max(0, rttMs / 2);
        inboundLatencyMs = oneWay;
        outboundLatencyMs = oneWay;
        jitterMs = Math.max(0, jitter);
        maxBytesPerSecond = Math.max(0, bytesPerSec);
        tokenBucket.set(maxBytesPerSecond); // initial burst capacity
        lastTokenRefreshNanos = System.nanoTime();
    }

    public static void clear() {
        inboundLatencyMs = 0;
        outboundLatencyMs = 0;
        jitterMs = 0;
        maxBytesPerSecond = 0;
    }

    public static boolean isEnabled() {
        return inboundLatencyMs > 0 || outboundLatencyMs > 0 || maxBytesPerSecond > 0;
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

    public static long getMaxBytesPerSecond() {
        return maxBytesPerSecond;
    }

    private long calculateDelay(long baseMs) {
        if (baseMs <= 0) return 0;
        if (jitterMs <= 0) return baseMs;
        long variance = (long) ((Math.random() * 2.0 - 1.0) * jitterMs);
        return Math.max(0, baseMs + variance);
    }

    /**
     * Calculates pacing delay if incoming packet exceeds current bandwidth token bucket.
     */
    private synchronized long calculateBandwidthDelay(int byteSize) {
        if (maxBytesPerSecond <= 0 || byteSize <= 0) return 0;

        long now = System.nanoTime();
        long elapsedNanos = now - lastTokenRefreshNanos;
        lastTokenRefreshNanos = now;

        // Refill tokens based on elapsed time (cap burst at 1 second worth of bandwidth)
        long newTokens = (elapsedNanos * maxBytesPerSecond) / 1_000_000_000L;
        long current = tokenBucket.get();
        long updated = Math.min(maxBytesPerSecond, current + newTokens);

        if (updated >= byteSize) {
            tokenBucket.set(updated - byteSize);
            return 0;
        } else {
            // Need to wait for remaining bytes
            long deficit = byteSize - updated;
            tokenBucket.set(0);
            return (deficit * 1000L) / maxBytesPerSecond; // delay in ms
        }
    }

    /** TCP preserves packet order; independently sampled jitter must not reorder messages. */
    private synchronized long orderedDelay(long requestedMs, boolean inbound) {
        long now = System.nanoTime();
        long requestedNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, requestedMs));
        long previous = inbound ? nextInboundDeliveryNanos : nextOutboundDeliveryNanos;
        if (requestedNanos == 0 && previous <= now) return 0;
        long delivery = Math.max(now + requestedNanos, previous + 1);
        if (inbound) nextInboundDeliveryNanos = delivery;
        else nextOutboundDeliveryNanos = delivery;
        long remaining = Math.max(0, delivery - now);
        return TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        int byteSize = 0;
        if (msg instanceof ByteBuf buf) {
            byteSize = buf.readableBytes();
        }

        long rttDelay = calculateDelay(inboundLatencyMs);
        long bwDelay = calculateBandwidthDelay(byteSize);
        long totalDelay = orderedDelay(rttDelay + bwDelay, true);

        if (totalDelay <= 0) {
            super.channelRead(ctx, msg);
        } else {
            ctx.executor().schedule(() -> {
                try {
                    super.channelRead(ctx, msg);
                } catch (Exception ex) {
                    ctx.fireExceptionCaught(ex);
                }
            }, totalDelay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        long delay = orderedDelay(calculateDelay(outboundLatencyMs), false);
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
