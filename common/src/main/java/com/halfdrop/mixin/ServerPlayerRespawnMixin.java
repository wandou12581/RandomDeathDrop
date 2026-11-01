package com.halfdrop.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 重生时把「死亡时保留在背包里」的物品带回新玩家。
 *
 * <p>原版 {@code PlayerList.respawn} 在新玩家上调用
 * {@code ServerPlayer.restoreFrom(oldPlayer, keepInventory)}：
 * keepInventory=true 时复制背包；false 时不复制——因为原版认为死亡时 {@code dropAll()}
 * 已经把背包清空，重生后本来就该是空的。
 *
 * <p>本模组只清空被随机选中的 50% 格，剩下的物品仍留在旧玩家背包里。若不补救，
 * 重生后这些物品会因为「新玩家没被复制背包」而凭空消失。这里在 restoreFrom 末尾把
 * 旧玩家背包复制给新玩家（keepInventory=true 时原版已复制，不重复处理）。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {

    @Inject(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at = @At("TAIL"))
    private void halfdrop$restoreKeptInventory(ServerPlayer that, boolean keepEverything, CallbackInfo ci) {
        if (keepEverything) {
            return;
        }
        Inventory kept = that.getInventory();
        if (kept == null) {
            return;
        }
        ((ServerPlayer) (Object) this).getInventory().replaceWith(kept);
    }
}
