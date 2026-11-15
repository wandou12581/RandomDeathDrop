package com.halfdrop.mixin;

import com.halfdrop.XpKeepStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 重生时把「死亡时保留在背包里」的物品和「按比例保留」的经验带回新玩家。
 *
 * <p>原版 {@code PlayerList.respawn} 在新玩家上调用
 * {@code ServerPlayer.restoreFrom(oldPlayer, keepInventory)}：
 * keepInventory=true 时复制背包与经验；false 时不复制——因为原版认为死亡时
 * {@code dropAll()} 已经把背包清空、经验也按原版规则掉完了。
 *
 * <p>本模组只清空被随机选中的物品格、并按 xpDropPercent 保留部分经验，
 * 剩下的部分仍留在旧玩家对象里。这里在 restoreFrom 末尾把背包和保留经验复制给新玩家。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {

    @Inject(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at = @At("TAIL"))
    private void halfdrop$restoreKept(ServerPlayer that, boolean keepEverything, CallbackInfo ci) {
        if (keepEverything) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;

        Inventory kept = that.getInventory();
        if (kept != null) {
            self.getInventory().replaceWith(kept);
        }

        // 按比例保留的经验：死亡时由 KeepXpMixin 写入
        XpKeepStore.Keep keep = XpKeepStore.take(that.getUUID());
        if (keep != null) {
            self.experienceLevel = keep.level;
            self.totalExperience = keep.total;
            self.experienceProgress = 0.0f;
        }
    }
}
