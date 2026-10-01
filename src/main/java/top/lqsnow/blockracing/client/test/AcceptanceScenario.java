package top.lqsnow.blockracing.client.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import top.lqsnow.blockracing.client.BoardClient;
import top.lqsnow.blockracing.client.BoardScreen;
import top.lqsnow.blockracing.client.BoardState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Three-client acceptance scenario for the handoff P0 list: real Tab input, shared
 * team progress, interactive action targets, restart recovery and single settlement.
 *
 * <p>Roles: {@code red_a} (coordinator), {@code red_b} and {@code blue}. Clients only
 * use vanilla commands (as isolated-test OPs) and normal client interactions; every
 * completion is still decided by the plugin's production listeners and polling.
 * Cross-client ordering uses files in {@code blockracing.test.syncDir}; the external
 * runner restarts Paper once all three clients report {@code AWAIT_RESTART}.
 */
public final class AcceptanceScenario {
    private static final Logger LOGGER = Logger.getLogger("BlockRacingAcceptance");
    private static final List<String> ROLES = List.of("red_a", "red_b", "blue");
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final long START_TIMEOUT_MS = 300_000;
    private static final long RESTART_TIMEOUT_MS = 300_000;
    private static final int ARENA_Y = 200;
    private static final String GOAL_PREFIX = "DRAFTOUT:";

    static final String ADVANCEMENTS = "GET_30_ADVANCEMENTS";
    static final String DISCS = "OBTAIN_5_UNIQUE_DISCS";
    static final String KILLS = "KILL_7_UNIQUE_HOSTILE_MOBS";
    static final String DAMAGE = "TAKE_200_DAMAGE";
    static final String LOOM = "USE_LOOM";
    static final String MILK = "REMOVE_STATUS_EFFECT_USING_MILK";
    static final String CAULDRON = "USE_CAULDRON";
    static final String COMPOSTER = "USE_COMPOSTER";
    static final String JUKEBOX = "USE_JUKEBOX";
    static final String DIAMOND = "MINE_DIAMOND_ORE";
    static final String EMERALD = "MINE_EMERALD_ORE";
    static final String BONUS = "ZOMBIE_HEAD";
    static final List<String> FINAL_RACE = List.of("TRIDENT", "PHANTOM_MEMBRANE", "SNIFFER_EGG");

    /**
     * 30 displayable, non-recipe vanilla advancements without loot rewards. Excludes ones the
     * round start can earn by itself (the starter kit grants story/upgrade_tools).
     */
    static final List<String> ADVANCEMENT_IDS = List.of(
            "husbandry/plant_seed", "husbandry/breed_an_animal", "husbandry/tame_an_animal", "story/smelt_iron", "story/obtain_armor",
            "story/lava_bucket", "story/iron_tools", "story/deflect_arrow", "story/form_obsidian", "story/mine_diamond",
            "story/enter_the_nether", "story/shiny_gear", "story/enchant_item", "story/follow_ender_eye", "story/enter_the_end",
            "nether/root", "nether/find_fortress", "nether/obtain_crying_obsidian", "nether/distract_piglin", "nether/find_bastion",
            "nether/obtain_ancient_debris", "nether/loot_bastion", "adventure/use_lodestone", "nether/charge_respawn_anchor", "nether/ride_strider",
            "end/root", "end/enter_end_gateway", "end/find_end_city", "end/elytra", "end/dragon_breath");

    interface Body { boolean tick(Ctx ctx); }
    record Step(String name, long timeoutMs, Body body) {}

    /** Per-step mutable context. */
    static final class Ctx {
        final Minecraft client;
        int ticks;
        int stage;
        long enteredAt = System.currentTimeMillis();
        long mark;
        Object memo;
        Ctx(Minecraft client) { this.client = client; }
        void cmd(String command) { client.getConnection().sendCommand(command); }
    }

    private static String role = "";
    private static Path syncDir;
    private static Path screenDir;
    private static List<Step> script = List.of();
    private static int index = -1;
    private static Ctx ctx;
    private static boolean failed;
    private static boolean done;
    private static boolean awaitingReconnect;
    private static long lastSubscribeAt;
    private static BlockPos arena = BlockPos.ZERO;
    private static double preReadyX;
    private static double preReadyZ;

    private AcceptanceScenario() {}

    public static boolean enabled() {
        return "acceptance".equals(System.getProperty("blockracing.test.scenario"));
    }

    public static boolean wantsReconnect() { return enabled() && awaitingReconnect && !done && !failed; }

    /** Called on every JOIN; the first join builds the script, later joins resume it. */
    public static void onJoin() {
        lastJoinAt = System.currentTimeMillis();
        if (index >= 0) {
            marker("REJOINED step=" + currentName());
            return;
        }
        role = System.getProperty("blockracing.test.role", "").toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new IllegalStateException("unknown acceptance role: " + role);
        syncDir = Path.of(System.getProperty("blockracing.test.syncDir", "sync"));
        screenDir = syncDir.resolveSibling("screens");
        try {
            Files.createDirectories(syncDir);
            Files.createDirectories(screenDir);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot create sync dir " + syncDir, ex);
        }
        script = buildScript();
        index = 0;
        ctx = null;
        marker("START role=" + role + " steps=" + script.size());
    }

