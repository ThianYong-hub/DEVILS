package com.devils.addon.modules;

import com.devils.addon.modules.stashmover.StashMoverOwnPearlTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverOwnPearlTrackerTest {
    @Test
    void tracksOnlyPendingOwnPearl() {
        StashMoverOwnPearlTracker tracker = new StashMoverOwnPearlTracker();
        tracker.beginAwaitingSpawn(4);

        assertEquals(StashMoverOwnPearlTracker.CaptureOutcome.IGNORED, tracker.onPearlAdded(11, false));
        assertTrue(tracker.isAwaitingSpawn());
        assertEquals(StashMoverOwnPearlTracker.CaptureOutcome.TRACKED, tracker.onPearlAdded(12, true));
        assertFalse(tracker.isAwaitingSpawn());
        assertTrue(tracker.hasTrackedPearl());
        assertEquals(12, tracker.trackedEntityId());
    }

    @Test
    void removalOnlyFiresForTrackedPearl() {
        StashMoverOwnPearlTracker tracker = new StashMoverOwnPearlTracker();
        tracker.beginAwaitingSpawn(4);
        tracker.onPearlAdded(55, true);

        assertEquals(StashMoverOwnPearlTracker.RemovalOutcome.IGNORED, tracker.onEntityRemoved(54));
        assertTrue(tracker.hasTrackedPearl());
        assertEquals(StashMoverOwnPearlTracker.RemovalOutcome.TRACKED_REMOVED, tracker.onEntityRemoved(55));
        assertFalse(tracker.hasTrackedPearl());
    }

    @Test
    void timesOutIfOwnPearlNeverAppears() {
        StashMoverOwnPearlTracker tracker = new StashMoverOwnPearlTracker();
        tracker.beginAwaitingSpawn(2);

        assertEquals(StashMoverOwnPearlTracker.AwaitOutcome.WAITING, tracker.tickAwaitingSpawn());
        assertEquals(StashMoverOwnPearlTracker.AwaitOutcome.TIMED_OUT, tracker.tickAwaitingSpawn());
        assertFalse(tracker.isAwaitingSpawn());
        assertFalse(tracker.hasTrackedPearl());
    }
}
