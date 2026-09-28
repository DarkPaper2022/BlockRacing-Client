package top.lqsnow.blockracing.client.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import top.lqsnow.blockracing.client.BoardClient;
import top.lqsnow.blockracing.client.BoardState;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Two-client E2E scenario. Team selection, ready/start, board transport,
 * favorites, inventory completion, mutual exclusion and RTP all travel through
 * the normal client/server paths. The external runner only supplies inventory
 * fixtures after both clients reach the same barrier.
 */
public final class TestScenarioRunner {
    private static final Logger LOGGER = Logger.getLogger("BlockRacingClientTest");
    private static final long GAME_START_TIMEOUT_MS = 240_000;
    private static final long STEP_TIMEOUT_MS = 45_000;

    public enum Step {
        IDLE, OPEN_LOBBY, SELECT_TEAM, WAIT_TEAM, READY, WAIT_GAME_START,
        VALIDATE_BOARD, FAVORITE, WAIT_MUTEX_FIXTURE, VERIFY_MUTEX,
        TRIGGER_RTP, MEASURE_RTP, WAIT_NEXT_RTP, COMPLETED, FAILED
    }

    private static volatile Step currentStep = Step.IDLE;
    private static String targetTeam = "red";
    private static boolean coordinator;
    private static int maxIterations = 1;
    private static int intervalSeconds = 2;
    private static int currentIteration;
    private static int samplesBeforeRequest;
    private static long stateEnteredAtMillis;
    private static long lastActionAtMillis;
    private static long lastSubscriptionAtMillis;
    private static String mutexTarget;
    private static int mutexTargetScore;
    private static long mutexDispatchAtMillis;
    private static final AtomicInteger ticksInState = new AtomicInteger();

    private TestScenarioRunner() {}

    public static void startAutoPlay(String team) {
        targetTeam = team == null || team.isBlank() ? "red" : team.toLowerCase(Locale.ROOT);
        coordinator = Boolean.parseBoolean(System.getProperty("blockracing.test.coordinator", "false"));
        maxIterations = integerProperty("blockracing.test.iterations", coordinator ? 2 : 1, 1);
        intervalSeconds = integerProperty("blockracing.test.interval", 2, 1);
        currentIteration = 0;
        mutexTarget = null;
        ClientProfiler.startSession();
        transitionTo(Step.OPEN_LOBBY);
        marker("START team=" + targetTeam + " coordinator=" + coordinator + " rtpIterations=" + maxIterations);
    }

    public static void stop() {
        currentStep = Step.IDLE;
        ClientProfiler.stopSession();
    }

    public static Step getCurrentStep() { return currentStep; }
    public static int getCurrentIteration() { return currentIteration; }

