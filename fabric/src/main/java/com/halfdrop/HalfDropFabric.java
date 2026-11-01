package com.halfdrop;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 平台入口：把 Fabric 的配置目录交给共享核心。
 */
public class HalfDropFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        HalfdropCore.init(FabricLoader.getInstance().getConfigDir());
        HalfDropCommands.register();
    }
}
