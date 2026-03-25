package com.example.addon.gui.screens.games;

import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.sync.MiniGamesSyncRuntime;
import com.example.addon.games.MiniGamesContracts.ActivePeer;
import com.example.addon.games.MiniGamesContracts.IncomingInvite;
import com.example.addon.games.MiniGamesContracts.OutgoingInvite;
import com.example.addon.games.MiniGamesContracts.SessionView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class MiniGamesHubScreen extends Screen {
    private final Screen parent;
    private final GameType preferred;
    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final ArrayList<ButtonHitbox> buttons = new ArrayList<>();

    public MiniGamesHubScreen(Screen parent) {
        this(parent, null);
    }

    public MiniGamesHubScreen(Screen parent, GameType preferred) {
        super(Text.literal("Devils-Game"));
        this.parent = parent;
        this.preferred = preferred;
    }

    @Override
    public void tick() {
        runtime.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE01B1B1B);
        buttons.clear();

        int y = 16;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y, 0xFFFFFFFF);
        y += 14;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("SyncHub: " + runtime.status()), width / 2, y, 0xFFB8C7FF);
        y += 18;

        drawHeader(context, y, "Play vs Script");
        y += 12;
        addButton(context, width / 2 - 170, y, 160, 20, "Chess (Script)", () -> client.setScreen(new ChessGameScreen(this, ChessGameScreen.Mode.SCRIPT)));
        addButton(context, width / 2 + 10, y, 160, 20, "Checkers (Script)", () -> client.setScreen(new CheckersGameScreen(this, CheckersGameScreen.Mode.SCRIPT)));
        y += 30;

        drawHeader(context, y, "Current Sync Sessions");
        y += 12;
        SessionView chessSession = runtime.sessionView(GameType.CHESS);
        SessionView checkersSession = runtime.sessionView(GameType.CHECKERS);
        addButton(
            context,
            width / 2 - 170,
            y,
            160,
            20,
            chessSession.active() ? "Open Chess Match" : "No Chess Match",
            chessSession.active() ? () -> client.setScreen(new ChessGameScreen(this, ChessGameScreen.Mode.SYNC)) : null
        );
        addButton(
            context,
            width / 2 + 10,
            y,
            160,
            20,
            checkersSession.active() ? "Open Checkers Match" : "No Checkers Match",
            checkersSession.active() ? () -> client.setScreen(new CheckersGameScreen(this, CheckersGameScreen.Mode.SYNC)) : null
        );
        y += 30;

        OutgoingInvite outgoing = runtime.outgoingInvite();
        if (outgoing != null) {
            String target = outgoing.toName() == null || outgoing.toName().isBlank() ? outgoing.toDeviceId() : outgoing.toName();
            drawHeader(context, y, "Outgoing Invite");
            y += 12;
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(outgoing.game().title() + " -> " + target + " [" + outgoing.status() + "]"),
                width / 2 - 170,
                y + 6,
                0xFFFFFFFF
            );
            addButton(context, width / 2 + 120, y, 50, 20, "Cancel", runtime::cancelOutgoingInvite);
            y += 30;
        }

        drawHeader(context, y, "Incoming Invites");
        y += 12;
        List<IncomingInvite> invites = runtime.incomingInvites();
        if (invites.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("No invites."), width / 2 - 170, y + 6, 0xFFAAAAAA);
            y += 24;
        } else {
            int shown = 0;
            for (IncomingInvite invite : invites) {
                if (shown >= 4) break;
                context.drawTextWithShadow(
                    textRenderer,
                    Text.literal(invite.fromName() + " -> " + invite.game().title()),
                    width / 2 - 170,
                    y + 6,
                    0xFFFFFFFF
                );
                addButton(
                    context,
                    width / 2 + 12,
                    y,
                    72,
                    20,
                    "Accept",
                    () -> {
                        runtime.acceptInvite(invite.inviteId(), invite.fromDeviceId(), invite.game());
                        openGame(invite.game(), ChessGameScreen.Mode.SYNC);
                    }
                );
                addButton(context, width / 2 + 92, y, 78, 20, "Decline", () -> runtime.declineInvite(invite.inviteId(), invite.fromDeviceId(), invite.game()));
                y += 24;
                shown++;
            }
        }

        drawHeader(context, y, "Active Addon Users");
        y += 12;
        List<ActivePeer> peers = runtime.activePeers();
        if (peers.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("No active users on SyncHub."), width / 2 - 170, y + 6, 0xFFAAAAAA);
            y += 24;
        } else {
            int shown = 0;
            for (ActivePeer peer : peers) {
                if (shown >= 6) break;
                String name = peer.name() + (peer.busy() ? " [busy]" : "");
                context.drawTextWithShadow(textRenderer, Text.literal(name), width / 2 - 170, y + 6, 0xFFFFFFFF);
                addButton(
                    context,
                    width / 2 + 6,
                    y,
                    78,
                    20,
                    "Chess",
                    () -> {
                        runtime.invitePlayer(GameType.CHESS, peer.deviceId(), peer.name());
                        client.setScreen(new ChessGameScreen(this, ChessGameScreen.Mode.SYNC));
                    }
                );
                addButton(
                    context,
                    width / 2 + 92,
                    y,
                    78,
                    20,
                    "Checkers",
                    () -> {
                        runtime.invitePlayer(GameType.CHECKERS, peer.deviceId(), peer.name());
                        client.setScreen(new CheckersGameScreen(this, CheckersGameScreen.Mode.SYNC));
                    }
                );
                y += 24;
                shown++;
            }
        }

        addButton(context, width / 2 - 60, height - 30, 120, 20, "Back", this::close);
        super.render(context, mouseX, mouseY, delta);
    }

    private void openGame(GameType type, ChessGameScreen.Mode mode) {
        if (type == GameType.CHECKERS) client.setScreen(new CheckersGameScreen(this, CheckersGameScreen.Mode.SYNC));
        else client.setScreen(new ChessGameScreen(this, mode));
    }

    private void drawHeader(DrawContext context, int y, String label) {
        context.drawTextWithShadow(textRenderer, Text.literal(label), width / 2 - 170, y, 0xFFF8DA7A);
    }

    private void addButton(DrawContext context, int x, int y, int w, int h, String label, Runnable action) {
        int bg = action == null ? 0x66333333 : 0xAA2E4B78;
        int outline = action == null ? 0xAA666666 : 0xFF78A6FF;
        context.fill(x, y, x + w, y + h, bg);
        context.fill(x, y, x + w, y + 1, outline);
        context.fill(x, y + h - 1, x + w, y + h, outline);
        context.fill(x, y, x + 1, y + h, outline);
        context.fill(x + w - 1, y, x + w, y + h, outline);
        int textX = x + (w / 2) - (textRenderer.getWidth(label) / 2);
        context.drawTextWithShadow(textRenderer, Text.literal(label), textX, y + 6, 0xFFFFFFFF);
        if (action != null) buttons.add(new ButtonHitbox(x, y, w, h, action));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        double mouseX = click.x();
        double mouseY = click.y();
        for (ButtonHitbox hitbox : buttons) {
            if (hitbox.contains(mouseX, mouseY)) {
                hitbox.action.run();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private record ButtonHitbox(int x, int y, int w, int h, Runnable action) {
        private boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }
}


