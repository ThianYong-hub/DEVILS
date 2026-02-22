package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;

public class PearlHelper extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgTargeting = this.settings.createGroup("Targeting");
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    // General Settings
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range to search for players.")
        .defaultValue(50.0d)
        .range(5.0d, 150.0d)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay between pearl throws in ticks.")
        .defaultValue(15)
        .range(1, 100)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disables the module when no pearls are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotateToTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-to-target")
        .description("Rotates head before throwing (required for servers).")
        .defaultValue(true)
        .build()
    );

    // Targeting Settings
    private final Setting<Boolean> onlyFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("only-friends")
        .description("Only target friends.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> friendFilter = sgTargeting.add(new BoolSetting.Builder()
        .name("friend-filter")
        .description("When enabled: onlyFriends=true -> friends, onlyFriends=false -> enemies.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SortPriority> sortPriority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("sort-priority")
        .description("Target priority.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    // Render Settings
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Target color.")
        .defaultValue(Color.GREEN)
        .build()
    );

    private final Setting<SettingColor> searchColor = sgRender.add(new ColorSetting.Builder()
        .name("search-color")
        .description("Search range color.")
        .defaultValue(Color.MAGENTA)
        .build()
    );

    private final Setting<Boolean> showRange = sgRender.add(new BoolSetting.Builder()
        .name("show-range")
        .description("Show search range.")
        .defaultValue(false)
        .build()
    );

    private PlayerEntity target = null;
    private int ticksSincePearl = 0;

    public PearlHelper() {
        super(AddonTemplate.CATEGORY, "pearl-aim", "Automatically throws ender pearls at nearby players.");
    }

    @Override
    public void onActivate() {
        ticksSincePearl = 0;
        target = null;
        findTarget();
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        ticksSincePearl++;

        // Validate current target
        if (target != null && !isValidTarget(target)) {
            target = null;
        }

        // Find new target if needed
        if (target == null) {
            findTarget();
        }

        // Try to throw if delay is met
        if (target != null && ticksSincePearl >= delay.get()) {
            if (throwPearl(target)) {
                ticksSincePearl = 0;
            }
        }

        // Render
        if (showRange.get() && mc.player != null) {
            renderSearchRange(event);
        }
        if (target != null) {
            renderTargetBox(event, target);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (autoDisable.get() && findPearlInHotbar() == -1) {
            warning("No ender pearls!");
            toggle();
        }
    }

    private void findTarget() {
        if (mc.world == null) return;

        target = (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;

            boolean isFriend = Friends.get().isFriend(player);

            if (friendFilter.get()) {
                if (onlyFriends.get()) {
                    return isFriend;
                } else {
                    return !isFriend;
                }
            } else {
                if (onlyFriends.get()) {
                    return isFriend;
                } else {
                    return true;
                }
            }
        }, sortPriority.get());
    }

    private boolean isValidTarget(PlayerEntity player) {
        if (player == null || player.isRemoved() || player.isDead() || player.getHealth() <= 0) {
            return false;
        }

        boolean isFriend = Friends.get().isFriend(player);

        if (friendFilter.get()) {
            return (onlyFriends.get() && isFriend) || (!onlyFriends.get() && !isFriend);
        } else {
            return !onlyFriends.get() || isFriend;
        }
    }

    /**
     * Throws ender pearl with ballistic arc trajectory.
     * Uses optimal 45-degree angle.
     */
    private boolean throwPearl(PlayerEntity target) {
        if (mc.player == null || mc.world == null) return false;

        // Player safety checks
        if (mc.player.isDead() || mc.player.getHealth() <= 0) return false;
        if (mc.player.isGliding()) return false;
        if (mc.player.isTouchingWater()) return false;

        // Target safety checks
        if (target == null || target.isRemoved() || target.isDead() || target.getHealth() <= 0) return false;
        if (target.isGliding()) return false;
        if (target.isTouchingWater()) return false;

        // Check for pearl
        int pearlSlot = findPearlInHotbar();
        if (pearlSlot == -1) return false;

        Vec3d fromPos = mc.player.getPos();
        Vec3d toPos = target.getPos();

        // Distance check (1.0 - range blocks)
        double distance = fromPos.distanceTo(toPos);
        if (distance < 1.0 || distance > range.get()) return false;

        // Calculate ballistic throw vector with 45 degrees
        Vec3d throwDir = calculateBallisticThrow(fromPos, toPos);
        if (throwDir == null) return false;

        // Validate complete trajectory
        if (!isTrajectatorySafe(fromPos, throwDir)) return false;

        // All checks passed - throw!
        if (rotateToTarget.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target, Target.Body), 
                () -> performPearlThrow(pearlSlot));
        } else {
            performPearlThrow(pearlSlot);
        }

        return true;
    }

    /**
     * Calculates ballistic arc trajectory to hit target.
     * Uses optimal 45-degree angle for distance.
     */
    private Vec3d calculateBallisticThrow(Vec3d fromPos, Vec3d toPos) {
        Vec3d eyePos = fromPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        Vec3d delta = toPos.subtract(eyePos);

        double dx = delta.x;
        double dz = delta.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.1) return null;

        // Normalize horizontal direction
        double dirX = dx / horizontalDist;
        double dirZ = dz / horizontalDist;

        // Optimal ballistic arc: 45 degrees
        double arc = Math.toRadians(45);
        double cosArc = Math.cos(arc);
        double sinArc = Math.sin(arc);

        // Initial speed - slightly higher to ensure reach
        double speed = 1.6;

        // Calculate velocity components
        double velX = dirX * cosArc * speed;
        double velY = sinArc * speed;
        double velZ = dirZ * cosArc * speed;

        return new Vec3d(velX, velY, velZ);
    }

    /**
     * Simulates entire pearl trajectory to find landing spot.
     * Simple validation: just check if landing spot is safe.
     */
    private boolean isTrajectatorySafe(Vec3d fromPos, Vec3d throwDir) {
        Vec3d eyePos = fromPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);

        double posX = eyePos.x;
        double posY = eyePos.y;
        double posZ = eyePos.z;

        double velX = throwDir.x;
        double velY = throwDir.y;
        double velZ = throwDir.z;

        final double GRAVITY = 0.03;
        final double DRAG = 0.99;
        final int MAX_TICKS = 120;

        // Simulate flight - find landing spot
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            // Apply physics
            velY -= GRAVITY;
            velX *= DRAG;
            velY *= DRAG;
            velZ *= DRAG;

            // Update position
            posX += velX;
            posY += velY;
            posZ += velZ;

            // Check for block collision
            BlockPos currPos = BlockPos.ofFloored(posX, posY, posZ);
            BlockState currState = mc.world.getBlockState(currPos);

            // Only check collision after leaving throw area (1+ block away)
            double distFromThrow = Math.sqrt(
                (posX - eyePos.x) * (posX - eyePos.x) +
                (posY - eyePos.y) * (posY - eyePos.y) +
                (posZ - eyePos.z) * (posZ - eyePos.z)
            );

            if (!currState.isReplaceable() && distFromThrow > 0.5) {
                // Hit block - landing spot is one above
                Vec3d landPos = new Vec3d(currPos.getX() + 0.5, currPos.getY() + 1.0, currPos.getZ() + 0.5);
                return isLandingSpotSafe(landPos);
            }

            // Check if speed is too low (pearl stopped)
            double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
            if (speed < 0.05 && tick > 10) {
                // Landed in air - check if spot is safe
                Vec3d landPos = new Vec3d(posX, posY + 0.5, posZ);
                return isLandingSpotSafe(landPos);
            }

            // Below ground
            if (posY < -64) return false;
        }

        // Flew way too far - reject
        return false;
    }

    /**
     * Checks if the path between two points is clear of solid blocks.
     * Uses raycasting approach to detect blocks along trajectory.
     */
    private boolean isPathClear(double x1, double y1, double z1, double x2, double y2, double z2, Vec3d throwOrigin) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.01) return true;
        
        // Check every 0.05 blocks along path for more precision
        int steps = (int) Math.ceil(distance / 0.05);
        
        for (int i = 0; i <= steps; i++) {
            double t = (steps == 0) ? 1.0 : (double) i / steps;
            double checkX = x1 + dx * t;
            double checkY = y1 + dy * t;
            double checkZ = z1 + dz * t;
            
            BlockPos blockPos = BlockPos.ofFloored(checkX, checkY, checkZ);
            BlockState state = mc.world.getBlockState(blockPos);
            
            if (!state.isReplaceable()) {
                // Hit block - check distance from throw origin
                double distFromThrow = Math.sqrt(
                    (checkX - throwOrigin.x) * (checkX - throwOrigin.x) +
                    (checkY - throwOrigin.y) * (checkY - throwOrigin.y) +
                    (checkZ - throwOrigin.z) * (checkZ - throwOrigin.z)
                );
                
                // If too close = suffocation risk (very strict: 0.2 blocks)
                if (distFromThrow < 0.2) return false;
            }
        }
        
        return true;
    }

    /**
     * Checks if landing position is safe (not suffocating).
     */
    private boolean isLandingSpotSafe(Vec3d pos) {
        BlockPos center = BlockPos.ofFloored(pos);

        // Check center - must be air
        BlockState centerState = mc.world.getBlockState(center);
        if (!centerState.isReplaceable()) return false;

        // Check head level - must be air
        BlockState headState = mc.world.getBlockState(center.up());
        if (!headState.isReplaceable()) return false;

        // That's it - simple safety check. Landing spot is valid
        return true;
    }

    private void performPearlThrow(int pearlSlot) {
        int oldSlot = mc.player.getInventory().getSelectedSlot();

        try {
            mc.player.getInventory().setSelectedSlot(pearlSlot);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        } finally {
            mc.player.getInventory().setSelectedSlot(oldSlot);
        }
    }

    private int findPearlInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }

    private void renderTargetBox(Render3DEvent event, PlayerEntity player) {
        Box box = player.getBoundingBox();
        event.renderer.box(box, targetColor.get(), targetColor.get(), ShapeMode.Both, 0);
    }

    private void renderSearchRange(Render3DEvent event) {
        Vec3d playerPos = mc.player.getPos();
        Box rangeBox = new Box(
            playerPos.x - range.get(), playerPos.y - range.get(), playerPos.z - range.get(),
            playerPos.x + range.get(), playerPos.y + range.get(), playerPos.z + range.get()
        );
        event.renderer.box(rangeBox, searchColor.get(), searchColor.get(), ShapeMode.Lines, 0);
    }
}
