package net.shasankp000.PlayerUtils;

import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiningTool {

    private static final long ATTACK_INTERVAL_MS = 200;
    private static final double MAX_MINING_DISTANCE = 5.0;
    private static final int MAX_LEAF_OBSTRUCTIONS_TO_CLEAR = 4;
    private static final long LEAF_CLEAR_TIMEOUT_MS = 1200;
    public static final Logger LOGGER = LoggerFactory.getLogger("mining-tool");

    public static CompletableFuture<String> mineBlock(ServerPlayer bot, BlockPos targetBlockPos) {
        CompletableFuture<String> miningResult = new CompletableFuture<>();
        try {


                ScheduledExecutorService miningExecutor = Executors.newSingleThreadScheduledExecutor();

                // Step 1: Face the block
                LookController.faceBlock(bot, targetBlockPos);

                clearLeafObstructions(bot, targetBlockPos);

                if (!canReachVisibleBlock(bot, targetBlockPos)) {
                    miningResult.complete("❌ Cannot mine through blocks at " + targetBlockPos);
                    miningExecutor.shutdownNow();
                    return miningResult;
                }

                // Step 2: Select best tool
                BlockState blockState = bot.level().getBlockState(targetBlockPos);
                ItemStack bestTool = ToolSelector.selectBestToolForBlock(bot, blockState);
                if (bestTool.isEmpty()) {
                    if (blockState.requiresCorrectToolForDrops()) {
                        miningResult.complete("❌ No usable tool for " + blockState.getBlock().getName().getString() + " at " + targetBlockPos);
                        miningExecutor.shutdownNow();
                        return miningResult;
                    }
                    bestTool = bot.getMainHandItem();
                }

                // Step 3: Switch to that tool
                switchToTool(bot, bestTool);

                // Step 4: Start mining loop
                ScheduledFuture<?> task = miningExecutor.scheduleAtFixedRate(() -> {
                    BlockState currentState = bot.level().getBlockState(targetBlockPos);

                    if (currentState.isAir()) {
                        System.out.println("✅ Mining complete!");
                        miningResult.complete("Mining complete!");
                        miningExecutor.shutdownNow();
                        return;
                    }

                    if (!canReachVisibleBlock(bot, targetBlockPos)) {
                        clearLeafObstructions(bot, targetBlockPos);
                        if (!canReachVisibleBlock(bot, targetBlockPos)) {
                            String error = "❌ Cannot mine through blocks at " + targetBlockPos;
                            LOGGER.warn(error);
                            miningResult.complete(error);
                            miningExecutor.shutdownNow();
                            return;
                        }
                    }

                    bot.swing(bot.getUsedItemHand());
                    bot.gameMode.destroyBlock(targetBlockPos);
                    System.out.println("⛏️ Mining...");

                }, 0, ATTACK_INTERVAL_MS, TimeUnit.MILLISECONDS);

                // In case something else cancels this process
                miningResult.whenComplete((result, error) -> {
                    if (!task.isCancelled() && !task.isDone()) {
                        task.cancel(true);
                    }
                    if (!miningExecutor.isShutdown()) {
                        miningExecutor.shutdownNow();
                    }
                });


        }
        catch (Exception e) {
            LOGGER.error("Error in mining tool! {}", e.getMessage());
        }

        return miningResult;
    }

    private static void clearLeafObstructions(ServerPlayer bot, BlockPos targetBlockPos) {
        for (int attempts = 0; attempts < MAX_LEAF_OBSTRUCTIONS_TO_CLEAR; attempts++) {
            if (canReachVisibleBlock(bot, targetBlockPos)) {
                return;
            }

            BlockPos leafPos = findLeafObstruction(bot, targetBlockPos);
            if (leafPos == null) {
                return;
            }

            LOGGER.info("Clearing leaf obstruction {} before mining {}", leafPos, targetBlockPos);
            mineObstruction(bot, leafPos);
            LookController.faceBlock(bot, targetBlockPos);
        }
    }

    private static BlockPos findLeafObstruction(ServerPlayer bot, BlockPos targetBlockPos) {
        Level world = bot.level();
        for (Direction direction : Direction.values()) {
            Vec3 faceCenter = Vec3.atCenterOf(targetBlockPos).add(
                    direction.getStepX() * 0.5,
                    direction.getStepY() * 0.5,
                    direction.getStepZ() * 0.5
            );
            Vec3 endInsideTarget = faceCenter.add(
                    direction.getStepX() * -0.01,
                    direction.getStepY() * -0.01,
                    direction.getStepZ() * -0.01
            );
            BlockHitResult lineOfSight = world.clip(new ClipContext(
                    bot.getEyePosition(1.0F),
                    endInsideTarget,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    bot
            ));

            if (lineOfSight.getType() != HitResult.Type.BLOCK) {
                continue;
            }

            BlockPos hitPos = lineOfSight.getBlockPos();
            if (hitPos.equals(targetBlockPos)) {
                return null;
            }

            BlockState hitState = world.getBlockState(hitPos);
            if (hitState.is(BlockTags.LEAVES) && Math.sqrt(hitPos.distToCenterSqr(bot.position())) <= MAX_MINING_DISTANCE) {
                return hitPos;
            }
        }

        return null;
    }

    private static void mineObstruction(ServerPlayer bot, BlockPos obstructionPos) {
        try {
            BlockState obstructionState = bot.level().getBlockState(obstructionPos);
            ItemStack bestTool = ToolSelector.selectBestToolForBlock(bot, obstructionState);
            switchToTool(bot, bestTool);
            LookController.faceBlock(bot, obstructionPos);

            long deadline = System.currentTimeMillis() + LEAF_CLEAR_TIMEOUT_MS;
            while (!bot.level().getBlockState(obstructionPos).isAir() && System.currentTimeMillis() < deadline) {
                bot.swing(bot.getUsedItemHand());
                bot.gameMode.destroyBlock(obstructionPos);
                Thread.sleep(100L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Failed to clear leaf obstruction {}: {}", obstructionPos, e.getMessage());
        }
    }

    private static boolean canReachVisibleBlock(ServerPlayer bot, BlockPos targetBlockPos) {
        double distance = Math.sqrt(targetBlockPos.distToCenterSqr(bot.position()));
        if (distance > MAX_MINING_DISTANCE) {
            LOGGER.warn("Target block {} is too far to mine: {} blocks", targetBlockPos, distance);
            return false;
        }

        Level world = bot.level();
        BlockState targetState = world.getBlockState(targetBlockPos);
        if (targetState.isAir()) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            Vec3 faceCenter = Vec3.atCenterOf(targetBlockPos).add(
                    direction.getStepX() * 0.5,
                    direction.getStepY() * 0.5,
                    direction.getStepZ() * 0.5
            );
            Vec3 endInsideTarget = faceCenter.add(
                    direction.getStepX() * -0.01,
                    direction.getStepY() * -0.01,
                    direction.getStepZ() * -0.01
            );
            BlockHitResult lineOfSight = world.clip(new ClipContext(
                    bot.getEyePosition(1.0F),
                    endInsideTarget,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    bot
            ));
            if (lineOfSight.getType() == HitResult.Type.BLOCK && lineOfSight.getBlockPos().equals(targetBlockPos)) {
                return true;
            }
        }

        return false;
    }

    private static void switchToTool(ServerPlayer bot, ItemStack tool) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getItem(i) == tool) {
                bot.getInventory().setSelectedSlot(i);
                return;
            }
        }

        for (int i = 9; i < bot.getInventory().getContainerSize(); i++) {
            if (bot.getInventory().getItem(i) != tool) {
                continue;
            }

            int hotbarSlot = 8;
            for (int hotbar = 0; hotbar < 9; hotbar++) {
                if (bot.getInventory().getItem(hotbar).isEmpty()) {
                    hotbarSlot = hotbar;
                    break;
                }
            }

            ItemStack stackToMove = bot.getInventory().getItem(i);
            bot.getInventory().setItem(hotbarSlot, stackToMove.copy());
            bot.getInventory().setItem(i, ItemStack.EMPTY);
            bot.getInventory().setSelectedSlot(hotbarSlot);
            bot.getInventory().setChanged();
            return;
        }
    }

}
