package com.halfdrop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 死亡掉落计算：把原版「掉落全部」替换成「按格子随机掉落一部分」。
 *
 * <p>参与随机的槽位由配置决定（槽位 0-8 快捷栏、9-35 主背包、36-39 盔甲、40 副手）：
 * <ul>
 *   <li>主背包 27 格恒参与；</li>
 *   <li>快捷栏仅当 {@code keepHotbar=false}（此时 {@code keepSelectedSlot} 可再保手持格）；</li>
 *   <li>盔甲仅当 {@code keepArmorSlot=false}；副手仅当 {@code keepWeaponOffhand=false}；</li>
 *   <li>白名单命中的物品不掉落，也不计入随机数（个人黑名单可覆盖）；空格子同样不计数。</li>
 * </ul>
 *
 * <p>性能：只在死亡瞬间执行一次，最多遍历 41 格 + 对前 k 个做部分 Fisher-Yates，
 * 之后每格调一次原版 {@code player.drop(...)}；没有 tick 循环、没有全局扫描。
 */
public final class DeathDrop {
    /** 快捷栏 9 格 + 主背包 27 格。 */
    private static final int MAIN_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;
    /** 盔甲槽 36-39，副手槽 40。 */
    private static final int ARMOR_START = 36;
    private static final int ARMOR_END = 40;
    private static final int OFFHAND_SLOT = 40;

    private DeathDrop() {
    }

    public static void dropOnDeath(ServerPlayer player, Inventory inventory) {
        Settings s = HalfdropCore.settingsFor(player);
        List<Integer> candidates = new ArrayList<>(MAIN_SIZE + 5);

        int selected = inventory.selected;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            if (s.keepHotbar && slot < HOTBAR_SIZE) {
                continue;
            }
            if (!s.keepHotbar && s.keepSelectedSlot && slot == selected) {
                continue;
            }
            addIfCandidate(inventory, slot, s, candidates);
        }

        if (!s.keepArmorSlot) {
            for (int slot = ARMOR_START; slot < ARMOR_END; slot++) {
                addIfCandidate(inventory, slot, s, candidates);
            }
        }
        if (!s.keepWeaponOffhand) {
            addIfCandidate(inventory, OFFHAND_SLOT, s, candidates);
        }

        int n = candidates.size();
        if (n == 0) {
            return;
        }

        // 掉落格数 = ceil/floor(n * dropPercent%)；除不尽时按 roundUp 决定方向
        int k = s.roundUp
                ? (int) Math.ceil(n * (s.dropPercent / 100.0))
                : (int) Math.floor(n * (s.dropPercent / 100.0));
        if (k <= 0) {
            return;
        }
        if (k > n) {
            k = n;
        }

        // 部分洗牌：只洗前 k 个，O(k)，避免整表随机排序
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < k; i++) {
            int j = i + random.nextInt(n - i);
            int tmp = candidates.get(i);
            candidates.set(i, candidates.get(j));
            candidates.set(j, tmp);
        }

        for (int i = 0; i < k; i++) {
            int slot = candidates.get(i);
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 与原版 PlayerInventory.dropAll() 完全相同的调用：
            // 随机方向散开、40 tick 拾取延迟、归属于该玩家（thrower UUID），动画与原版一致。
            player.drop(stack, true, false);
            inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    private static void addIfCandidate(Inventory inventory, int slot, Settings s, List<Integer> out) {
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        if (s.protects(stack)) {
            // 白名单：不掉落，也不计入随机数
            return;
        }
        out.add(slot);
    }
}
