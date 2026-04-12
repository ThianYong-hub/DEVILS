package com.example.addon.modules.games;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
final class BlackjackWindow {
    private static final int DARK_BG = 0xD00B0F15;
    private static final int HEADER_BG = 0xD8322A1F;
    private static final int HEADER_BORDER = 0xFFC18845;
    private static final int TABLE_BORDER = 0xFF7A4A27;
    private static final int PANEL_BG = 0xD0181210;
    private static final int PANEL_BORDER = 0xFF9D6F44;
    private static final int STATUS_GOLD = 0xFFFFE29A;
    private static final int STATUS_GREEN = 0xFFC9F4AE;
    private static final int CARD_W = 132;
    private static final int CARD_H = 186;
    private static final int CARD_BACK_W = 132;
    private static final int CARD_BACK_H = 183;
    private static final Identifier CARD_BACK = Identifier.of("devils-game", "textures/games/blackjack/cards/backgroundred.png");
    private static final Identifier CHIP_WHITE = Identifier.of("devils-game", "textures/games/blackjack/chips/chipwhite.png");
    private static final Identifier CHIP_RED = Identifier.of("devils-game", "textures/games/blackjack/chips/chipred.png");
    private static final Identifier CHIP_GREEN = Identifier.of("devils-game", "textures/games/blackjack/chips/chipgreen.png");
    private static final Identifier CHIP_BLACK = Identifier.of("devils-game", "textures/games/blackjack/chips/chipblack.png");
    private static final Identifier CHIP_PURPLE = Identifier.of("devils-game", "textures/games/blackjack/chips/chippurple.png");
    private static final Identifier CHIP_YELLOW = Identifier.of("devils-game", "textures/games/blackjack/chips/chipyellow.png");
    private static final Identifier CHIP_ORANGE = Identifier.of("devils-game", "textures/games/blackjack/chips/chiporange.png");
    private static final Identifier CHIP_BLUE = Identifier.of("devils-game", "textures/games/blackjack/chips/chipblue.png");
    private static final int[] CHIP_VALUES = {500, 100, 25, 5, 1};
    private static final Identifier[] BET_CHIPS = {CHIP_PURPLE, CHIP_BLACK, CHIP_GREEN, CHIP_RED, CHIP_WHITE};
    private static final long CARD_FLY_MS = 420L;
    private static final long CARD_RETURN_MS = 360L;
    private static final long CHIP_FLY_MS = 560L;
    private static final long BET_TWEEN_MS = 420L;
    private static final long SHUFFLE_MS = 1200L;
    private static final long CARD_DEAL_STAGGER_MS = 150L;
    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;
    private final BlackjackSession session = new BlackjackSession();
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
    private final List<CardFlight> cardFlights = new ArrayList<>();
    private final List<CardGhost> cardGhosts = new ArrayList<>();
    private final List<ChipFlight> chipFlights = new ArrayList<>();
    private final IdentityHashMap<BlackjackSession.Card, CardPose> previousCardPoses = new IdentityHashMap<>();
    private List<BlackjackSession.Card> lastDealerCards = List.of();
    private List<BlackjackSession.Card> lastPlayerCards = List.of();
    private BlackjackSession.Stage lastStage = BlackjackSession.Stage.BETTING;
    private int lastRoundBet;
    private int betTweenFrom = 25;
    private int betTweenTo = 25;
    private long betTweenStartMs;
    private long shuffleAnimUntilMs;
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
        cardFlights.clear();
        cardGhosts.clear();
        chipFlights.clear();
        previousCardPoses.clear();
        lastDealerCards = List.of();
        lastPlayerCards = List.of();
        lastStage = BlackjackSession.Stage.BETTING;
        lastRoundBet = 0;
        betTweenFrom = session.baseBet();
        betTweenTo = session.baseBet();
        betTweenStartMs = System.currentTimeMillis();
        shuffleAnimUntilMs = 0L;
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
        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, DARK_BG);
        drawHeader(context, tr, l, pinned);
        drawTable(context, tr, l);
        drawFooter(context, tr, l, mouseX, mouseY);
        drawResizeHandle(context, l, mouseX, mouseY);
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
        if (!inside(mouseX, mouseY, l.x, l.y, l.w, l.h)) return false;
        if (inside(mouseX, mouseY, l.pinX, l.pinY, l.btnW, l.btnH)) {
            setPinned.accept(!pinned);
            return true;
        }
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Blackjack") : 58;
        if (inside(mouseX, mouseY, l.x + 6, l.y + 2, titleW + 2, l.btnH)) {
            cycleGame.run();
            return true;
        }
        if (inside(mouseX, mouseY, l.closeX, l.closeY, l.btnW, l.btnH)) {
            closeOverlay.run();
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize)) {
            resizing = true;
            dragging = false;
            resizeStartX = mouseX;
            resizeStartY = mouseY;
            resizeStartW = windowW;
            resizeStartH = windowH;
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.x, l.y, l.w, l.headerH)) {
            dragging = true;
            resizing = false;
            dragOffsetX = mouseX - windowX;
            dragOffsetY = mouseY - windowY;
            return true;
        }
        if (inside(mouseX, mouseY, l.betMinusX, l.betBtnY, l.betBtnW, l.betBtnH)) {
            session.decreaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betPlusX, l.betBtnY, l.betBtnW, l.betBtnH)) {
            session.increaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betMaxX, l.betBtnY, l.betMaxW, l.betBtnH)) {
            session.maxBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.dealX, l.actionY, l.actionBtnW, l.actionBtnH) && session.canDeal()) {
            session.deal();
            return true;
        }
        if (inside(mouseX, mouseY, l.hitX, l.actionY, l.actionBtnW, l.actionBtnH) && session.canHit()) {
            session.hit();
            return true;
        }
        if (inside(mouseX, mouseY, l.standX, l.actionY, l.actionBtnW, l.actionBtnH) && session.canStand()) {
            session.stand();
            return true;
        }
        if (inside(mouseX, mouseY, l.doubleX, l.actionY, l.actionBtnW, l.actionBtnH) && session.canDouble()) {
            session.doubleDown();
            return true;
        }
        if (inside(mouseX, mouseY, l.restartX, l.actionY, l.actionBtnW, l.actionBtnH)) {
            session.restartRound();
            return true;
        }
        if (inside(mouseX, mouseY, l.resetBankX, l.actionY, l.actionBtnW, l.actionBtnH)) {
            session.resetAll();
            return true;
        }
        return true;
    }
    private void drawHeader(DrawContext context, TextRenderer tr, Layout l, boolean pinned) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);
        context.drawTextWithShadow(tr, "Blackjack", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        drawHeaderButton(context, tr, l.closeX, l.closeY, l.btnW, l.btnH, "X");
    }
    private void drawTable(DrawContext context, TextRenderer tr, Layout l) {
        long now = System.currentTimeMillis();
        drawFeltTable(context, l);
        drawTopChipRail(context, l);
        BlackjackSession.HandScore dealerScoreVisible = session.dealerScoreVisible();
        BlackjackSession.HandScore dealerScoreFull = session.dealerScoreFull();
        BlackjackSession.HandScore playerScore = session.playerScore();
        String dealerTitle = session.dealerHoleHidden()
            ? "Dealer: " + dealerScoreVisible.total() + " + ?"
            : "Dealer: " + scoreText(dealerScoreFull);
        String playerTitle = "Player: " + scoreText(playerScore);
        context.drawTextWithShadow(tr, dealerTitle, l.tableX + 10, l.dealerLabelY, STATUS_GOLD);
        context.drawTextWithShadow(tr, playerTitle, l.tableX + 10, l.playerLabelY, STATUS_GREEN);
        List<CardPlacement> dealerPlacements = computePlacements(session.dealerCards(), true, l.cardsAreaX, l.dealerCardsY, l.cardsAreaW, l.cardW, l.cardH, l.cardGap);
        List<CardPlacement> playerPlacements = computePlacements(session.playerCards(), false, l.cardsAreaX, l.playerCardsY, l.cardsAreaW, l.cardW, l.cardH, l.cardGap);
        syncAnimationState(l, dealerPlacements, playerPlacements, now);
        drawBetCircles(context, l);
        int animatedAmount = updateBetTween(session.roundBet() > 0 ? session.roundBet() : session.baseBet(), now);
        drawMainBetStack(context, l, animatedAmount);
        drawCornerStacks(context, tr, l, animatedAmount);
        drawStaticCards(context, dealerPlacements, l, now);
        drawStaticCards(context, playerPlacements, l, now);
        drawCardGhosts(context, now, l);
        drawCardFlights(context, now, l);
        drawChipFlights(context, now, l);
        drawDeckShoe(context, l, now);
    }
    private void drawFeltTable(DrawContext context, Layout l) {
        context.fill(l.tableX, l.tableY, l.tableX + l.tableW, l.tableY + l.tableH, 0xFF5D1F23);
        for (int i = 0; i < 14; i++) {
            float t = i / 13f;
            float mid = 1f - Math.abs((t * 2f) - 1f);
            int color = lerpColor(0xFF4A171A, 0xFF8B2D34, 0.14f + mid * 0.65f);
            int y1 = l.tableY + (l.tableH * i) / 14;
            int y2 = l.tableY + (l.tableH * (i + 1)) / 14;
            context.fill(l.tableX + 2, y1, l.tableX + l.tableW - 2, y2, color);
        }
        context.fill(l.tableX + 3, l.tableY + 3, l.tableX + l.tableW - 3, l.tableY + l.tableH - 3, 0x22000000);
        context.fill(l.tableX, l.tableY, l.tableX + l.tableW, l.tableY + 1, TABLE_BORDER);
        context.fill(l.tableX, l.tableY + l.tableH - 1, l.tableX + l.tableW, l.tableY + l.tableH, TABLE_BORDER);
        context.fill(l.tableX, l.tableY, l.tableX + 1, l.tableY + l.tableH, TABLE_BORDER);
        context.fill(l.tableX + l.tableW - 1, l.tableY, l.tableX + l.tableW, l.tableY + l.tableH, TABLE_BORDER);
        String title = "BLACKJACK";
        String rule = "PAYS 3 TO 2";
        int titleY = l.topBandY + 3;
        int ruleY = l.topBandY + 13;
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, title, l.tableX + (l.tableW - MinecraftClient.getInstance().textRenderer.getWidth(title)) / 2, titleY, 0xFFF8D9BF);
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, rule, l.tableX + (l.tableW - MinecraftClient.getInstance().textRenderer.getWidth(rule)) / 2, ruleY, 0xFFECC8AB);
    }
    private void drawTopChipRail(DrawContext context, Layout l) {
        Identifier[] rail = {CHIP_WHITE, CHIP_RED, CHIP_GREEN, CHIP_BLACK, CHIP_PURPLE, CHIP_YELLOW, CHIP_ORANGE, CHIP_BLUE};
        int size = l.chipSize;
        int gap = size + 4;
        int startX = l.tableX + (l.tableW - (rail.length * gap - 4)) / 2;
        for (int i = 0; i < rail.length; i++) {
            drawChip(context, rail[i], startX + i * gap, l.chipRailY, size);
        }
    }
    private void drawBetCircles(DrawContext context, Layout l) {
        drawCircleOutline(context, l.betCenterX, l.betCenterY, l.betRadius, 0xFFDEBC95);
        drawCircleOutline(context, l.leftBetX, l.betCenterY, l.betRadius - 3, 0x9FD6B78F);
        drawCircleOutline(context, l.rightBetX, l.betCenterY, l.betRadius - 3, 0x9FD6B78F);
    }
    private void drawMainBetStack(DrawContext context, Layout l, int amount) {
        int[] counts = splitAmount(amount);
        int size = l.chipSize;
        int x = l.betCenterX - size / 2;
        int baseY = l.betCenterY + l.betRadius - size / 2 - 2;
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
    private void drawCornerStacks(DrawContext context, TextRenderer tr, Layout l, int amount) {
        int[] counts = splitAmount(amount);
        int size = Math.max(16, l.chipSize - 10);
        int leftX = l.tableX + l.tablePad + 4;
        int rightX = l.tableX + l.tableW - l.tablePad - size - 4;
        int baseY = l.tableY + l.tableH - 14;
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
    private void drawDeckShoe(DrawContext context, Layout l, long now) {
        boolean shuffling = now < shuffleAnimUntilMs;
        int jitterX = 0;
        int jitterY = 0;
        if (shuffling) {
            double phase = 1.0 - ((shuffleAnimUntilMs - now) / (double) SHUFFLE_MS);
            double envelope = Math.sin(clamp01(phase) * Math.PI);
            jitterX = (int) Math.round(Math.sin(now * 0.026) * 1.6 * envelope);
            jitterY = (int) Math.round(Math.cos(now * 0.023) * 1.2 * envelope);
        }
        for (int i = 0; i < 3; i++) {
            int dx = l.deckX - i + (shuffling ? ((i & 1) == 0 ? jitterX : -jitterX) : 0);
            int dy = l.deckY - i + jitterY;
            int tint = shuffling && i > 0 ? 0xF2FFFFFF : 0xFFFFFFFF;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, CARD_BACK, dx, dy, 0, 0, l.deckW, l.deckH, CARD_BACK_W, CARD_BACK_H, CARD_BACK_W, CARD_BACK_H, tint);
        }
    }
    private List<CardPlacement> computePlacements(
        List<BlackjackSession.Card> cards,
        boolean dealerHand,
        int baseX,
        int baseY,
        int areaW,
        int cardW,
        int cardH,
        int cardGap
    ) {
        List<CardPlacement> placements = new ArrayList<>();
        if (cards.isEmpty()) return placements;
        int gap = cardGap;
        int width = cardW + Math.max(0, cards.size() - 1) * gap;
        if (width > areaW) {
            gap = Math.max(8, (areaW - cardW) / Math.max(1, cards.size() - 1));
            width = cardW + Math.max(0, cards.size() - 1) * gap;
        }
        int x = baseX + Math.max(0, (areaW - width) / 2);
        for (int i = 0; i < cards.size(); i++) {
            placements.add(new CardPlacement(cards.get(i), x + i * gap, baseY, dealerHand));
        }
        return placements;
    }
    private void drawStaticCards(DrawContext context, List<CardPlacement> placements, Layout l, long now) {
        cardFlights.removeIf(flight -> now >= flight.endMs);
        Set<BlackjackSession.Card> active = new HashSet<>();
        for (CardFlight flight : cardFlights) active.add(flight.card);
        for (CardPlacement placement : placements) {
            if (active.contains(placement.card)) continue;
            boolean hidden = placement.dealerHand && isDealerHoleCard(placement.card);
            drawCard(context, placement.card, placement.x, placement.y, l.cardW, l.cardH, hidden, 0xFFFFFFFF);
        }
    }
    private void drawCardFlights(DrawContext context, long now, Layout l) {
        Iterator<CardFlight> it = cardFlights.iterator();
        while (it.hasNext()) {
            CardFlight flight = it.next();
            if (now < flight.startMs) continue;
            double t = (now - flight.startMs) / (double) Math.max(1L, flight.endMs - flight.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeOutCubic(clamp01(t));
            int x = (int) Math.round(lerp(flight.fromX, flight.toX, eased));
            int y = (int) Math.round(lerp(flight.fromY, flight.toY, eased) - Math.sin(Math.PI * eased) * 8.0);
            boolean hidden = flight.dealerHand && isDealerHoleCard(flight.card);
            drawCard(context, flight.card, x, y, l.cardW, l.cardH, hidden, 0xFFFFFFFF);
        }
    }
    private void drawCardGhosts(DrawContext context, long now, Layout l) {
        Iterator<CardGhost> it = cardGhosts.iterator();
        while (it.hasNext()) {
            CardGhost ghost = it.next();
            double t = (now - ghost.startMs) / (double) Math.max(1L, ghost.endMs - ghost.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeInOut(clamp01(t));
            int x = (int) Math.round(lerp(ghost.fromX, ghost.toX, eased));
            int y = (int) Math.round(lerp(ghost.fromY, ghost.toY, eased));
            int alpha = clamp((int) Math.round((1.0 - eased) * 255.0), 0, 255);
            int tint = (alpha << 24) | 0x00FFFFFF;
            drawCard(context, ghost.card, x, y, l.cardW, l.cardH, ghost.dealerHand && isDealerHoleCard(ghost.card), tint);
        }
    }
    private void drawChipFlights(DrawContext context, long now, Layout l) {
        int size = Math.max(16, l.chipSize - 4);
        Iterator<ChipFlight> it = chipFlights.iterator();
        while (it.hasNext()) {
            ChipFlight flight = it.next();
            double t = (now - flight.startMs) / (double) Math.max(1L, flight.endMs - flight.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeInOut(clamp01(t));
            int cx = (int) Math.round(lerp(flight.fromX, flight.toX, eased));
            int cy = (int) Math.round(lerp(flight.fromY, flight.toY, eased) - Math.sin(Math.PI * eased) * 14.0);
            int[] split = splitAmount(flight.amount);
            int drawn = 0;
            for (int i = 0; i < split.length && drawn < 6; i++) {
                for (int c = 0; c < split[i] && drawn < 6; c++) {
                    drawChip(context, BET_CHIPS[i], cx - size / 2 + ((drawn & 1) == 0 ? 0 : 1), cy - drawn * 2, size);
                    drawn++;
                }
            }
        }
    }
    private void syncAnimationState(Layout l, List<CardPlacement> dealerPlacements, List<CardPlacement> playerPlacements, long now) {
        List<CardPlacement> all = new ArrayList<>(dealerPlacements.size() + playerPlacements.size());
        all.addAll(dealerPlacements);
        all.addAll(playerPlacements);
        Set<BlackjackSession.Card> currentCards = new HashSet<>();
        Set<BlackjackSession.Card> previousCards = new HashSet<>();
        for (CardPlacement placement : all) currentCards.add(placement.card);
        previousCards.addAll(lastDealerCards);
        previousCards.addAll(lastPlayerCards);
        int deckCardX = l.deckX + l.deckW / 2 - l.cardW / 2;
        int deckCardY = l.deckY + l.deckH / 2 - l.cardH / 2;
        List<CardPlacement> newlyAppeared = new ArrayList<>();
        for (CardPlacement placement : all) {
            if (!previousCards.contains(placement.card)) {
                newlyAppeared.add(placement);
            } else {
                for (CardFlight flight : cardFlights) {
                    if (flight.card == placement.card) {
                        flight.toX = placement.x;
                        flight.toY = placement.y;
                        flight.dealerHand = placement.dealerHand;
                    }
                }
            }
        }
        List<CardPlacement> orderedNew = orderNewCardPlacements(newlyAppeared, dealerPlacements, playerPlacements, previousCards);
        for (int i = 0; i < orderedNew.size(); i++) {
            CardPlacement placement = orderedNew.get(i);
            long start = now + i * CARD_DEAL_STAGGER_MS;
            long end = start + CARD_FLY_MS;
            cardFlights.add(new CardFlight(placement.card, placement.dealerHand, deckCardX, deckCardY, placement.x, placement.y, start, end));
            shuffleAnimUntilMs = Math.max(shuffleAnimUntilMs, end + SHUFFLE_MS / 3);
        }
        for (BlackjackSession.Card card : previousCards) {
            if (currentCards.contains(card)) continue;
            CardPose pose = previousCardPoses.get(card);
            if (pose != null) {
                cardGhosts.add(new CardGhost(card, pose.dealerHand, pose.x, pose.y, deckCardX, deckCardY, now, now + CARD_RETURN_MS));
            }
        }
        cardFlights.removeIf(flight -> !currentCards.contains(flight.card) && now > flight.startMs + 50L);
        BlackjackSession.Stage stage = session.stage();
        if (stage != lastStage) {
            if ((lastStage == BlackjackSession.Stage.BETTING || lastStage == BlackjackSession.Stage.ROUND_OVER) && stage == BlackjackSession.Stage.PLAYER_TURN) {
                shuffleAnimUntilMs = Math.max(shuffleAnimUntilMs, now + SHUFFLE_MS);
                spawnChipFlight(l.bankChipX, l.bankChipY, l.betCenterX, l.betCenterY, Math.max(1, session.roundBet()), now);
            } else if ((lastStage == BlackjackSession.Stage.PLAYER_TURN || lastStage == BlackjackSession.Stage.DEALER_TURN) && stage == BlackjackSession.Stage.ROUND_OVER && lastRoundBet > 0) {
                String status = session.status().toLowerCase();
                boolean toBank = status.contains("win") || status.contains("push") || status.contains("blackjack");
                int tx = toBank ? l.bankChipX : l.houseChipX;
                int ty = toBank ? l.bankChipY : l.houseChipY;
                spawnChipFlight(l.betCenterX, l.betCenterY, tx, ty, Math.max(1, lastRoundBet), now);
            }
        }
        previousCardPoses.clear();
        for (CardPlacement placement : all) {
            previousCardPoses.put(placement.card, new CardPose(placement.x, placement.y, placement.dealerHand));
        }
        lastDealerCards = new ArrayList<>(session.dealerCards());
        lastPlayerCards = new ArrayList<>(session.playerCards());
        lastStage = stage;
        lastRoundBet = session.roundBet();
    }
    private List<CardPlacement> orderNewCardPlacements(
        List<CardPlacement> newlyAppeared,
        List<CardPlacement> dealerPlacements,
        List<CardPlacement> playerPlacements,
        Set<BlackjackSession.Card> previousCards
    ) {
        if (newlyAppeared.isEmpty()) return List.of();
        Set<BlackjackSession.Card> fresh = new HashSet<>();
        for (CardPlacement placement : newlyAppeared) fresh.add(placement.card);
        List<CardPlacement> ordered = new ArrayList<>(newlyAppeared.size());
        if (previousCards.isEmpty() && dealerPlacements.size() >= 2 && playerPlacements.size() >= 2) {
            CardPlacement p0 = playerPlacements.get(0);
            CardPlacement d0 = dealerPlacements.get(0);
            CardPlacement p1 = playerPlacements.get(1);
            CardPlacement d1 = dealerPlacements.get(1);
            if (fresh.contains(p0.card)) ordered.add(p0);
            if (fresh.contains(d0.card)) ordered.add(d0);
            if (fresh.contains(p1.card)) ordered.add(p1);
            if (fresh.contains(d1.card)) ordered.add(d1);
        }
        for (CardPlacement placement : playerPlacements) {
            if (fresh.contains(placement.card) && !ordered.contains(placement)) ordered.add(placement);
        }
        for (CardPlacement placement : dealerPlacements) {
            if (fresh.contains(placement.card) && !ordered.contains(placement)) ordered.add(placement);
        }
        return ordered;
    }
    private int updateBetTween(int targetAmount, long now) {
        if (targetAmount != betTweenTo) {
            betTweenFrom = currentBetTweenAmount(now);
            betTweenTo = targetAmount;
            betTweenStartMs = now;
        }
        return currentBetTweenAmount(now);
    }
    private int currentBetTweenAmount(long now) {
        if (betTweenFrom == betTweenTo) return betTweenTo;
        double t = (now - betTweenStartMs) / (double) BET_TWEEN_MS;
        if (t >= 1.0) return betTweenTo;
        double eased = easeOutCubic(clamp01(t));
        return Math.max(1, (int) Math.round(lerp(betTweenFrom, betTweenTo, eased)));
    }
    private boolean isDealerHoleCard(BlackjackSession.Card card) {
        List<BlackjackSession.Card> dealer = session.dealerCards();
        return session.dealerHoleHidden() && dealer.size() > 1 && dealer.get(1) == card;
    }
    private void spawnChipFlight(int fromX, int fromY, int toX, int toY, int amount, long now) {
        chipFlights.add(new ChipFlight(fromX, fromY, toX, toY, Math.max(1, amount), now, now + CHIP_FLY_MS));
    }
    private void drawCard(DrawContext context, BlackjackSession.Card card, int x, int y, int w, int h, boolean hidden, int tint) {
        int alpha = (tint >>> 24) & 0xFF;
        int shadowAlpha = Math.max(0, alpha - 80);
        context.fill(x + 2, y + 3, x + w + 2, y + h + 3, (shadowAlpha << 24));
        Identifier texture = hidden ? CARD_BACK : card.texture();
        int tw = hidden ? CARD_BACK_W : CARD_W;
        int th = hidden ? CARD_BACK_H : CARD_H;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, tw, th, tw, th, tint);
    }
    private void drawFooter(DrawContext context, TextRenderer tr, Layout l, int mouseX, int mouseY) {
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BG);
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + 1, PANEL_BORDER);
        context.fill(l.panelX, l.panelY + l.panelH - 1, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX, l.panelY, l.panelX + 1, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX + l.panelW - 1, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        String bankroll = "Bankroll: " + session.bankroll();
        String summary = "W/L/P " + session.wins() + "/" + session.losses() + "/" + session.pushes() + "   Hands " + session.handsPlayed();
        context.drawTextWithShadow(tr, bankroll, l.panelX + 8, l.panelY + 8, 0xFFE5F0FF);
        String trimmedSummary = trim(tr, summary, Math.max(80, l.panelW - 230));
        context.drawTextWithShadow(tr, trimmedSummary, l.panelX + l.panelW - 8 - tr.getWidth(trimmedSummary), l.panelY + 8, 0xFFD4E0F0);
        String betLine = "Bet: " + session.baseBet() + "   Round: " + session.roundBet();
        context.drawTextWithShadow(tr, betLine, l.panelX + 8, l.panelY + 24, 0xFFE7F2FF);
        drawBetButton(context, tr, l.betMinusX, l.betBtnY, l.betBtnW, l.betBtnH, "-");
        drawBetButton(context, tr, l.betPlusX, l.betBtnY, l.betBtnW, l.betBtnH, "+");
        drawBetButton(context, tr, l.betMaxX, l.betBtnY, l.betMaxW, l.betBtnH, "MAX");
        drawActionButton(context, tr, l.dealX, l.actionY, l.actionBtnW, l.actionBtnH, "Deal", session.canDeal(), 0xFF2C6CA5, mouseX, mouseY);
        drawActionButton(context, tr, l.hitX, l.actionY, l.actionBtnW, l.actionBtnH, "Hit", session.canHit(), 0xFF2A8D4B, mouseX, mouseY);
        drawActionButton(context, tr, l.standX, l.actionY, l.actionBtnW, l.actionBtnH, "Stand", session.canStand(), 0xFFA0393D, mouseX, mouseY);
        drawActionButton(context, tr, l.doubleX, l.actionY, l.actionBtnW, l.actionBtnH, "Double", session.canDouble(), 0xFF9A7430, mouseX, mouseY);
        drawActionButton(context, tr, l.restartX, l.actionY, l.actionBtnW, l.actionBtnH, "Restart", true, 0xFF666A77, mouseX, mouseY);
        drawActionButton(context, tr, l.resetBankX, l.actionY, l.actionBtnW, l.actionBtnH, "Reset", true, 0xFF614A67, mouseX, mouseY);
        context.drawTextWithShadow(tr, "Blackjack x1.5, Push returns bet.", l.panelX + 8, l.panelY + 74, 0xFFE7CF96);
        int statusColor = switch (session.stage()) {
            case ROUND_OVER -> STATUS_GOLD;
            case PLAYER_TURN -> STATUS_GREEN;
            case DEALER_TURN -> 0xFFF7DC9A;
            default -> 0xFFE6EEF9;
        };
        context.drawTextWithShadow(tr, trim(tr, session.status(), l.panelW - 16), l.panelX + 8, l.statusY, statusColor);
    }
    private void drawBetButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC314C74);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.fill(x, y, x + 1, y + h, 0xFF9ED0FF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9ED0FF);
        context.drawTextWithShadow(tr, text, x + (w - tr.getWidth(text)) / 2, y + 4, 0xFFFFFFFF);
    }
    private void drawActionButton(
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
    private void drawHeaderButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.drawTextWithShadow(tr, text, x + (w - tr.getWidth(text)) / 2, y + 3, 0xFFFFFFFF);
    }
    private void drawResizeHandle(DrawContext context, Layout l, int mouseX, int mouseY) {
        int color = inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize) ? 0xFF9FD4FF : 0xFF5A86B4;
        context.fill(l.resizeX, l.resizeY + l.resizeSize - 2, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
        context.fill(l.resizeX + l.resizeSize - 2, l.resizeY, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
    }
    private void drawCircleOutline(DrawContext context, int cx, int cy, int radius, int color) {
        int points = 100;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0 * i) / points;
            int x = cx + (int) Math.round(Math.cos(a) * radius);
            int y = cy + (int) Math.round(Math.sin(a) * radius);
            context.fill(x, y, x + 2, y + 2, color);
        }
    }
    private void drawChip(DrawContext context, Identifier texture, int x, int y, int size) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, 131, 131, 131, 131, 0xFFFFFFFF);
    }
    private int[] splitAmount(int amount) {
        int[] counts = new int[CHIP_VALUES.length];
        int remaining = Math.max(1, amount);
        for (int i = 0; i < CHIP_VALUES.length; i++) {
            counts[i] = remaining / CHIP_VALUES[i];
            remaining %= CHIP_VALUES[i];
        }
        return counts;
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
        int headerH = 20;
        int pad = 8;
        int panelH = 128;
        int tableX = windowX + pad;
        int tableY = windowY + headerH + pad;
        int tableW = windowW - pad * 2;
        int tableH = windowH - headerH - panelH - pad * 3;
        int tablePad = 10;
        int topBandH = clamp(tableH / 6, 44, 64);
        int midBandH = clamp(tableH / 4, 62, 92);
        int lanes = Math.max(80, tableH - topBandH - midBandH);
        int dealerBandH = lanes / 2;
        int playerBandH = lanes - dealerBandH;
        int topBandY = tableY;
        int dealerBandY = topBandY + topBandH;
        int midBandY = dealerBandY + dealerBandH;
        int playerBandY = midBandY + midBandH;
        int cardsAreaX = tableX + tablePad + 6;
        int cardsAreaW = tableW - (tablePad * 2) - 12;
        int cardHByLane = clamp(Math.min(dealerBandH, playerBandH) - 20, 58, 124);
        int cardWByLane = (cardHByLane * CARD_W) / CARD_H;
        int cardWByTable = clamp((tableW - 48) / 7, 46, 92);
        int cardW = Math.max(46, Math.min(cardWByLane, cardWByTable));
        int cardH = (cardW * CARD_H) / CARD_W;
        int cardGap = Math.max(12, cardW / 2);
        int dealerLabelY = dealerBandY + 3;
        int dealerCardsY = dealerBandY + 16;
        int playerLabelY = playerBandY + 4;
        int playerCardsY = playerBandY + 16;
        int betCenterX = tableX + tableW / 2;
        int betCenterY = midBandY + midBandH / 2 + 1;
        int betRadius = clamp(cardW / 2 + 8, 30, 52);
        int leftBetX = betCenterX - betRadius * 3;
        int rightBetX = betCenterX + betRadius * 3;
        int chipSize = clamp(cardW / 2, 24, 34);
        int chipRailY = topBandY + 20;
        int deckW = Math.max(26, cardW / 2 + 10);
        int deckH = Math.max(40, cardH / 2 + 10);
        int deckX = tableX + tableW - tablePad - deckW;
        int deckY = dealerBandY + 8;
        int houseChipX = tableX + tableW - tablePad - 20;
        int houseChipY = topBandY + topBandH - 8;
        int panelX = tableX;
        int panelY = tableY + tableH + pad;
        int panelW = tableW;
        int bankChipX = panelX + panelW - 50;
        int bankChipY = panelY + 30;
        int btnW = 44;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int pinX = closeX - btnW - 4;
        int betBtnH = 16;
        int betBtnW = 24;
        int betMaxW = 40;
        int betBtnY = panelY + 22;
        int betMaxX = panelX + panelW - 8 - betMaxW;
        int betPlusX = betMaxX - 4 - betBtnW;
        int betMinusX = betPlusX - 4 - betBtnW;
        int actionY = panelY + 48;
        int actionBtnH = 18;
        int actionGap = 4;
        int actionBtnW = Math.max(54, (panelW - 16 - actionGap * 5) / 6);
        int dealX = panelX + 8;
        int hitX = dealX + actionBtnW + actionGap;
        int standX = hitX + actionBtnW + actionGap;
        int doubleX = standX + actionBtnW + actionGap;
        int restartX = doubleX + actionBtnW + actionGap;
        int resetBankX = restartX + actionBtnW + actionGap;
        int statusY = panelY + panelH - 14;
        return new Layout(
            windowX, windowY, windowW, windowH, headerH,
            tableX, tableY, tableW, tableH, tablePad,
            topBandY, topBandH, dealerBandY, dealerBandH, midBandY, midBandH, playerBandY, playerBandH,
            cardsAreaX, cardsAreaW, cardW, cardH, cardGap,
            dealerLabelY, dealerCardsY, playerLabelY, playerCardsY,
            betCenterX, betCenterY, betRadius, leftBetX, rightBetX, chipRailY, chipSize,
            deckX, deckY, deckW, deckH, houseChipX, houseChipY,
            panelX, panelY, panelW, panelH,
            bankChipX, bankChipY,
            pinX, windowY + 3, closeX, windowY + 3, btnW, btnH,
            betMinusX, betPlusX, betMaxX, betBtnY, betBtnW, betMaxW, betBtnH,
            dealX, hitX, standX, doubleX, restartX, resetBankX, actionY, actionBtnW, actionBtnH,
            statusY,
            windowX + windowW - 12, windowY + windowH - 12, 12
        );
    }
    private static String scoreText(BlackjackSession.HandScore score) {
        if (score == null) return "?";
        if (score.bust()) return score.total() + " (bust)";
        if (score.blackjack()) return "Blackjack";
        if (score.soft()) return score.total() + " (soft)";
        return Integer.toString(score.total());
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
    private static double clamp01(double value) {
        if (value <= 0.0) return 0.0;
        if (value >= 1.0) return 1.0;
        return value;
    }
    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
    private static double easeOutCubic(double t) {
        double inv = 1.0 - t;
        return 1.0 - inv * inv * inv;
    }
    private static double easeInOut(double t) {
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
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
    private static boolean shouldRender(MinecraftClient mc, boolean pinned) {
        return mc != null && mc.player != null;
    }
    private static int scaledMouseX(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth());
    }
    private static int scaledMouseY(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight());
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    private static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
    private record CardPlacement(BlackjackSession.Card card, int x, int y, boolean dealerHand) {
    }
    private record CardPose(int x, int y, boolean dealerHand) {
    }
    private static final class CardFlight {
        private final BlackjackSession.Card card;
        private boolean dealerHand;
        private final int fromX;
        private final int fromY;
        private int toX;
        private int toY;
        private final long startMs;
        private final long endMs;
        private CardFlight(
            BlackjackSession.Card card,
            boolean dealerHand,
            int fromX,
            int fromY,
            int toX,
            int toY,
            long startMs,
            long endMs
        ) {
            this.card = card;
            this.dealerHand = dealerHand;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
    private static final class CardGhost {
        private final BlackjackSession.Card card;
        private final boolean dealerHand;
        private final int fromX;
        private final int fromY;
        private final int toX;
        private final int toY;
        private final long startMs;
        private final long endMs;
        private CardGhost(
            BlackjackSession.Card card,
            boolean dealerHand,
            int fromX,
            int fromY,
            int toX,
            int toY,
            long startMs,
            long endMs
        ) {
            this.card = card;
            this.dealerHand = dealerHand;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
    private static final class ChipFlight {
        private final int fromX;
        private final int fromY;
        private final int toX;
        private final int toY;
        private final int amount;
        private final long startMs;
        private final long endMs;
        private ChipFlight(int fromX, int fromY, int toX, int toY, int amount, long startMs, long endMs) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.amount = amount;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
    private record Layout(
        int x, int y, int w, int h, int headerH,
        int tableX, int tableY, int tableW, int tableH, int tablePad,
        int topBandY, int topBandH, int dealerBandY, int dealerBandH, int midBandY, int midBandH, int playerBandY, int playerBandH,
        int cardsAreaX, int cardsAreaW, int cardW, int cardH, int cardGap,
        int dealerLabelY, int dealerCardsY, int playerLabelY, int playerCardsY,
        int betCenterX, int betCenterY, int betRadius, int leftBetX, int rightBetX, int chipRailY, int chipSize,
        int deckX, int deckY, int deckW, int deckH, int houseChipX, int houseChipY,
        int panelX, int panelY, int panelW, int panelH,
        int bankChipX, int bankChipY,
        int pinX, int pinY, int closeX, int closeY, int btnW, int btnH,
        int betMinusX, int betPlusX, int betMaxX, int betBtnY, int betBtnW, int betMaxW, int betBtnH,
        int dealX, int hitX, int standX, int doubleX, int restartX, int resetBankX, int actionY, int actionBtnW, int actionBtnH,
        int statusY,
        int resizeX, int resizeY, int resizeSize
    ) {
    }
}

