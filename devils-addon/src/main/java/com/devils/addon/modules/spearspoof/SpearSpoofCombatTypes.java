package com.devils.addon.modules.spearspoof;

import net.minecraft.util.math.Vec3d;

public final class SpearSpoofCombatTypes {
    private SpearSpoofCombatTypes() {
    }

    public enum RunStage {
        WINDUP,
        READY;

        /**
         * The spear only has two states that matter: the KINETIC_WEAPON delay before the charge arms,
         * and the armed charge itself. Vanilla stops the item use on its own after
         * {@code delayTicks + damageConditions.maxDurationTicks} ({@code KineticWeaponComponent.getUseTicks}),
         * so there is no fatigue or recovery stage to model here.
         */
        public static RunStage fromHold(long holdMs, long windupMs) {
            return holdMs < windupMs ? WINDUP : READY;
        }
    }

    public static final class AttackContext {
        public final Vec3d playerPos;
        public final Vec3d playerVel;
        public final Vec3d targetPos;
        public final Vec3d targetVel;
        public final Vec3d aimPos;
        public final float yaw;
        public final float pitch;
        public final double distance;
        public final double verticalDiff;
        public final double speedBps;
        public final double forwardDot;
        public final double lookDot;
        public final double closingSpeedBps;
        public final double cooldown;
        public final long holdMs;
        public final boolean smallTarget;
        public final double targetWidth;
        public final double targetHeight;
        public final RunStage stage;

        public AttackContext(
            Vec3d playerPos,
            Vec3d playerVel,
            Vec3d targetPos,
            Vec3d targetVel,
            Vec3d aimPos,
            float yaw,
            float pitch,
            double distance,
            double verticalDiff,
            double speedBps,
            double forwardDot,
            double lookDot,
            double closingSpeedBps,
            double cooldown,
            long holdMs,
            boolean smallTarget,
            double targetWidth,
            double targetHeight,
            RunStage stage
        ) {
            this.playerPos = playerPos;
            this.playerVel = playerVel;
            this.targetPos = targetPos;
            this.targetVel = targetVel;
            this.aimPos = aimPos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.distance = distance;
            this.verticalDiff = verticalDiff;
            this.speedBps = speedBps;
            this.forwardDot = forwardDot;
            this.lookDot = lookDot;
            this.closingSpeedBps = closingSpeedBps;
            this.cooldown = cooldown;
            this.holdMs = holdMs;
            this.smallTarget = smallTarget;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.stage = stage;
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;
        public final String detail;
        public final RunStage stage;

        private Decision(boolean allowed, String reason, String detail, RunStage stage) {
            this.allowed = allowed;
            this.reason = reason;
            this.detail = detail;
            this.stage = stage;
        }

        public static Decision allow(RunStage stage) {
            return new Decision(true, "", "", stage);
        }

        public static Decision reject(String reason, String detail, RunStage stage) {
            return new Decision(false, reason, detail, stage);
        }
    }
}
