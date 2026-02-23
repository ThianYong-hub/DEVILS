package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.AbstractSignEditScreenAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoSign extends Module {

    // ── Settings groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgAura     = settings.createGroup("Sign Aura");
    private final SettingGroup sgPlace    = settings.createGroup("Auto Place");
    private final SettingGroup sgFront    = settings.createGroup("Front Text");
    private final SettingGroup sgBack     = settings.createGroup("Back Text");

    // General
    private final Setting<Boolean> colorCodes = sgGeneral.add(new BoolSetting.Builder()
        .name("color-codes")
        .description("Convert & color codes to § (e.g. &4 = dark red, &l = bold).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enableBack = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-back")
        .description("Also write text on the back side of the sign.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> copyMode = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-mode")
        .description("Copy text from the next sign you edit into the line settings instead of writing.")
        .defaultValue(false)
        .build());

    // Sign Aura
    private final Setting<Boolean> signAura = sgAura.add(new BoolSetting.Builder()
        .name("sign-aura")
        .description("Automatically find and edit nearby signs, even through blocks.")
        .defaultValue(false)
        .build());

    private final Setting<Double> signAuraRange = sgAura.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range to detect and edit signs.")
        .defaultValue(4.0).min(1.0).max(6.0).sliderRange(1.0, 6.0)
        .visible(signAura::get)
        .build());

    private final Setting<Integer> signAuraDelay = sgAura.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between editing signs.")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(signAura::get)
        .build());

    // Auto Place
    private final Setting<Boolean> autoPlace = sgPlace.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically place signs from hotbar on nearby surfaces.")
        .defaultValue(false)
        .build());

    private final Setting<List<Item>> signItems = sgPlace.add(new ItemListSetting.Builder()
        .name("sign-types")
        .description("Which sign types to place. Empty = place any sign found in hotbar.")
        .filter(item -> item instanceof BlockItem bi && bi.getBlock() instanceof AbstractSignBlock)
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
        .description("Minimum distance between placed signs (blocks).")
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
        .name("back-line-1").description("Back side line 1.").defaultValue("")
        .visible(enableBack::get).build());
    private final Setting<String> backLine2 = sgBack.add(new StringSetting.Builder()
        .name("back-line-2").description("Back side line 2.").defaultValue("")
        .visible(enableBack::get).build());
    private final Setting<String> backLine3 = sgBack.add(new StringSetting.Builder()
        .name("back-line-3").description("Back side line 3.").defaultValue("")
        .visible(enableBack::get).build());
    private final Setting<String> backLine4 = sgBack.add(new StringSetting.Builder()
        .name("back-line-4").description("Back side line 4.").defaultValue("")
        .visible(enableBack::get).build());

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean awaitingCopy;

    /** Signs already edited by sign aura this session. */
    private final ArrayList<BlockPos> editedSigns = new ArrayList<>();

    private int auraTimer;
    private int placeTimer;
    private BlockPos lastPlacePos;

    /** Sequence counter for raw interact packets. */
    private int packetSequence;

    /**
     * Two-phase back-side editing:
     * Phase 1 (onOpenScreen): write front, cancel screen, set backEditPos.
     * Phase 2 (onPostTick):   interact with sign again → server opens new session →
     *                         send UpdateSignC2SPacket(front=false), set prevBackEditPos
     *                         so onOpenScreen (fired by the second interact) just cancels.
     */
    private BlockPos backEditPos;
    private BlockPos prevBackEditPos;

    public AutoSign() {
        super(AddonTemplate.CATEGORY, "auto-sign",
            "Instantly fills signs with configured text. Sign Aura edits nearby signs through blocks, Auto Place places new ones.");
    }

    @Override
    public void onActivate() {
        awaitingCopy    = false;
        editedSigns.clear();
        auraTimer       = 0;
        placeTimer      = 0;
        lastPlacePos    = null;
        packetSequence  = 0;
        backEditPos     = null;
        prevBackEditPos = null;
    }

    @Override
    public void onDeactivate() {
        editedSigns.clear();
        lastPlacePos    = null;
        backEditPos     = null;
        prevBackEditPos = null;
    }

    // ── Pre-Tick: Sign Aura + Auto Place ──────────────────────────────────────

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // ── Sign Aura: find and edit existing signs (works through blocks) ───
        if (signAura.get()) {
            auraTimer--;
            if (auraTimer <= 0) {
                for (BlockEntity be : Utils.blockEntities()) {
                    if (!(be instanceof SignBlockEntity sign)) continue;

                    BlockPos pos = sign.getPos();
                    if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > signAuraRange.get()) continue;
                    if (editedSigns.contains(pos)) continue;

                    // Skip if sign already has our text
                    if (signAlreadyHasText(sign)) {
                        editedSigns.add(pos);
                        continue;
                    }

                    // Send raw interact packet — bypasses client-side line-of-sight checks
                    // This works through blocks and from any direction
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                    mc.player.networkHandler.sendPacket(
                        new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, packetSequence++)
                    );

                    editedSigns.add(pos);
                    auraTimer = signAuraDelay.get();
                    break;
                }
            }
        }

        // ── Auto Place: place new signs on surfaces ──────────────────────────
        if (autoPlace.get()) {
            placeTimer--;
            if (placeTimer <= 0) {
                int signSlot = findSignSlot();
                if (signSlot != -1) {
                    BlockPos target = findPlaceTarget();
                    if (target != null) {
                        placeTimer = placeDelay.get();

                        Vec3d hitVec = Vec3d.ofCenter(target).add(0, 0.5, 0);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, target, false);

                        InvUtils.swap(signSlot, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                        InvUtils.swapBack();

                        lastPlacePos = target.up();
                    }
                }
            }
        }
    }

    // ── Post-Tick: phase 2 — interact with sign again for back side ──────────

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (backEditPos == null || !enableBack.get()) return;
        if (mc.player == null || mc.world == null) return;
        // Don't re-process the same sign (prevents loop with onOpenScreen)
        if (backEditPos.equals(prevBackEditPos)) return;

        // Interact with the sign again — opens a NEW editing session on the server
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(backEditPos), Direction.DOWN, backEditPos, false
        );
        mc.player.networkHandler.sendPacket(
            new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, packetSequence++)
        );

        // Immediately send back text before the server responds with OpenScreen
        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(
            backEditPos, false,
            processLine(backLine1.get()), processLine(backLine2.get()),
            processLine(backLine3.get()), processLine(backLine4.get())
        ));

        prevBackEditPos = backEditPos;
        backEditPos = null;
    }

    // ── Sign screen intercept: write front text ──────────────────────────────

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

        BlockPos pos = sign.getPos();

        // Send front text
        mc.player.networkHandler.sendPacket(new UpdateSignC2SPacket(
            pos, true,
            processLine(line1.get()), processLine(line2.get()),
            processLine(line3.get()), processLine(line4.get())
        ));

        event.cancel();

        // Schedule back side edit for post-tick (phase 2)
        if (enableBack.get() && !pos.equals(prevBackEditPos)) {
            backEditPos = pos;
        }
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

    // ── Sign text check ─────────────────────────────────────────────────────

    private boolean signAlreadyHasText(SignBlockEntity sign) {
        var frontText = sign.getFrontText();

        boolean frontMatch =
            frontText.getMessage(0, false).getString().equals(processLine(line1.get()))
            && frontText.getMessage(1, false).getString().equals(processLine(line2.get()))
            && frontText.getMessage(2, false).getString().equals(processLine(line3.get()))
            && frontText.getMessage(3, false).getString().equals(processLine(line4.get()));

        if (!frontMatch) return false;

        if (enableBack.get()) {
            var backText = sign.getBackText();
            return backText.getMessage(0, false).getString().equals(processLine(backLine1.get()))
                && backText.getMessage(1, false).getString().equals(processLine(backLine2.get()))
                && backText.getMessage(2, false).getString().equals(processLine(backLine3.get()))
                && backText.getMessage(3, false).getString().equals(processLine(backLine4.get()));
        }

        return true;
    }

    // ── Auto-place helpers ────────────────────────────────────────────────────

    private int findSignSlot() {
        if (mc.player == null) return -1;
        List<Item> allowed = signItems.get();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            if (!(blockItem.getBlock() instanceof AbstractSignBlock)) continue;

            if (allowed != null && !allowed.isEmpty() && !allowed.contains(stack.getItem())) continue;
            return i;
        }
        return -1;
    }

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

                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isSolidSurface(mc.world, pos, mc.player, Direction.UP)) continue;

                    BlockPos above = pos.up();
                    if (!mc.world.getBlockState(above).isAir()) continue;

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

    // ── Text processing ───────────────────────────────────────────────────────

    private String processLine(String line) {
        if (line == null) return "";
        if (colorCodes.get()) {
            line = line.replace("&", "\u00a7");
        }
        return line;
    }
}
