package top.lqsnow.blockracing.client.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClientProfilerTest {

    @BeforeEach
    public void setUp() {
        ClientProfiler.clear();
        ClientProfiler.stopSession();
    }

    @Test
    public void testInactiveSamplingIgnored() {
        assertFalse(ClientProfiler.isActive());
        ClientProfiler.recordTeleportSent(1);
        ClientProfiler.recordTeleportPacketReceived(100, 200);
        ClientProfiler.recordChunkLoaded();
        ClientProfiler.recordFirstFrameRendered();
        assertTrue(ClientProfiler.getSamples().isEmpty());
    }

    @Test
    public void testActiveSampling() {
        ClientProfiler.startSession();
        assertTrue(ClientProfiler.isActive());

        ClientProfiler.recordTeleportSent(1);
        assertFalse(ClientProfiler.hasTeleportPacketForCurrentRequest());
        ClientProfiler.recordTeleportPacketReceived(1000, -2000);
        assertTrue(ClientProfiler.hasTeleportPacketForCurrentRequest());
        ClientProfiler.recordChunkLoaded();
        ClientProfiler.recordFirstFrameRendered();

        var samples = ClientProfiler.getSamples();
        assertEquals(1, samples.size());
        ClientProfiler.TeleportSample sample = samples.get(0);
        assertEquals(1, sample.requestId());
        assertEquals(1000, sample.targetX());
        assertEquals(-2000, sample.targetZ());
        assertTrue(sample.totalTimeMs() >= 0);
    }
}
