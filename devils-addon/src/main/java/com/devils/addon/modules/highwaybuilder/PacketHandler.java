package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.math.BlockPos;

public class PacketHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;

    public PacketHandler(HighwayBuilder module) {
        this.module = module;
    }

    public void handlePacket(Packet<?> packet) {
        if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            handleBlockUpdate(blockUpdate);
        } else if (packet instanceof PlayerPositionLookS2CPacket) {
            module.pathfinder.rubberbandTimer = System.currentTimeMillis();
        } else if (packet instanceof OpenScreenS2CPacket openScreen) {
            handleOpenScreen(openScreen);
        } else if (packet instanceof InventoryS2CPacket) {
            BlockTask containerTask = module.containerHandler.containerTask;
            // Mark loaded only while a non-player container screen is open.
            if (containerTask.isOpen
                && mc.player != null
                && mc.player.currentScreenHandler != null
                && mc.player.currentScreenHandler.syncId != 0) {
                containerTask.isLoaded = true;
            }
        } else if (packet instanceof ScreenHandlerSlotUpdateS2CPacket slotUpdate) {
            handleSlotUpdate(slotUpdate);
        }
    }

    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        if (mc.world == null) return;

        BlockPos pos = packet.getPos();
        Block prevBlock = mc.world.getBlockState(pos).getBlock();
        Block newBlock = packet.getState().getBlock();

        if (prevBlock == newBlock) return;

        // Check container task first вЂ” it may be placed outside the blueprint
        BlockTask containerTask = module.containerHandler.containerTask;
        if (pos.equals(containerTask.blockPos) && containerTask.taskState != TaskState.DONE) {
            confirmBlockUpdate(containerTask, newBlock);
            return;
        }

        // For regular tasks, must be inside the blueprint
        if (!module.blueprintGenerator.isInsideBlueprint(pos)) return;

        BlockTask task = module.taskManager.getTasks().get(pos);
        if (task == null) return;

        confirmBlockUpdate(task, newBlock);
    }

    private void confirmBlockUpdate(BlockTask task, Block newBlock) {
        switch (task.taskState) {
            case PENDING_BREAK, BREAKING -> {
                if (newBlock == Blocks.AIR) {
                    task.updateState(TaskState.BROKEN);
                }
            }
            case PENDING_PLACE -> {
                if (newBlock != Blocks.AIR
                    && (task.targetBlock == newBlock || task.isFiller)) {
                    task.updateState(TaskState.PLACED);
                }
            }
            default -> { /* ignored */ }
        }
    }

    private void handleOpenScreen(OpenScreenS2CPacket packet) {
        BlockTask containerTask = module.containerHandler.containerTask;
        if (containerTask.taskState == TaskState.DONE) return;

        ScreenHandlerType<?> type = packet.getScreenHandlerType();
        boolean isShulker = type == ScreenHandlerType.SHULKER_BOX && containerTask.isShulker();
        boolean isGeneric = (type == ScreenHandlerType.GENERIC_9X3
            || type == ScreenHandlerType.GENERIC_9X6) && !containerTask.isShulker();

        if (isShulker || isGeneric) {
            containerTask.isOpen = true;
        }
    }

    private void handleSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet) {
        if (mc.player == null) return;

        BlockTask containerTask = module.containerHandler.containerTask;
        if (containerTask.isOpen
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0
            && packet.getSyncId() == mc.player.currentScreenHandler.syncId
            && isContainerStorageSlot(packet.getSlot(), mc.player.currentScreenHandler.slots.size())) {
            containerTask.isLoaded = true;
        }

        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (packet.getSlot() == currentSlot + 36) {
            ItemStack currentStack = mc.player.getInventory().getStack(currentSlot);
            ItemStack newStack = packet.getStack();
            if (newStack.getItem() == currentStack.getItem()
                && newStack.getDamage() > currentStack.getDamage()) {
                module.statistics.durabilityUsages += newStack.getDamage() - currentStack.getDamage();
            }
        }
    }

    private boolean isContainerStorageSlot(int slot, int totalSlots) {
        if (slot < 0 || totalSlots <= 36) return false;
        int containerSlots = totalSlots - 36;
        return slot < containerSlots;
    }
}



