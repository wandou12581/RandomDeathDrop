package com.halfdrop;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * NeoForge 侧命令：{@code /randomdeathdrop reload} 与 {@code /rdd reload}（需要 OP 权限）。
 *
 * <p>两个命令指向同一执行逻辑；旧的 {@code /halfdrop} 已移除。
 */
@EventBusSubscriber(modid = HalfdropCore.MOD_ID)
public final class NeoCommands {
    private NeoCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("randomdeathdrop").then(reloadNode()));
        event.getDispatcher().register(Commands.literal("rdd").then(reloadNode()));
    }

    private static LiteralCommandNode<CommandSourceStack> reloadNode() {
        return Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(NeoCommands::executeReload)
                .build();
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        HalfdropCore.reload();
        context.getSource().sendSuccess(() -> Component.literal("[RandomDeathDrop] 配置已重载"), true);
        return 1;
    }
}
