package com.halfdrop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.Set;

/**
 * 某个玩家生效的配置（global 与个人条目合并后的结果）。
 *
 * <p>白名单 = global.whiteList ∪ 个人 whiteList；个人 blackList 优先级最高：
 * 命中黑名单的物品即使出现在白名单里也不受保护（正常参与随机）。
 */
public final class Settings {
    public final int dropPercent;
    public final boolean roundUp;
    public final boolean keepHotbar;
    public final boolean keepSelectedSlot;
    public final boolean keepArmorSlot;
    public final boolean keepWeaponOffhand;
    public final boolean keepXP;

    private final Set<String> whiteList;
    private final Set<String> blackList;

    public Settings(int dropPercent, boolean roundUp, boolean keepHotbar, boolean keepSelectedSlot,
                    boolean keepArmorSlot, boolean keepWeaponOffhand, boolean keepXP,
                    Set<String> whiteList, Set<String> blackList) {
        this.dropPercent = Math.max(0, Math.min(100, dropPercent));
        this.roundUp = roundUp;
        this.keepHotbar = keepHotbar;
        this.keepSelectedSlot = keepSelectedSlot;
        this.keepArmorSlot = keepArmorSlot;
        this.keepWeaponOffhand = keepWeaponOffhand;
        this.keepXP = keepXP;
        this.whiteList = whiteList == null ? Collections.emptySet() : whiteList;
        this.blackList = blackList == null ? Collections.emptySet() : blackList;
    }

    /** 该物品是否受白名单保护（不掉落、也不计入随机数）。 */
    public boolean protects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (blackList.contains(id)) {
            return false;
        }
        return whiteList.contains(id);
    }
}
