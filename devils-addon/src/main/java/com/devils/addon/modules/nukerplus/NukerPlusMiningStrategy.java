package com.devils.addon.modules.nukerplus;

import com.devils.addon.mixin.ClientPlayerInteractionManagerInvoker;
import com.devils.addon.modules.NukerPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.SpeedMine;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * The NukerPlus break engine: baseline/legacy breaking, Mio-style SpeedMineDamage timing,
 * instant packet breaking, hotbar tool auto-swap, and the break-timing math. Logic was
 * moved verbatim from NukerPlus; all mutable module state stays owned by the module and is
 * accessed through the injected {@link NukerPlus} reference.
 */
public final class NukerPlusMiningStrategy {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final float DAMAGE_FINISH_PROGRESS_EPSILON = 1.0E-4f;
    private static final float INSTA_CHAIN_MINING_DELTA = 0.5f;

    private final NukerPlus module;

    public NukerPlusMiningStrategy(NukerPlus module) {
        this.module = module;
    }

    public BreakAttemptResult dispatchBreakAttempt(BlockPos blockPos, String accelerationSuppressionReason) {
        if (accelerationSuppressionReason == null) {
            if (module.usesInstaAcceleration()) {
                float blockBreakingDelta = resolveBlockBreakingDelta(blockPos);
                return performInstaBreak(blockPos, blockBreakingDelta);
            }

            if (module.usesSpeedMineDamageAcceleration()) {
                DamageToolSelection toolSelection = prepareSpeedMineDamageTool(blockPos);
                float blockBreakingDelta = toolSelection.blockBreakingDelta();
                return performSpeedMineDamageBreak(blockPos, blockBreakingDelta, toolSelection);
            }
        }

        resetDamageBreakState("baseline-path");
        performLegacyBreak(blockPos);
        return BreakAttemptResult.legacy(blockPos);
    }

    private void performLegacyBreak(BlockPos blockPos) {
        if (module.interact.get()) {
            BlockUtils.interact(new BlockHitResult(blockPos.toCenterPos(), BlockUtils.getDirection(blockPos), blockPos, true), Hand.MAIN_HAND, module.swing.get());
            module.interacted.add(blockPos);
            return;
        }

        BlockUtils.breakBlock(blockPos, module.swing.get());
    }

    private float resolveBlockBreakingDelta(BlockPos blockPos) {
        if (mc.player == null || mc.world == null || blockPos == null) return 0.0f;
        BlockState blockState = mc.world.getBlockState(blockPos);
        return blockState.calcBlockBreakingDelta(mc.player, mc.world, blockPos);
    }

    private BreakAttemptResult performInstaBreak(BlockPos blockPos, float blockBreakingDelta) {
        return performInstaBreak(blockPos, blockBreakingDelta, true);
    }

