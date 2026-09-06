package top.lqsnow.blockracing.client;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

class BoardKeyPolicyTest {
    @Test void ordinaryTabAndRepeatsAreConsumedOnlyOnCompatibleGameplay() {
        assertTrue(BoardKeyPolicy.intercept(GLFW.GLFW_PRESS, GLFW.GLFW_KEY_TAB, 0, true, false));
        assertTrue(BoardKeyPolicy.intercept(GLFW.GLFW_REPEAT, GLFW.GLFW_KEY_TAB, 0, true, false));
        assertFalse(BoardKeyPolicy.intercept(GLFW.GLFW_PRESS, GLFW.GLFW_KEY_TAB, 0, false, false));
        assertFalse(BoardKeyPolicy.intercept(GLFW.GLFW_PRESS, GLFW.GLFW_KEY_TAB, 0, true, true));
    }
    @Test void shiftAndReleasesPassThroughWithoutStuckPlayerList() {
        assertFalse(BoardKeyPolicy.intercept(GLFW.GLFW_PRESS, GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT, true, false));
        assertFalse(BoardKeyPolicy.intercept(GLFW.GLFW_RELEASE, GLFW.GLFW_KEY_TAB, 0, true, false));
        assertFalse(BoardKeyPolicy.intercept(GLFW.GLFW_PRESS, GLFW.GLFW_KEY_T, 0, true, false));
    }
}
