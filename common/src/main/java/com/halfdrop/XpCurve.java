package com.halfdrop;

/** Minecraft 1.21.1 经验曲线：某等级起点的累计经验点。 */
public final class XpCurve {
    private XpCurve() {
    }

    public static int totalForLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        }
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }
}
