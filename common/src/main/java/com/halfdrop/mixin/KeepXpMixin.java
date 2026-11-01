package com.halfdrop.mixin;

import com.halfdrop.HalfdropCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 经验保留：{@code LivingEntity.dropExperience(Entity)} 是死亡掉落经验的入口。
 *
 * <p>当玩家生效配置里 {@code keepXP=true} 时取消这次经验掉落（等级与经验保留，
 * 重生后仍在）；其他生物或 {@code keepXP=false} 时保持原版行为。
 */
@Mixin(LivingEntity.class)
public abstract class KeepXpMixin {

    @Inject(method = "dropExperience", at = @At("HEAD"), cancellable = true)
    private void halfdrop$keepXp(Entity killer, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && HalfdropCore.settingsFor(player).keepXP) {
            ci.cancel();
        }
    }
}
