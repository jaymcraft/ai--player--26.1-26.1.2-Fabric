package net.shasankp000.PlayerUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class ToolSelector {

    public static ItemStack selectBestToolForBlock(ServerPlayer bot, BlockState blockState) {
        ItemStack bestTool = ItemStack.EMPTY;
        float highestSpeed = 0.0f;

        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            ItemStack item = bot.getInventory().getItem(slot);
            if (item.isEmpty()) continue;
            if (!hasEnoughDurabilityForMining(item)) continue;

            float speed = item.getDestroySpeed(blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                bestTool = item;
            }
        }

        if (highestSpeed <= 1.0f) {
            return ItemStack.EMPTY;
        }

        return bestTool;
    }

    private static boolean hasEnoughDurabilityForMining(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1;
    }
}
