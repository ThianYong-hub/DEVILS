package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.Locale;

public final class SpearSpoofDevDebugService {
    private final SpearSpoof module;
    private final SpearSpoofRuntime runtime;
    private final SpearSpoofTargetingService targeting;
    private final SpearSpoofCombatService combat;
    private final SpearSpoofFlightService flight;
    private final SpearSpoofDebugLogger debugLogger;
    private final Setting<Integer> intervalMs;

    private boolean armed;

    public SpearSpoofDevDebugService(
        SpearSpoof module,
        SpearSpoofRuntime runtime,
        SpearSpoofTargetingService targeting,
        SpearSpoofCombatService combat,
        SpearSpoofFlightService flight,
        SpearSpoofDebugLogger debugLogger,
        Setting<Integer> intervalMs
    ) {
        this.module = module;
        this.runtime = runtime;
        this.targeting = targeting;
        this.combat = combat;
        this.flight = flight;
        this.debugLogger = debugLogger;
        this.intervalMs = intervalMs;
    }

    public void onTick() {
        if (module.client() == null || module.client().player == null || module.client().world == null || module.client().options == null) return;
        long now = System.currentTimeMillis();

        if (!armed) arm();

        LivingEntity target = targeting.resolve(runtime);
        runtime.target = target;
        if (target != null) runtime.rememberTargetSnapshot(target, now);

        int currentTargetId = target != null ? target.getId() : -1;
        if (currentTargetId != runtime.devLastLoggedTargetId) {
            if (target != null) {
                debugLogger.logDev(
                    "target-lock",
                    "targetId=" + currentTargetId + " dist=" + f2(distanceToTargetHitbox(target)),
                    target,
                    runtime
                );
            } else {
                debugLogger.logDev("target-lost", "targetId=-1", null, runtime);
            }
            runtime.devLastLoggedTargetId = currentTargetId;
        }

        boolean usePressed = module.client().options.useKey.isPressed();
        boolean attackPressed = module.client().options.attackKey.isPressed();
        boolean jumpPressed = module.client().options.jumpKey.isPressed();
        boolean sneakPressed = module.client().options.sneakKey.isPressed();
        boolean forwardPressed = module.client().options.forwardKey.isPressed();
        boolean backPressed = module.client().options.backKey.isPressed();
        boolean leftPressed = module.client().options.leftKey.isPressed();
        boolean rightPressed = module.client().options.rightKey.isPressed();
        boolean sprintPressed = module.client().options.sprintKey.isPressed();

        if (!runtime.devKeysInitialized) {
            runtime.devKeysInitialized = true;
            runtime.devPrevUsePressed = usePressed;
            runtime.devPrevAttackPressed = attackPressed;
            runtime.devPrevJumpPressed = jumpPressed;
            runtime.devPrevSneakPressed = sneakPressed;
            runtime.devPrevForwardPressed = forwardPressed;
            runtime.devPrevBackPressed = backPressed;
            runtime.devPrevLeftPressed = leftPressed;
            runtime.devPrevRightPressed = rightPressed;
            runtime.devPrevSprintPressed = sprintPressed;
        }

        if (usePressed && !runtime.devPrevUsePressed) {
            runtime.devRmbDownSinceMs = now;
            debugLogger.logDev("key", "rmb=down", target, runtime);
        } else if (!usePressed && runtime.devPrevUsePressed) {
            long holdMs = runtime.devRmbDownSinceMs > 0L ? Math.max(0L, now - runtime.devRmbDownSinceMs) : 0L;
            debugLogger.logDev("key", "rmb=up holdMs=" + holdMs, target, runtime);
            runtime.devRmbDownSinceMs = 0L;
        }

        logKeyTransition("lmb", attackPressed, runtime.devPrevAttackPressed, target);
        logKeyTransition("jump", jumpPressed, runtime.devPrevJumpPressed, target);
        logKeyTransition("sneak", sneakPressed, runtime.devPrevSneakPressed, target);
        logKeyTransition("forward", forwardPressed, runtime.devPrevForwardPressed, target);
        logKeyTransition("back", backPressed, runtime.devPrevBackPressed, target);
        logKeyTransition("left", leftPressed, runtime.devPrevLeftPressed, target);
        logKeyTransition("right", rightPressed, runtime.devPrevRightPressed, target);
        logKeyTransition("sprint", sprintPressed, runtime.devPrevSprintPressed, target);

        runtime.devPrevUsePressed = usePressed;
        runtime.devPrevAttackPressed = attackPressed;
        runtime.devPrevJumpPressed = jumpPressed;
        runtime.devPrevSneakPressed = sneakPressed;
        runtime.devPrevForwardPressed = forwardPressed;
        runtime.devPrevBackPressed = backPressed;
        runtime.devPrevLeftPressed = leftPressed;
        runtime.devPrevRightPressed = rightPressed;
        runtime.devPrevSprintPressed = sprintPressed;

        double dist = distanceToTargetHitbox(target);
        runtime.updateDevDistanceAfterHit(dist);

        long interval = Math.max(50L, intervalMs.get());
        if (now - runtime.devLastStateLogMs < interval) return;
        runtime.devLastStateLogMs = now;

        Vec3d velocity = module.client().player.getVelocity();
        double horizontalSpeedBps = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0;
        double verticalSpeedBps = velocity.y * 20.0;
        String detail = "dist=" + f2(dist)
            + " hSpeed=" + f2(horizontalSpeedBps)
            + " vSpeed=" + f2(verticalSpeedBps)
            + " gliding=" + module.client().player.isGliding()
            + " onGround=" + module.client().player.isOnGround()
            + " use=" + usePressed
            + " atk=" + attackPressed
            + " jump=" + jumpPressed
            + " sneak=" + sneakPressed;
        debugLogger.logDev("state", detail, target, runtime);
    }

