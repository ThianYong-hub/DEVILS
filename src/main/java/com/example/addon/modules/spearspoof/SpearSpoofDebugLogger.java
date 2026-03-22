package com.example.addon.modules.spearspoof;

import meteordevelopment.meteorclient.settings.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class SpearSpoofDebugLogger {
    private final Setting<Boolean> enabled;
    private final Setting<Boolean> packetEnabled;
    private final Setting<Boolean> devEnabled;

    private Path debugPath;
    private Path packetDebugPath;
    private Path devDebugPath;

    public SpearSpoofDebugLogger(Setting<Boolean> enabled, Setting<Boolean> packetEnabled, Setting<Boolean> devEnabled) {
        this.enabled = enabled;
        this.packetEnabled = packetEnabled;
        this.devEnabled = devEnabled;
    }

    public void onActivate(boolean clearFile, boolean clearPacketFile, boolean clearDevFile) {
        debugPath = FabricLoader.getInstance().getGameDir()
            .resolve("devils-addon")
            .resolve("spearspoof")
            .resolve("attempt-debug.log");
        packetDebugPath = FabricLoader.getInstance().getGameDir()
            .resolve("devils-addon")
            .resolve("spearspoof")
            .resolve("packet-debug.log");
        devDebugPath = FabricLoader.getInstance().getGameDir()
            .resolve("devils-addon")
            .resolve("spearspoof")
            .resolve("dev-debug.log");

        try {
            Files.createDirectories(debugPath.getParent());
            if (clearFile) Files.deleteIfExists(debugPath);
            if (clearPacketFile) Files.deleteIfExists(packetDebugPath);
            if (clearDevFile) Files.deleteIfExists(devDebugPath);
        } catch (IOException ignored) {
        }
    }

    public void onDeactivate() {
        debugPath = null;
        packetDebugPath = null;
        devDebugPath = null;
    }

    public void logReject(
        String reason,
        String detail,
        SpearSpoofCombatTypes.RunStage stage,
        LivingEntity target,
        SpearSpoofCombatTypes.AttackContext ctx,
        SpearSpoofRuntime runtime
    ) {
        write("reject", reason, detail, stage, target, ctx, runtime, null);
    }

    public void logHit(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx, SpearSpoofRuntime runtime) {
        write("hit", "Hit", "spear-pass", ctx.stage, target, ctx, runtime, null);
    }

    public void logSkip(String reason, String detail, LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx, SpearSpoofRuntime runtime) {
        SpearSpoofCombatTypes.RunStage stage = ctx != null ? ctx.stage : null;
        write("skip", reason, detail, stage, target, ctx, runtime, null);
    }

    public void logPhaseChange(String reason, String detail, LivingEntity target, SpearSpoofRuntime runtime) {
        write("phase", reason, detail, null, target, null, runtime, null);
    }

    public void logMove(String detail, LivingEntity target, SpearSpoofRuntime runtime, Vec3d velocity) {
        if (!enabled.get() || debugPath == null || runtime == null) return;

        String targetName = target != null ? target.getName().getString() : "none";
        String line = String.format(
            Locale.US,
            "{\"id\":%d,\"ts\":%d,\"outcome\":\"move\",\"reason\":\"Move\",\"detail\":\"%s\",\"target\":\"%s\",\"phase\":\"%s\",\"vel\":\"%s\",\"hitChain\":%d,\"rejectStreak\":%d}",
            runtime.nextAttemptId(),
            System.currentTimeMillis(),
            escapeJson(detail),
            escapeJson(targetName),
            runtime.passPhase.name(),
            escapeJson(formatVec(velocity)),
            runtime.hitChain,
            runtime.rejectStreak
        );

        appendLine(line);
    }

    public void logPacketSend(Object packet, String detail, SpearSpoofRuntime runtime) {
        writePacket("send", packet, detail, runtime);
    }

    public void logPacketReceive(Object packet, String detail, SpearSpoofRuntime runtime) {
        writePacket("recv", packet, detail, runtime);
    }

    public void logDev(String type, String detail, LivingEntity target, SpearSpoofRuntime runtime) {
        if (!devEnabled.get() || devDebugPath == null || runtime == null) return;

        long now = System.currentTimeMillis();
        long rmbHoldMs = runtime.devRmbDownSinceMs > 0L ? Math.max(0L, now - runtime.devRmbDownSinceMs) : 0L;
        long pendingAttackAgeMs = runtime.devPendingAttackAtMs > 0L ? Math.max(0L, now - runtime.devPendingAttackAtMs) : -1L;
        String targetName = target != null ? target.getName().getString() : "none";
        int targetId = target != null ? target.getId() : -1;
        String line = String.format(
            Locale.US,
            "{\"id\":%d,\"ts\":%d,\"type\":\"%s\",\"detail\":\"%s\",\"target\":\"%s\",\"targetId\":%d,\"phase\":\"%s\",\"holdMs\":%d,\"hitCount\":%d,\"lastHitDist\":%.2f,\"maxDistAfterHit\":%.2f,\"lastRetreatDist\":%.2f,\"lastReturnDist\":%.2f,\"pendingAttackTargetId\":%d,\"pendingAttackAgeMs\":%d,\"lastHitSignal\":\"%s\"}",
            runtime.nextAttemptId(),
            now,
            escapeJson(type),
            escapeJson(detail == null ? "" : detail),
            escapeJson(targetName),
            targetId,
            runtime.passPhase != null ? runtime.passPhase.name() : "none",
            rmbHoldMs,
            runtime.devHitCount,
            runtime.devLastHitDistance,
            runtime.devMaxDistanceAfterHit,
            runtime.devLastRetreatDistance,
            runtime.devLastReturnDistance,
            runtime.devPendingAttackTargetId,
            pendingAttackAgeMs,
            escapeJson(runtime.devLastHitSignal)
        );

        appendDevLine(line);
    }

    private void write(
        String outcome,
        String reason,
        String detail,
        SpearSpoofCombatTypes.RunStage stage,
        LivingEntity target,
        SpearSpoofCombatTypes.AttackContext ctx,
        SpearSpoofRuntime runtime,
        Vec3d velocity
    ) {
        if (!enabled.get() || debugPath == null || runtime == null) return;
        long now = System.currentTimeMillis();
        long nextAttemptInMs = Math.max(0L, runtime.nextAttemptAtMs - now);
        long repositionInMs = Math.max(0L, runtime.repositionUntilMs - now);
        long unstuckInMs = Math.max(0L, runtime.unstuckUntilMs - now);
        String stageName = stage != null ? stage.name() : "none";
        String phaseName = runtime.passPhase != null ? runtime.passPhase.name() : "none";
        String targetName = target != null ? target.getName().getString() : "none";
        double dist = ctx != null ? ctx.distance : -1.0;
        double speed = ctx != null ? ctx.speedBps : -1.0;
        double closing = ctx != null ? ctx.closingSpeedBps : -1.0;
        double cooldown = ctx != null ? ctx.cooldown : -1.0;
        long holdMs = ctx != null ? ctx.holdMs : runtime.holdMs(now);
        double forward = ctx != null ? ctx.forwardDot : -2.0;
        double look = ctx != null ? ctx.lookDot : -2.0;
        double vertical = ctx != null ? ctx.verticalDiff : -1.0;
        double yawErr = ctx != null ? ctx.yawError : -1.0;
        double pitchErr = ctx != null ? ctx.pitchError : -1.0;
        double hitboxW = ctx != null ? ctx.targetWidth : -1.0;
        double hitboxH = ctx != null ? ctx.targetHeight : -1.0;
        double predictLeadTicks = ctx != null ? ctx.predictionLeadTicks : -1.0;
        double predictExtraTicks = ctx != null ? ctx.predictionExtraTicks : -1.0;
        double predictTotalTicks = ctx != null ? ctx.predictionTotalTicks : -1.0;
        boolean predictAuto = ctx != null && ctx.predictionAuto;
        boolean predictCollisionAware = ctx != null && ctx.predictionCollisionAware;
        long phaseAgeMs = Math.max(0L, now - runtime.passPhaseStartMs);
        long resetInMs = Math.max(0L, runtime.movementResetUntilMs - now);
        long targetLockAgeMs = runtime.targetLockedAtMs > 0L ? Math.max(0L, now - runtime.targetLockedAtMs) : -1L;
        long targetLostAgeMs = runtime.targetLostAtMs > 0L ? Math.max(0L, now - runtime.targetLostAtMs) : -1L;
        String playerPos = ctx != null ? formatVec(ctx.playerPos) : "null";
        String playerVel = ctx != null ? formatVec(ctx.playerVel) : "null";
        String targetPos = ctx != null ? formatVec(ctx.targetPos) : "null";
        String targetVel = ctx != null ? formatVec(ctx.targetVel) : "null";
        String predictedTargetPos = ctx != null ? formatVec(ctx.predictedTargetPos) : "null";
        String aimPos = ctx != null ? formatVec(ctx.aimPos) : "null";

        String line = String.format(
            Locale.US,
            "{\"id\":%d,\"ts\":%d,\"outcome\":\"%s\",\"reason\":\"%s\",\"detail\":\"%s\",\"target\":\"%s\",\"stage\":\"%s\",\"phase\":\"%s\",\"phaseAgeMs\":%d,\"resetInMs\":%d,\"targetLockAgeMs\":%d,\"targetLostAgeMs\":%d,\"dist\":%.2f,\"speed\":%.2f,\"closing\":%.2f,\"cooldown\":%.2f,\"holdMs\":%d,\"forward\":%.2f,\"look\":%.2f,\"vertical\":%.2f,\"yawErr\":%.2f,\"pitchErr\":%.2f,\"hitboxW\":%.2f,\"hitboxH\":%.2f,\"predictLeadTicks\":%.2f,\"predictExtraTicks\":%.2f,\"predictTotalTicks\":%.2f,\"predictAuto\":%b,\"predictCollisionAware\":%b,\"hitChain\":%d,\"rejectStreak\":%d,\"lastReject\":\"%s\",\"nextAttemptInMs\":%d,\"repositionInMs\":%d,\"unstuckInMs\":%d,\"switchDelayTicks\":%d,\"rmbRechargeTicks\":%d,\"windupRestartTicks\":%d,\"useKeyInjected\":%b,\"playerPos\":\"%s\",\"playerVel\":\"%s\",\"targetPos\":\"%s\",\"targetVel\":\"%s\",\"predTargetPos\":\"%s\",\"aimPos\":\"%s\",\"vel\":\"%s\"}",
            runtime.nextAttemptId(),
            now,
            escapeJson(outcome),
            escapeJson(reason),
            escapeJson(detail),
            escapeJson(targetName),
            stageName,
            phaseName,
            phaseAgeMs,
            resetInMs,
            targetLockAgeMs,
            targetLostAgeMs,
            dist,
            speed,
            closing,
            cooldown,
            holdMs,
            forward,
            look,
            vertical,
            yawErr,
            pitchErr,
            hitboxW,
            hitboxH,
            predictLeadTicks,
            predictExtraTicks,
            predictTotalTicks,
            predictAuto,
            predictCollisionAware,
            runtime.hitChain,
            runtime.rejectStreak,
            escapeJson(runtime.lastRejectReason),
            nextAttemptInMs,
            repositionInMs,
            unstuckInMs,
            runtime.switchDelayTicks,
            runtime.rmbRechargeReleaseTicks,
            runtime.windupRestartTicks,
            runtime.useKeyInjected,
            escapeJson(playerPos),
            escapeJson(playerVel),
            escapeJson(targetPos),
            escapeJson(targetVel),
            escapeJson(predictedTargetPos),
            escapeJson(aimPos),
            escapeJson(formatVec(velocity))
        );

        appendLine(line);
    }

    private void writePacket(String direction, Object packet, String detail, SpearSpoofRuntime runtime) {
        if (!packetEnabled.get() || packetDebugPath == null || runtime == null) return;

        long now = System.currentTimeMillis();
        String packetName = packet != null ? packet.getClass().getSimpleName() : "null";
        LivingEntity target = runtime.target;
        String targetName = target != null ? target.getName().getString() : "none";
        int targetId = target != null ? target.getId() : -1;
        long holdMs = runtime.holdMs(now);
        long nextAttemptInMs = Math.max(0L, runtime.nextAttemptAtMs - now);
        long resetInMs = Math.max(0L, runtime.movementResetUntilMs - now);
        long repositionInMs = Math.max(0L, runtime.repositionUntilMs - now);
        long passAgeMs = Math.max(0L, now - runtime.passPhaseStartMs);

        String line = String.format(
            Locale.US,
            "{\"id\":%d,\"ts\":%d,\"dir\":\"%s\",\"packet\":\"%s\",\"detail\":\"%s\",\"target\":\"%s\",\"targetId\":%d,\"phase\":\"%s\",\"phaseAgeMs\":%d,\"holdMs\":%d,\"nextAttemptInMs\":%d,\"resetInMs\":%d,\"repositionInMs\":%d,\"hitConfirmPending\":%b,\"hitConfirmTargetId\":%d,\"hitConfirmRetry\":%d,\"hitChain\":%d,\"rejectStreak\":%d,\"lastReject\":\"%s\"}",
            runtime.nextAttemptId(),
            now,
            escapeJson(direction),
            escapeJson(packetName),
            escapeJson(detail == null ? "" : detail),
            escapeJson(targetName),
            targetId,
            runtime.passPhase != null ? runtime.passPhase.name() : "none",
            passAgeMs,
            holdMs,
            nextAttemptInMs,
            resetInMs,
            repositionInMs,
            runtime.hitConfirmPending,
            runtime.hitConfirmTargetId,
            runtime.hitConfirmRetryCount,
            runtime.hitChain,
            runtime.rejectStreak,
            escapeJson(runtime.lastRejectReason)
        );

        appendPacketLine(line);
    }

    private void appendLine(String line) {
        try {
            Files.createDirectories(debugPath.getParent());
            Files.writeString(debugPath, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void appendPacketLine(String line) {
        try {
            Files.createDirectories(packetDebugPath.getParent());
            Files.writeString(packetDebugPath, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void appendDevLine(String line) {
        try {
            Files.createDirectories(devDebugPath.getParent());
            Files.writeString(devDebugPath, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private String formatVec(Vec3d value) {
        if (value == null) return "null";
        return String.format(Locale.US, "(%.3f,%.3f,%.3f)", value.x, value.y, value.z);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }
}
