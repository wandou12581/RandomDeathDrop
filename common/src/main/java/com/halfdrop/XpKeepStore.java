package com.halfdrop;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「死亡时按比例保留」的等级与经验点，重生时由 ServerPlayerRespawnMixin 恢复。
 *
 * <p>原版 keepInventory=false 重生不会复制经验（新玩家从 0 开始），所以按比例保留的部分
 * 需要在重生时手动带回去；xpDropPercent=100（原版）时不写入，避免双倍经验。
 */
public final class XpKeepStore {
    public static final class Keep {
        public final int level;
        public final int total;

        public Keep(int level, int total) {
            this.level = level;
            this.total = total;
        }
    }

    private static final Map<UUID, Keep> KEPT = new ConcurrentHashMap<>();

    private XpKeepStore() {
    }

    public static void put(UUID id, int level, int total) {
        KEPT.put(id, new Keep(level, total));
    }

    public static Keep take(UUID id) {
        return KEPT.remove(id);
    }
}
