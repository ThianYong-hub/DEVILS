package com.example.addon.modules.xaerosync;

import com.example.addon.modules.Ping;
import com.example.addon.modules.SyncHub;
import com.example.addon.modules.XaeroSync;
import com.example.addon.util.XaeroSyncWaypoints;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.Locale;

public final class XaeroSyncDebugController {
    private final XaeroSync module;

    private String lastRuntimeDebugSnapshot = "";
    private String lastWaypointPipelineDebugSnapshot = "";
    private String lastWaypointDebugMessage = "";
    private long lastWaypointDebugMessageAtMs;
    private int suppressedWaypointDebugRepeats;
    private String lastSyncUnavailableReason = "";

    public XaeroSyncDebugController(XaeroSync module) {
        this.module = module;
    }

    public void clear() {
        resetRuntimeSnapshots();
        lastWaypointDebugMessage = "";
        lastWaypointDebugMessageAtMs = 0;
        suppressedWaypointDebugRepeats = 0;
        XaeroSyncWaypoints.setDebugListener(null);
    }

    public void resetRuntimeSnapshots() {
        lastRuntimeDebugSnapshot = "";
        lastWaypointPipelineDebugSnapshot = "";
        lastSyncUnavailableReason = "";
    }

    public void configureWaypointDebugBridge() {
        if (!isWaypointDebugEnabled()) {
            XaeroSyncWaypoints.setDebugListener(null);
            return;
        }
        XaeroSyncWaypoints.setDebugListener(this::forwardWaypointDebug);
    }

    public void debugRuntimeUnavailable(String reason) {
        if (!reason.equals(lastSyncUnavailableReason)) {
            debugPipeline("runtime config unavailable: %s", reason);
            lastSyncUnavailableReason = reason;
        }
    }

    public void clearRuntimeUnavailableReason() {
        lastSyncUnavailableReason = "";
    }

    public void debugRuntimeSnapshot(String snapshot) {
        if (!snapshot.equals(lastRuntimeDebugSnapshot)) {
            debugPipeline("runtime config active: %s", snapshot);
            lastRuntimeDebugSnapshot = snapshot;
        }
    }

    public void debugWaypointPipelineSnapshot(String snapshot) {
        if (!snapshot.equals(lastWaypointPipelineDebugSnapshot)) {
            debugPipeline("pipeline snapshot: %s", snapshot);
            lastWaypointPipelineDebugSnapshot = snapshot;
        }
    }

    public boolean isWaypointDebugEnabled() {
        if (module.waypointDebugEnabled()) return true;

        Modules modules = Modules.get();
        if (modules == null) return false;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub != null && syncHub.xaeroDebugPipeline()) return true;

        Ping ping = modules.get(Ping.class);
        return ping != null && ping.xaeroMapDebug();
    }

    public void debugPipeline(String format, Object... args) {
        if (!isWaypointDebugEnabled()) return;

        String message;
        try {
            message = args == null || args.length == 0
                ? XaeroSyncValueUtils.safe(format)
                : String.format(Locale.ROOT, XaeroSyncValueUtils.safe(format), args);
        } catch (Throwable ignored) {
            message = XaeroSyncValueUtils.safe(format);
        }
        if (message.isBlank()) return;
        forwardWaypointDebug(message);
    }

    private void forwardWaypointDebug(String message) {
        if (!isWaypointDebugEnabled()) return;
        String safeMessage = XaeroSyncValueUtils.safe(message).trim();
        if (safeMessage.isBlank()) return;

        long now = System.currentTimeMillis();
        if (safeMessage.equals(lastWaypointDebugMessage) && (now - lastWaypointDebugMessageAtMs) < 1200) {
            suppressedWaypointDebugRepeats++;
            return;
        }

        if (suppressedWaypointDebugRepeats > 0 && !lastWaypointDebugMessage.isBlank()) {
            module.info("[XaeroDbg] %s (repeated %d times)", lastWaypointDebugMessage, suppressedWaypointDebugRepeats);
            suppressedWaypointDebugRepeats = 0;
        }

        lastWaypointDebugMessage = safeMessage;
        lastWaypointDebugMessageAtMs = now;
        module.info("[XaeroDbg] %s", safeMessage);
    }
}


