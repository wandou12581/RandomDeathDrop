package com.halfdrop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 某个玩家生效的配置（global 与个人条目合并后的结果）。
 *
 * <p>名单规则：
 * <ul>
 *   <li>个人名单含关键字 {@code "glwhitelist"} / {@code "glblacklist"} → 继承全局（并集）</li>
 *   <li>不含关键字 → 只用个人项（不继承全局）</li>
 *   <li>个人名单未配置/为空 → 空名单（不继承全局，该玩家不受保护）</li>
 *   <li>{@code !item} → 从所在名单排除该项（支持通配符，判定时生效）</li>
 *   <li>玩家不在 players 里 → 用全局名单</li>
 * </ul>
 *
 * <p>判定顺序：黑名单命中 → 参与随机；白名单命中 → 保护；都不命中 → 参与随机。
 * 黑名单优先级高于白名单（黑名单命中即取消保护，即使白名单也命中）。
 *
 * <p>白名单/黑名单支持 glob：{@code *} 匹配任意长度、{@code ?} 匹配单字符。
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

    private final boolean hasPersonalEntry;
    private final List<Pattern> globalWhite;
    private final List<Pattern> globalBlack;
    private final Parsed personalWhite;
    private final Parsed personalBlack;

    /** 解析后的个人名单：是否继承全局 + 正项 + 排除项（!）。 */
    private static final class Parsed {
        static final Parsed EMPTY = new Parsed(false, Collections.emptyList(), Collections.emptyList());

        final boolean inherit;
        final List<Pattern> positives;
        final List<Pattern> negatives;

        Parsed(boolean inherit, List<Pattern> positives, List<Pattern> negatives) {
            this.inherit = inherit;
            this.positives = positives;
            this.negatives = negatives;
        }
    }

    public Settings(int dropPercent, boolean roundUp, boolean keepHotbar, boolean keepSelectedSlot,
                    boolean keepArmorSlot, boolean keepWeaponOffhand, int xpDropPercent,
                    boolean hasPersonalEntry,
                    Set<String> personalWhite, Set<String> personalBlack,
                    Set<String> globalWhite, Set<String> globalBlack) {
        this.dropPercent = Math.max(0, Math.min(100, dropPercent));
        this.roundUp = roundUp;
        this.keepHotbar = keepHotbar;
        this.keepSelectedSlot = keepSelectedSlot;
        this.keepArmorSlot = keepArmorSlot;
        this.keepWeaponOffhand = keepWeaponOffhand;
        this.xpDropPercent = Math.max(0, Math.min(100, xpDropPercent));
        this.hasPersonalEntry = hasPersonalEntry;
        this.globalWhite = compile(globalWhite);
        this.globalBlack = compile(globalBlack);
        this.personalWhite = parse(personalWhite, "glwhitelist");
        this.personalBlack = parse(personalBlack, "glblacklist");
    }

    /** 该物品是否受保护（不掉落、也不计入随机数）。 */
    public boolean protects(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (hasPersonalEntry) {
            if (blackHit(id)) {
                return false;
            }
            return whiteHit(id);
        }
        if (hit(globalBlack, id)) {
            return false;
        }
        return hit(globalWhite, id);
    }

    private boolean whiteHit(String id) {
        boolean matched = (personalWhite.inherit && hit(globalWhite, id)) || hit(personalWhite.positives, id);
        return matched && !hit(personalWhite.negatives, id);
    }

    private boolean blackHit(String id) {
        boolean matched = (personalBlack.inherit && hit(globalBlack, id)) || hit(personalBlack.positives, id);
        return matched && !hit(personalBlack.negatives, id);
    }

    private static Parsed parse(Set<String> entries, String keyword) {
        if (entries == null || entries.isEmpty()) {
            return Parsed.EMPTY;
        }
        boolean inherit = entries.contains(keyword);
        List<Pattern> positives = new ArrayList<>();
        List<Pattern> negatives = new ArrayList<>();
        for (String entry : entries) {
            if (entry.equals(keyword)) {
                continue;
            }
            if (entry.startsWith("!")) {
                String item = entry.substring(1).trim();
                if (!item.isEmpty()) {
                    negatives.add(globToPattern(item));
                }
            } else {
                positives.add(globToPattern(entry));
            }
        }
        return new Parsed(inherit, positives, negatives);
    }

    private static List<Pattern> compile(Set<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Pattern> list = new ArrayList<>(globs.size());
        for (String glob : globs) {
            list.add(globToPattern(glob));
        }
        return list;
    }

    private static boolean hit(List<Pattern> patterns, String id) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    /** glob → 正则：* 匹配任意长度，? 匹配单字符，其余字符按字面匹配。 */
    private static Pattern globToPattern(String glob) {
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