    public static void onClientTick(Minecraft client) {
        if (currentStep == Step.IDLE || currentStep == Step.FAILED || client.player == null
                || client.getConnection() == null) return;

        int ticks = ticksInState.incrementAndGet();
        try {
            if (System.currentTimeMillis() - lastSubscriptionAtMillis >= 5_000L) {
                BoardClient.subscribe(true);
                lastSubscriptionAtMillis = System.currentTimeMillis();
            }
            updateChunkReadiness(client);
            switch (currentStep) {
                case OPEN_LOBBY -> {
                    BoardClient.subscribe(true);
                    lastSubscriptionAtMillis = System.currentTimeMillis();
                    if (ticks >= 20) {
                        client.getConnection().sendCommand("menu");
                        lastActionAtMillis = System.currentTimeMillis();
                        transitionTo(Step.SELECT_TEAM);
                    }
                }
                case SELECT_TEAM -> {
                    if (clickSlot(client, 11 + teamOrdinal())) {
                        marker("LOBBY_TEAM_CLICK slot=" + (11 + teamOrdinal()));
                        transitionTo(Step.WAIT_TEAM);
                    } else if (elapsedSinceAction(2_000)) {
                        client.getConnection().sendCommand("menu");
                        lastActionAtMillis = System.currentTimeMillis();
                    }
                    failIfTimedOut("team selection", STEP_TIMEOUT_MS, client);
                }
                case WAIT_TEAM -> {
                    BoardState board = BoardClient.snapshot;
                    if (board != null && targetTeam.equals(board.team())) {
                        marker("TEAM_CONFIRMED team=" + board.team());
                        transitionTo(Step.READY);
                    } else if (elapsedSinceAction(2_000)) {
                        client.getConnection().sendCommand("menu");
                        lastActionAtMillis = System.currentTimeMillis();
                    }
                    failIfTimedOut("team confirmation", STEP_TIMEOUT_MS, client);
                }
                case READY -> {
                    if (!(client.gui.screen() instanceof AbstractContainerScreen<?>)) {
                        if (elapsedSinceAction(1_000)) {
                            client.getConnection().sendCommand("menu");
                            lastActionAtMillis = System.currentTimeMillis();
                        }
                    } else if (clickSlot(client, 38)) {
                        marker("READY_CLICKED");
                        transitionTo(Step.WAIT_GAME_START);
                    }
                    failIfTimedOut("ready click", STEP_TIMEOUT_MS, client);
                }
                case WAIT_GAME_START -> {
                    BoardState board = BoardClient.snapshot;
                    if (isInGame(board)) {
                        marker("GAME_STARTED tasks=" + board.tasks().size());
                        transitionTo(Step.VALIDATE_BOARD);
                    } else if (coordinator && ticks >= 80 && ticks % 60 == 0) {
                        if (!clickSlot(client, 39)) client.getConnection().sendCommand("menu");
                        marker("START_ATTEMPT");
                    }
                    failIfTimedOut("natural game start", GAME_START_TIMEOUT_MS, client);
                }
                case VALIDATE_BOARD -> {
                    BoardState board = requireBoard();
                    check(targetTeam.equals(board.team()), "board team mismatch: " + board.team());
                    check(board.error().isEmpty(), "board error: " + board.error());
                    check(!board.tasks().isEmpty(), "empty in-game task board");
                    check(board.winScore() > 0 && board.totalScore() >= board.winScore(), "invalid score thresholds");
                    List<String> configured = configuredTargets();
                    if (!configured.isEmpty()) {
                        check(board.tasks().size() == configured.size(), "fixture task count mismatch");
                        check(board.tasks().stream().map(BoardState.Task::id).toList().equals(configured),
                                "fixture order/content mismatch");
                    }
                    BoardState.Task target = board.tasks().stream()
                            .filter(task -> "active".equals(task.status()) && !task.bonus())
                            .findFirst().orElseThrow(() -> new IllegalStateException("no active regular target"));
                    mutexTarget = target.id();
                    mutexTargetScore = target.score();
                    mutexDispatchAtMillis = ((System.currentTimeMillis() / 15_000L) + 2L) * 15_000L;
                    marker("BOARD_VALIDATED total=" + board.totalScore() + " win=" + board.winScore()
                            + " mutexTarget=" + mutexTarget + " score=" + mutexTargetScore);
                    transitionTo(Step.FAVORITE);
                }
                case FAVORITE -> {
                    BoardState.Task task = task(mutexTarget);
                    if (coordinator && !task.favorited() && ticks == 1) BoardClient.toggleFavorite(mutexTarget);
                    if (coordinator ? task.favorited() : ticks >= 60) {
                        check(coordinator == task.favorited(), "favorite leaked across teams or was not applied");
                        marker("FAVORITE_ISOLATION_OK favorited=" + task.favorited());
                        marker("READY_FOR_MUTEX target=" + mutexTarget + " dispatchAt=" + mutexDispatchAtMillis);
                        transitionTo(Step.WAIT_MUTEX_FIXTURE);
                    }
                    failIfTimedOut("favorite propagation", STEP_TIMEOUT_MS, client);
                }
                case WAIT_MUTEX_FIXTURE -> {
                    BoardState.Task task = task(mutexTarget);
                    if ("resolved".equals(task.status())) {
                        transitionTo(Step.VERIFY_MUTEX);
                    } else if (System.currentTimeMillis() >= mutexDispatchAtMillis) {
                        TaskCommandPlanner.Plan plan = TaskCommandPlanner.plan(task, client.getUser().getName());
                        check(plan.supported(), "mutex target has no command solver: " + plan.reason());
                        for (String command : plan.commands()) client.getConnection().sendCommand(command);
                        marker("TASK_SOLVER_DISPATCH kind=" + plan.kind() + " commands=" + plan.commands().size());
                        mutexDispatchAtMillis = Long.MAX_VALUE;
                    }
                    failIfTimedOut("client task solver", STEP_TIMEOUT_MS, client);
                }
                case VERIFY_MUTEX -> {
                    if (ticks < 40) break;
                    BoardState board = requireBoard();
                    BoardState.Task task = task(mutexTarget);
                    check("resolved".equals(task.status()), "mutual target did not resolve");
                    check(!task.favorited(), "resolved target remained favorited");
                    check(board.score() == 0 || board.score() == mutexTargetScore,
                            "unexpected mutex score " + board.score());
                    marker("MUTEX_RESULT score=" + board.score() + " targetScore=" + mutexTargetScore);
                    transitionTo(Step.TRIGGER_RTP);
                }
                case TRIGGER_RTP -> {
                    currentIteration++;
                    samplesBeforeRequest = ClientProfiler.getSamples().size();
                    ClientProfiler.recordTeleportSent(System.currentTimeMillis());
                    client.getConnection().sendCommand("menu randomTP");
                    marker("RTP_SENT iteration=" + currentIteration);
                    transitionTo(Step.MEASURE_RTP);
                }
                case MEASURE_RTP -> {
                    if (ClientProfiler.getSamples().size() > samplesBeforeRequest) {
                        logLatestSample();
                        if (currentIteration >= maxIterations) transitionTo(Step.COMPLETED);
                        else transitionTo(Step.WAIT_NEXT_RTP);
                    }
                    failIfTimedOut("RTP completion", STEP_TIMEOUT_MS, client);
                }
                case WAIT_NEXT_RTP -> {
                    if (elapsed() >= intervalSeconds * 1_000L) transitionTo(Step.TRIGGER_RTP);
                }
                case COMPLETED -> complete(client);
                default -> { }
            }
        } catch (Throwable failure) {
            fail(client, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    private static boolean clickSlot(Minecraft client, int slot) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || client.gameMode == null || slot < 0 || slot >= screen.getMenu().slots.size()) return false;
        client.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0,
                ContainerInput.PICKUP, client.player);
        lastActionAtMillis = System.currentTimeMillis();
        return true;
    }

