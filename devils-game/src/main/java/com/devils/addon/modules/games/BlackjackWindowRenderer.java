package com.devils.addon.modules.games;
import static com.devils.addon.modules.games.BlackjackWindowLayout.clamp;
import static com.devils.addon.modules.games.BlackjackWindowLayout.inside;
import com.devils.addon.modules.games.BlackjackWindowLayout.Layout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
final class BlackjackWindowRenderer {
    static final int DARK_BG = 0xD00B0F15;
    private static final int HEADER_BG = 0xD8322A1F;
    private static final int HEADER_BORDER = 0xFFC18845;
    private static final int TABLE_BORDER = 0xFF7A4A27;
    private static final int PANEL_BG = 0xD0181210;
    private static final int PANEL_BORDER = 0xFF9D6F44;
    static final int STATUS_GOLD = 0xFFFFE29A;
    static final int STATUS_GREEN = 0xFFC9F4AE;
    static final int CARD_W = 132;
    static final int CARD_H = 186;
    static final int CARD_BACK_W = 132;
    static final int CARD_BACK_H = 183;
    static final Identifier CARD_BACK = Identifier.of("devils-game", "textures/games/blackjack/cards/backgroundred.png");
    private static final Identifier CHIP_WHITE = Identifier.of("devils-game", "textures/games/blackjack/chips/chipwhite.png");
    private static final Identifier CHIP_RED = Identifier.of("devils-game", "textures/games/blackjack/chips/chipred.png");
    private static final Identifier CHIP_GREEN = Identifier.of("devils-game", "textures/games/blackjack/chips/chipgreen.png");
    private static final Identifier CHIP_BLACK = Identifier.of("devils-game", "textures/games/blackjack/chips/chipblack.png");
    private static final Identifier CHIP_PURPLE = Identifier.of("devils-game", "textures/games/blackjack/chips/chippurple.png");
    private static final Identifier CHIP_YELLOW = Identifier.of("devils-game", "textures/games/blackjack/chips/chipyellow.png");
    private static final Identifier CHIP_ORANGE = Identifier.of("devils-game", "textures/games/blackjack/chips/chiporange.png");
    private static final Identifier CHIP_BLUE = Identifier.of("devils-game", "textures/games/blackjack/chips/chipblue.png");
    private static final int[] CHIP_VALUES = {500, 100, 25, 5, 1};
    static final Identifier[] BET_CHIPS = {CHIP_PURPLE, CHIP_BLACK, CHIP_GREEN, CHIP_RED, CHIP_WHITE};
    private BlackjackWindowRenderer() {
    }
    static void drawHeader(DrawContext context, TextRenderer tr, Layout l, boolean pinned) {
        context.fill(l.x(), l.y(), l.x() + l.w(), l.y() + l.headerH(), HEADER_BG);
        context.fill(l.x(), l.y(), l.x() + l.w(), l.y() + 1, HEADER_BORDER);
        context.fill(l.x(), l.y() + l.headerH() - 1, l.x() + l.w(), l.y() + l.headerH(), HEADER_BORDER);
        context.drawTextWithShadow(tr, "Blackjack", l.x() + 6, l.y() + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX(), l.pinY(), l.btnW(), l.btnH(), pinned ? "Unpin" : "Pin");
        drawHeaderButton(context, tr, l.closeX(), l.closeY(), l.btnW(), l.btnH(), "X");
    }
    static void drawFeltTable(DrawContext context, Layout l) {
        context.fill(l.tableX(), l.tableY(), l.tableX() + l.tableW(), l.tableY() + l.tableH(), 0xFF5D1F23);
        for (int i = 0; i < 14; i++) {
            float t = i / 13f;
            float mid = 1f - Math.abs((t * 2f) - 1f);
            int color = lerpColor(0xFF4A171A, 0xFF8B2D34, 0.14f + mid * 0.65f);
            int y1 = l.tableY() + (l.tableH() * i) / 14;
            int y2 = l.tableY() + (l.tableH() * (i + 1)) / 14;
            context.fill(l.tableX() + 2, y1, l.tableX() + l.tableW() - 2, y2, color);
        }
        context.fill(l.tableX() + 3, l.tableY() + 3, l.tableX() + l.tableW() - 3, l.tableY() + l.tableH() - 3, 0x22000000);
        context.fill(l.tableX(), l.tableY(), l.tableX() + l.tableW(), l.tableY() + 1, TABLE_BORDER);
        context.fill(l.tableX(), l.tableY() + l.tableH() - 1, l.tableX() + l.tableW(), l.tableY() + l.tableH(), TABLE_BORDER);
        context.fill(l.tableX(), l.tableY(), l.tableX() + 1, l.tableY() + l.tableH(), TABLE_BORDER);
        context.fill(l.tableX() + l.tableW() - 1, l.tableY(), l.tableX() + l.tableW(), l.tableY() + l.tableH(), TABLE_BORDER);
        String title = "BLACKJACK";
        String rule = "PAYS 3 TO 2";
        int titleY = l.topBandY() + 3;
        int ruleY = l.topBandY() + 13;
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, title, l.tableX() + (l.tableW() - MinecraftClient.getInstance().textRenderer.getWidth(title)) / 2, titleY, 0xFFF8D9BF);
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, rule, l.tableX() + (l.tableW() - MinecraftClient.getInstance().textRenderer.getWidth(rule)) / 2, ruleY, 0xFFECC8AB);
    }
    static void drawTopChipRail(DrawContext context, Layout l) {
        Identifier[] rail = {CHIP_WHITE, CHIP_RED, CHIP_GREEN, CHIP_BLACK, CHIP_PURPLE, CHIP_YELLOW, CHIP_ORANGE, CHIP_BLUE};
        int size = l.chipSize();
        int gap = size + 4;
        int startX = l.tableX() + (l.tableW() - (rail.length * gap - 4)) / 2;
        for (int i = 0; i < rail.length; i++) {
            drawChip(context, rail[i], startX + i * gap, l.chipRailY(), size);
        }
    }
    static void drawBetCircles(DrawContext context, Layout l) {
        drawCircleOutline(context, l.betCenterX(), l.betCenterY(), l.betRadius(), 0xFFDEBC95);
        drawCircleOutline(context, l.leftBetX(), l.betCenterY(), l.betRadius() - 3, 0x9FD6B78F);
        drawCircleOutline(context, l.rightBetX(), l.betCenterY(), l.betRadius() - 3, 0x9FD6B78F);
    }
    static void drawMainBetStack(DrawContext context, Layout l, int amount) {
        int[] counts = splitAmount(amount);
        int size = l.chipSize();
        int x = l.betCenterX() - size / 2;
        int baseY = l.betCenterY() + l.betRadius() - size / 2 - 2;
        int drawn = 0;
        for (int i = 0; i < counts.length && drawn < 12; i++) {
            for (int c = 0; c < counts[i] && drawn < 12; c++) {
                int y = baseY - drawn * Math.max(2, size / 7);
                drawChip(context, BET_CHIPS[i], x + ((drawn & 1) == 0 ? 0 : 1), y, size);
                drawn++;
            }
        }
        if (drawn == 0) {
            drawChip(context, CHIP_WHITE, x, baseY, size);
        }
    }
    static void drawCornerStacks(DrawContext context, TextRenderer tr, Layout l, int amount) {
        int[] counts = splitAmount(amount);
        int size = Math.max(16, l.chipSize() - 10);
        int leftX = l.tableX() + l.tablePad() + 4;
        int rightX = l.tableX() + l.tableW() - l.tablePad() - size - 4;
        int baseY = l.tableY() + l.tableH() - 14;
        int stackIndex = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            boolean left = (stackIndex % 2) == 0;
            int row = stackIndex / 2;
            int x = left ? leftX : rightX;
            int y = baseY - row * (size + 6);
            int shown = Math.min(8, counts[i]);
            for (int s = 0; s < shown; s++) {
                drawChip(context, BET_CHIPS[i], x, y - s * 2, size);
            }
            if (counts[i] > shown) {
                String label = "x" + counts[i];
                int labelX = left ? (x + size + 2) : (x - 2 - tr.getWidth(label));
                context.drawTextWithShadow(tr, label, labelX, y - shown * 2 - 2, 0xFFF8E7C1);
            }
            stackIndex++;
        }
    }
    static void drawCard(DrawContext context, BlackjackSession.Card card, int x, int y, int w, int h, boolean hidden, int tint) {
        int alpha = (tint >>> 24) & 0xFF;
        int shadowAlpha = Math.max(0, alpha - 80);
        context.fill(x + 2, y + 3, x + w + 2, y + h + 3, (shadowAlpha << 24));
        Identifier texture = hidden ? CARD_BACK : card.texture();
        int tw = hidden ? CARD_BACK_W : CARD_W;
        int th = hidden ? CARD_BACK_H : CARD_H;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, tw, th, tw, th, tint);
    }
    static void drawFooter(DrawContext context, TextRenderer tr, Layout l, BlackjackSession session, int mouseX, int mouseY) {
        context.fill(l.panelX(), l.panelY(), l.panelX() + l.panelW(), l.panelY() + l.panelH(), PANEL_BG);
        context.fill(l.panelX(), l.panelY(), l.panelX() + l.panelW(), l.panelY() + 1, PANEL_BORDER);
        context.fill(l.panelX(), l.panelY() + l.panelH() - 1, l.panelX() + l.panelW(), l.panelY() + l.panelH(), PANEL_BORDER);
        context.fill(l.panelX(), l.panelY(), l.panelX() + 1, l.panelY() + l.panelH(), PANEL_BORDER);
        context.fill(l.panelX() + l.panelW() - 1, l.panelY(), l.panelX() + l.panelW(), l.panelY() + l.panelH(), PANEL_BORDER);
        String bankroll = "Bankroll: " + session.bankroll();
        String summary = "W/L/P " + session.wins() + "/" + session.losses() + "/" + session.pushes() + "   Hands " + session.handsPlayed();
        context.drawTextWithShadow(tr, bankroll, l.panelX() + 8, l.panelY() + 8, 0xFFE5F0FF);
        String trimmedSummary = trim(tr, summary, Math.max(80, l.panelW() - 230));
        context.drawTextWithShadow(tr, trimmedSummary, l.panelX() + l.panelW() - 8 - tr.getWidth(trimmedSummary), l.panelY() + 8, 0xFFD4E0F0);
        String betLine = "Bet: " + session.baseBet() + "   Round: " + session.roundBet();
        context.drawTextWithShadow(tr, betLine, l.panelX() + 8, l.panelY() + 24, 0xFFE7F2FF);
        drawBetButton(context, tr, l.betMinusX(), l.betBtnY(), l.betBtnW(), l.betBtnH(), "-");
        drawBetButton(context, tr, l.betPlusX(), l.betBtnY(), l.betBtnW(), l.betBtnH(), "+");
        drawBetButton(context, tr, l.betMaxX(), l.betBtnY(), l.betMaxW(), l.betBtnH(), "MAX");
        drawActionButton(context, tr, l.dealX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Deal", session.canDeal(), 0xFF2C6CA5, mouseX, mouseY);
        drawActionButton(context, tr, l.hitX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Hit", session.canHit(), 0xFF2A8D4B, mouseX, mouseY);
        drawActionButton(context, tr, l.standX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Stand", session.canStand(), 0xFFA0393D, mouseX, mouseY);
        drawActionButton(context, tr, l.doubleX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Double", session.canDouble(), 0xFF9A7430, mouseX, mouseY);
        drawActionButton(context, tr, l.restartX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Restart", true, 0xFF666A77, mouseX, mouseY);
        drawActionButton(context, tr, l.resetBankX(), l.actionY(), l.actionBtnW(), l.actionBtnH(), "Reset", true, 0xFF614A67, mouseX, mouseY);
        context.drawTextWithShadow(tr, "Blackjack x1.5, Push returns bet.", l.panelX() + 8, l.panelY() + 74, 0xFFE7CF96);
        int statusColor = switch (session.stage()) {
            case ROUND_OVER -> STATUS_GOLD;
            case PLAYER_TURN -> STATUS_GREEN;
            case DEALER_TURN -> 0xFFF7DC9A;
            default -> 0xFFE6EEF9;
        };
        context.drawTextWithShadow(tr, trim(tr, session.status(), l.panelW() - 16), l.panelX() + 8, l.statusY(), statusColor);
    }
    private static void drawBetButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC314C74);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.fill(x, y, x + 1, y + h, 0xFF9ED0FF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9ED0FF);
        context.drawTextWithShadow(tr, text, x + (w - tr.getWidth(text)) / 2, y + 4, 0xFFFFFFFF);
    }
    private static void drawActionButton(
        DrawContext context,
        TextRenderer tr,
        int x,
        int y,
        int w,
        int h,
        String text,
        boolean enabled,
        int baseColor,
        int mouseX,
        int mouseY
    ) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, w, h);
        int bg = enabled ? (hover ? lighten(baseColor, 0.14f) : (0xCC000000 | (baseColor & 0x00FFFFFF))) : 0x885A5D66;
        int border = enabled ? lighten(baseColor, 0.32f) : 0xFF7A838D;
        int textColor = enabled ? 0xFFFFFFFF : 0xFFBDC3CB;
        context.fill(x, y, x + w, y + h, bg);
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);
        context.drawTextWithShadow(tr, text, x + (w - tr.getWidth(text)) / 2, y + 5, textColor);
    }
    private static void drawHeaderButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.drawTextWithShadow(tr, text, x + (w - tr.getWidth(text)) / 2, y + 3, 0xFFFFFFFF);
    }
    static void drawResizeHandle(DrawContext context, Layout l, int mouseX, int mouseY) {
        int color = inside(mouseX, mouseY, l.resizeX(), l.resizeY(), l.resizeSize(), l.resizeSize()) ? 0xFF9FD4FF : 0xFF5A86B4;
        context.fill(l.resizeX(), l.resizeY() + l.resizeSize() - 2, l.resizeX() + l.resizeSize(), l.resizeY() + l.resizeSize(), color);
        context.fill(l.resizeX() + l.resizeSize() - 2, l.resizeY(), l.resizeX() + l.resizeSize(), l.resizeY() + l.resizeSize(), color);
    }
    private static void drawCircleOutline(DrawContext context, int cx, int cy, int radius, int color) {
        int points = 100;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0 * i) / points;
            int x = cx + (int) Math.round(Math.cos(a) * radius);
            int y = cy + (int) Math.round(Math.sin(a) * radius);
            context.fill(x, y, x + 2, y + 2, color);
        }
    }
    static void drawChip(DrawContext context, Identifier texture, int x, int y, int size) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, 131, 131, 131, 131, 0xFFFFFFFF);
    }
    static int[] splitAmount(int amount) {
        int[] counts = new int[CHIP_VALUES.length];
        int remaining = Math.max(1, amount);
        for (int i = 0; i < CHIP_VALUES.length; i++) {
            counts[i] = remaining / CHIP_VALUES[i];
            remaining %= CHIP_VALUES[i];
        }
        return counts;
    }
    private static String trim(TextRenderer tr, String value, int maxWidth) {
        if (value == null) return "";
        if (tr.getWidth(value) <= maxWidth) return value;
        String text = value;
        while (!text.isEmpty() && tr.getWidth(text + "...") > maxWidth) text = text.substring(0, text.length() - 1);
        return text + "...";
    }
    private static int lighten(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        r = clamp((int) (r + (255 - r) * amount), 0, 255);
        g = clamp((int) (g + (255 - g) * amount), 0, 255);
        b = clamp((int) (b + (255 - b) * amount), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static int lerpColor(int from, int to, float t) {
        int fa = (from >>> 24) & 0xFF;
        int fr = (from >>> 16) & 0xFF;
        int fg = (from >>> 8) & 0xFF;
        int fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF;
        int tr = (to >>> 16) & 0xFF;
        int tg = (to >>> 8) & 0xFF;
        int tb = to & 0xFF;
        int a = (int) (fa + (ta - fa) * t);
        int r = (int) (fr + (tr - fr) * t);
        int g = (int) (fg + (tg - fg) * t);
        int b = (int) (fb + (tb - fb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
