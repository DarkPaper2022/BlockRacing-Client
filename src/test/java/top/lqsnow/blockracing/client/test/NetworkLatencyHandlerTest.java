package top.lqsnow.blockracing.client.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NetworkLatencyHandlerTest {

    @BeforeEach
    public void setUp() {
        NetworkLatencyHandler.clear();
    }

    @Test
    public void testDefaultDisabled() {
        assertFalse(NetworkLatencyHandler.isEnabled());
        assertEquals(0, NetworkLatencyHandler.getInboundLatencyMs());
        assertEquals(0, NetworkLatencyHandler.getOutboundLatencyMs());
        assertEquals(0, NetworkLatencyHandler.getJitterMs());
        assertEquals(0, NetworkLatencyHandler.getMaxBytesPerSecond());
    }

    @Test
    public void testConfigInjection() {
        // 60ms RTT, 10ms Jitter, 3MB/s bandwidth
        long threeMBps = 3 * 1024 * 1024;
        NetworkLatencyHandler.setConfig(60, 10, threeMBps);
        assertTrue(NetworkLatencyHandler.isEnabled());
        assertEquals(30, NetworkLatencyHandler.getInboundLatencyMs()); // half of 60ms RTT
        assertEquals(30, NetworkLatencyHandler.getOutboundLatencyMs());
        assertEquals(10, NetworkLatencyHandler.getJitterMs());
        assertEquals(threeMBps, NetworkLatencyHandler.getMaxBytesPerSecond());

        NetworkLatencyHandler.clear();
        assertFalse(NetworkLatencyHandler.isEnabled());
    }

    @Test
    public void testNegativeClamping() {
        NetworkLatencyHandler.setConfig(-50, -10, -100);
        assertFalse(NetworkLatencyHandler.isEnabled());
        assertEquals(0, NetworkLatencyHandler.getInboundLatencyMs());
        assertEquals(0, NetworkLatencyHandler.getMaxBytesPerSecond());
    }
}
