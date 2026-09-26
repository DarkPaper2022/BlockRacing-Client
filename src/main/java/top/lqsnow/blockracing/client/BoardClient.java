package top.lqsnow.blockracing.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

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
            if (client.gui.screen() instanceof BoardScreen) client.gui.setScreen(null);
            else { error = ""; client.gui.setScreen(new BoardScreen()); }
        }
        return true; // Consume repeats so a held key cannot toggle repeatedly.
    }
}
