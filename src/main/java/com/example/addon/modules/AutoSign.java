package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.AbstractSignEditScreenAccessor;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class AutoSign extends Module {

    // ── Settings groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgPlace    = settings.createGroup("Auto Place");
    private final SettingGroup sgFront    = settings.createGroup("Front Text");
    private final SettingGroup sgBack     = settings.createGroup("Back Text");
    private final SettingGroup sgRemote   = settings.createGroup("Remote Command");

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

    // Auto Place
    private final Setting<Boolean> autoPlace = sgPlace.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically place signs from hotbar on nearby surfaces.")
        .defaultValue(false)
        .build());

    private final Setting<List<Block>> signBlocks = sgPlace.add(new BlockListSetting.Builder()
        .name("sign-types")
        .description("Which sign types to place. Empty = place any sign found in hotbar.")
        .filter(block -> block instanceof AbstractSignBlock)
        .build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Maximum reach distance for placing signs.")
        .defaultValue(4.5).min(1.0).max(6.0).sliderRange(1.0, 6.0)
        .build());

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each sign placement.")
        .defaultValue(10).min(1).sliderMax(60)
        .build());

    private final Setting<Double> spacing = sgPlace.add(new DoubleSetting.Builder()
        .name("spacing")
        .description("Minimum distance between placed signs (blocks). Prevents spam-stacking.")
        .defaultValue(3.0).min(1.0).max(20.0).sliderRange(1.0, 10.0)
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

    private boolean awaitingCopy;
    private int placeTicks;
    /** Last position where a sign was placed, to enforce spacing. */
    private BlockPos lastPlacePos;

    public AutoSign() {
        super(AddonTemplate.CATEGORY, "auto-sign",
            "Instantly fills signs with configured text. Auto-places signs, supports front/back, color codes (&), copy mode, and remote chat commands.");
    }

    @Override
    public void onActivate() {
        awaitingCopy  = false;
        placeTicks    = 0;
        lastPlacePos  = null;
    }

    @Override
    public void onDeactivate() {
        lastPlacePos = null;
    }

    // ── Auto-place: tick logic ────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoPlace.get()) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        placeTicks++;
        if (placeTicks < placeDelay.get()) return;

        int signSlot = findSignSlot();
        if (signSlot == -1) return;

        // Find a valid surface to place the sign on
        BlockPos placeTarget = findPlaceTarget();
        if (placeTarget == null) return;

        // Check spacing from last placed sign
        if (lastPlacePos != null) {
            double dist = Math.sqrt(lastPlacePos.getSquaredDistance(placeTarget));
            if (dist < spacing.get()) return;
        }

        placeTicks = 0;

        // Place the sign
        Vec3d hitVec = Vec3d.ofCenter(placeTarget).add(0, 0.5, 0);
        Direction side = Direction.UP;

        // Rotate to face the target block
        float yaw = (float) Rotations.getYaw(hitVec);
        float pitch = (float) Rotations.getPitch(hitVec);

        BlockHitResult hitResult = new BlockHitResult(hitVec, side, placeTarget, false);

        InvUtils.swap(signSlot, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        InvUtils.swapBack();

        lastPlacePos = placeTarget.up(); // sign goes on top of the target block
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

    // ── Auto-place helpers ────────────────────────────────────────────────────

    /**
     * Finds a sign in the hotbar that matches the sign-types filter.
     * Returns hotbar slot index, or -1 if not found.
     */
    private int findSignSlot() {
        if (mc.player == null) return -1;
        List<Block> allowed = signBlocks.get();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            if (!(block instanceof AbstractSignBlock)) continue;

            // If filter list is empty, accept any sign
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(block)) continue;

            return i;
        }
        return -1;
    }

    /**
     * Scans nearby blocks for a valid surface to place a sign on top of.
     * Finds the closest solid block with air above it within place-range.
     */
    private BlockPos findPlaceTarget() {
        if (mc.player == null || mc.world == null) return null;

        Vec3d playerPos = mc.player.getPos();
        BlockPos playerBlock = mc.player.getBlockPos();
        int range = (int) Math.ceil(placeRange.get());

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos pos = playerBlock.add(x, y, z);
                    Vec3d center = Vec3d.ofCenter(pos);

                    if (center.distanceTo(playerPos) > placeRange.get()) continue;

                    // The block must be solid (support surface)
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isSolidSurface(mc.world, pos, mc.player, Direction.UP)) continue;

                    // The block above must be air (where the sign goes)
                    BlockPos above = pos.up();
                    if (!mc.world.getBlockState(above).isAir()) continue;

                    // Check spacing from last placed sign
                    if (lastPlacePos != null) {
                        double spaceDist = Math.sqrt(above.getSquaredDistance(lastPlacePos));
                        if (spaceDist < spacing.get()) continue;
                    }

                    double dist = center.distanceTo(playerPos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    // ── Packet helpers ────────────────────────────────────────────────────────

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
