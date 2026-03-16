package com.example.addon.chesttracker.impl.gui.invbutton.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import com.example.addon.chesttracker.api.memory.Memory;
import com.example.addon.chesttracker.api.providers.MemoryLocation;
import com.example.addon.chesttracker.impl.ChestTracker;
import com.example.addon.chesttracker.impl.memory.MemoryBankImpl;
import com.example.addon.chesttracker.impl.memory.MemoryKeyImpl;
import com.example.addon.chesttracker.impl.memory.key.OverrideInfo;
import com.example.addon.chesttracker.impl.util.GuiUtil;

import java.util.Optional;

public class RenameButton extends SecondaryButton {
    private static final WidgetSprites SPRITES = GuiUtil.twoSprite("inventory_button/rename");
    private final AbstractContainerScreen<?> parent;
    private final MemoryBankImpl bank;
    private final MemoryLocation memoryLocation;

    public RenameButton(AbstractContainerScreen<?> parent, MemoryBankImpl bank, MemoryLocation memoryLocation) {
        super(SPRITES, Component.translatable("chesttracker.inventoryButton.rename"), () -> {});
        this.parent = parent;
        this.bank = bank;
        this.memoryLocation = memoryLocation;

        this.onClick = this::openRename;
    }

    private void openRename() {
        Optional<Memory> memory = this.bank.getMemory(memoryLocation);
        ItemStack preview = memory.flatMap(Memory::container).orElse(Blocks.CHEST).asItem().getDefaultInstance();
        String inGameName = memory.isPresent() && memory.get().savedName() != null ? memory.get().savedName().getString() : "";

        String current = null;

        Optional<MemoryKeyImpl> key = bank.getKeyInternal(this.memoryLocation.memoryKey());
        if (key.isPresent()) {
            OverrideInfo overrideInfo = key.get().overrides().get(this.memoryLocation.position());
            if (overrideInfo != null && overrideInfo.getCustomName() != null) {
                current = overrideInfo.getCustomName();
            }
        }

        ChestTracker.skipProviderForNextGuiClose();

        Minecraft.getInstance().setScreen(new RenameInputScreen(memoryLocation,
                current,
                preview,
                inGameName,
                opt -> {
            opt.ifPresent(newName -> this.bank.setNameOverride(memoryLocation.memoryKey(), memoryLocation.position(), newName));
            InventoryButton.setRestoreLocation(parent, memoryLocation);
            Minecraft.getInstance().setScreen(parent);
        }));
    }
}
