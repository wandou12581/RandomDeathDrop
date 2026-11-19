package com.halfdrop.mixin;

import com.halfdrop.HalfdropCore;
import com.halfdrop.Settings;
import com.halfdrop.XpCurve;
import com.halfdrop.XpKeepStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按等级掉落经验：{@code LivingEntity.dropExperience(Entity)} 是死亡掉落经验的入口。
 *
 * <p>xpDropPercent=100 时保持原版（掉全部经验）；0 时不掉；
 * 0-100 之间按等级计算：掉落等级 = ceil/floor(当前等级 × xpDropPercent%)，
 * 取整方向与物品共用 roundUp。掉落的等级换算成经验点生成经验球，
 * 玩家剩余「等级 - 掉落等级」并清零进度。
 *
 * <p>剩余部分通过 XpKeepStore 记录，重生时由 ServerPlayerRespawnMixin 恢复
 * （原版 keepInventory=false 不复制经验）。
 */
@Mixin(LivingEntity.class)
public abstract class KeepXpMixin {

    @Inject(method = "dropExperience", at = @At("HEAD"), cancellable = true)
    private void halfdrop$dropPartialXp(Entity killer, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) {
            return;
        }
        Settings s = HalfdropCore.settingsFor(player);
        int pct = s.xpDropPercent;
        if (pct >= 100) {
            return; // 原版行为
        }

        int level = player.experienceLevel;
        int dropLevels = s.roundUp
                ? (int) Math.ceil(level * (pct / 100.0))
                : (int) Math.floor(level * (pct / 100.0));
        if (dropLevels > level) {
            dropLevels = level;
        }
        if (dropLevels <= 0) {
            // 不掉经验：保留原等级与进度
            ci.cancel();
            return;
        }

        int keepLevels = level - dropLevels;
        // 按等级曲线计算：掉落 = 当前等级起点经验 - 剩余等级起点经验（进度清零）
        int levelXp = XpCurve.totalForLevel(level);
        int keepXp = XpCurve.totalForLevel(keepLevels);
        int dropXp = levelXp - keepXp;
        if (dropXp > 0 && player.level() instanceof ServerLevel serverLevel) {
            ExperienceOrb.award(serverLevel, player.position(), dropXp);
        }

        player.experienceLevel = keepLevels;
        player.totalExperience = keepXp;
        player.experienceProgress = 0.0f;
        XpKeepStore.put(player.getUUID(), keepLevels, keepXp);
        ci.cancel();
    }
}
