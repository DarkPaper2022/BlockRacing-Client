package top.lqsnow.blockracing.client;

import org.lwjgl.glfw.GLFW;

final class BoardKeyPolicy {
    private BoardKeyPolicy() { }
    static boolean intercept(int action, int key, int modifiers, boolean supported, boolean otherScreen) {
        return action != GLFW.GLFW_RELEASE && key == GLFW.GLFW_KEY_TAB && modifiers == 0
                && supported && !otherScreen;
    }
}
