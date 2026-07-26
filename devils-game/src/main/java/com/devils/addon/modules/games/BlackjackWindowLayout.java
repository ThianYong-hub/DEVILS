package com.devils.addon.modules.games;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
final class BlackjackWindowLayout {
    private BlackjackWindowLayout() {
    }
    static Layout compute(int windowX, int windowY, int windowW, int windowH) {
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
        int cardWByLane = (cardHByLane * BlackjackWindowRenderer.CARD_W) / BlackjackWindowRenderer.CARD_H;
        int cardWByTable = clamp((tableW - 48) / 7, 46, 92);
        int cardW = Math.max(46, Math.min(cardWByLane, cardWByTable));
        int cardH = (cardW * BlackjackWindowRenderer.CARD_H) / BlackjackWindowRenderer.CARD_W;
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
    static List<CardPlacement> computePlacements(
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
    static int scaledMouseX(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth());
    }
    static int scaledMouseY(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight());
    }
    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
    record CardPlacement(BlackjackSession.Card card, int x, int y, boolean dealerHand) {
    }
    record Layout(
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
