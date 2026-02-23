package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.AbstractBlockAccessor;
import meteordevelopment.meteorclient.mixin.DirectionAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

public class AutoWasp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private final Setting<Vector3d> offset = sgGeneral.add(new Vector3dSetting.Builder()
        .name("offset")
        .description("How many blocks offset to wasp at from the target.")
        .defaultValue(0, 0, 0)
        .build()
    );

    public PlayerEntity target;
    private int jumpTimer = 0;
    private boolean incrementJumpTimer = false;

    public AutoWasp() {
        super(AddonTemplate.CATEGORY, "auto-wasp", "Wasps for you. Unable to traverse around blocks, assumes a clear straight line to the target.");
    }

    @Override
    public void onActivate() {
        if (target == null || target.isRemoved()) {
            findTarget();
            
            if (target == null) {
                error("No valid targets.");
                toggle();
                return;
            } else {
                info("Target set to: " + target.getName().getString());
            }
        }

        jumpTimer = 0;
        incrementJumpTimer = false;
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    private void findTarget() {
        target = (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            
            boolean isFriend = Friends.get().isFriend(player);
            
            if (friendFilter.get()) {
                // Новая логика:
                // Если onlyFriends включен -> выбираем только друзей
                // Если onlyFriends выключен -> выбираем только НЕ-друзей
                if (onlyFriends.get()) {
                    return isFriend; // Только друзья
                } else {
                    return !isFriend; // Только не-друзья
                }
            } else {
                // Оригинальная логика:
                // Если onlyFriends включен -> выбираем только друзей
                // Если onlyFriends выключен -> выбираем всех (и друзей, и не-друзей)
                if (onlyFriends.get()) {
                    return isFriend; // Только друзья
                } else {
                    return true; // Все игроки (оригинальное поведение)
                }
            }
        }, SortPriority.LowestDistance);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Проверяем, есть ли валидная цель
        if (target == null || target.isRemoved() || target.isDead() || target.getHealth() <= 0) {
            handleTargetLoss();
            return;
        }
        
        // Проверяем, должен ли target быть игнорирован согласно текущим настройкам
        boolean isFriend = Friends.get().isFriend(target);
        boolean shouldIgnore = false;
        
        if (friendFilter.get()) {
            // Новая логика
            shouldIgnore = (onlyFriends.get() && !isFriend) || (!onlyFriends.get() && isFriend);
        } else {
            // Оригинальная логика
            shouldIgnore = onlyFriends.get() && !isFriend;
        }
        
        if (shouldIgnore) {
            // Target больше не подходит под критерии
            warning("Current target no longer matches criteria, finding new target...");
            findTarget();
            
            if (target == null) {
                handleTargetLoss();
                return;
            } else {
                info("New target: " + target.getName().getString());
            }
        }

        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) {
            error("No elytra equipped!");
            toggle();
            return;
        }

        if (incrementJumpTimer) {
            jumpTimer++;
        }

        if (!mc.player.isGliding()) {
            if (!incrementJumpTimer) incrementJumpTimer = true;

            if (mc.player.isOnGround() && incrementJumpTimer) {
                mc.player.jump();
                return;
            }

            if (jumpTimer >= 4) {
                jumpTimer = 0;
                mc.player.setJumping(false);
                mc.player.setSprinting(true);
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
        } else {
            incrementJumpTimer = false;
            jumpTimer = 0;
        }
    }

    private void handleTargetLoss() {
        warning("Lost target!");

        switch (action.get()) {
            case CHOOSE_NEW_TARGET:
                findTarget();
                if (target == null) {
                    error("No valid targets found.");
                    toggle();
                } else {
                    info("New target: " + target.getName().getString());
                }
                break;
                
            case TOGGLE:
                toggle();
                break;
                
            case DISCONNECT:
                if (mc.player != null && mc.player.networkHandler != null) {
                    mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(
                        Text.literal("%s[%sAuto Wasp%s] Lost target."
                            .formatted(Formatting.GRAY, Formatting.BLUE, Formatting.GRAY))
                    ));
                }
                break;
        }
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        if (target == null || target.isRemoved() || target.isDead()) return;
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) return;
        if (!mc.player.isGliding()) return;

        double xVel = 0, yVel = 0, zVel = 0;

        Vec3d targetPos = target.getPos().add(offset.get().x, offset.get().y, offset.get().z);

        if (predictMovement.get()) {
            targetPos = targetPos.add(target.getVelocity());
        }

        if (avoidLanding.get()) {
            double d = target.getBoundingBox().getLengthX() / 2;

            for (Direction dir : DirectionAccessor.meteor$getHorizontal()) {
                BlockPos pos = BlockPos.ofFloored(targetPos.offset(dir, d).offset(dir.rotateYClockwise(), d)).down();
                if (((AbstractBlockAccessor) mc.world.getBlockState(pos).getBlock()).meteor$isCollidable() && 
                    Math.abs(targetPos.getY() - (pos.getY() + 1)) <= 0.25) {
                    targetPos = new Vec3d(targetPos.x, pos.getY() + 1.25, targetPos.z);
                    break;
                }
            }
        }

        double xDist = targetPos.getX() - mc.player.getX();
        double zDist = targetPos.getZ() - mc.player.getZ();
        double yDist = targetPos.getY() - mc.player.getY();

        double absX = Math.abs(xDist);
        double absZ = Math.abs(zDist);
        double absY = Math.abs(yDist);

        // Горизонтальное движение
        double diag = 0;
        if (absX > 1.0E-5F && absZ > 1.0E-5F) {
            diag = 1 / Math.sqrt(absX * absX + absZ * absZ);
        }

        if (absX > 1.0E-5F) {
            if (absX < horizontalSpeed.get()) {
                xVel = xDist;
            } else {
                xVel = horizontalSpeed.get() * Math.signum(xDist);
            }

            if (diag != 0) {
                xVel *= (absX * diag);
            }
        }

        if (absZ > 1.0E-5F) {
            if (absZ < horizontalSpeed.get()) {
                zVel = zDist;
            } else {
                zVel = horizontalSpeed.get() * Math.signum(zDist);
            }

            if (diag != 0) {
                zVel *= (absZ * diag);
            }
        }

        // Вертикальное движение
        if (absY > 1.0E-5F) {
            if (absY < verticalSpeed.get()) {
                yVel = yDist;
            } else {
                yVel = verticalSpeed.get() * Math.signum(yDist);
            }
        }

        ((IVec3d) event.movement).meteor$set(xVel, yVel, zVel);
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
