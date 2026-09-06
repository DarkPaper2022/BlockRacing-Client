package top.lqsnow.blockracing.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BoardLayoutTest {
    @Test void everyCellIsReachableAndOutsideNeverSelects() {
        for (int i = 0; i < 64; i++) assertEquals(i, BoardLayout.slot(12 + i % 8 * 56 + 20, 44 + i / 8 * 56 + 20));
        assertEquals(-1, BoardLayout.slot(11.9, 50));
        assertEquals(-1, BoardLayout.slot(460, 50));
        assertEquals(-1, BoardLayout.slot(20, 492));
    }
    @Test void smallScreensFitWithoutClipping() {
        for (int[] size : new int[][]{{320, 240}, {640, 360}, {1920, 1080}}) {
            float scale = BoardLayout.scale(size[0], size[1]);
            assertTrue(BoardLayout.WIDTH * scale <= size[0]);
            assertTrue(BoardLayout.HEIGHT * scale <= size[1]);
        }
    }
    @Test void bonusAndOversizedRoundsDoNotDisappear() {
        assertEquals(1, BoardLayout.pages(64, 64));
        assertEquals(2, BoardLayout.pages(65, 64));
        assertEquals(1, BoardLayout.pages(0, 64));
        assertEquals(2, BoardLayout.pages(4, 3));
    }
}
