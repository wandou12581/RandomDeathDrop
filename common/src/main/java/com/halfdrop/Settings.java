package com.halfdrop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 某个玩家生效的配置（global 与个人条目合并后的结果）。
 *
 * <p>两种模式二选一：
 * <ul>
 *   <li>白名单模式（whiteMode）：默认全部参与随机，名单内物品<b>不掉落</b>（保护）</li>
 *   <li>黑名单模式（blackMode）：默认全部<b>不掉落</b>，名单内物品参与随机；名单外完全不掉</li>
 * </ul>
 *
 * <p>名单支持 glob：{@code *} 任意长度、{@code ?} 单字符；{@code !item} 从名单移除。
 * 个人名单里写 {@code globalWhiteList} / {@code globalBlackList} 关键字 = 继承全局对应名单。
 */
public final class Settings {
    public final int dropPercent;
    public final boolean roundUp;
    public final boolean keepHotbar;
    public final boolean keepSelectedSlot;
    public final boolean keepArmorSlot;
    public final boolean keepWeaponOffhand;
    /** 经验掉落百分比（按等级），0 = 不掉经验，100 = 原版全掉。 */
    public final int xpDropPercent;

    private final boolean whiteMode;
    private final boolean blackMode;
    private final List<Pattern> whitePos;
    private final List<Pattern> whiteNeg;
    private final List<Pattern> blackPos;
    private final List<Pattern> blackNeg;

    public Settings(int dropPercent, boolean roundUp, boolean keepHotbar, boolean keepSelectedSlot,
                    boolean keepArmorSlot, boolean keepWeaponOffhand, int xpDropPercent,
                    boolean whiteMode, boolean blackMode,
                    List<String> whiteList, List<String> blackList) {
        this.dropPercent = Math.max(0, Math.min(100, dropPercent));
        this.roundUp = roundUp;
        this.keepHotbar = keepHotbar;
        this.keepSelectedSlot = keepSelectedSlot;
        this.keepArmorSlot = keepArmorSlot;
        this.keepWeaponOffhand = keepWeaponOffhand;
        this.xpDropPercent = Math.max(0, Math.min(100, xpDropPercent));
        this.whiteMode = whiteMode;
        this.blackMode = blackMode && !whiteMode;
        Compiled white = compile(whiteList);
        Compiled black = compile(blackList);
        this.whitePos = white.pos;
        this.whiteNeg = white.neg;
        this.blackPos = black.pos;
        this.blackNeg = black.neg;
    }

    /** 该物品是否受保护（不掉落、也不计入随机数）。 */
    public boolean protects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (blackMode) {
            // 黑名单：名单内（且未被 ! 排除）参与随机，名单外完全不掉
            boolean listed = hit(blackPos, id) && !hit(blackNeg, id);
            return !listed;
        }
        // 白名单：名单内（且未被 ! 排除）保护
        return hit(whitePos, id) && !hit(whiteNeg, id);
    }

    private static final class Compiled {
        final List<Pattern> pos;
        final List<Pattern> neg;

        Compiled(List<Pattern> pos, List<Pattern> neg) {
            this.pos = pos;
            this.neg = neg;
        }
    }

    /** 把名单编译成 glob 正则；"!item" 归入负项（判定时排除）。 */
    static Compiled compile(List<String> globs) {
        List<Pattern> pos = new ArrayList<>();
        List<Pattern> neg = new ArrayList<>();
        if (globs != null) {
            for (String glob : globs) {
                if (glob == null || glob.isEmpty()) {
                    continue;
                }
                if (glob.startsWith("!")) {
                    String target = glob.substring(1).trim();
                    if (!target.isEmpty()) {
                        neg.add(globToPattern(target));
                    }
                } else {
                    pos.add(globToPattern(glob));
                }
            }
        }
        return new Compiled(pos, neg);
    }

    static boolean hit(List<Pattern> patterns, String id) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    /** glob → 正则：* 匹配任意长度，? 匹配单字符。 */
    static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
