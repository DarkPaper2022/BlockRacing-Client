package top.lqsnow.blockracing.client.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Test-only direct connector that also bypasses first-run onboarding screens. */
public final class TestAutoConnector {
    private static int ticks;
    private static boolean attempted;
    private static int retryTicks;

    private TestAutoConnector() {}

    public static void onClientTick(Minecraft client) {
        // Acceptance runs restart Paper mid-round; keep retrying until the server is back.
        // Only retry from the disconnect screen: login/configuration/loading screens also have
        // no level or play connection yet, and retrying there would abort a live join.
        if (attempted && AcceptanceScenario.wantsReconnect() && client.getConnection() == null && client.level == null
                && client.gui.screen() instanceof DisconnectedScreen && ++retryTicks >= 100) {
            retryTicks = 0;
            attempted = false;
            ticks = 200;
        }
        if (attempted || !Boolean.parseBoolean(System.getProperty("blockracing.test.autoconnect", "false"))) return;
        if (client.level != null || client.getConnection() != null || client.gui.screen() == null
                || client.gui.screen() instanceof ConnectScreen || ++ticks < 200) return;
        attempted = true;
        String host = System.getProperty("blockracing.test.host", "127.0.0.1");
        int port;
        try { port = Integer.parseInt(System.getProperty("blockracing.test.port", "25565")); }
        catch (NumberFormatException ignored) { port = 25565; }
        String address = host + ":" + port;
        ServerData data = new ServerData("BlockRacing E2E", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString(address), data, false, null);
    }
}