    public static void onClientTick(Minecraft client) {
        if (index < 0 || failed || done) return;
        if (client.player == null || client.getConnection() == null) {
            if (!awaitingReconnect && index > 0) fail(client, "unexpected disconnect at " + currentName());
            return;
        }
        if (System.currentTimeMillis() - lastSubscribeAt >= 5_000L) {
            BoardClient.subscribe(true);
            lastSubscribeAt = System.currentTimeMillis();
        }
        if (client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen && !client.player.isAlive()) {
            client.player.respawn();
            client.gui.setScreen(null);
        }
        Step step = script.get(index);
        if (ctx == null) ctx = new Ctx(client);
        ctx.ticks++;
        try {
            if (step.body().tick(ctx)) {
                marker("STEP_OK " + step.name() + " ticks=" + ctx.ticks);
                index++;
                ctx = null;
                if (index >= script.size()) {
                    done = true;
                    marker("PASS role=" + role);
                    client.stop();
                }
            } else if (System.currentTimeMillis() - ctx.enteredAt > step.timeoutMs()) {
                fail(client, "timeout in " + step.name() + " (stage " + ctx.stage + ")");
            }
        } catch (Throwable failure) {
            fail(client, step.name() + ": " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    // ---------------------------------------------------------------- script

    private static List<Step> buildScript() {
        List<Step> s = new ArrayList<>();
        boolean redA = role.equals("red_a"), redB = role.equals("red_b"), blue = role.equals("blue");
        String team = blue ? "blue" : "red";

        // Lobby: real menu clicks, then RED_A presses the real start button.
        s.add(step("pregame-reset", 20_000, c -> {
            if (c.ticks == 20) {
                c.cmd("advancement revoke @s everything");
                c.cmd("clear @s");
                c.cmd("effect clear @s");
            }
            return c.ticks >= 40;
        }));
        s.add(step("select-team", DEFAULT_TIMEOUT_MS, c -> {
            BoardState board = BoardClient.snapshot;
            if (board != null && team.equals(board.team())) return true;
            if (c.ticks % 40 == 1) {
                if (openContainer(c.client)) clickSlot(c.client, blue ? 12 : 11);
                else c.cmd("menu");
            }
            return false;
        }));
        s.add(step("ready", DEFAULT_TIMEOUT_MS, c -> {
            if (c.stage == 0) {
                c.client.gui.setScreen(null);
                c.stage = 1;
                return false;
            }
            if (c.stage == 1 && c.ticks % 20 == 0) {
                if (openContainer(c.client)) {
                    preReadyX = c.client.player.getX();
                    preReadyZ = c.client.player.getZ();
                    clickSlot(c.client, 38);
                    c.stage = 2;
                } else c.cmd("menu");
            }
            return c.stage == 2 && c.ticks % 20 == 10;
        }));
        s.add(barrier("all-ready"));
        s.add(step("game-start", START_TIMEOUT_MS, c -> {
            if (inGame()) return true;
            if (redA && c.ticks % 60 == 0) {
                if (openContainer(c.client)) clickSlot(c.client, 39);
                else c.cmd("menu");
            }
            return false;
        }));
        s.add(step("initial-rtp", START_TIMEOUT_MS, c -> {
            double dx = c.client.player.getX() - preReadyX, dz = c.client.player.getZ() - preReadyZ;
            return dx * dx + dz * dz > 32 * 32 && c.client.level.hasChunkAt(c.client.player.blockPosition());
        }));
        s.add(step("build-arena", DEFAULT_TIMEOUT_MS, c -> {
            // Each player owns the area around its own RTP landing point. Retry until the
            // server has the chunk loaded and the platform is visible client-side.
            if (c.stage == 0) {
                c.client.gui.setScreen(null);
                BlockPos p = c.client.player.blockPosition();
                arena = new BlockPos(p.getX(), ARENA_Y, p.getZ());
                c.stage = 1;
            }
            if (c.stage == 1 && (c.mark == 0 || c.ticks - c.mark >= 60)) {
                c.cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone",
                        arena.getX() - 5, ARENA_Y - 1, arena.getZ() - 5, arena.getX() + 5, ARENA_Y - 1, arena.getZ() + 5));
                c.cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                        arena.getX() - 5, ARENA_Y, arena.getZ() - 5, arena.getX() + 5, ARENA_Y + 5, arena.getZ() + 5));
                c.cmd(teleportHome());
                c.mark = c.ticks;
            }
            return c.ticks - c.mark > 20 && atArena(c.client)
                    && blockId(c.client, arena.below()).equals("smooth_stone");
        }));
        s.add(step("validate-board", DEFAULT_TIMEOUT_MS, c -> {
            BoardState b = board();
            check(team.equals(b.team()), "team mismatch " + b.team());
            int regular = b.tasks().stream().filter(t -> !t.bonus()).mapToInt(BoardState.Task::score).sum();
            check(b.totalScore() == regular, "totalScore " + b.totalScore() + " != regular sum " + regular + " (bonus must be excluded)");
            check(b.winScore() == (regular + 1) / 2, "winScore " + b.winScore() + " != ceil(" + regular + "/2)");
            BoardState.Task bonus = task(BONUS);
            check(bonus.bonus() && bonus.score() >= 11, "ZOMBIE_HEAD must be an 11+ bonus");
            check(bonus.score() < b.winScore(), "fixture invalid: bonus alone would win");
            check(b.tasks().stream().filter(BoardState.Task::bonus).count() == 1, "expected exactly one bonus");
            check(b.tasks().stream().allMatch(t -> t.status().equals("active")), "all fixture tasks should be active");
            check(b.score() == 0, "fresh round score must be 0");
            advBaseline = progress(ADVANCEMENTS);
            marker("BOARD total=" + b.totalScore() + " win=" + b.winScore() + " bonus=" + bonus.score()
                    + " advBaseline=" + advBaseline);
            return true;
        }));
        s.add(barrier("board"));

        // P0-1: real X11 key events through GLFW -> KeyboardHandler -> mixin.
        if (redA) s.addAll(tabPanelSteps());
        s.add(barrier("tab-done"));

