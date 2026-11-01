package com.halfdrop;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NeoForge 平台入口：把 NeoForge 的 config 目录交给共享核心。
 *
 * <p>1.21.1 的 FML 不是默认 javafml，元数据里必须显式声明
 * {@code modLoader="javafml"} 与 {@code loaderVersion}，否则加载器直接拒载。
 */
@Mod(HalfdropCore.MOD_ID)
public class HalfDropNeo {
    public HalfDropNeo(IEventBus modEventBus) {
        HalfdropCore.init(FMLPaths.CONFIGDIR.get());
    }
}
