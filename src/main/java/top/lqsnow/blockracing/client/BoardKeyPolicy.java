package top.lqsnow.blockracing.client;

import org.lwjgl.glfw.GLFW;

final class BoardKeyPolicy {
    static final int ACTIVE_MODIFIERS = GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL
            | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
    private BoardKeyPolicy() { }
    static boolean intercept(int action, int key, int modifiers, boolean supported, boolean otherScreen,
                             boolean boardOpen) {
        if (key != GLFW.GLFW_KEY_TAB) return false;
        // A release only belongs to us if it closes the board. Otherwise vanilla must see it,
        // e.g. Shift+Tab player list where Shift was let go before Tab.
        if (action == GLFW.GLFW_RELEASE) return boardOpen;
        return (modifiers & ACTIVE_MODIFIERS) == 0 && supported && !otherScreen;
    }
}