    private static void updateChunkReadiness(Minecraft client) {
        if (!ClientProfiler.isWaitingForChunk() || client.level == null || client.player == null) return;
        double dx = client.player.getX() - ClientProfiler.targetX();
        double dz = client.player.getZ() - ClientProfiler.targetZ();
        if (dx * dx + dz * dz <= 16.0 && client.level.hasChunkAt(client.player.blockPosition())) {
            ClientProfiler.recordChunkLoaded();
        }
    }

    private static void complete(Minecraft client) {
        List<ClientProfiler.TeleportSample> samples = ClientProfiler.getSamples();
        check(samples.size() == maxIterations, "expected " + maxIterations + " RTP samples, got " + samples.size());
        marker("PASS team=" + targetTeam + " rtpSamples=" + samples.size());
        stop();
        client.stop();
    }

    private static void logLatestSample() {
        ClientProfiler.TeleportSample sample = ClientProfiler.getSamples().getLast();
        marker("RTP_RESULT iteration=" + currentIteration + " target=" + sample.targetX() + "," + sample.targetZ()
                + " networkMs=" + sample.networkRoundTripMs() + " chunkMs=" + sample.chunkLoadingDurationMs()
                + " totalMs=" + sample.totalTimeMs());
    }

    private static BoardState requireBoard() {
        BoardState board = BoardClient.snapshot;
        if (!isInGame(board)) throw new IllegalStateException("missing live in-game board");
        return board;
    }

    private static BoardState.Task task(String id) {
        return requireBoard().tasks().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("task disappeared from board: " + id));
    }

    private static boolean isInGame(BoardState board) {
        return board != null && board.error().isEmpty() && "INGAME".equals(board.state());
    }

    private static List<String> configuredTargets() {
        String value = System.getProperty("blockracing.test.targets", "");
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static int teamOrdinal() {
        return switch (targetTeam) {
            case "red" -> 0;
            case "blue" -> 1;
            case "green" -> 2;
            case "yellow" -> 3;
            default -> throw new IllegalStateException("unsupported test team: " + targetTeam);
        };
    }

    private static int integerProperty(String key, int fallback, int minimum) {
        try { return Math.max(minimum, Integer.parseInt(System.getProperty(key, Integer.toString(fallback)))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static void failIfTimedOut(String operation, long timeoutMs, Minecraft client) {
        if (elapsed() > timeoutMs) fail(client, "timeout waiting for " + operation + " in " + currentStep);
    }

    private static boolean elapsedSinceAction(long millis) {
        return System.currentTimeMillis() - lastActionAtMillis >= millis;
    }

    private static long elapsed() { return System.currentTimeMillis() - stateEnteredAtMillis; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(Minecraft client, String message) {
        if (currentStep == Step.FAILED) return;
        Step failedAt = currentStep;
        currentStep = Step.FAILED;
        marker("FAIL step=" + failedAt + " reason=" + message.replace('\n', ' '));
        ClientProfiler.stopSession();
        client.stop();
    }

    private static void transitionTo(Step next) {
        currentStep = next;
        stateEnteredAtMillis = System.currentTimeMillis();
        ticksInState.set(0);
    }

    private static void marker(String message) {
        String line = "BLOCKRACING_E2E " + message;
        LOGGER.info(line);
        System.out.println(line);
    }
}
