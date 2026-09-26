package top.lqsnow.blockracing.client;

import org.lwjgl.glfw.GLFW;

final class BoardKeyPolicy {
    static final int ACTIVE_MODIFIERS = GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL
            | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
    private BoardKeyPolicy() { }
    static boolean intercept(int action, int key, int modifiers, boolean supported, boolean otherScreen) {
        return key == GLFW.GLFW_KEY_TAB
                && (modifiers & ACTIVE_MODIFIERS) == 0 && supported && !otherScreen;
    }
}
