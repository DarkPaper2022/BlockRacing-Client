package top.lqsnow.blockracing.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.lwjgl.glfw.GLFW;
import java.util.*;

import static top.lqsnow.blockracing.client.BoardLayout.*;

public final class BoardScreen extends Screen {
    private int heartbeat, page, bonusPage, focus = -1;
    private float scale, originX, originY;
    private int lastMouseX = -1, lastMouseY = -1;
    private final Map<String, ItemStack> icons = new HashMap<>();
    public BoardScreen() { super(Component.literal("BlockRacing · 目标 / Tasks")); }
    @Override protected void init() { BoardClient.subscribe(true); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { BoardClient.subscribe(false); }
    @Override public void tick() { if (++heartbeat % 100 == 0) BoardClient.subscribe(true); }
    private String tr(String zh, String en) {
        return BoardClient.snapshot == null || BoardClient.snapshot.chinese() ? zh : en;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xB00A1019);
        scale = scale(width, height);
        originX = (width - WIDTH * scale) / 2;
        originY = (height - HEIGHT * scale) / 2;
        double mx = (mouseX - originX) / scale, my = (mouseY - originY) / scale;
        if (mouseX != lastMouseX || mouseY != lastMouseY) focus = -1;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        g.pose().pushMatrix();
        g.pose().translate(originX, originY).scale(scale);
        g.fill(0, 0, WIDTH, HEIGHT, 0xF2111C2A);
        g.text(font, tr("本局目标", "ROUND TASKS"), 12, 10, 0xFFF1F5F9);
        BoardState board = BoardClient.snapshot;
        String message = !BoardClient.error.isEmpty() ? BoardClient.error : board == null
                ? tr("正在同步…", "Synchronizing…") : !board.error().isEmpty() ? board.error()
                : board.team().isEmpty() ? tr("加入队伍后查看目标", "Join a team to view tasks")
                : board.state().equals("PREGAME") ? tr("等待本局目标抽取", "Waiting for the round to start") : "";
        if (!message.isEmpty()) {
            g.textWithWordWrap(font, Component.literal(message), 12, 30, WIDTH - 24, 0xFFFACB75);
        } else {
            String heading = (board.team().equals("red") ? tr("红队", "RED") : tr("蓝队", "BLUE"))
                    + "  " + board.score() + " / " + board.winScore() + tr(" 胜利分", " to win")
                    + "  ·  " + tr("总分 ", "Pool ") + board.totalScore();
            g.text(font, heading, 12, 26, 0xFF9EB7CE);
        }
        List<BoardState.Task> main = board == null ? List.of() : board.tasks().stream().filter(t -> !t.bonus()).toList();
        List<BoardState.Task> bonus = board == null ? List.of() : board.tasks().stream().filter(BoardState.Task::bonus).toList();
        page = Math.min(page, pages(main.size(), 64) - 1);
        bonusPage = Math.min(bonusPage, pages(bonus.size(), 3) - 1);
        BoardState.Task hovered = null;
        int slot = focus >= 0 ? focus : slot(mx, my);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int x = LEFT + (i % 8) * CELL, y = TOP + (i / 8) * CELL;
            int index = page * 64 + i;
            if (index >= main.size()) { g.fill(x, y, x + CELL - 2, y + CELL - 2, 0xFF152232); continue; }
            var task = main.get(index);
            boolean selected = slot == i;
            card(g, task, x, y, CELL - 2, selected);
            if (selected) hovered = task;
        }
        g.text(font, "BONUS · " + tr("不计胜利分", "extra rewards") + (pages(bonus.size(), 3) > 1
                ? "  [B] " + (bonusPage + 1) + "/" + pages(bonus.size(), 3) : ""), 12, 498, 0xFFF0BC62);
        for (int i = 0; i < 3; i++) {
            int x = 12 + i * 150, index = bonusPage * 3 + i;
            if (index >= bonus.size()) continue;
            var task = bonus.get(index);
            boolean selected = mx >= x && mx < x + 146 && my >= 512 && my < 548;
            g.fill(x, 512, x + 146, 548, selected ? 0xFF3F3540 : 0xFF292937);
            g.fill(x, 512, x + 2, 548, 0xFFF0BC62);
            g.item(icon(task), x + 6, 522);
            g.text(font, trim(task.title(), 116), x + 26, 519, 0xFFF1E1C3);
            g.text(font, "+" + task.score() + " · " + status(task), x + 26, 534, 0xFFC6BDAE);
            if (selected) hovered = task;
        }
        String footer = "Tab / Esc " + tr("关闭", "close") + " · " + tr("悬停查看规则", "hover for rules");
        if (pages(main.size(), 64) > 1) footer += " · PgUp/PgDn " + (page + 1) + "/" + pages(main.size(), 64);
        if (board != null && System.nanoTime() - BoardClient.receivedAt > 4_000_000_000L) footer = tr("同步延迟：当前为旧快照", "STALE: waiting for server update");
        g.text(font, trim(footer, WIDTH - 24), 12, 558, 0xFFAFBDCA);
        g.pose().popMatrix();
        if (hovered != null) {
            var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
            int wrap = Math.max(100, Math.min(330, width - 24));
            for (String text : List.of(hovered.title(), status(hovered) + " · " + hovered.score() + tr(" 分", " points"),
                    hovered.progressKnown() ? (hovered.individual() ? tr("最佳个人（须同一人满足） ", "Best player (one player required) ")
                            : tr("队伍共享进度 ", "Shared team progress ")) + hovered.current() + " / " + hovered.required() : "",
                    hovered.requirement())) {
                if (!text.isEmpty()) lines.addAll(font.split(Component.literal(text), wrap));
            }
            int maxLines = Math.max(4, (height - 32) / 10);
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(0, maxLines - 1));
                lines.add(Component.literal(tr("…完整候选见 /menu targets", "…more candidates: /menu targets")).getVisualOrderText());
            }
            g.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private void card(GuiGraphicsExtractor g, BoardState.Task task, int x, int y, int w, boolean selected) {
        boolean resolved = task.status().equals("resolved"), queued = task.status().equals("queued");
        g.fill(x, y, x + w, y + w, selected ? 0xFF304C64 : resolved ? 0xFF202B32 : 0xFF1B3044);
        if (selected) g.outline(x, y, w, w, 0xFF9EEBEE);
        int ink = resolved || queued ? 0xFF9AAAB5 : 0xFFF1F5F9;
        g.text(font, "#" + task.index(), x + 3, y + 2, 0xFF96A9B8);
        String score = task.score() + tr("分", "p");
        g.text(font, score, x + w - font.width(score) - 3, y + 2, 0xFFF0CB83);
        g.item(icon(task), x + (w - 16) / 2, y + 12);
        var label = font.split(Component.literal(task.title()), w - 6);
        for (int line = 0; line < Math.min(2, label.size()); line++)
            g.text(font, label.get(line), x + 3, y + 28 + line * 9, ink);
        String progress = task.progressKnown() ? task.current() + "/" + task.required() : status(task);
        g.text(font, trim(progress, w - 6), x + 3, y + 45, ink);
        g.fill(x, y + w - 1, x + w, y + w, 0xFF3F5261);
        if (task.progressKnown()) g.fill(x, y + w - 1, x + (int) (w * task.fraction()), y + w, 0xFF77D5CA);
    }

    private String status(BoardState.Task task) {
        return switch (task.status()) {
            case "resolved" -> tr("已结算", "Resolved");
            case "queued" -> tr("未开放", "Queued");
            default -> tr("可完成", "Active");
        };
    }
    private String trim(String text, int width) { return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - font.width("…")) + "…"; }
    private ItemStack icon(BoardState.Task task) {
        String key = task.icon() + task.model() + task.bonus() + task.glint() + task.requirement();
        return icons.computeIfAbsent(key, ignored -> {
            ItemStack stack;
            try {
                stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(task.icon())));
                if (stack.isEmpty()) stack = new ItemStack(Items.PAPER);
            } catch (RuntimeException ex) { stack = new ItemStack(Items.PAPER); }
            if (!task.model().isEmpty()) stack.set(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(List.of(), List.of(task.bonus()), List.of(task.model()), List.of()));
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, task.glint());
            if (task.requirement().equals("consume-potion:WATER"))
                stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            return stack;
        });
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB && event.modifiers() == 0) { onClose(); return true; }
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN) { page++; return true; }
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP) { page = Math.max(0, page - 1); return true; }
        if (event.key() == GLFW.GLFW_KEY_B) { bonusPage++; if (BoardClient.snapshot != null) bonusPage %= pages((int) BoardClient.snapshot.tasks().stream().filter(BoardState.Task::bonus).count(), 3); return true; }
        int direction = switch (event.key()) { case GLFW.GLFW_KEY_LEFT -> -1; case GLFW.GLFW_KEY_RIGHT -> 1;
            case GLFW.GLFW_KEY_UP -> -8; case GLFW.GLFW_KEY_DOWN -> 8; default -> 0; };
        if (direction != 0) { focus = Math.floorMod(Math.max(0, focus) + direction, 64); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        page = Math.max(0, page + (vertical < 0 ? 1 : vertical > 0 ? -1 : 0)); return true;
    }
}
