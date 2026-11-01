package com.halfdrop;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Fabric 侧命令：{@code /randomdeathdrop reload} 与 {@code /rdd reload}（需要 OP 权限）。
 *
 * <p>两个命令指向同一执行逻辑；旧的 {@code /halfdrop} 已移除。
 */
public final class HalfDropCommands {
    private HalfDropCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("randomdeathdrop").then(reloadNode()));
            dispatcher.register(Commands.literal("rdd").then(reloadNode()));
        });
    }

    private static LiteralCommandNode<CommandSourceStack> reloadNode() {
        return Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(HalfDropCommands::executeReload)
                .build();
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        HalfdropCore.reload();
        context.getSource().sendSuccess(() -> Component.literal("[RandomDeathDrop] 配置已重载"), true);
        return 1;
    }
}
