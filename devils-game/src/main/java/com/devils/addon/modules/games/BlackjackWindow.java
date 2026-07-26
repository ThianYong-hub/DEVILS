package com.devils.addon.modules.games;
import static com.devils.addon.modules.games.BlackjackWindowLayout.clamp;
import static com.devils.addon.modules.games.BlackjackWindowLayout.computePlacements;
import static com.devils.addon.modules.games.BlackjackWindowLayout.inside;
import static com.devils.addon.modules.games.BlackjackWindowLayout.scaledMouseX;
import static com.devils.addon.modules.games.BlackjackWindowLayout.scaledMouseY;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.DARK_BG;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.STATUS_GOLD;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.STATUS_GREEN;
import com.devils.addon.modules.games.BlackjackWindowLayout.CardPlacement;
import com.devils.addon.modules.games.BlackjackWindowLayout.Layout;
import java.util.List;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
final class BlackjackWindow {
    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;
    private final BlackjackSession session = new BlackjackSession();
    private final BlackjackWindowAnimator animator = new BlackjackWindowAnimator(session);
    private int windowX;
    private int windowY;
    private int windowW;
    private int windowH;
    private boolean dragging;
    private boolean resizing;
    private int dragOffsetX;
    private int dragOffsetY;
    private int resizeStartX;
    private int resizeStartY;
    private int resizeStartW;
    private int resizeStartH;
    BlackjackWindow(int minW, int minH, int maxW, int maxH) {
        this.minW = minW;
        this.minH = minH;
        this.maxW = maxW;
        this.maxH = maxH;
    }
    void reset(int x, int y, int w, int h) {
        windowX = x;
        windowY = y;
        windowW = w;
        windowH = h;
        dragging = false;
        resizing = false;
        session.resetAll();
        animator.reset();
    }
    void restoreBounds(int x, int y, int w, int h) {
        windowX = x;
        windowY = y;
        windowW = w;
        windowH = h;
        stopInteraction();
    }
    void stopInteraction() {
        dragging = false;
        resizing = false;
    }
    void onTick() {
        session.onTick();
    }
    void render(DrawContext context, MinecraftClient mc, boolean pinned) {
        if (!shouldRender(mc, pinned)) return;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);
        Layout l = computeLayout();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.fill(l.x(), l.y(), l.x() + l.w(), l.y() + l.h(), DARK_BG);
        BlackjackWindowRenderer.drawHeader(context, tr, l, pinned);
        drawTable(context, tr, l);
        BlackjackWindowRenderer.drawFooter(context, tr, l, session, mouseX, mouseY);
        BlackjackWindowRenderer.drawResizeHandle(context, l, mouseX, mouseY);
    }
    boolean onMouse(
        MouseClickEvent event,
        MinecraftClient mc,
        boolean pinned,
        Consumer<Boolean> setPinned,
        Runnable cycleGame,
        Runnable closeOverlay
    ) {
        if (!shouldRender(mc, pinned)) return false;
        if (event.button() != 0) return false;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        if (event.action == KeyAction.Release) {
            dragging = false;
            resizing = false;
            return false;
        }
        if (event.action != KeyAction.Press) return false;
        Layout l = computeLayout();
        if (!inside(mouseX, mouseY, l.x(), l.y(), l.w(), l.h())) return false;
        if (inside(mouseX, mouseY, l.pinX(), l.pinY(), l.btnW(), l.btnH())) {
            setPinned.accept(!pinned);
            return true;
        }
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Blackjack") : 58;
        if (inside(mouseX, mouseY, l.x() + 6, l.y() + 2, titleW + 2, l.btnH())) {
            cycleGame.run();
            return true;
        }
        if (inside(mouseX, mouseY, l.closeX(), l.closeY(), l.btnW(), l.btnH())) {
            closeOverlay.run();
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.resizeX(), l.resizeY(), l.resizeSize(), l.resizeSize())) {
            resizing = true;
            dragging = false;
            resizeStartX = mouseX;
            resizeStartY = mouseY;
            resizeStartW = windowW;
            resizeStartH = windowH;
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.x(), l.y(), l.w(), l.headerH())) {
            dragging = true;
            resizing = false;
            dragOffsetX = mouseX - windowX;
            dragOffsetY = mouseY - windowY;
            return true;
        }
        if (inside(mouseX, mouseY, l.betMinusX(), l.betBtnY(), l.betBtnW(), l.betBtnH())) {
            session.decreaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betPlusX(), l.betBtnY(), l.betBtnW(), l.betBtnH())) {
            session.increaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betMaxX(), l.betBtnY(), l.betMaxW(), l.betBtnH())) {
            session.maxBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.dealX(), l.actionY(), l.actionBtnW(), l.actionBtnH()) && session.canDeal()) {
            session.deal();
            return true;
        }
        if (inside(mouseX, mouseY, l.hitX(), l.actionY(), l.actionBtnW(), l.actionBtnH()) && session.canHit()) {
            session.hit();
            return true;
        }
        if (inside(mouseX, mouseY, l.standX(), l.actionY(), l.actionBtnW(), l.actionBtnH()) && session.canStand()) {
            session.stand();
            return true;
        }
        if (inside(mouseX, mouseY, l.doubleX(), l.actionY(), l.actionBtnW(), l.actionBtnH()) && session.canDouble()) {
            session.doubleDown();
            return true;
        }
        if (inside(mouseX, mouseY, l.restartX(), l.actionY(), l.actionBtnW(), l.actionBtnH())) {
            session.restartRound();
            return true;
        }
        if (inside(mouseX, mouseY, l.resetBankX(), l.actionY(), l.actionBtnW(), l.actionBtnH())) {
            session.resetAll();
            return true;
        }
        return true;
    }
    private void drawTable(DrawContext context, TextRenderer tr, Layout l) {
        long now = System.currentTimeMillis();
        BlackjackWindowRenderer.drawFeltTable(context, l);
        BlackjackWindowRenderer.drawTopChipRail(context, l);
        BlackjackSession.HandScore dealerScoreVisible = session.dealerScoreVisible();
        BlackjackSession.HandScore dealerScoreFull = session.dealerScoreFull();
        BlackjackSession.HandScore playerScore = session.playerScore();
        String dealerTitle = session.dealerHoleHidden()
            ? "Dealer: " + dealerScoreVisible.total() + " + ?"
            : "Dealer: " + scoreText(dealerScoreFull);
        String playerTitle = "Player: " + scoreText(playerScore);
        context.drawTextWithShadow(tr, dealerTitle, l.tableX() + 10, l.dealerLabelY(), STATUS_GOLD);
        context.drawTextWithShadow(tr, playerTitle, l.tableX() + 10, l.playerLabelY(), STATUS_GREEN);
        List<CardPlacement> dealerPlacements = computePlacements(session.dealerCards(), true, l.cardsAreaX(), l.dealerCardsY(), l.cardsAreaW(), l.cardW(), l.cardH(), l.cardGap());
        List<CardPlacement> playerPlacements = computePlacements(session.playerCards(), false, l.cardsAreaX(), l.playerCardsY(), l.cardsAreaW(), l.cardW(), l.cardH(), l.cardGap());
        animator.syncAnimationState(l, dealerPlacements, playerPlacements, now);
        BlackjackWindowRenderer.drawBetCircles(context, l);
        int animatedAmount = animator.updateBetTween(session.roundBet() > 0 ? session.roundBet() : session.baseBet(), now);
        BlackjackWindowRenderer.drawMainBetStack(context, l, animatedAmount);
        BlackjackWindowRenderer.drawCornerStacks(context, tr, l, animatedAmount);
        animator.drawStaticCards(context, dealerPlacements, l, now);
        animator.drawStaticCards(context, playerPlacements, l, now);
        animator.drawCardGhosts(context, now, l);
        animator.drawCardFlights(context, now, l);
        animator.drawChipFlights(context, now, l);
        animator.drawDeckShoe(context, l, now);
    }
    private void updateWindowTransform(MinecraftClient mc, int mouseX, int mouseY) {
        int sw = mc.getWindow() == null ? 1920 : mc.getWindow().getScaledWidth();
        int sh = mc.getWindow() == null ? 1080 : mc.getWindow().getScaledHeight();
        if (dragging) {
            windowX = mouseX - dragOffsetX;
            windowY = mouseY - dragOffsetY;
        } else if (resizing) {
            windowW = clamp(resizeStartW + (mouseX - resizeStartX), minW, Math.min(maxW, sw - 4));
            windowH = clamp(resizeStartH + (mouseY - resizeStartY), minH, Math.min(maxH, sh - 4));
        }
        windowX = clamp(windowX, 0, Math.max(0, sw - windowW));
        windowY = clamp(windowY, 0, Math.max(0, sh - windowH));
    }
    private Layout computeLayout() {
        return BlackjackWindowLayout.compute(windowX, windowY, windowW, windowH);
    }
    private static String scoreText(BlackjackSession.HandScore score) {
        if (score == null) return "?";
        if (score.bust()) return score.total() + " (bust)";
        if (score.blackjack()) return "Blackjack";
        if (score.soft()) return score.total() + " (soft)";
        return Integer.toString(score.total());
    }
    private static boolean shouldRender(MinecraftClient mc, boolean pinned) {
        return mc != null && mc.player != null;
    }
}
