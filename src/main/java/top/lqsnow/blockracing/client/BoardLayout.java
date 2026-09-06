package top.lqsnow.blockracing.client;

/** Fixed slot positions: completed targets never make the grid jump. */
public final class BoardLayout {
    public static final int COLUMNS = 8, PAGE_SIZE = 64, CELL = 56;
    public static final int WIDTH = 472, HEIGHT = 572, LEFT = 12, TOP = 44;
    private BoardLayout() { }
    public static int pages(int count, int pageSize) { return Math.max(1, (count + pageSize - 1) / pageSize); }
    public static int slot(double x, double y) {
        int column = (int) Math.floor((x - LEFT) / CELL);
        int row = (int) Math.floor((y - TOP) / CELL);
        return column < 0 || column >= 8 || row < 0 || row >= 8 ? -1 : row * 8 + column;
    }
    public static float scale(int width, int height) {
        return Math.max(0.1f, Math.min((width - 12f) / WIDTH, (height - 12f) / HEIGHT));
    }
}