        // P0-2: shared progress -- advancements (union, duplicates once, team isolation).
        if (redA) s.add(step("adv-a-grant", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 1) {
                // Baseline = advancements already earned this round (e.g. starter kit); the
                // ledger keeps them, so top up to exactly 20 with ids[0, 20 - baseline).
                int baseline = progress(ADVANCEMENTS);
                check(baseline >= 0 && baseline <= 10, "unexpected advancement baseline " + baseline);
                marker("ADV_BASELINE " + baseline);
                for (String id : ADVANCEMENT_IDS.subList(0, 20 - baseline)) c.cmd("advancement grant @s only minecraft:" + id);
            }
            return progress(ADVANCEMENTS) == 20;
        }));
        s.add(barrier("adv-a"));
        if (redB) s.add(step("adv-b-overlap", DEFAULT_TIMEOUT_MS, c -> {
            if (c.stage == 0) {
                if (progress(ADVANCEMENTS) != 20) return false; // teammate's 20 must be visible as shared progress
                for (String id : ADVANCEMENT_IDS.subList(0, 10)) c.cmd("advancement grant @s only minecraft:" + id);
                c.stage = 1;
                c.mark = c.ticks;
            }
            if (c.ticks - c.mark < 100) return false;
            check(progress(ADVANCEMENTS) == 20 && active(ADVANCEMENTS), "same advancements by two players must count once");
            return true;
        }));
        if (blue) s.add(step("adv-blue-isolated", DEFAULT_TIMEOUT_MS, c ->
                c.ticks > 100 && expect(progress(ADVANCEMENTS) == advBaseline,
                "blue must only see its own " + advBaseline + " advancements, got " + progress(ADVANCEMENTS))));
        s.add(barrier("adv-overlap"));
        if (redB) s.add(completion("adv-b-complete", ADVANCEMENTS, c -> {
            for (String id : ADVANCEMENT_IDS.subList(20, 30)) c.cmd("advancement grant @s only minecraft:" + id);
        }));
        s.add(barrier("adv-done"));
        s.add(step("adv-mutex-view", DEFAULT_TIMEOUT_MS, c -> task(ADVANCEMENTS).status().equals("resolved")
                && expect(board().score() == (blue ? 0 : task(ADVANCEMENTS).score()), "unexpected score after advancements")));

        // Discs: union of online inventories + team chest, duplicates once.
        if (redA) s.add(step("discs-a", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 1) for (String disc : List.of("cat", "13", "blocks")) c.cmd("give @s minecraft:music_disc_" + disc);
            return progress(DISCS) == 3;
        }));
        s.add(barrier("discs-a"));
        if (redB) s.add(step("discs-b-dup", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 1) for (String disc : List.of("cat", "chirp")) c.cmd("give @s minecraft:music_disc_" + disc);
            return c.ticks > 60 && expect(progress(DISCS) == 4, "duplicate CAT must count once; got " + progress(DISCS));
        }));
        if (redB) s.add(step("discs-b-chest", DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { c.cmd("menu chest 1"); c.stage = 1; }
                case 1 -> {
                    if (!(c.client.gui.screen() instanceof AbstractContainerScreen<?>) || c.ticks < 20) return false;
                    int slot = playerSlotWith(c.client, "music_disc_chirp");
                    check(slot >= 0, "chirp disc not in inventory view");
                    container(c.client, slot, ContainerInput.QUICK_MOVE, 0);
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (c.ticks - c.mark < 20) return false;
                    check(playerSlotWith(c.client, "music_disc_chirp") < 0, "chirp should have moved into the team chest");
                    c.client.player.closeContainer();
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                default -> {
                    if (c.ticks - c.mark < 60) return false;
                    return expect(progress(DISCS) == 4 && active(DISCS), "team chest disc must still count; got " + progress(DISCS));
                }
            }
            return false;
        }));
        if (blue) s.add(step("discs-blue-isolated", DEFAULT_TIMEOUT_MS, c ->
                c.ticks > 60 && expect(progress(DISCS) == 0, "blue must not see red discs")));
        s.add(barrier("discs-partial"));

        // Damage: summed decimals across players and across a server restart.
        if (redA) s.add(step("damage-a", DEFAULT_TIMEOUT_MS, c -> {
            // Round start grants 60 s of Resistance V; final damage under it is 0 and not recorded.
            if (c.stage == 0) { c.cmd("effect clear @s"); c.stage = 1; c.mark = c.ticks; return false; }
            if (c.stage == 1 && c.ticks - c.mark == 10) c.cmd("damage @s 99.6 minecraft:generic");
            if (c.stage == 1 && c.client.player.isAlive() && c.ticks - c.mark > 50 && progress(DAMAGE) >= 99) {
                check(progress(DAMAGE) < 200 && active(DAMAGE), "one player's 99.6 damage must not complete 200");
                c.cmd(teleportHome());
                c.stage = 2;
                c.mark = c.ticks;
            }
            return c.stage == 2 && c.ticks - c.mark > 40 && atArena(c.client);
        }));
        s.add(barrier("damage-a"));
        if (blue) s.add(step("damage-blue-isolated", DEFAULT_TIMEOUT_MS, c ->
                expect(progress(DAMAGE) < 99, "blue must not see red damage")));

        // Snapshot before restart for comparison after recovery.
        s.add(step("pre-restart-snapshot", DEFAULT_TIMEOUT_MS, c -> {
            c.client.gui.setScreen(null);
            BoardState b = board();
            preRestart = new Snapshot(b.score(), progress(DISCS), progress(DAMAGE), statusLine(b));
            marker("SNAPSHOT " + preRestart);
            return true;
        }));
        s.add(barrier("pre-restart"));
        s.add(step("await-restart", RESTART_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { awaitingReconnect = true; marker("AWAIT_RESTART"); c.stage = 1; }
                case 1 -> { /* Disconnected ticks are not delivered; reaching here after rejoin. */
                    if (c.ticks > 40 && c.client.getConnection() != null && reconnectedSince(c)) c.stage = 2;
                }
                default -> {
                    if (!inGame()) return false;
                    awaitingReconnect = false;
                    return true;
                }
            }
            return false;
        }));
        s.add(step("post-restart-verify", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks < 60) return false;
            BoardState b = board();
            Snapshot now = new Snapshot(b.score(), progress(DISCS), progress(DAMAGE), statusLine(b));
            marker("RECOVERED " + now);
            check(now.equals(preRestart), "state changed across restart: before=" + preRestart + " after=" + now);
            c.client.gui.setScreen(null);
            c.cmd(teleportHome());
            return true;
        }));
        s.add(barrier("recovered"));

        if (redB) s.add(step("damage-b-unprotect", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 1) c.cmd("effect clear @s");
            return c.ticks > 10;
        }));
        if (redB) s.add(completion("damage-b-complete", DAMAGE, c -> c.cmd("damage @s 100.4 minecraft:generic")));
        if (redB) s.add(step("damage-b-home", DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 40) c.cmd(teleportHome());
            return c.ticks > 80 && c.client.player.isAlive() && atArena(c.client);
        }));
        s.add(barrier("damage-done"));
        if (redB) s.add(completion("discs-complete", DISCS, c -> c.cmd("give @s minecraft:music_disc_far")));
        s.add(barrier("discs-done"));

        // Kills: distinct hostile types across two players; duplicate type counts once.
        if (redA) s.add(killSteps("kills-a", List.of("zombie", "skeleton", "spider", "husk"), 4));
        s.add(barrier("kills-a"));
        if (redB) {
            s.add(killSteps("kills-b-dup", List.of("husk"), 4));
            s.add(killSteps("kills-b-new", List.of("stray", "cave_spider"), 6));
            s.add(step("kills-b-pre", DEFAULT_TIMEOUT_MS, c -> expect(active(KILLS), "6 unique kills must not complete 7")));
            s.add(killSteps("kills-b-final", List.of("silverfish"), -1));
        }
        if (blue) s.add(step("kills-blue-isolated", DEFAULT_TIMEOUT_MS, c ->
                expect(progress(KILLS) == 0 || task(KILLS).status().equals("resolved"), "blue must not see red kills")));
        s.add(barrier("kills-done"));
        s.add(step("kills-resolved", DEFAULT_TIMEOUT_MS, c -> task(KILLS).status().equals("resolved")));

        // P0-3: semantically repaired action targets, each with a negative probe.
        if (redA) {
            s.add(milkSteps());
            s.add(cauldronSteps());
            s.add(loomNegativeSteps());
        }
        s.add(barrier("loom-negative"));
        if (blue) s.add(loomPositiveSteps());
        s.add(barrier("loom-done"));
        if (redA) s.add(step("loom-mutex", DEFAULT_TIMEOUT_MS, c ->
                task(LOOM).status().equals("resolved") && expect(board().score() == lastScore, "blue loom must not score for red")));
        if (redB) {
            s.add(composterPositiveSteps());
            s.add(jukeboxSteps());
            s.add(mineSteps("mine-diamond", DIAMOND, "deepslate_diamond_ore", new BlockPos(2, 0, -2)));
            s.add(mineSteps("mine-emerald", EMERALD, "deepslate_emerald_ore", new BlockPos(-2, 0, -2)));
        }
        s.add(barrier("actions-done"));

        // P0-4: bonus counts toward progress but not the pool; winning settles exactly once.
        if (blue) s.add(step("bonus", DEFAULT_TIMEOUT_MS, c -> {
            BoardState b = board();
            if (c.ticks == 1) {
                lastScore = b.score();
                c.cmd("give @s minecraft:zombie_head");
            }
            if (!task(BONUS).status().equals("resolved")) return false;
            check(b.score() == lastScore + task(BONUS).score(), "bonus must add progress score");
            check(b.score() < b.winScore() && b.state().equals("INGAME"), "bonus alone must not win in this fixture");
            check(b.totalScore() == b.tasks().stream().filter(t -> !t.bonus()).mapToInt(BoardState.Task::score).sum(),
                    "bonus must not change the pool");
            return true;
        }));
        s.add(barrier("bonus"));
        if (redA) s.add(completion("netherite-block", "NETHERITE_BLOCK", c -> c.cmd("give @s minecraft:netherite_block")));
        s.add(barrier("pre-final"));
        if (redB) s.add(step("final-race", DEFAULT_TIMEOUT_MS, c -> {
            BoardState b = BoardClient.snapshot;
            if (c.ticks == 1) {
                lastScore = board().score();
                check(lastScore < board().winScore(), "red should not have won yet");
                // One command batch: the same inventory poll can see all three.
                for (String id : FINAL_RACE) c.cmd("give @s minecraft:" + id.toLowerCase(Locale.ROOT));
            }
            return b != null && "END".equals(b.state());
        }));
        s.add(step("settled", DEFAULT_TIMEOUT_MS, c -> {
            BoardState b = BoardClient.snapshot;
            if (b == null || !"END".equals(b.state()) || c.ticks < 40) return false;
            if (!blue) {
                long resolved = FINAL_RACE.stream().filter(id -> task(id).status().equals("resolved")).count();
                int gained = FINAL_RACE.stream().filter(id -> task(id).status().equals("resolved"))
                        .mapToInt(id -> task(id).score()).sum();
                check(b.score() >= b.winScore(), "red must reach the win line");
                check(resolved == 2, "exactly two race targets should settle before END, got " + resolved);
                if (role.equals("red_b")) check(b.score() == lastScore + gained, "final score " + b.score() + " != " + lastScore + "+" + gained);
                marker("FINAL red score=" + b.score() + " win=" + b.winScore() + " raceResolved=" + resolved + " gained=" + gained);
            } else {
                check(b.score() < b.winScore(), "blue must not win");
                marker("FINAL blue score=" + b.score() + " win=" + b.winScore());
            }
            c.memo = b.score();
            return true;
        }));
        if (redA) s.add(step("post-end-ignored", DEFAULT_TIMEOUT_MS, c -> {
            BoardState b = board(false);
            if (c.ticks == 1) {
                lastScore = b.score();
                c.cmd("give @s minecraft:wither_skeleton_skull");
            }
            if (c.ticks < 100) return false;
            check("END".equals(b.state()) && b.score() == lastScore, "completions after END must be ignored");
            return true;
        }));
        s.add(barrier("finished"));
        return s;
    }

    private static Snapshot preRestart;
    private static int advBaseline;
    private static int lastScore;
    private static long lastJoinAt;

    record Snapshot(int score, int discs, int damage, String statuses) {}

    private static boolean reconnectedSince(Ctx c) { return lastJoinAt > c.enteredAt; }

    // ---------------------------------------------------------------- P0-1 Tab panel

    private static List<Step> tabPanelSteps() {
        List<Step> s = new ArrayList<>();
        s.add(step("tab-hold-open", DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { c.client.gui.setScreen(null); xdotool("mousemove", "200", "200"); c.stage = 1; c.mark = c.ticks; }
                case 1 -> { if (c.ticks - c.mark > 10) { xdotool("keydown", "Tab"); c.stage = 2; c.mark = c.ticks; } }
                case 2 -> {
                    if (c.client.gui.screen() instanceof BoardScreen) { c.stage = 3; c.mark = c.ticks; }
                    else if (c.ticks - c.mark > 60) throw new IllegalStateException("Tab press did not open the board");
                }
                case 3 -> {
                    // Held for 3 s: key repeat must not flap the screen.
                    check(c.client.gui.screen() instanceof BoardScreen, "board closed while Tab is held");
                    if (c.ticks - c.mark == 30) screenshot("board-held-default");
                    if (c.ticks - c.mark > 60) { xdotool("keyup", "Tab"); c.stage = 4; c.mark = c.ticks; }
                }
                default -> {
                    if (c.client.gui.screen() == null) return true;
                    if (c.ticks - c.mark > 40) throw new IllegalStateException("Tab release did not close the board");
                }
            }
            return false;
        }));
        s.add(step("tab-shift-playerlist", DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { xdotool("keydown", "Shift_L", "keydown", "Tab"); c.stage = 1; c.mark = c.ticks; }
                case 1 -> {
                    check(!(c.client.gui.screen() instanceof BoardScreen), "Shift+Tab must not open the board");
                    if (c.ticks - c.mark == 20) {
                        check(c.client.options.keyPlayerList.isDown(), "Shift+Tab must reach the vanilla player list");
                        screenshot("shift-tab-playerlist");
                    }
                    if (c.ticks - c.mark > 30) {
                        // Release Shift first: vanilla must still see the Tab release.
                        xdotool("keyup", "Shift_L", "keyup", "Tab");
                        c.stage = 2;
                        c.mark = c.ticks;
                    }
                }
                default -> {
                    if (c.ticks - c.mark < 10) return false;
                    check(!c.client.options.keyPlayerList.isDown(), "player list stuck after Shift released before Tab");
                    check(c.client.gui.screen() == null, "unexpected screen after Shift+Tab");
                    return true;
                }
            }
            return false;
        }));
        s.add(lockKeyStep("tab-capslock", "Caps_Lock"));
        s.add(lockKeyStep("tab-numlock", "Num_Lock"));
        s.add(step("tab-escape-while-held", DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { xdotool("keydown", "Tab"); c.stage = 1; c.mark = c.ticks; }
                case 1 -> {
                    if (!(c.client.gui.screen() instanceof BoardScreen)) {
                        check(c.ticks - c.mark < 60, "board did not open");
                        return false;
                    }
                    xdotool("key", "Right", "key", "Down", "key", "Next", "key", "Prior", "key", "b");
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (c.ticks - c.mark < 20) return false;
                    check(c.client.gui.screen() instanceof BoardScreen, "navigation keys must keep the board open");
                    screenshot("board-keyboard-focus");
                    xdotool("key", "Escape");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                case 3 -> {
                    if (c.ticks - c.mark < 20) return false;
                    check(c.client.gui.screen() == null, "Esc must close the board while Tab is held");
                    xdotool("keyup", "Tab");
                    c.stage = 4;
                    c.mark = c.ticks;
                }
                default -> {
                    if (c.ticks - c.mark < 20) return false;
                    check(c.client.gui.screen() == null, "releasing Tab after Esc must not reopen anything");
                    return true;
                }
            }
            return false;
        }));
        s.add(step("tab-in-chat", DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { xdotool("key", "t"); c.stage = 1; c.mark = c.ticks; }
                case 1 -> {
                    if (!(c.client.gui.screen() instanceof ChatScreen)) {
                        check(c.ticks - c.mark < 60, "chat did not open");
                        return false;
                    }
                    xdotool("type", "/men", "key", "Tab");
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (c.ticks - c.mark < 20) return false;
                    check(c.client.gui.screen() instanceof ChatScreen, "Tab inside chat must stay with chat");
                    xdotool("key", "Escape");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                default -> {
                    if (c.ticks - c.mark < 20) return false;
                    return expect(c.client.gui.screen() == null, "chat should close");
                }
            }
            return false;
        }));
        for (int scale : new int[] {1, 2, 3, 4}) {
            s.add(step("tab-guiscale-" + scale, DEFAULT_TIMEOUT_MS, c -> {
                switch (c.stage) {
                    case 0 -> {
                        c.client.options.guiScale().set(scale);
                        c.client.resizeGui();
                        xdotool("keydown", "Tab");
                        c.stage = 1;
                        c.mark = c.ticks;
                    }
                    case 1 -> {
                        if (c.ticks - c.mark < 30) return false;
                        check(c.client.gui.screen() instanceof BoardScreen, "board not open at gui scale " + scale);
                        screenshot("board-guiscale-" + scale);
                        xdotool("keyup", "Tab");
                        c.stage = 2;
                        c.mark = c.ticks;
                    }
                    default -> {
                        if (c.ticks - c.mark < 15) return false;
                        check(c.client.gui.screen() == null, "board stuck at gui scale " + scale);
                        if (scale == 4) {
                            c.client.options.guiScale().set(0);
                            c.client.resizeGui();
                        }
                        return true;
                    }
                }
                return false;
            }));
        }
        return s;
    }

    private static Step lockKeyStep(String name, String lockKey) {
        return step(name, DEFAULT_TIMEOUT_MS, c -> {
            switch (c.stage) {
                case 0 -> { xdotool("key", lockKey); c.stage = 1; c.mark = c.ticks; }
                case 1 -> { if (c.ticks - c.mark > 10) { xdotool("keydown", "Tab"); c.stage = 2; c.mark = c.ticks; } }
                case 2 -> {
                    if (!(c.client.gui.screen() instanceof BoardScreen)) {
                        check(c.ticks - c.mark < 60, lockKey + " must not block the board");
                        return false;
                    }
                    xdotool("keyup", "Tab");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                case 3 -> {
                    if (c.ticks - c.mark < 15) return false;
                    check(c.client.gui.screen() == null, "board stuck with " + lockKey);
                    xdotool("key", lockKey);
                    c.stage = 4;
                    c.mark = c.ticks;
                }
                default -> { return c.ticks - c.mark > 10; }
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- P0-2 kills

    private static Step killSteps(String name, List<String> types, int expectedAfter) {
        return step(name, 120_000, c -> {
            if (c.memo == null) {
                c.cmd("item replace entity @s weapon.mainhand with minecraft:netherite_sword");
                c.cmd("effect give @s minecraft:strength 300 20 true");
                c.memo = Boolean.TRUE;
                c.mark = c.ticks;
                return false;
            }
            int i = c.stage / 3, phase = c.stage % 3;
            if (i >= types.size()) {
                if (c.ticks - c.mark < 40) return false;
                if (expectedAfter < 0) return expect(task(KILLS).status().equals("resolved"), "7 unique kills should resolve");
                return expect(progress(KILLS) == expectedAfter, "unique kills expected " + expectedAfter + " got " + progress(KILLS));
            }
            String type = types.get(i);
            BlockPos at = arena.offset(2, 0, 0);
            switch (phase) {
                case 0 -> {
                    if (c.ticks - c.mark < 10) return false;
                    c.cmd(String.format(Locale.ROOT, "summon minecraft:%s %d %d %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}",
                            type, at.getX(), at.getY(), at.getZ()));
                    c.stage++;
                    c.mark = c.ticks;
                }
                case 1 -> {
                    LivingEntity mob = nearestOfType(c.client, type, at);
                    if (mob == null || c.ticks - c.mark < 25) return false;
                    c.client.gameMode.attack(c.client.player, mob);
                    c.client.player.swing(InteractionHand.MAIN_HAND);
                    c.stage++;
                    c.mark = c.ticks;
                }
                default -> {
                    LivingEntity mob = nearestOfType(c.client, type, at);
                    if (mob != null && mob.isAlive()) {
                        if (c.ticks - c.mark > 30) { c.stage--; c.mark = c.ticks; } // swing again
                        return false;
                    }
                    if (c.ticks - c.mark < 20) return false;
                    c.stage++;
                    c.mark = c.ticks;
                }
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- P0-3 actions

    private static Step milkSteps() {
        return step("milk", 90_000, c -> {
            switch (c.stage) {
                case 0 -> {
                    c.cmd("effect clear @s");
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:milk_bucket");
                    lastScore = board().score();
                    c.stage = 1;
                    c.mark = c.ticks;
                }
                case 1 -> { if (c.ticks - c.mark > 20 && holding(c.client, "milk_bucket")) { drink(c.client, true); c.stage = 2; c.mark = c.ticks; } }
                case 2 -> {
                    ensureUsing(c);
                    if (c.ticks - c.mark < 60) return false;
                    drink(c.client, false);
                    check(holding(c.client, "bucket"), "first milk was not drunk");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                case 3 -> {
                    if (c.ticks - c.mark < 40) return false;
                    check(active(MILK), "milk without any effect must not count");
                    c.cmd("effect give @s minecraft:speed 60 0");
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:milk_bucket");
                    c.stage = 4;
                    c.mark = c.ticks;
                }
                case 4 -> { if (c.ticks - c.mark > 20 && holding(c.client, "milk_bucket")) { drink(c.client, true); c.stage = 5; c.mark = c.ticks; } }
                default -> {
                    if (c.ticks - c.mark < 60) ensureUsing(c);
                    if (c.ticks - c.mark < 60) return false;
                    drink(c.client, false); // never leave the use key held for the next step
                    return task(MILK).status().equals("resolved") && scoreGained(MILK);
                }
            }
            return false;
        });
    }

    private static Step cauldronSteps() {
        BlockPos pos = new BlockPos(2, 0, 2);
        return step("cauldron", 60_000, c -> {
            BlockPos at = arena.offset(pos);
            switch (c.stage) {
                case 0 -> {
                    lastScore = board().score();
                    c.cmd(setblock(at, "water_cauldron[level=3]"));
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:leather_chestplate");
                    c.stage = 1;
                    c.mark = c.ticks;
                }
                case 1 -> {
                    if (c.ticks - c.mark < 20 || !holding(c.client, "leather_chestplate")) return false;
                    use(c.client, at);
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (c.ticks - c.mark < 40) return false;
                    check(active(CAULDRON), "undyed armour in a cauldron must not count");
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:leather_chestplate[dyed_color=16711680]");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                case 3 -> {
                    if (c.ticks - c.mark < 20) return false;
                    use(c.client, at);
                    c.stage = 4;
                }
                default -> { return task(CAULDRON).status().equals("resolved") && scoreGained(CAULDRON); }
            }
            return false;
        });
    }

    private static Step loomNegativeSteps() {
        return step("loom-negative", 60_000, c -> loomFlow(c, arena.offset(-2, 0, 2), false));
    }

    private static Step loomPositiveSteps() {
        return step("loom-positive", 60_000, c -> loomFlow(c, arena.offset(-2, 0, 2), true));
    }

    /** Insert banner + dye, pick a pattern, then either close (negative) or click the result (positive). */
    private static boolean loomFlow(Ctx c, BlockPos at, boolean take) {
        switch (c.stage) {
            case 0 -> {
                lastScore = board().score();
                c.cmd(setblock(at, "loom"));
                c.cmd("clear @s");
                c.cmd("give @s minecraft:white_banner");
                c.cmd("give @s minecraft:red_dye 4");
                c.stage = 1;
                c.mark = c.ticks;
            }
            case 1 -> {
                if (c.ticks - c.mark < 20) return false;
                use(c.client, at);
                c.stage = 2;
                c.mark = c.ticks;
            }
            case 2 -> {
                if (!(c.client.player.containerMenu instanceof LoomMenu)) {
                    check(c.ticks - c.mark < 60, "loom did not open");
                    return false;
                }
                container(c.client, playerSlotWith(c.client, "white_banner"), ContainerInput.QUICK_MOVE, 0);
                container(c.client, playerSlotWith(c.client, "red_dye"), ContainerInput.QUICK_MOVE, 0);
                c.stage = 3;
                c.mark = c.ticks;
            }
            case 3 -> {
                LoomMenu loom = (LoomMenu) c.client.player.containerMenu;
                if (c.ticks - c.mark < 10 || loom.getSelectablePatterns().isEmpty()) return false;
                c.client.gameMode.handleInventoryButtonClick(loom.containerId, 0);
                loom.clickMenuButton(c.client.player, 0);
                c.stage = 4;
                c.mark = c.ticks;
            }
            case 4 -> {
                LoomMenu loom = (LoomMenu) c.client.player.containerMenu;
                if (!loom.getResultSlot().hasItem()) {
                    check(c.ticks - c.mark < 60, "loom produced no result");
                    return false;
                }
                screenshot("loom-" + (take ? "take" : "preview") + "-" + role);
                if (take) {
                    // Normal click puts the banner on the cursor, then place it into the inventory.
                    container(c.client, loom.getResultSlot().index, ContainerInput.PICKUP, 0);
                    c.stage = 5;
                } else {
                    c.client.player.closeContainer();
                    c.stage = 6;
                }
                c.mark = c.ticks;
            }
            case 5 -> {
                if (c.ticks - c.mark < 10) return false;
                int empty = emptyPlayerSlot(c.client);
                if (empty >= 0) container(c.client, empty, ContainerInput.PICKUP, 0);
                c.client.player.closeContainer();
                c.stage = 7;
                c.mark = c.ticks;
            }
            case 6 -> {
                if (c.ticks - c.mark < 60) return false;
                check(active(LOOM), "previewing a loom pattern and closing must not count");
                check(playerSlotWith(c.client, "white_banner") >= 0, "closing the loom should return the banner");
                return true;
            }
            default -> { return task(LOOM).status().equals("resolved") && scoreGained(LOOM); }
        }
        return false;
    }

    private static Step composterPositiveSteps() {
        BlockPos pos = new BlockPos(0, 0, 1);
        return step("composter-positive", 90_000, c -> {
            BlockPos at = arena.offset(pos);
            switch (c.stage) {
                case 0 -> {
                    lastScore = board().score();
                    c.cmd(setblock(at, "composter"));
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:pumpkin_pie 16");
                    c.stage = 1;
                    c.mark = c.ticks;
                }
                case 1 -> {
                    int level = composterLevel(c.client, at);
                    if (level >= 7) { c.stage = 2; c.mark = c.ticks; return false; }
                    if (c.ticks - c.mark >= 8 && holding(c.client, "pumpkin_pie")) {
                        use(c.client, at);
                        c.mark = c.ticks;
                    }
                }
                case 2 -> {
                    if (composterLevel(c.client, at) != 8 || c.ticks - c.mark < 40) return false;
                    check(active(COMPOSTER), "filling alone must not complete the composter task");
                    use(c.client, at);
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                default -> {
                    if (c.ticks - c.mark > 60) check(playerSlotWith(c.client, "bone_meal") >= 0, "bone meal was not picked up");
                    return task(COMPOSTER).status().equals("resolved") && scoreGained(COMPOSTER);
                }
            }
            return false;
        });
    }

    private static Step jukeboxSteps() {
        BlockPos pos = new BlockPos(-2, 0, 0);
        return step("jukebox", 60_000, c -> {
            BlockPos at = arena.offset(pos);
            switch (c.stage) {
                case 0 -> {
                    lastScore = board().score();
                    c.cmd(setblock(at, "jukebox"));
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:stick");
                    c.stage = 1;
                    c.mark = c.ticks;
                }
                case 1 -> {
                    if (c.ticks - c.mark < 20) return false;
                    use(c.client, at);
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (c.ticks - c.mark < 40) return false;
                    check(active(JUKEBOX), "right-clicking a jukebox without a disc must not count");
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:music_disc_mall");
                    c.stage = 3;
                    c.mark = c.ticks;
                }
                case 3 -> {
                    if (c.ticks - c.mark < 20 || !holding(c.client, "music_disc_mall")) return false;
                    use(c.client, at);
                    c.stage = 4;
                }
                default -> { return task(JUKEBOX).status().equals("resolved") && scoreGained(JUKEBOX); }
            }
            return false;
        });
    }

    private static Step mineSteps(String name, String target, String block, BlockPos offset) {
        return step(name, 60_000, c -> {
            BlockPos at = arena.offset(offset);
            switch (c.stage) {
                case 0 -> {
                    lastScore = board().score();
                    c.cmd(setblock(at, block));
                    c.cmd("item replace entity @s weapon.mainhand with minecraft:netherite_pickaxe");
                    c.cmd("effect give @s minecraft:haste 60 1 true");
                    c.stage = 1;
                    c.mark = c.ticks;
                }
                case 1 -> {
                    if (c.ticks - c.mark < 20 || !holding(c.client, "netherite_pickaxe")) return false;
                    check(blockId(c.client, at).equals(block), "expected " + block + " at " + at + " got " + blockId(c.client, at));
                    c.client.gameMode.startDestroyBlock(at, Direction.UP);
                    c.stage = 2;
                    c.mark = c.ticks;
                }
                case 2 -> {
                    if (blockId(c.client, at).equals("air")) { c.stage = 3; return false; }
                    c.client.gameMode.continueDestroyBlock(at, Direction.UP);
                    c.client.player.swing(InteractionHand.MAIN_HAND);
                    check(c.ticks - c.mark < 200, "block did not break");
                }
                default -> { return task(target).status().equals("resolved") && scoreGained(target); }
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- step helpers

    private static Step step(String name, long timeoutMs, Body body) { return new Step(name, timeoutMs, body); }

    /** Runs {@code action} once and waits until {@code target} resolves with the expected score gain. */
    private static Step completion(String name, String target, java.util.function.Consumer<Ctx> action) {
        return step(name, DEFAULT_TIMEOUT_MS, c -> {
            if (c.ticks == 1) {
                check(active(target), target + " should still be active");
                lastScore = board().score();
                action.accept(c);
            }
            return task(target).status().equals("resolved") && scoreGained(target);
        });
    }

    private static boolean scoreGained(String target) {
        int expected = lastScore + task(target).score();
        check(board().score() == expected, target + ": score " + board().score() + " != " + expected);
        return true;
    }

    private static Step barrier(String name) {
        return step("barrier:" + name, 600_000, c -> {
            if (c.ticks == 1) {
                try { Files.writeString(syncDir.resolve(name + "." + role), Long.toString(System.currentTimeMillis())); }
                catch (IOException ex) { throw new IllegalStateException(ex); }
            }
            if (c.ticks % 5 != 0) return false;
            return ROLES.stream().allMatch(r -> Files.exists(syncDir.resolve(name + "." + r)));
        });
    }

    // ---------------------------------------------------------------- board helpers

    private static boolean inGame() {
        BoardState b = BoardClient.snapshot;
        return b != null && b.error().isEmpty() && "INGAME".equals(b.state());
    }

    private static BoardState board() { return board(true); }

    private static BoardState board(boolean requireInGame) {
        BoardState b = BoardClient.snapshot;
        if (b == null || !b.error().isEmpty() || (requireInGame && !"INGAME".equals(b.state())))
            throw new IllegalStateException("missing live board" + (b == null ? "" : " state=" + b.state()));
        return b;
    }

    private static BoardState.Task task(String id) {
        return BoardClient.snapshot == null ? null : BoardClient.snapshot.tasks().stream()
                .filter(t -> t.id().equals(id) || t.id().equals(GOAL_PREFIX + id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("task missing: " + id));
    }

    private static boolean active(String id) { return task(id).status().equals("active"); }

    private static int progress(String id) {
        BoardState.Task t = task(id);
        return t.progressKnown() ? t.current() : -1;
    }

    private static String statusLine(BoardState b) {
        StringBuilder out = new StringBuilder();
        for (BoardState.Task t : b.tasks()) out.append(t.id(), 0, Math.min(6, t.id().length())).append('=').append(t.status().charAt(0)).append(' ');
        return out.toString().trim();
    }

    // ---------------------------------------------------------------- world helpers

    private static String teleportHome() {
        return String.format(Locale.ROOT, "minecraft:teleport @s %.1f %d %.1f 0 0", arena.getX() + 0.5, ARENA_Y, arena.getZ() + 0.5);
    }

    private static boolean atArena(Minecraft client) {
        BlockPos p = client.player.blockPosition();
        return p.getY() == ARENA_Y && Math.abs(p.getX() - arena.getX()) <= 1 && Math.abs(p.getZ() - arena.getZ()) <= 1
                && client.level.hasChunkAt(p);
    }

    private static String setblock(BlockPos at, String block) {
        return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:" + block;
    }

    private static void use(Minecraft client, BlockPos at) {
        Vec3 hit = new Vec3(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, at, false));
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    private static void drink(Minecraft client, boolean down) {
        client.player.setXRot(-90); // look at the sky so the use key cannot target a block
        client.options.keyUse.setDown(down);
    }

    /** Fallback if the held use key did not start using the item (vanilla normally does). */
    private static void ensureUsing(Ctx c) {
        if (c.ticks - c.mark == 10 && !c.client.player.isUsingItem() && holding(c.client, "milk_bucket"))
            c.client.gameMode.useItem(c.client.player, InteractionHand.MAIN_HAND);
    }

    private static boolean holding(Minecraft client, String item) {
        return itemId(client.player.getMainHandItem()).equals(item);
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String blockId(Minecraft client, BlockPos at) {
        return BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(at).getBlock()).getPath();
    }

    private static int composterLevel(Minecraft client, BlockPos at) {
        BlockState state = client.level.getBlockState(at);
        return state.is(Blocks.COMPOSTER) ? state.getValue(ComposterBlock.LEVEL) : -1;
    }

    private static LivingEntity nearestOfType(Minecraft client, String type, BlockPos at) {
        Predicate<LivingEntity> matches = e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(type);
        return client.level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(3), matches)
                .stream().findFirst().orElse(null);
    }

    private static boolean openContainer(Minecraft client) {
        return client.gui.screen() instanceof AbstractContainerScreen<?>;
    }

    private static void clickSlot(Minecraft client, int slot) {
        container(client, slot, ContainerInput.PICKUP, 0);
    }

    private static void container(Minecraft client, int slot, ContainerInput input, int button) {
        AbstractContainerMenu menu = client.player.containerMenu;
        check(slot >= 0 && slot < menu.slots.size(), "slot out of range: " + slot);
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, client.player);
    }

    /** Menu slot index of the first player-inventory slot holding {@code item}. */
    private static int playerSlotWith(Minecraft client, String item) {
        for (Slot slot : client.player.containerMenu.slots)
            if (slot.container == client.player.getInventory() && itemId(slot.getItem()).equals(item)) return slot.index;
        return -1;
    }

    private static int emptyPlayerSlot(Minecraft client) {
        for (Slot slot : client.player.containerMenu.slots)
            if (slot.container == client.player.getInventory() && !slot.hasItem() && slot.getContainerSlot() < 36) return slot.index;
        return -1;
    }

    // ---------------------------------------------------------------- X11 input + screenshots

    private static void xdotool(String... args) {
        List<String> command = new ArrayList<>();
        command.add("xdotool");
        command.addAll(List.of(args));
        run(command);
    }

    private static void screenshot(String name) {
        Path out = screenDir.resolve(role + "-" + name + ".png");
        run(List.of("import", "-window", "root", out.toString()));
        marker("SCREENSHOT " + out);
    }

    private static void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out: " + command);
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException(command + " failed: " + new String(process.getInputStream().readAllBytes()));
        } catch (IOException ex) {
            throw new IllegalStateException("cannot run " + command, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- reporting

    private static String currentName() { return index >= 0 && index < script.size() ? script.get(index).name() : "-"; }

    private static boolean expect(boolean condition, String message) {
        check(condition, message);
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(Minecraft client, String message) {
        if (failed) return;
        failed = true;
        marker("FAIL role=" + role + " step=" + currentName() + " reason=" + message.replace('\n', ' '));
        client.options.keyUse.setDown(false);
        client.stop();
    }

    private static void marker(String message) {
        String line = "BLOCKRACING_E2E " + message;
        LOGGER.info(line);
        System.out.println(line);
    }
}
