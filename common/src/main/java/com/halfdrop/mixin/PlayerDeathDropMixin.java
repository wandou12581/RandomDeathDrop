package com.halfdrop.mixin;

import com.halfdrop.DeathDrop;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 {@code Inventory.dropAll()}（原版死亡掉落全部物品的唯一入口）：
 * 服务端玩家死亡时改成按规则随机掉落；客户端（单人游戏的客户端侧）与原版行为保持不变。
 */
@Mixin(Inventory.class)
public abstract class PlayerDeathDropMixin {

    @Inject(method = "dropAll()V", at = @At("HEAD"), cancellable = true)
    private void halfdrop$dropRandomPart(CallbackInfo ci) {
        Inventory self = (Inventory) (Object) this;
        if (self.player instanceof ServerPlayer serverPlayer) {
            DeathDrop.dropOnDeath(serverPlayer, self);
            ci.cancel();
        }
    }
}
