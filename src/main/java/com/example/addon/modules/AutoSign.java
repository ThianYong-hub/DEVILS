package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.AbstractSignEditScreenAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;

public class AutoSign extends Module {

    // ── Settings groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFront   = settings.createGroup("Front Text");
    private final SettingGroup sgBack    = settings.createGroup("Back Text");
    private final SettingGroup sgRemote  = settings.createGroup("Remote Command");

    // General
    private final Setting<Boolean> colorCodes = sgGeneral.add(new BoolSetting.Builder()
        .name("color-codes")
        .description("Convert & color codes to § (e.g. &4 = dark red, &l = bold).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> copyMode = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-mode")
        .description("Copy text from the next sign you edit into the line settings instead of writing.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> enableBack = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-back")
        .description("Also write text on the back side of the sign.")
        .defaultValue(false)
        .build());

    // Front text
    private final Setting<String> line1 = sgFront.add(new StringSetting.Builder()
        .name("line-1").description("Front side line 1.").defaultValue("").build());
    private final Setting<String> line2 = sgFront.add(new StringSetting.Builder()
        .name("line-2").description("Front side line 2.").defaultValue("").build());
    private final Setting<String> line3 = sgFront.add(new StringSetting.Builder()
        .name("line-3").description("Front side line 3.").defaultValue("").build());
    private final Setting<String> line4 = sgFront.add(new StringSetting.Builder()
        .name("line-4").description("Front side line 4.").defaultValue("").build());

    // Back text
    private final Setting<String> backLine1 = sgBack.add(new StringSetting.Builder()
        .name("back-line-1").description("Back side line 1.").defaultValue("").build());
    private final Setting<String> backLine2 = sgBack.add(new StringSetting.Builder()
        .name("back-line-2").description("Back side line 2.").defaultValue("").build());
    private final Setting<String> backLine3 = sgBack.add(new StringSetting.Builder()
        .name("back-line-3").description("Back side line 3.").defaultValue("").build());
    private final Setting<String> backLine4 = sgBack.add(new StringSetting.Builder()
        .name("back-line-4").description("Back side line 4.").defaultValue("").build());

    // Remote Command
    private final Setting<String> commandPrefix = sgRemote.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("Chat command prefix. Usage: !sign line1|line2|line3|line4 or !sign clear or !sign copy")
        .defaultValue("!sign")
        .build());

    private final Setting<String> commandPlayer = sgRemote.add(new StringSetting.Builder()
        .name("command-player")
        .description("Only obey commands from this player. Empty = obey anyone.")
        .defaultValue("")
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    /** When copy-mode captures text from a manually edited sign, it goes here temporarily. */
    private boolean awaitingCopy;

    public AutoSign() {
        super(AddonTemplate.CATEGORY, "auto-sign",
            "Instantly fills signs with configured text. Supports front/back, color codes (&), copy mode, and remote chat commands.");
    }

    @Override
    public void onActivate() {
        awaitingCopy = false;
    }

    // ── Sign screen intercept ─────────────────────────────────────────────────

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null) return;
        if (!(event.screen instanceof AbstractSignEditScreen screen)) return;

        // Copy mode: let the screen open normally, capture on packet send
        if (copyMode.get()) {
            awaitingCopy = true;
            return;
        }

        SignBlockEntity sign = ((AbstractSignEditScreenAccessor) screen).meteor$getSign();
        if (sign == null) return;

        sendSignPacket(sign, true, line1.get(), line2.get(), line3.get(), line4.get());

        if (enableBack.get()) {
            sendSignPacket(sign, false, backLine1.get(), backLine2.get(), backLine3.get(), backLine4.get());
        }

        event.cancel();
    }

    // ── Copy mode: intercept outgoing sign packet ─────────────────────────────

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!awaitingCopy) return;
        if (!(event.packet instanceof UpdateSignC2SPacket packet)) return;

        awaitingCopy = false;

        String[] text = packet.getText();
        if (text.length < 4) return;

        if (packet.isFront()) {
            line1.set(text[0]);
            line2.set(text[1]);
            line3.set(text[2]);
            line4.set(text[3]);
            info("Copied front text: §e" + String.join(" | ", text));
        } else {
            backLine1.set(text[0]);
            backLine2.set(text[1]);
            backLine3.set(text[2]);
            backLine4.set(text[3]);
            info("Copied back text: §e" + String.join(" | ", text));
        }
    }

    // ── Chat command listener ─────────────────────────────────────────────────

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;

        String msg    = event.getMessage().getString();
        String prefix = commandPrefix.get().trim();
        if (prefix.isEmpty()) return;

        int cmdIdx = msg.indexOf(prefix);
        if (cmdIdx == -1) return;

        // Sender check
        String auth = commandPlayer.get().trim();
        if (!auth.isEmpty()) {
            String beforeCmd = msg.substring(0, cmdIdx);
            if (!beforeCmd.toLowerCase().contains(auth.toLowerCase())) return;
        }

        String afterCmd = msg.substring(cmdIdx + prefix.length()).trim();
        if (afterCmd.isEmpty()) return;

        String arg = afterCmd.split("\\s+", 2)[0];

        switch (arg.toLowerCase()) {
            case "clear" -> {
                line1.set(""); line2.set(""); line3.set(""); line4.set("");
                backLine1.set(""); backLine2.set(""); backLine3.set(""); backLine4.set("");
                info("All sign text cleared.");
            }
            case "copy" -> {
                copyMode.set(!copyMode.get());
                info("Copy mode: " + (copyMode.get() ? "§aON" : "§cOFF"));
            }
            case "on" -> {
                if (!isActive()) toggle();
                info("Module enabled via chat.");
            }
            case "off" -> {
                if (isActive()) toggle();
                info("Module disabled via chat.");
            }
            default -> {
                // Parse pipe-separated lines: "line1|line2|line3|line4"
                // Allow the full remaining text (not just first word)
                String fullArg = msg.substring(cmdIdx + prefix.length()).trim();
                String[] lines = fullArg.split("\\|", -1);
                line1.set(lines.length > 0 ? lines[0] : "");
                line2.set(lines.length > 1 ? lines[1] : "");
                line3.set(lines.length > 2 ? lines[2] : "");
                line4.set(lines.length > 3 ? lines[3] : "");
                info("Sign text set: §e" + fullArg);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void sendSignPacket(SignBlockEntity sign, boolean front,
                                String l1, String l2, String l3, String l4) {
        if (mc.player == null) return;

        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(
            sign.getPos(), front,
            processLine(l1), processLine(l2), processLine(l3), processLine(l4)
        ));
    }

    private String processLine(String line) {
        if (line == null) return "";
        if (colorCodes.get()) {
            line = line.replace("&", "\u00a7"); // & → §
        }
        return line;
    }
}
