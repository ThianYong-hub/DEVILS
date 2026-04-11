package com.example.addon.modules;

import com.example.addon.modules.stashmover.StashMoverSlotPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverSlotPolicyTest {
    @Test
    void detectsPearlAlreadyPresentInHotbar() {
        List<StashMoverSlotPolicy.SlotKind> hotbar = hotbarOf(
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.ENDER_PEARL,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE
        );

        var selection = StashMoverSlotPolicy.selectPearlHotbarSlotKinds(hotbar);
        assertEquals(1, selection.slot());
        assertEquals(StashMoverSlotPolicy.Status.ALREADY_PRESENT, selection.status());
    }

    @Test
    void prefersEmptyHotbarSlotBeforeReplaceableSlot() {
        List<StashMoverSlotPolicy.SlotKind> hotbar = hotbarOf(
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            null,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE
        );

        var selection = StashMoverSlotPolicy.selectPearlHotbarSlotKinds(hotbar);
        assertEquals(3, selection.slot());
        assertEquals(StashMoverSlotPolicy.Status.EMPTY_SLOT, selection.status());
        assertTrue(selection.success());
    }

    @Test
    void fallsBackToReplaceableSlotWhenNoEmptySlotExists() {
        List<StashMoverSlotPolicy.SlotKind> hotbar = hotbarOf(
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE,
            StashMoverSlotPolicy.SlotKind.REPLACEABLE
        );

        var selection = StashMoverSlotPolicy.selectPearlHotbarSlotKinds(hotbar);
        assertEquals(3, selection.slot());
        assertEquals(StashMoverSlotPolicy.Status.REPLACEABLE_SLOT, selection.status());
        assertTrue(selection.success());
    }

    @Test
    void reportsFailureWhenAllSlotsAreProtected() {
        List<StashMoverSlotPolicy.SlotKind> hotbar = hotbarOf(
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED,
            StashMoverSlotPolicy.SlotKind.PROTECTED
        );

        var selection = StashMoverSlotPolicy.selectPearlHotbarSlotKinds(hotbar);
        assertEquals(-1, selection.slot());
        assertEquals(StashMoverSlotPolicy.Status.NO_SAFE_SLOT, selection.status());
    }

    private static List<StashMoverSlotPolicy.SlotKind> hotbarOf(StashMoverSlotPolicy.SlotKind... items) {
        List<StashMoverSlotPolicy.SlotKind> hotbar = new ArrayList<>(9);
        for (StashMoverSlotPolicy.SlotKind item : items) {
            hotbar.add(item == null ? StashMoverSlotPolicy.SlotKind.EMPTY : item);
        }
        return hotbar;
    }
}