    private BreakAttemptResult performInstaBreak(BlockPos blockPos, float blockBreakingDelta, boolean resetDamageState) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            resetDamageBreakState("insta-fallback");
            performLegacyBreak(blockPos);
            return BreakAttemptResult.legacy(blockPos);
        }

        if (resetDamageState) resetDamageBreakState("insta-priority");
        Direction direction = BlockUtils.getDirection(blockPos);
        ((ClientPlayerInteractionManagerInvoker) mc.interactionManager).devilsAddon$sendSequencedPacket(
            mc.world,
            sequence -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, direction, sequence)
        );

        swingBreakingHand();

        ((ClientPlayerInteractionManagerInvoker) mc.interactionManager).devilsAddon$sendSequencedPacket(
            mc.world,
            sequence -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence)
        );

        if (module.grimBypass.get()) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos.up(), direction));
        }

        boolean continueLoop = isInstaChainEligible(blockBreakingDelta);
        module.debugAcceleration("insta packet " + blockPos + " delta=" + formatDelta(blockBreakingDelta) + (continueLoop ? " chain" : " single") + (module.grimBypass.get() ? " grim-bypass" : ""));
        if (!resetDamageState) {
            module.damageBreakState.clear();
            restoreDamageAutoSwap();
        }
        return BreakAttemptResult.insta(continueLoop);
    }

    private BreakAttemptResult performSpeedMineDamageBreak(BlockPos blockPos, float blockBreakingDelta, DamageToolSelection toolSelection) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            resetDamageBreakState("damage-fallback");
            performLegacyBreak(blockPos);
            return BreakAttemptResult.legacy(blockPos);
        }

        BlockState blockState = mc.world.getBlockState(blockPos);
        if (blockState.isAir()) {
            resetDamageBreakState("target-air");
            return BreakAttemptResult.stop();
        }

        if (!Float.isFinite(blockBreakingDelta) || blockBreakingDelta <= 0.0f) {
            resetDamageBreakState("delta-unusable");
            performLegacyBreak(blockPos);
            return BreakAttemptResult.stop();
        }

        if (canUseDamageBurstChain()) {
            return performChargedSpeedMineDamageBreak(blockPos, blockState, blockBreakingDelta, toolSelection);
        }

        Direction direction = module.damageBreakState.matches(blockPos) && module.damageBreakState.direction != null
            ? module.damageBreakState.direction
            : BlockUtils.getDirection(blockPos);
        ItemStack toolSnapshot = toolSelection.toolStackSnapshot();
        int vanillaBreakTicks = calculateVanillaBreakTicks(blockBreakingDelta);
        int targetBreakTicks = calculateTargetBreakTicks(vanillaBreakTicks, module.damage.get(), blockBreakingDelta);

        if (module.damageBreakState.requiresNewCycle(blockPos, blockState, toolSnapshot)) {
            if (module.damageBreakState.isTracking()) resetDamageBreakState("target-switch");
            module.damageBreakState.start(blockPos.toImmutable(), blockState, direction, toolSnapshot, mc.world.getTime(), vanillaBreakTicks, targetBreakTicks, blockBreakingDelta);
        } else {
            module.damageBreakState.refresh(blockState, direction, toolSnapshot, mc.world.getTime(), vanillaBreakTicks, targetBreakTicks, blockBreakingDelta);
        }

        ClientPlayerInteractionManagerInvoker interactionManager = (ClientPlayerInteractionManagerInvoker) mc.interactionManager;
        boolean currentlyBreaking = interactionManager.devilsAddon$isCurrentlyBreaking(blockPos);
        module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();

        if (!currentlyBreaking) {
            boolean started = mc.interactionManager.attackBlock(blockPos, direction);
            swingBreakingHand();
            seedSpeedMineDamageProgress(interactionManager);
            module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();
            if (!started) {
                resetDamageBreakState("attack-failed");
                return BreakAttemptResult.stop();
            }

            module.debugAcceleration(
                "damage start " + blockPos
                    + " delta=" + formatDelta(blockBreakingDelta)
                    + " vanilla=" + module.damageBreakState.vanillaBreakTicks
                    + " target=" + module.damageBreakState.targetBreakTicks
                    + " seed=" + formatDamageMultiplier(module.damage.get())
            );
            if (module.damageBreakState.targetBreakTicks <= 1) {
                module.damageBreakState.elapsedBreakTicks = 1;
                return finishSpeedMineDamageBreak(blockPos, direction, blockBreakingDelta, interactionManager);
            }

            boolean progressed = mc.interactionManager.updateBlockBreakingProgress(blockPos, direction);
            swingBreakingHand();
            module.damageBreakState.markInitialProgressApplied(mc.world.getTime());
            if (!mc.world.getBlockState(blockPos).isAir()) {
                seedSpeedMineDamageProgress(interactionManager);
            }
            module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();

            if (!progressed) {
                module.damageRetryCount++;
                resetDamageBreakState("start-progress-lost");
                return BreakAttemptResult.stop();
            }

            if (mc.world.getBlockState(blockPos).isAir()) {
                module.debugAcceleration(
                    "damage start-finish " + blockPos
                        + " delta=" + formatDelta(blockBreakingDelta)
                        + " vanilla=" + module.damageBreakState.vanillaBreakTicks
                        + " target=" + module.damageBreakState.targetBreakTicks
                        + " seed=" + formatDamageMultiplier(module.damage.get())
                );
                armDamageBurstChain();
                module.damageBreakState.clear();
                restoreDamageAutoSwap();
                return BreakAttemptResult.keepGoing();
            }

            return BreakAttemptResult.stop();
        }

        module.damageBreakState.elapsedBreakTicks = module.damageBreakState.computeElapsedTicks(mc.world.getTime());
        module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();
        seedSpeedMineDamageProgress(interactionManager);

        if (module.damageBreakState.elapsedBreakTicks >= module.damageBreakState.targetBreakTicks) {
            return finishSpeedMineDamageBreak(blockPos, direction, blockBreakingDelta, interactionManager);
        }

        boolean progressed = mc.interactionManager.updateBlockBreakingProgress(blockPos, direction);
        swingBreakingHand();
        seedSpeedMineDamageProgress(interactionManager);
        module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();

        if (!progressed) {
            module.damageRetryCount++;
            resetDamageBreakState("progress-lost");
        }

        return BreakAttemptResult.stop();
    }

    private BreakAttemptResult performChargedSpeedMineDamageBreak(BlockPos blockPos, BlockState blockState, float blockBreakingDelta, DamageToolSelection toolSelection) {
        Direction direction = BlockUtils.getDirection(blockPos);
        ItemStack toolSnapshot = toolSelection.toolStackSnapshot();
        int vanillaBreakTicks = calculateVanillaBreakTicks(blockBreakingDelta);
        int targetBreakTicks = calculateTargetBreakTicks(vanillaBreakTicks, module.damage.get(), blockBreakingDelta);

        module.damageBreakState.start(blockPos.toImmutable(), blockState, direction, toolSnapshot, mc.world.getTime(), vanillaBreakTicks, targetBreakTicks, blockBreakingDelta);
        module.damageBreakState.elapsedBreakTicks = targetBreakTicks;

        ClientPlayerInteractionManagerInvoker interactionManager = (ClientPlayerInteractionManagerInvoker) mc.interactionManager;
        boolean started = mc.interactionManager.attackBlock(blockPos, direction);
        swingBreakingHand();
        seedSpeedMineDamageProgress(interactionManager);
        module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();
        if (!started) {
            resetDamageBreakState("charged-attack-failed");
            return BreakAttemptResult.stop();
        }

        module.debugAcceleration(
            "damage charged " + blockPos
                + " delta=" + formatDelta(blockBreakingDelta)
                + " vanilla=" + module.damageBreakState.vanillaBreakTicks
                + " target=" + module.damageBreakState.targetBreakTicks
                + " seed=" + formatDamageMultiplier(module.damage.get())
        );
        return finishSpeedMineDamageBreak(blockPos, direction, blockBreakingDelta, interactionManager);
    }

    private BreakAttemptResult finishSpeedMineDamageBreak(BlockPos blockPos, Direction direction, float blockBreakingDelta, ClientPlayerInteractionManagerInvoker interactionManager) {
        interactionManager.devilsAddon$setCurrentBreakingProgress(1.0f);

        boolean progressed = mc.interactionManager.updateBlockBreakingProgress(blockPos, direction);
        swingBreakingHand();

        module.damageBreakState.forcedFinishAttempted = true;
        module.damageBreakState.lastProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();
        module.damageForcedFinishCount++;
        module.debugAcceleration(
            "damage finish " + blockPos
                + " elapsed=" + module.damageBreakState.elapsedBreakTicks + "/" + module.damageBreakState.targetBreakTicks
                + " vanilla=" + module.damageBreakState.vanillaBreakTicks
                + " progress=" + formatDelta(module.damageBreakState.lastProgress)
                + " seed=" + formatDamageMultiplier(module.damage.get())
                + " forced=" + module.damageForcedFinishCount
        );

        if (!progressed) {
            module.damageRetryCount++;
            resetDamageBreakState("finish-progress-lost");
            return BreakAttemptResult.stop();
        }

        armDamageBurstChain();
        module.damageBreakState.clear();
        restoreDamageAutoSwap();
        return BreakAttemptResult.keepGoing();
    }

    private void seedSpeedMineDamageProgress(ClientPlayerInteractionManagerInvoker interactionManager) {
        if (interactionManager == null) return;
        float currentProgress = interactionManager.devilsAddon$getCurrentBreakingProgress();
        float seedProgress = damageSeedProgress(module.damage.get());
        if (currentProgress < seedProgress) {
            interactionManager.devilsAddon$setCurrentBreakingProgress(seedProgress);
            module.damageBreakState.lastProgress = seedProgress;
        } else {
            module.damageBreakState.lastProgress = currentProgress;
        }
    }

    private boolean canUseDamageBurstChain() {
        return mc.world != null
            && module.maxBlocksPerTick.get() > 1
            && module.damage.get() < NukerPlus.DAMAGE_MAX
            && module.damageBurstChainTick == mc.world.getTime();
    }

    private void armDamageBurstChain() {
        if (mc.world == null || module.maxBlocksPerTick.get() <= 1 || module.damage.get() >= NukerPlus.DAMAGE_MAX) return;
        module.damageBurstChainTick = mc.world.getTime();
    }

    public String resolveAccelerationSuppressionReason() {
        if (module.accelerationMode.get() == NukerPlus.MiningAccelerationMode.Off) return null;
        if (module.interact.get()) return "interact-mode";
        return isMeteorSpeedMineActive() ? "meteor-speedmine" : null;
    }

    private boolean isMeteorSpeedMineActive() {
        try {
            SpeedMine speedMine = Modules.get().get(SpeedMine.class);
            return speedMine != null && speedMine.isActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void resetDamageBreakState(String reason) {
        // Meteor's ClientPlayerInteractionManagerMixin no-ops this while BlockUtils.breaking is set, so it cannot
        // abort our own progressive legacy mining; it only clears a stray vanilla break before we sequence ours.
        if (mc.interactionManager != null && mc.interactionManager.isBreakingBlock()) {
            try {
                mc.interactionManager.cancelBlockBreaking();
            } catch (Throwable ignored) {
            }
        }

        if (!module.damageBreakState.isTracking()) {
            module.damageBreakState.clear();
            restoreDamageAutoSwap();
            return;
        }

        String summary = module.damageBreakState.summary();
        module.damageBreakState.clear();
        restoreDamageAutoSwap();

        if (reason != null && !reason.isBlank()) {
            module.debugAcceleration("damage reset " + reason + " " + summary);
        }
    }

    private DamageToolSelection prepareSpeedMineDamageTool(BlockPos blockPos) {
        if (mc.player == null || mc.world == null || blockPos == null) return DamageToolSelection.empty();

        BlockState state = mc.world.getBlockState(blockPos);
        if (state.isAir()) return DamageToolSelection.empty();

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        int bestSlot = selectedSlot;
        float bestDelta = resolveBlockBreakingDeltaWithSlot(state, blockPos, selectedSlot);
        ItemStack bestStack = mc.player.getInventory().getStack(selectedSlot).copy();

        if (!module.speedMineAutoSwap.get()) {
            return new DamageToolSelection(bestDelta, selectedSlot, selectedSlot, bestStack);
        }

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;

            float candidateDelta = resolveBlockBreakingDeltaWithSlot(state, blockPos, slot);
            if (candidateDelta > bestDelta + 1.0E-6f) {
                bestDelta = candidateDelta;
                bestSlot = slot;
                bestStack = stack.copy();
            }
        }

        if (bestSlot == selectedSlot) {
            return new DamageToolSelection(bestDelta, selectedSlot, bestSlot, bestStack);
        }

        selectDamageToolSlotSilently(selectedSlot, bestSlot, bestDelta);
        return new DamageToolSelection(bestDelta, selectedSlot, bestSlot, bestStack);
    }

    private float resolveBlockBreakingDeltaWithSlot(BlockState state, BlockPos blockPos, int slot) {
        if (mc.player == null || mc.world == null || state == null || blockPos == null) return 0.0f;

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        try {
            mc.player.getInventory().setSelectedSlot(slot);
            return state.calcBlockBreakingDelta(mc.player, mc.world, blockPos);
        } finally {
            mc.player.getInventory().setSelectedSlot(previousSlot);
        }
    }

    public void restoreDamageAutoSwap() {
        if (module.damageSwapBackSlot < 0 || mc.player == null) {
            module.damageSwapBackSlot = -1;
            return;
        }

        int restoreSlot = module.damageSwapBackSlot;
        module.damageSwapBackSlot = -1;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(restoreSlot));
            markSelectedDamageToolSynced(restoreSlot);
            module.debugAcceleration("damage autoswap silent-restore slot=" + restoreSlot);
        } else if (mc.player.getInventory().getSelectedSlot() != restoreSlot) {
            InvUtils.swap(restoreSlot, false);
            module.debugAcceleration("damage autoswap restore slot=" + restoreSlot);
        }
    }

    private void selectDamageToolSlotSilently(int selectedSlot, int bestSlot, float bestDelta) {
        if (mc.world == null || mc.getNetworkHandler() == null || selectedSlot < 0 || selectedSlot > 8 || bestSlot < 0 || bestSlot > 8) return;
        if (module.damageSwapBackSlot < 0) module.damageSwapBackSlot = selectedSlot;
        if (module.damageToolSyncTick == mc.world.getTime() && module.damageToolSyncSlot == bestSlot) return;

        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(bestSlot));
        markSelectedDamageToolSynced(bestSlot);
        module.damageAutoSwapSelectCount++;
        module.damageLastAutoSwapFromSlot = selectedSlot;
        module.damageLastAutoSwapToSlot = bestSlot;
        module.debugAcceleration("damage autoswap silent slot=" + selectedSlot + "->" + bestSlot + " delta=" + formatDelta(bestDelta));
    }

    private void markSelectedDamageToolSynced(int slot) {
        module.damageToolSyncTick = mc.world == null ? Long.MIN_VALUE : mc.world.getTime();
        module.damageToolSyncSlot = slot;
    }

    private void swingBreakingHand() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (module.swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    public static boolean isInstaChainEligible(float blockBreakingDelta) {
        return blockBreakingDelta > INSTA_CHAIN_MINING_DELTA;
    }

    private static String formatDelta(float blockBreakingDelta) {
        return String.format(Locale.US, "%.3f", blockBreakingDelta);
    }

    private static String formatDamageMultiplier(double damageMultiplier) {
        return String.format(Locale.US, "%.2f", damageMultiplier);
    }

    public static int calculateVanillaBreakTicks(float blockBreakingDelta) {
        if (!Float.isFinite(blockBreakingDelta) || blockBreakingDelta <= 0.0f) return 0;
        return Math.max(1, (int) Math.ceil(1.0D / blockBreakingDelta));
    }

    public static int calculateTargetBreakTicks(int vanillaBreakTicks, double damageMultiplier) {
        if (vanillaBreakTicks <= 0) return 0;
        double remainingProgress = remainingBreakProgress(damageMultiplier);
        int targetBreakTicks = ceilProgressTicks(vanillaBreakTicks * remainingProgress);
        return Math.max(1, Math.min(vanillaBreakTicks, targetBreakTicks));
    }

    public static int calculateTargetBreakTicks(int vanillaBreakTicks, double damageMultiplier, float blockBreakingDelta) {
        if (vanillaBreakTicks <= 0) return 0;
        if (!Float.isFinite(blockBreakingDelta) || blockBreakingDelta <= 0.0f) {
            return calculateTargetBreakTicks(vanillaBreakTicks, damageMultiplier);
        }

        double remainingProgress = remainingBreakProgress(damageMultiplier);
        int targetBreakTicks = ceilProgressTicks(remainingProgress / blockBreakingDelta);
        return Math.max(1, Math.min(vanillaBreakTicks, targetBreakTicks));
    }

    private static float damageSeedProgress(double damageMultiplier) {
        double clampedDamage = MathHelper.clamp(damageMultiplier, NukerPlus.DAMAGE_MIN, NukerPlus.DAMAGE_MAX);
        return (float) MathHelper.clamp(clampedDamage, 0.0D, 1.0D - DAMAGE_FINISH_PROGRESS_EPSILON);
    }

    private static double remainingBreakProgress(double damageMultiplier) {
        double clampedDamage = MathHelper.clamp(damageMultiplier, NukerPlus.DAMAGE_MIN, NukerPlus.DAMAGE_MAX);
        return MathHelper.clamp(1.0D - clampedDamage, 0.0D, 1.0D);
    }

    private static int ceilProgressTicks(double progressTicks) {
        return (int) Math.ceil(Math.max(0.0D, progressTicks) - 1.0E-9D);
    }

    public static final class DamageBreakState {
        public BlockPos targetPos;
        public BlockState targetState;
        public Direction direction;
        public ItemStack toolSnapshot = ItemStack.EMPTY;
        public long breakStartTick = Long.MIN_VALUE;
        public int elapsedBreakTicks;
        public int vanillaBreakTicks;
        public int targetBreakTicks;
        public float lastProgress;
        public float lastDelta;
        public boolean forcedFinishAttempted;
        public boolean initialProgressApplied;

        public boolean isTracking() {
            return targetPos != null && targetState != null;
        }

        public boolean matches(BlockPos blockPos) {
            return isTracking() && targetPos.equals(blockPos);
        }

        public boolean requiresNewCycle(BlockPos blockPos, BlockState blockState, ItemStack stack) {
            if (!isTracking()) return true;
            if (!targetPos.equals(blockPos)) return true;
            if (!targetState.equals(blockState)) return true;
            return !ItemStack.areItemsAndComponentsEqual(toolSnapshot, stack);
        }

        public void start(BlockPos blockPos, BlockState blockState, Direction direction, ItemStack toolSnapshot, long breakStartTick, int vanillaBreakTicks, int targetBreakTicks, float lastDelta) {
            this.targetPos = blockPos;
            this.targetState = blockState;
            this.direction = direction;
            this.toolSnapshot = toolSnapshot.copy();
            this.breakStartTick = breakStartTick;
            this.elapsedBreakTicks = 0;
            this.vanillaBreakTicks = vanillaBreakTicks;
            this.targetBreakTicks = targetBreakTicks;
            this.lastProgress = 0.0f;
            this.lastDelta = lastDelta;
            this.forcedFinishAttempted = false;
            this.initialProgressApplied = false;
        }

        public void refresh(BlockState blockState, Direction direction, ItemStack toolSnapshot, long worldTick, int vanillaBreakTicks, int targetBreakTicks, float lastDelta) {
            this.targetState = blockState;
            this.direction = direction;
            this.toolSnapshot = toolSnapshot.copy();
            this.elapsedBreakTicks = computeElapsedTicks(worldTick);
            this.vanillaBreakTicks = vanillaBreakTicks;
            this.targetBreakTicks = targetBreakTicks;
            this.lastDelta = lastDelta;
        }

        public int computeElapsedTicks(long worldTick) {
            if (breakStartTick == Long.MIN_VALUE) return 0;
            long elapsed = worldTick - breakStartTick;
            int elapsedTicks;
            if (elapsed <= 0L) elapsedTicks = 0;
            else elapsedTicks = elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
            if (initialProgressApplied && elapsedTicks < Integer.MAX_VALUE) elapsedTicks++;
            return elapsedTicks;
        }

        public void markInitialProgressApplied(long worldTick) {
            if (initialProgressApplied) return;
            initialProgressApplied = true;
            this.elapsedBreakTicks = computeElapsedTicks(worldTick);
        }

        public void clear() {
            targetPos = null;
            targetState = null;
            direction = null;
            toolSnapshot = ItemStack.EMPTY;
            breakStartTick = Long.MIN_VALUE;
            elapsedBreakTicks = 0;
            vanillaBreakTicks = 0;
            targetBreakTicks = 0;
            lastProgress = 0.0f;
            lastDelta = 0.0f;
            forcedFinishAttempted = false;
            initialProgressApplied = false;
        }

        public String summary() {
            if (!isTracking()) return "idle";
            return "target=" + targetPos
                + " elapsed=" + elapsedBreakTicks
                + " targetTicks=" + targetBreakTicks
                + " vanillaTicks=" + vanillaBreakTicks
                + " progress=" + formatDelta(lastProgress)
                + " delta=" + formatDelta(lastDelta)
                + " forced=" + forcedFinishAttempted;
        }
    }

    public record BreakAttemptResult(boolean continueLoop) {
        public static BreakAttemptResult legacy(BlockPos blockPos) {
            return new BreakAttemptResult(BlockUtils.canInstaBreak(blockPos));
        }

        public static BreakAttemptResult insta(boolean continueLoop) {
            return new BreakAttemptResult(continueLoop);
        }

        public static BreakAttemptResult keepGoing() {
            return new BreakAttemptResult(true);
        }

        public static BreakAttemptResult stop() {
            return new BreakAttemptResult(false);
        }
    }

    record DamageToolSelection(float blockBreakingDelta, int selectedSlot, int toolSlot, ItemStack toolStackSnapshot) {
        static DamageToolSelection empty() {
            return new DamageToolSelection(0.0f, -1, -1, ItemStack.EMPTY);
        }
    }
}
