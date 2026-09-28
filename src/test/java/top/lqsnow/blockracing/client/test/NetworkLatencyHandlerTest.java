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
    }

    @Test
    public void testConfigInjection() {
        NetworkLatencyHandler.setConfig(150, 25);
        assertTrue(NetworkLatencyHandler.isEnabled());
        assertEquals(150, NetworkLatencyHandler.getInboundLatencyMs());
        assertEquals(150, NetworkLatencyHandler.getOutboundLatencyMs());
        assertEquals(25, NetworkLatencyHandler.getJitterMs());

        NetworkLatencyHandler.clear();
        assertFalse(NetworkLatencyHandler.isEnabled());
    }

    @Test
    public void testNegativeClamping() {
        NetworkLatencyHandler.setConfig(-50, -10);
        assertFalse(NetworkLatencyHandler.isEnabled());
        assertEquals(0, NetworkLatencyHandler.getInboundLatencyMs());
    }
}
