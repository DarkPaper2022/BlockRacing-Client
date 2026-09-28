package top.lqsnow.blockracing.client.test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Collects teleportation, chunk loading, frame times, and network latency samples.
 */
public final class ClientProfiler {
    public record TeleportSample(
            long requestId,
            long requestSentAtNanos,
            long packetReceivedAtNanos,
            long chunkLoadedAtNanos,
            long firstFrameRenderedAtNanos,
            int targetX,
            int targetZ
    ) {
        public long networkRoundTripMs() {
            return packetReceivedAtNanos > requestSentAtNanos
                    ? (packetReceivedAtNanos - requestSentAtNanos) / 1_000_000
                    : 0;
        }

        public long chunkLoadingDurationMs() {
            return chunkLoadedAtNanos > packetReceivedAtNanos
                    ? (chunkLoadedAtNanos - packetReceivedAtNanos) / 1_000_000
                    : 0;
        }

        public long totalTimeMs() {
            return firstFrameRenderedAtNanos > requestSentAtNanos
                    ? (firstFrameRenderedAtNanos - requestSentAtNanos) / 1_000_000
                    : 0;
        }
    }

    private static final Deque<TeleportSample> SAMPLES = new ArrayDeque<>();
    private static volatile boolean active = false;

    // In-flight teleport tracker
    private static long currentReqId = 0;
    private static long inFlightStartNanos = 0;
    private static long inFlightPacketReceivedNanos = 0;
    private static long inFlightChunkLoadedNanos = 0;
    private static int inFlightTargetX = 0;
    private static int inFlightTargetZ = 0;

    private ClientProfiler() {}

    public static void startSession() {
        active = true;
        SAMPLES.clear();
    }

    public static void stopSession() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized void recordTeleportSent(long reqId) {
        if (!active) return;
        currentReqId = reqId;
        inFlightStartNanos = System.nanoTime();
        inFlightPacketReceivedNanos = 0;
        inFlightChunkLoadedNanos = 0;
    }

    public static synchronized void recordTeleportPacketReceived(int x, int z) {
        if (!active || inFlightStartNanos == 0) return;
        inFlightPacketReceivedNanos = System.nanoTime();
        inFlightTargetX = x;
        inFlightTargetZ = z;
    }

    public static synchronized void recordChunkLoaded() {
        if (!active || inFlightPacketReceivedNanos == 0) return;
        if (inFlightChunkLoadedNanos == 0) {
            inFlightChunkLoadedNanos = System.nanoTime();
        }
    }

    public static synchronized boolean isWaitingForChunk() {
        return active && inFlightPacketReceivedNanos != 0 && inFlightChunkLoadedNanos == 0;
    }

    public static synchronized int targetX() { return inFlightTargetX; }
    public static synchronized int targetZ() { return inFlightTargetZ; }

    public static synchronized boolean hasInFlightRequest() {
        return active && inFlightStartNanos != 0;
    }

    public static synchronized void recordFirstFrameRendered() {
        if (!active || inFlightPacketReceivedNanos == 0 || inFlightChunkLoadedNanos == 0) return;
        long now = System.nanoTime();
        if (inFlightChunkLoadedNanos == 0) {
            inFlightChunkLoadedNanos = now;
        }
        SAMPLES.addLast(new TeleportSample(
                currentReqId,
                inFlightStartNanos,
                inFlightPacketReceivedNanos,
                inFlightChunkLoadedNanos,
                now,
                inFlightTargetX,
                inFlightTargetZ
        ));
        // Reset in-flight
        inFlightStartNanos = 0;
        inFlightPacketReceivedNanos = 0;
        inFlightChunkLoadedNanos = 0;
    }

    public static synchronized List<TeleportSample> getSamples() {
        return List.copyOf(SAMPLES);
    }

    public static synchronized void clear() {
        SAMPLES.clear();
        inFlightStartNanos = 0;
        inFlightPacketReceivedNanos = 0;
        inFlightChunkLoadedNanos = 0;
    }
}
