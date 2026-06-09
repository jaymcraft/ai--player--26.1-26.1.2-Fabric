package net.shasankp000.PlayerUtils;

import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.AutoFaceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostileMobAttackTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("hostile-mob-attack");
    public static final double MELEE_ATTACK_REACH = 3.0;
    private static final double MAX_VISIBLE_TARGET_RANGE = 16.0;

    public static String attackHostileMobIfSeeable(ServerPlayer bot, MinecraftServer server, CommandSourceStack botSource) {
        if (bot == null || server == null || botSource == null) {
            return "Cannot attack: bot, server, or command source is unavailable.";
        }

        Entity target = findBestVisibleHostileMob(bot);
        if (target == null) {
            return "No seeable hostile mob found within " + (int) MAX_VISIBLE_TARGET_RANGE + " blocks.";
        }

        faceEntity(bot, target);

        String botName = bot.getName().getString();
        String targetName = target.getName().getString();
        double distance = bot.position().distanceTo(target.position());

        if (distance > MELEE_ATTACK_REACH && RangedWeaponUtils.hasBowOrCrossbow(bot) && RangedWeaponUtils.getAmmoType(bot) != null) {
            AutoFaceEntity.setShootingTarget(target);
            server.getCommands().performPrefixedCommand(botSource, "/bot shoot_arrow " + botName + " false");
            return "Attacking seeable hostile mob " + targetName + " at "
                    + String.format("%.1f", distance) + " blocks with a ranged weapon.";
        }

        if (distance > MELEE_ATTACK_REACH) {
            return "Target " + targetName + " is "
                    + String.format("%.1f", distance) + " blocks away, outside the "
                    + String.format("%.1f", MELEE_ATTACK_REACH) + " block melee attack reach.";
        }

        String heldWeapon = equipSwordOrEmptyHand(bot);
        faceEntity(bot, target);
        bot.attack(target);
        bot.swing(InteractionHand.MAIN_HAND);

        return "Attacked seeable hostile mob " + targetName + " at "
                + String.format("%.1f", distance) + " blocks"
                + " with " + heldWeapon + ".";
    }

    public static Entity findBestVisibleHostileMob(ServerPlayer bot) {
        return AutoFaceEntity.detectNearbyEntities(bot, MAX_VISIBLE_TARGET_RANGE).stream()
                .filter(HostileMobAttackTool::isAttackableHostileMob)
                .filter(entity -> hasLineOfSight(bot, entity))
                .min(Comparator
                        .comparingDouble((Entity entity) -> threatPriority(entity))
                        .reversed()
                        .thenComparingDouble(entity -> entity.distanceToSqr(bot)))
                .orElse(null);
    }

    private static boolean isAttackableHostileMob(Entity entity) {
        return entity instanceof Enemy
                && entity instanceof LivingEntity livingEntity
                && livingEntity.isAlive()
                && !entity.isRemoved();
    }

    private static String equipSwordOrEmptyHand(ServerPlayer bot) {
        int swordSlot = findBestSwordSlot(bot);
        if (swordSlot >= 0) {
            int selectedSlot = moveInventorySlotToHotbarIfNeeded(bot, swordSlot);
            bot.getInventory().setSelectedSlot(selectedSlot);
            return bot.getInventory().getItem(selectedSlot).getItem().getName(bot.getInventory().getItem(selectedSlot)).getString();
        }

        int emptyHotbarSlot = findEmptyHotbarSlot(bot);
        if (emptyHotbarSlot >= 0) {
            bot.getInventory().setSelectedSlot(emptyHotbarSlot);
            return "fist";
        }

        int emptyInventorySlot = findEmptyInventorySlot(bot);
        if (emptyInventorySlot >= 0) {
            int selectedSlot = bot.getInventory().getSelectedSlot();
            ItemStack selectedStack = bot.getInventory().getItem(selectedSlot);
            bot.getInventory().setItem(emptyInventorySlot, selectedStack);
            bot.getInventory().setItem(selectedSlot, ItemStack.EMPTY);
            bot.getInventory().setChanged();
            return "fist";
        }

        return "the currently held item because no sword or empty inventory slot was available";
    }

    private static int findBestSwordSlot(ServerPlayer bot) {
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.is(ItemTags.SWORDS)) {
                continue;
            }

            int score = scoreSword(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private static int scoreSword(ItemStack stack) {
        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int score = 0;

        if (itemPath.contains("netherite")) {
            score += 50;
        } else if (itemPath.contains("diamond")) {
            score += 40;
        } else if (itemPath.contains("iron")) {
            score += 30;
        } else if (itemPath.contains("stone")) {
            score += 20;
        } else if (itemPath.contains("golden")) {
            score += 15;
        } else if (itemPath.contains("wooden")) {
            score += 10;
        }

        if (stack.hasFoil()) {
            score += 5;
        }

        return score;
    }

    private static int moveInventorySlotToHotbarIfNeeded(ServerPlayer bot, int sourceSlot) {
        if (sourceSlot < 9) {
            return sourceSlot;
        }

        int targetHotbarSlot = findEmptyHotbarSlot(bot);
        if (targetHotbarSlot < 0) {
            targetHotbarSlot = bot.getInventory().getSelectedSlot();
        }

        ItemStack swordStack = bot.getInventory().getItem(sourceSlot);
        ItemStack hotbarStack = bot.getInventory().getItem(targetHotbarSlot);
        bot.getInventory().setItem(sourceSlot, hotbarStack);
        bot.getInventory().setItem(targetHotbarSlot, swordStack);
        bot.getInventory().setChanged();

        return targetHotbarSlot;
    }

    private static int findEmptyHotbarSlot(ServerPlayer bot) {
        for (int slot = 0; slot < 9; slot++) {
            if (bot.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    private static int findEmptyInventorySlot(ServerPlayer bot) {
        for (int slot = 9; slot < 36; slot++) {
            if (bot.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    private static double threatPriority(Entity entity) {
        String name = entity.getName().getString().toLowerCase();
        if (name.contains("creeper")) {
            return 100.0;
        }
        if (name.contains("warden")) {
            return 95.0;
        }
        if (name.contains("skeleton") || name.contains("witch") || name.contains("blaze")
                || name.contains("pillager") || name.contains("ghast")) {
            return 80.0;
        }
        return 50.0;
    }

    public static boolean hasLineOfSight(ServerPlayer bot, Entity target) {
        Vec3 eyePos = bot.getEyePosition();
        List<Vec3> targetPoints = List.of(
                target.getEyePosition(),
                target.position().add(0.0, target.getBbHeight() * 0.5, 0.0)
        );

        for (Vec3 targetPoint : targetPoints) {
            HitResult hitResult = bot.level().clip(new ClipContext(
                    eyePos,
                    targetPoint,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    bot
            ));

            if (hitResult.getType() == HitResult.Type.MISS) {
                return true;
            }
        }

        LOGGER.debug("Hostile mob {} is blocked from line of sight", target.getName().getString());
        return false;
    }

    private static void faceEntity(ServerPlayer bot, Entity target) {
        Vec3 botEye = bot.getEyePosition();
        Vec3 targetPoint = target.getEyePosition();
        Vec3 direction = targetPoint.subtract(botEye).normalize();

        double yaw = Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0;
        double horizontalDistance = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        double pitch = Math.toDegrees(-Math.atan2(direction.y, horizontalDistance));

        bot.setYRot((float) yaw);
        bot.setXRot((float) pitch);
    }
}
