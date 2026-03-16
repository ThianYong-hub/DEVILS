package com.example.addon.modules.autopearl;

import com.example.addon.modules.AutoPearl;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

public final class AutoPearlTrajectory {
    private AutoPearlTrajectory() {
    }

    public static float calculatePitchToTarget(MinecraftClient mc, PlayerEntity target, float yaw, float fallbackPitch) {
        if (mc == null || mc.player == null || mc.world == null || target == null) return fallbackPitch;
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() * 0.5, 0);
        return calculatePitchToPoint(mc, targetCenter, yaw, fallbackPitch);
    }

    public static float calculatePitchToPoint(MinecraftClient mc, Vec3d targetPoint, float yaw, float fallbackPitch) {
        if (mc == null || mc.player == null || mc.world == null || targetPoint == null) return fallbackPitch;
        Vec3d eye = mc.player.getEyePos();

        float bestPitch = fallbackPitch;
        double bestScore = Double.MAX_VALUE;

        for (int deg = -5; deg >= -80; deg--) {
            double score = scorePitch(mc, eye, yaw, deg, targetPoint);
            if (score < bestScore) {
                bestScore = score;
                bestPitch = deg;
            }
        }
        return bestPitch;
    }

    private static double scorePitch(MinecraftClient mc, Vec3d eyePos, float yawDeg, float pitchDeg, Vec3d targetCenter) {
        if (mc.world == null) return Double.MAX_VALUE;

        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);

        double velX = -Math.sin(yaw) * cosPitch * 1.5;
        double velY = -sinPitch * 1.5;
        double velZ = Math.cos(yaw) * cosPitch * 1.5;

        double x = eyePos.x;
        double y = eyePos.y;
        double z = eyePos.z;
        double minDist = Double.MAX_VALUE;

        for (int tick = 0; tick < 200; tick++) {
            velY -= 0.03;
            velX *= 0.99;
            velY *= 0.99;
            velZ *= 0.99;

            x += velX;
            y += velY;
            z += velZ;

            if (y < mc.world.getBottomY()) break;

            BlockPos bp = BlockPos.ofFloored(x, y, z);
            BlockState state = mc.world.getBlockState(bp);

            if (!state.isAir() && !state.isReplaceable()) {
                Vec3d land = new Vec3d(x, y + 1.0, z);
                double d = land.distanceTo(targetCenter);
                if (d < minDist) minDist = d;
                break;
            }

            double dx = x - targetCenter.x;
            double dy = y - targetCenter.y;
            double dz = z - targetCenter.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < minDist) minDist = d;
        }

        return minDist;
    }

    public static final class RemoteControl {
        private static final MinecraftClient mc = MinecraftClient.getInstance();
        private final AutoPearl module;

        public RemoteControl(AutoPearl module) {
            this.module = module;
        }

        public void handleChat(ReceiveMessageEvent event) {
            if (mc.player == null) return;

            String msg = event.getMessage().getString();
            String prefix = module.commandPrefix.get().trim();
            if (prefix.isEmpty()) return;

            int cmdIdx = msg.indexOf(prefix);
            if (cmdIdx == -1) return;

            String auth = module.commandPlayer.get().trim();
            if (!auth.isEmpty()) {
                String beforeCmd = msg.substring(0, cmdIdx);
                if (!beforeCmd.toLowerCase().contains(auth.toLowerCase())) return;
            }

            String afterCmd = msg.substring(cmdIdx + prefix.length()).trim();
            if (afterCmd.isEmpty()) return;

            String arg = afterCmd.split("\\s+")[0];
            switch (arg.toLowerCase()) {
                case "auto", "reset" -> {
                    module.forcedTargetName = null;
                    module.target = null;
                    module.info("Target reset to auto-select.");
                }
                case "on" -> {
                    if (!module.isActive()) module.toggle();
                    module.info("Module enabled via chat command.");
                }
                case "off" -> {
                    if (module.isActive()) module.toggle();
                    module.info("Module disabled via chat command.");
                }
                default -> {
                    module.forcedTargetName = arg;
                    module.target = null;
                    module.info("Target forced to: В§e" + arg);
                }
            }
        }
    }

    public static final class EscapeHelper {
        private static final MinecraftClient mc = MinecraftClient.getInstance();
        private final AutoPearl module;

        public EscapeHelper(AutoPearl module) {
            this.module = module;
        }

        public boolean tryBreakFree() {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;

            Vec3d pos = mc.player.getPos();
            BlockPos feet = BlockPos.ofFloored(pos.x, pos.y + 0.05, pos.z);
            BlockPos body = BlockPos.ofFloored(pos.x, pos.y + 0.9, pos.z);
            BlockPos eyes = BlockPos.ofFloored(pos.x, pos.y + 1.62, pos.z);

            boolean feetSolid = isSolid(feet);
            boolean bodySolid = isSolid(body);
            boolean eyesSolid = isSolid(eyes);
            if (feetSolid && !bodySolid && !eyesSolid && mc.player.isOnGround()) {
                module.stuckTicks = 0;
                return false;
            }

            BlockPos stuckPos = eyesSolid ? eyes : bodySolid ? body : feetSolid ? feet : null;
            if (stuckPos == null) {
                module.stuckTicks = 0;
                return false;
            }

            module.stuckTicks++;
            if (module.stuckTicks >= AutoPearl.STUCK_CONFIRM_TICKS) {
                breakFreeFromBlock(stuckPos);
            }
            return true;
        }

        private boolean isSolid(BlockPos pos) {
            BlockState state = mc.world.getBlockState(pos);
            return !state.isAir() && !state.isReplaceable() && mc.world.getFluidState(pos).isEmpty();
        }

        private void breakFreeFromBlock(BlockPos pos) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.getHardness(mc.world, pos) < 0) return;

            int pickSlot = findPickaxeSlot();
            if (pickSlot != -1) InvUtils.swap(pickSlot, false);
            mc.interactionManager.attackBlock(pos, Direction.UP);
            if (pickSlot != -1) InvUtils.swapBack();
        }

        private int findPickaxeSlot() {
            if (mc.player == null) return -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) return i;
            }
            return -1;
        }
    }

    public static final class Targeting {
        private Targeting() {
        }

        public static boolean isValidTarget(
            MinecraftClient mc,
            PlayerEntity target,
            boolean ignoreFriends,
            boolean skipGliding,
            double maxHeightDiff,
            double range
        ) {
            if (mc == null || mc.player == null || target == null) return false;
            if (target == mc.player) return false;
            if (target.isDead() || target.getHealth() <= 0) return false;
            if (ignoreFriends && Friends.get().isFriend(target)) return false;
            if (skipGliding && target.isGliding()) return false;
            if (target.getY() - mc.player.getY() > maxHeightDiff) return false;
            return mc.player.distanceTo(target) <= range;
        }

        public static PlayerEntity resolveTarget(
            MinecraftClient mc,
            PlayerEntity currentTarget,
            String forcedTargetName,
            SortPriority priority,
            Predicate<PlayerEntity> validTarget
        ) {
            if (mc == null || mc.player == null || mc.world == null) return null;

            if (forcedTargetName != null) {
                PlayerEntity forced = findPlayerByName(mc, forcedTargetName);
                if (forced != null && forced != mc.player && !forced.isDead() && forced.getHealth() > 0) {
                    return forced;
                }
                return null;
            }

            if (currentTarget != null && validTarget.test(currentTarget)) return currentTarget;
            return (PlayerEntity) TargetUtils.get(entity -> entity instanceof PlayerEntity p && validTarget.test(p), priority);
        }

        public static PlayerEntity findPlayerByName(MinecraftClient mc, String name) {
            if (mc == null || mc.world == null || name == null) return null;
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player.getGameProfile().getName().equalsIgnoreCase(name)) return player;
            }
            return null;
        }
    }
}

