package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.autowasp.AutoWaspFlightController;
import com.example.addon.modules.autowasp.AutoWaspPathfinder;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Vector3dSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

public class AutoWasp extends Module {
    private static final Direction[] CARDINAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int TARGET_SEARCH_TICKS = 10;
    private static final int AUTO_ARMOR_COMMAND_COOLDOWN_TICKS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChestSwap = settings.createGroup("Chest Swap");

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal elytra speed.")
        .defaultValue(2.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical elytra speed.")
        .defaultValue(3.0)
        .build()
    );

    private final Setting<Boolean> avoidLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-landing")
        .description("Will try to avoid landing if your target is on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Tries to predict the targets position according to their movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("only-friends")
        .description("Will only follow friends.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> friendFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("friend-filter")
        .description("When enabled: onlyFriends=true -> friends, onlyFriends=false -> enemies. When disabled: original logic.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action-on-target-loss")
        .description("What to do if you lose the target.")
        .defaultValue(Action.TOGGLE)
        .build()
    );

    private final Setting<Boolean> keepSearching = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-searching")
        .description("Stays enabled and keeps looking for a target instead of disabling when none is loaded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Vector3d> offset = sgGeneral.add(new Vector3dSetting.Builder()
        .name("offset")
        .description("How many blocks offset to wasp at from the target.")
        .defaultValue(0, 0, 0)
        .build()
    );

    private final Setting<Boolean> autoChestSwap = sgChestSwap.add(new BoolSetting.Builder()
        .name("auto-chest-swap")
        .description("Uses AutoArmor chat commands to swap off elytra when you are close enough to fight on stable ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> chestSwapHorizontalRange = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-horizontal-range")
        .description("Maximum horizontal distance to the target before swapping off elytra.")
        .defaultValue(2.5)
        .range(0.5, 6.0)
        .sliderRange(0.5, 6.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> chestSwapVerticalRange = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-vertical-range")
        .description("Maximum vertical difference to the target before swapping off elytra.")
        .defaultValue(1.0)
        .range(0.0, 4.0)
        .sliderRange(0.0, 4.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> chestSwapGroundDistance = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-ground-distance")
        .description("Only swaps off elytra when there is solid ground close enough below you.")
        .defaultValue(1.25)
        .range(0.0, 4.0)
        .sliderRange(0.0, 4.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final AutoWaspPathfinder pathfinder = new AutoWaspPathfinder(this);
    private final AutoWaspFlightController flightController = new AutoWaspFlightController(this, pathfinder);

    private PlayerEntity target;
    private int targetSearchTimer;
    private boolean waitingForElytra;
    private int autoArmorCommandCooldown;
    private Boolean autoArmorElytraEnabled;

    public AutoWasp() {
        super(AddonTemplate.CATEGORY, "auto-wasp", "Follows a target with elytra using obstacle-aware routing.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) return;

        resetNavigationState();
        autoArmorCommandCooldown = 0;
        autoArmorElytraEnabled = hasEquippedElytra();

        if (target == null || target.isRemoved()) {
            if (tryAcquireTarget(false)) {
                info("Target set to: " + target.getName().getString());
            } else if (!keepSearching.get()) {
                error("No valid targets.");
                toggle();
            }
        }
    }

    @Override
    public void onDeactivate() {
        target = null;
        targetSearchTimer = 0;
        waitingForElytra = false;
        autoArmorCommandCooldown = 0;
        autoArmorElytraEnabled = null;
        flightController.onDeactivate();
        pathfinder.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe());
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        CrashGuard.run(this, "onMove", () -> onMoveSafe(event));
    }

    private void onTickSafe() {
        if (mc.player == null || mc.world == null) return;

        pathfinder.tickCaches();
        if (autoArmorCommandCooldown > 0) autoArmorCommandCooldown--;
        if (targetSearchTimer > 0) targetSearchTimer--;

        if (target == null || target.isRemoved() || target.isDead() || target.getHealth() <= 0) {
            if (!handleMissingTarget()) {
                tickAutoChestSwap();
                return;
            }
        }

        if (target == null) {
            tickAutoChestSwap();
            return;
        }

        boolean isFriend = Friends.get().isFriend(target);
        boolean shouldIgnore = friendFilter.get()
            ? (onlyFriends.get() && !isFriend) || (!onlyFriends.get() && isFriend)
            : onlyFriends.get() && !isFriend;
        if (shouldIgnore) {
            clearTargetState();
            if (!handleMissingTarget()) {
                tickAutoChestSwap();
                return;
            }
        }

        if (target == null) {
            tickAutoChestSwap();
            return;
        }

        boolean shouldUseElytra = tickAutoChestSwap();
        if (!hasEquippedElytra()) {
            if (!shouldUseElytra) {
                waitingForElytra = false;
                flightController.resetFlightTracking();
                return;
            }
            if (!waitingForElytra) {
                warning("No elytra equipped, waiting for swap...");
                waitingForElytra = true;
            }
            flightController.resetFlightTracking();
            return;
        }

        waitingForElytra = false;
        if (!mc.player.isGliding()) {
            flightController.handleNotGliding();
            return;
        }

        flightController.onGlidingTick();
        pathfinder.tickPathing(com.example.addon.util.EntityPositionCompat.pos(mc.player), getTargetPos(), flightController.getStuckTicks());
    }

    private void onMoveSafe(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (target == null || target.isRemoved() || target.isDead()) return;
        if (!hasEquippedElytra() || !mc.player.isGliding()) return;

        Vec3d targetPos = getTargetPos();
        flightController.onMove(event, pathfinder.steerTarget(targetPos));
    }

    private void findTarget() {
        target = (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;

            boolean isFriend = Friends.get().isFriend(player);
            if (friendFilter.get()) return onlyFriends.get() ? isFriend : !isFriend;
            return !onlyFriends.get() || isFriend;
        }, SortPriority.LowestDistance);
    }

    private Vec3d getTargetPos() {
        if (target == null || mc.player == null) return mc.player != null ? com.example.addon.util.EntityPositionCompat.pos(mc.player) : Vec3d.ZERO;

        Vec3d targetPos = com.example.addon.util.EntityPositionCompat.pos(target).add(offset.get().x, offset.get().y, offset.get().z);
        if (predictMovement.get()) targetPos = targetPos.add(target.getVelocity());
        if (avoidLanding.get()) targetPos = liftTargetIfOnGround(targetPos);
        return pathfinder.adjustToSafeCorridor(targetPos, com.example.addon.util.EntityPositionCompat.pos(mc.player));
    }

    private Vec3d liftTargetIfOnGround(Vec3d targetPos) {
        if (target == null) return targetPos;

        double distance = Math.max(0.35, target.getBoundingBox().getLengthX() / 2.0);
        for (Direction direction : CARDINAL_DIRECTIONS) {
            BlockPos pos = BlockPos.ofFloored(targetPos.offset(direction, distance).offset(direction.rotateYClockwise(), distance)).down();
            if (pathfinder.isSolid(pos) && Math.abs(targetPos.getY() - (pos.getY() + 1)) <= 0.35) {
                return new Vec3d(targetPos.x, pos.getY() + 1.35, targetPos.z);
            }
        }
        return targetPos;
    }

    private boolean tickAutoChestSwap() {
        boolean shouldUseElytra = !shouldSwapToChestplate();
        requestAutoArmorElytra(shouldUseElytra);
        return shouldUseElytra;
    }

    private boolean shouldSwapToChestplate() {
        if (!autoChestSwap.get() || mc.player == null || target == null) return false;

        double horizontalDistance = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        if (horizontalDistance > chestSwapHorizontalRange.get()) return false;

        double verticalDistance = Math.abs(mc.player.getY() - target.getY());
        if (verticalDistance > chestSwapVerticalRange.get()) return false;

        int scanDepth = Math.max(3, MathHelper.ceil(chestSwapGroundDistance.get()) + 2);
        double groundDistance = pathfinder.distanceToSolidBelow(com.example.addon.util.EntityPositionCompat.pos(mc.player), scanDepth);
        return groundDistance <= chestSwapGroundDistance.get();
    }

    private void requestAutoArmorElytra(boolean enabled) {
        if (mc.player == null || mc.player.networkHandler == null) return;

        boolean elytraEquipped = hasEquippedElytra();
        if (autoArmorElytraEnabled != null && autoArmorElytraEnabled == enabled && elytraEquipped == enabled) return;
        if (autoArmorCommandCooldown > 0) return;

        ChatUtils.sendPlayerMsg(";autoarmor Elytra " + enabled);
        autoArmorElytraEnabled = enabled;
        autoArmorCommandCooldown = AUTO_ARMOR_COMMAND_COOLDOWN_TICKS;
    }

    private boolean hasEquippedElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER);
    }

    private boolean handleMissingTarget() {
        clearTargetState();
        if (tryAcquireTarget(true)) {
            info("New target: " + target.getName().getString());
            return true;
        }
        if (keepSearching.get()) return false;

        handleTargetLoss();
        return false;
    }

    private boolean tryAcquireTarget(boolean useCooldown) {
        if (useCooldown && targetSearchTimer > 0) return false;

        findTarget();
        targetSearchTimer = TARGET_SEARCH_TICKS;
        return target != null;
    }

    private void clearTargetState() {
        target = null;
        pathfinder.clearTargetState();
        flightController.clearStuckState();
    }

    private void resetNavigationState() {
        targetSearchTimer = 0;
        pathfinder.resetNavigationState();
        flightController.resetNavigationState();
    }

    private void handleTargetLoss() {
        warning("Lost target!");

        switch (action.get()) {
            case CHOOSE_NEW_TARGET -> {
                findTarget();
                if (target == null) {
                    error("No valid targets found.");
                    toggle();
                } else {
                    info("New target: " + target.getName().getString());
                }
            }
            case TOGGLE -> toggle();
            case DISCONNECT -> {
                if (mc.player != null && mc.player.networkHandler != null) {
                    mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(
                        Text.literal("%s[%sAuto Wasp%s] Lost target.".formatted(Formatting.GRAY, Formatting.BLUE, Formatting.GRAY))
                    ));
                }
            }
        }
    }

    public MinecraftClient client() {
        return mc;
    }

    public double horizontalSpeedValue() {
        return horizontalSpeed.get();
    }

    public double verticalSpeedValue() {
        return verticalSpeed.get();
    }

    public enum Action {
        TOGGLE("Toggle module"),
        CHOOSE_NEW_TARGET("Choose new target"),
        DISCONNECT("Disconnect");

        private final String title;

        Action(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}


