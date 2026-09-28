package top.lqsnow.blockracing.client.test;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Automates common match scenarios (Join team -> Ready -> Trigger RTP -> Measure).
 */
public final class TestScenarioRunner {
    private static final Logger LOGGER = Logger.getLogger("BlockRacingClientTest");

    public enum Step {
        IDLE,
        JOIN_TEAM,
        READY,
        WAIT_GAME_START,
        GAME_INGAME,
        TRIGGER_RTP,
        MEASURE_RTP,
        COMPLETED
    }

    private static volatile Step currentStep = Step.IDLE;
    private static String targetTeam = "red";
    private static long stateEnteredAtMillis = 0;
    private static final AtomicInteger ticksInState = new AtomicInteger(0);

    private TestScenarioRunner() {}

    public static void startAutoPlay(String team) {
        targetTeam = (team == null || team.isEmpty()) ? "red" : team.toLowerCase();
        currentStep = Step.JOIN_TEAM;
        stateEnteredAtMillis = System.currentTimeMillis();
        ticksInState.set(0);
        ClientProfiler.startSession();
        LOGGER.info("[TestScenario] Started auto play targeting team: " + targetTeam);
    }

    public static void stop() {
        currentStep = Step.IDLE;
        ClientProfiler.stopSession();
        LOGGER.info("[TestScenario] Stopped auto play.");
    }

    public static Step getCurrentStep() {
        return currentStep;
    }

    /**
     * Called on each client tick (ClientTickEvents.END_CLIENT_TICK).
     */
    public static void onClientTick(Minecraft client) {
        if (currentStep == Step.IDLE || client.player == null || client.getConnection() == null) {
            return;
        }

        int ticks = ticksInState.incrementAndGet();

        switch (currentStep) {
            case JOIN_TEAM -> {
                // Wait 20 ticks (1s) after joining world to let connection settle
                if (ticks >= 20) {
                    String playerName = client.getUser().getName();
                    client.getConnection().sendCommand("debug setteam " + targetTeam + " add " + playerName);
                    LOGGER.info("[TestScenario] Dispatched team join command for: " + targetTeam);
                    transitionTo(Step.READY);
                }
            }
            case READY -> {
                if (ticks >= 20) {
                    client.getConnection().sendCommand("menu ready");
                    LOGGER.info("[TestScenario] Dispatched ready command");
                    transitionTo(Step.WAIT_GAME_START);
                }
            }
            case WAIT_GAME_START -> {
                // Polling or listening to title/actionbar/chat to detect game start
                // Timeout after 30 seconds
                if (System.currentTimeMillis() - stateEnteredAtMillis > 30000) {
                    LOGGER.warning("[TestScenario] Timeout waiting for game to start");
                    transitionTo(Step.IDLE);
                }
            }
            case GAME_INGAME -> {
                if (ticks >= 40) {
                    LOGGER.info("[TestScenario] In game! Triggering RTP...");
                    transitionTo(Step.TRIGGER_RTP);
                }
            }
            case TRIGGER_RTP -> {
                long reqId = System.currentTimeMillis();
                ClientProfiler.recordTeleportSent(reqId);
                client.getConnection().sendCommand("menu randomTP");
                LOGGER.info("[TestScenario] Random teleport command sent! Awaiting teleport packet and chunk load...");
                transitionTo(Step.MEASURE_RTP);
            }
            case MEASURE_RTP -> {
                // Handled via ClientProfiler listeners
                if (System.currentTimeMillis() - stateEnteredAtMillis > 15000) {
                    LOGGER.info("[TestScenario] Finished RTP measurement window.");
                    transitionTo(Step.COMPLETED);
                }
            }
            case COMPLETED -> {
                LOGGER.info("[TestScenario] Scenario completed. Teleport samples collected: " + ClientProfiler.getSamples().size());
                for (ClientProfiler.TeleportSample sample : ClientProfiler.getSamples()) {
                    LOGGER.info(String.format("[Profile Result] Target: (%d, %d) | RTT: %d ms | ChunkLoad: %d ms | Total: %d ms",
                            sample.targetX(), sample.targetZ(),
                            sample.networkRoundTripMs(), sample.chunkLoadingDurationMs(), sample.totalTimeMs()));
                }
                transitionTo(Step.IDLE);
            }
        }
    }

    /**
     * External trigger called by chat / packet hooks when game start notification is detected.
     */
    public static void onGameStarted() {
        if (currentStep == Step.WAIT_GAME_START) {
            LOGGER.info("[TestScenario] Game start detected!");
            transitionTo(Step.GAME_INGAME);
        }
    }

    private static void transitionTo(Step next) {
        currentStep = next;
        stateEnteredAtMillis = System.currentTimeMillis();
        ticksInState.set(0);
    }
}
