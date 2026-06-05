package com.devils.addon.modules.autocraft;

import com.devils.addon.modules.AutoCraft;
import com.devils.addon.modules.autocraft.AutoCraftExecutor.Outcome;
import com.devils.addon.modules.autocraft.AutoCraftExecutor.TickResult;
import com.devils.addon.modules.autocraft.AutoCraftModels.Plan;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlanStep;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlannerInput;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlannerResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class AutoCraftSessionController {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final AutoCraft module;
    private final AutoCraftPlanner planner = new AutoCraftPlanner();
    private final AutoCraftRecipeCatalogue recipeCatalogue = new AutoCraftRecipeCatalogue();
    private final AutoCraftInventoryOps inventoryOps = new AutoCraftInventoryOps();
    private final AutoCraftExecutor executor = new AutoCraftExecutor(inventoryOps);

    private Plan currentPlan;
    private int stepIndex;
    private int craftedFinalItems;
    private long tickCounter;
    private long nextActionTick;
    private long nextCycleTick;
    private long lastAutoOpenAttemptTick = -1;
    private long autoOpenSuppressedUntilTick = -1;
    private long lastProgrammaticCloseTick = -1;
    private int autoOpenedSyncId = -1;
    private String status = "idle";

    public AutoCraftSessionController(AutoCraft module) {
        this.module = module;
    }

    public void onActivate() {
        executor.reset();
        currentPlan = null;
        stepIndex = 0;
        craftedFinalItems = 0;
        tickCounter = 0;
        nextActionTick = 0;
        nextCycleTick = 0;
        lastAutoOpenAttemptTick = -1;
        autoOpenSuppressedUntilTick = -1;
        lastProgrammaticCloseTick = -1;
        autoOpenedSyncId = -1;
        status = "idle";
    }

    public void onDeactivate() {
        executor.reset();
        maybeFastClose();
        currentPlan = null;
        stepIndex = 0;
        status = "disabled";
    }

    public void onTick() {
        tickCounter++;

        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            status = "waiting for world";
            return;
        }

        trackScreenTransitions();

        if (tickCounter < nextActionTick && executor.isRunning()) return;

        if (executor.isRunning()) {
            AbstractCraftingScreenHandler handler = inventoryOps.currentCraftingHandler();
            if (handler == null) {
                invalidatePlan("Crafting context was closed.");
                return;
            }

            TickResult result = executor.tick(handler);
            if (result.reason() != null && !result.reason().isBlank()) status = result.reason();

            if (result.outcome() == Outcome.ACTION) {
                nextActionTick = tickCounter + module.delay();
                return;
            }

            if (result.outcome() == Outcome.COMPLETED) {
                handleExecutorCompleted();
                return;
            }

            if (result.outcome() == Outcome.INVALIDATED) {
                invalidatePlan(result.reason());
                return;
            }

            if (result.outcome() == Outcome.BLOCKED) {
                nextCycleTick = tickCounter + module.frequency();
                return;
            }
        }

        if (tickCounter < nextCycleTick) return;

        AbstractCraftingScreenHandler handler = inventoryOps.currentCraftingHandler();
        if (!executor.isRunning() && handler != null && inventoryOps.findFirstDirtyGridSlotId(handler) >= 0) {
            executor.startCleanup(handler);
            status = "cleaning crafting grid";
            return;
        }

        if (currentPlan != null && stepIndex < currentPlan.steps().size()) {
            if (!ensureContext(currentPlan.steps().get(stepIndex))) return;

            AbstractCraftingScreenHandler craftingHandler = inventoryOps.currentCraftingHandler();
            if (craftingHandler == null) return;

            PlanStep step = currentPlan.steps().get(stepIndex);
            executor.startStep(craftingHandler, step, AutoCraftPolicies.shouldDropFinalOutput(module.drop(), step.finalStep()));
            status = "executing " + shortItemId(step.outputItemId());
            return;
        }

        if (currentPlan != null && stepIndex >= currentPlan.steps().size()) {
            currentPlan = null;
            stepIndex = 0;
            status = "plan completed";
            maybeFastClose();
            nextCycleTick = tickCounter + module.frequency();
            return;
        }

        int remainingLimit = AutoCraftPolicies.remainingLimit(module.limit(), craftedFinalItems);
        if (remainingLimit == 0) {
            status = "limit reached";
            maybeFastClose();
            return;
        }

        if (!AutoCraftPolicies.shouldPlanNextCycle(module.craftAll(), craftedFinalItems)) {
            status = "single batch completed";
            maybeFastClose();
            return;
        }

        PlannerInput input = new PlannerInput(
            selectedItemIds(module.items()),
            selectedItemIdsSet(module.recipeBlacklist()),
            module.craftAll(),
            remainingLimit,
            module.drop(),
            module.autoOpen() || (handler != null && handler.getWidth() >= AutoCraftModels.TABLE_GRID_WIDTH),
            inventoryOps.createPlannerSnapshot(),
            recipeCatalogue.snapshot(mc)
        );

        PlannerResult planResult = planner.plan(input);
        if (!planResult.success()) {
            status = planResult.failureReason();
            maybeFastClose();
            nextCycleTick = tickCounter + module.frequency();
            return;
        }

        currentPlan = planResult.plan();
        stepIndex = 0;
        status = "planned " + shortItemId(currentPlan.targetItemId());
    }

    public String infoString() {
        if (currentPlan != null) return shortItemId(currentPlan.targetItemId()) + " " + craftedFinalItems;
        return status;
    }

    private void handleExecutorCompleted() {
        if (executor.isCleanupOnly()) {
            executor.reset();
            status = "crafting grid cleaned";
            nextCycleTick = tickCounter + module.frequency();
            return;
        }

        PlanStep completedStep = currentPlan.steps().get(stepIndex);
        if (completedStep.finalStep()) craftedFinalItems += completedStep.producedItemCount();

        executor.reset();
        stepIndex++;
        nextCycleTick = tickCounter + module.frequency();

        if (currentPlan != null && stepIndex >= currentPlan.steps().size()) {
            status = "crafted " + currentPlan.craftedItemCount() + "x " + shortItemId(currentPlan.targetItemId());
            currentPlan = null;
            stepIndex = 0;
            maybeFastClose();
            return;
        }

        status = "step complete";
    }

    private boolean ensureContext(PlanStep step) {
        int requiredWidth = step.requiresThreeByThree() ? AutoCraftModels.TABLE_GRID_WIDTH : AutoCraftModels.PLAYER_GRID_WIDTH;
        AbstractCraftingScreenHandler handler = inventoryOps.currentCraftingHandler();

        if (handler != null && handler.getWidth() >= requiredWidth) return true;

        if (requiredWidth <= AutoCraftModels.PLAYER_GRID_WIDTH) {
            status = "waiting for player crafting context";
            return false;
        }

        if (!module.autoOpen()) {
            status = "crafting table required";
            return false;
        }

        if (!AutoCraftPolicies.shouldAttemptAutoOpen(
            tickCounter,
            autoOpenSuppressedUntilTick,
            lastAutoOpenAttemptTick,
            AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS
        )) {
            status = "waiting to retry crafting table open";
            return false;
        }

        BlockHitResult hitResult = findCraftingTableHit();
        if (hitResult == null) {
            lastAutoOpenAttemptTick = tickCounter;
            status = "no reachable crafting table";
            return false;
        }

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastAutoOpenAttemptTick = tickCounter;
        status = "opening crafting table";
        return false;
    }

    private void invalidatePlan(String reason) {
        executor.reset();
        currentPlan = null;
        stepIndex = 0;
        nextCycleTick = tickCounter + module.frequency();
        status = reason == null || reason.isBlank() ? "plan invalidated" : reason;
    }

    private void trackScreenTransitions() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean programmaticClose = tickCounter - lastProgrammaticCloseTick <= 2;

        if (autoOpenedSyncId != -1) {
            boolean sameAutoOpenedScreen = handler instanceof CraftingScreenHandler craftingHandler
                && craftingHandler.syncId == autoOpenedSyncId;

            if (!sameAutoOpenedScreen) {
                autoOpenedSyncId = -1;

                if (!programmaticClose) {
                    autoOpenSuppressedUntilTick = AutoCraftPolicies.suppressAutoOpenUntil(
                        tickCounter,
                        AutoCraftPolicies.DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS
                    );
                    status = "crafting table closed by player";

                    if (handler instanceof CraftingScreenHandler) {
                        lastProgrammaticCloseTick = tickCounter;
                        mc.player.closeHandledScreen();
                        handler = mc.player.currentScreenHandler;
                    }
                }
            }
        }

        boolean recentAutoOpenAttempt = lastAutoOpenAttemptTick >= 0
            && tickCounter - lastAutoOpenAttemptTick <= AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS;
        if (handler instanceof CraftingScreenHandler craftingHandler && autoOpenedSyncId == -1 && recentAutoOpenAttempt) {
            autoOpenedSyncId = craftingHandler.syncId;
        }
    }

    private void maybeFastClose() {
        if (!AutoCraftPolicies.shouldFastClose(module.fastClose(), autoOpenedSyncId != -1, !executor.isRunning())) return;
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) return;

        lastProgrammaticCloseTick = tickCounter;
        mc.player.closeHandledScreen();
        autoOpenedSyncId = -1;
    }

    private BlockHitResult findCraftingTableHit() {
        if (mc.player == null || mc.world == null) return null;

        if (mc.crosshairTarget instanceof BlockHitResult hitResult
            && hitResult.getType() == HitResult.Type.BLOCK
            && mc.world.getBlockState(hitResult.getBlockPos()).isOf(Blocks.CRAFTING_TABLE)) {
            return hitResult;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        Vec3d eyePos = mc.player.getEyePos();
        double maxReach = 5.0;
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, 4, 3, 4)) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) continue;

            Vec3d center = Vec3d.ofCenter(pos);
            double distance = eyePos.distanceTo(center);
            if (distance > maxReach) continue;
            if (distance >= bestDistance) continue;

            bestDistance = distance;
            bestPos = pos.toImmutable();
        }

        if (bestPos == null) return null;
        return createHitResult(bestPos, eyePos);
    }

    private BlockHitResult createHitResult(BlockPos pos, Vec3d eyePos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Direction side = Direction.getFacing(center.x - eyePos.x, center.y - eyePos.y, center.z - eyePos.z).getOpposite();
        Vec3d hitPos = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        return new BlockHitResult(hitPos, side, pos, false);
    }

    private List<String> selectedItemIds(List<Item> items) {
        ArrayList<String> ids = new ArrayList<>();
        for (Item item : items) ids.add(AutoCraftInventoryOps.itemId(item));
        return ids;
    }

    private Set<String> selectedItemIdsSet(List<Item> items) {
        return new HashSet<>(selectedItemIds(items));
    }

    private String shortItemId(String itemId) {
        int separator = itemId.indexOf(':');
        return separator >= 0 ? itemId.substring(separator + 1) : itemId;
    }
}
