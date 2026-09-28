package top.lqsnow.blockracing.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import top.lqsnow.blockracing.client.test.TestScenarioRunner;

public final class BoardClient implements ClientModInitializer {
    public static BoardState snapshot;
    public static long receivedAt;
    public static String error = "";

    @Override public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(BoardPayload.TYPE, BoardPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BoardPayload.Request.TYPE, BoardPayload.Request.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BoardPayload.FavoriteAction.TYPE, BoardPayload.FavoriteAction.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(BoardPayload.TYPE, (payload, context) -> {
            try {
                snapshot = BoardState.decode(payload.data());
                receivedAt = System.nanoTime();
                error = "";
            } catch (Exception ex) {
                snapshot = null;
                error = "目标数据无效，请检查服务端版本 / Invalid server board data";
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());

        // Register TestScenario runner on client end tick
        ClientTickEvents.END_CLIENT_TICK.register(TestScenarioRunner::onClientTick);

        // Auto-configure latency or scenario from system properties if launched in test mode
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String latencyProp = System.getProperty("blockracing.test.latency");
            String jitterProp = System.getProperty("blockracing.test.jitter");
            if (latencyProp != null) {
                try {
                    long latency = Long.parseLong(latencyProp);
                    long jitter = jitterProp != null ? Long.parseLong(jitterProp) : 0;
                    top.lqsnow.blockracing.client.test.NetworkLatencyHandler.setConfig(latency, jitter);
                } catch (NumberFormatException ignored) {}
            }
            String autoTeam = System.getProperty("blockracing.test.team");
            if (autoTeam != null && !autoTeam.isEmpty()) {
                TestScenarioRunner.startAutoPlay(autoTeam);
            }
        });
    }

    public static void clear() { snapshot = null; receivedAt = 0; error = ""; }
    public static boolean supported() {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(BoardPayload.Request.TYPE);
    }
    public static void subscribe(boolean open) {
        if (supported()) ClientPlayNetworking.send(new BoardPayload.Request(open));
    }

    public static void toggleFavorite(String target) {
        if (target != null && !target.isEmpty() && Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(BoardPayload.FavoriteAction.TYPE)) {
            ClientPlayNetworking.send(new BoardPayload.FavoriteAction(target));
        }
    }

    /** Only consumes Tab in gameplay on compatible servers; leaves chat and other screens alone. */
    public static boolean onKey(int action, KeyEvent event) {
        Minecraft client = Minecraft.getInstance();
        // Always let vanilla release its mapping, even if Shift was released before Tab.
        if (!BoardKeyPolicy.intercept(action, event.key(), event.modifiers(), supported(),
                client.gui.screen() != null && !(client.gui.screen() instanceof BoardScreen))) return false;
        if (action == GLFW.GLFW_PRESS) {
            if (!(client.gui.screen() instanceof BoardScreen)) {
                error = "";
                client.gui.setScreen(new BoardScreen());
            }
        } else if (action == GLFW.GLFW_RELEASE) {
            if (client.gui.screen() instanceof BoardScreen) {
                client.gui.setScreen(null);
            }
        }
        return true; // Consume repeats and press/release so vanilla player list doesn't flash
    }
}