    public void onPacketSend(Object packet) {
        if (!(packet instanceof IPlayerInteractEntityC2SPacket interact)) return;
        if (interact.meteor$getType() != PlayerInteractEntityC2SPacket.InteractType.ATTACK) return;

        long now = System.currentTimeMillis();
        Entity rawTarget = interact.meteor$getEntity();
        LivingEntity target = rawTarget instanceof LivingEntity living ? living : runtime.target;
        int targetId = rawTarget != null ? rawTarget.getId() : -1;
        runtime.onDevAttackPacket(now, targetId);

        String detail = "attack-packet targetId=" + targetId
            + " target=" + (rawTarget != null ? rawTarget.getName().getString() : "none");
        debugLogger.logDev("attack-packet", detail, target, runtime);
    }

    public void onPacketReceive(Object packet) {
        if (!(packet instanceof EntityStatusS2CPacket statusPacket)) return;
        int status = statusPacket.getStatus();
        if (status != 2 && status != 3) return;

        Integer entityIdObj = extractEntityId(packet);
        if (entityIdObj == null) return;
        int entityId = entityIdObj;

        boolean matchesPending = runtime.devPendingAttackTargetId >= 0 && entityId == runtime.devPendingAttackTargetId;
        boolean matchesCurrent = runtime.target != null && entityId == runtime.target.getId();
        if (!matchesPending && !matchesCurrent) return;

        LivingEntity target = null;
        if (module.client() != null && module.client().world != null) {
            Entity entity = module.client().world.getEntityById(entityId);
            if (entity instanceof LivingEntity living) target = living;
        }
        if (target == null) target = runtime.target;

        long now = System.currentTimeMillis();
        long attackAgeMs = runtime.devPendingAttackAtMs > 0L ? Math.max(0L, now - runtime.devPendingAttackAtMs) : -1L;
        double hitDistance = distanceToTargetHitbox(target);
        runtime.onDevHitConfirmed(now, entityId, hitDistance, "EntityStatus:" + status);

        String detail = "status=" + status
            + " entityId=" + entityId
            + " hitDist=" + f2(hitDistance)
            + " retreatPrev=" + f2(runtime.devLastRetreatDistance)
            + " returnDist=" + f2(runtime.devLastReturnDistance)
            + " attackAgeMs=" + attackAgeMs;
        debugLogger.logDev("hit-confirm", detail, target, runtime);
    }

    public void onDisable() {
        if (!armed) return;
        runtime.resetDevState();
        runtime.clearTargetAndWindup();
        armed = false;
        debugLogger.logDev("dev-exit", "automation-disabled=false", null, runtime);
    }

    private void arm() {
        combat.onDeactivate();
        flight.onDeactivate();
        runtime.clearTargetAndWindup();
        runtime.resetDevState();
        armed = true;
        debugLogger.logDev("dev-enter", "automation-disabled=true", null, runtime);
    }

    private void logKeyTransition(String keyName, boolean current, boolean previous, LivingEntity target) {
        if (current == previous) return;
        debugLogger.logDev("key", keyName + "=" + (current ? "down" : "up"), target, runtime);
    }

    private double distanceToTargetHitbox(LivingEntity target) {
        if (target == null || module.client() == null || module.client().player == null) return -1.0;
        return distanceFromPointToHitbox(module.client().player.getEntityPos(), target.getBoundingBox());
    }

    private double distanceFromPointToHitbox(Vec3d point, Box box) {
        if (point == null || box == null) return -1.0;
        double x = MathHelper.clamp(point.x, box.minX, box.maxX);
        double y = MathHelper.clamp(point.y, box.minY, box.maxY);
        double z = MathHelper.clamp(point.z, box.minZ, box.maxZ);
        return point.distanceTo(new Vec3d(x, y, z));
    }

    private Integer extractEntityId(Object packet) {
        Object value = invokeNoArg(packet, "getEntityId");
        if (value instanceof Integer i) return i;
        value = invokeNoArg(packet, "getId");
        if (value instanceof Integer i) return i;
        return null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String f2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}

