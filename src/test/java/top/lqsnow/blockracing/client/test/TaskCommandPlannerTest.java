package top.lqsnow.blockracing.client.test;

import org.junit.jupiter.api.Test;
import top.lqsnow.blockracing.client.BoardState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskCommandPlannerTest {
    @Test void plansVanillaBlockInventoryInput() {
        var plan = TaskCommandPlanner.plan(task("CHERRY_PLANKS", ""), "TestBot_Red");
        assertTrue(plan.supported());
        assertEquals("block", plan.kind());
        assertEquals("give TestBot_Red minecraft:cherry_planks 1", plan.commands().getFirst());
    }

    @Test void plansItemCountsAndAdvancement() {
        var items = TaskCommandPlanner.plan(task("GOAL", "item:ARROW*64"), "Bot");
        assertEquals(List.of("give Bot minecraft:arrow 64"), items.commands());
        var advancement = TaskCommandPlanner.plan(task("GOAL", "advancement:story/enter_the_nether"), "Bot");
        assertEquals(List.of("advancement grant Bot only minecraft:story/enter_the_nether"), advancement.commands());
    }

    @Test void refusesInteractiveAndUnsafeInputs() {
        assertFalse(TaskCommandPlanner.plan(task("GOAL", "breed:COW"), "Bot").supported());
        assertFalse(TaskCommandPlanner.plan(task("STONE", ""), "Bot @a").supported());
    }

    private static BoardState.Task task(String id, String requirement) {
        return new BoardState.Task(id, 1, id, requirement, "minecraft:stone", "",
                false, 1, false, false, "active", 0, 1, true, false);
    }
}
