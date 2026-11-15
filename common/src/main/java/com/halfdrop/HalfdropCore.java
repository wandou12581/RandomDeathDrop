package com.halfdrop;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 平台无关的模组核心：持有配置、按玩家解析生效设置、支持热重载。
 *
 * <p>Fabric 侧在 {@code ModInitializer} 里传入 {@code FabricLoader.getConfigDir()}，
 * NeoForge 侧在 {@code @Mod} 构造器里传入 {@code FMLPaths.CONFIGDIR.get()}。
 */
public final class HalfdropCore {
    public static final String MOD_ID = "halfdrop";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 配置子文件夹名（config/randomdeathdrop/）。 */
    private static final String CONFIG_FOLDER = "randomdeathdrop";
    /** 配置文件名。 */
    private static final String CONFIG_FILE = "randomdeathdrop.json";

    private static Path configRoot;
    private static Path configPath;
    private static Config config = new Config();

    private HalfdropCore() {
    }

    public static void init(Path configDir) {
        configRoot = configDir;
        configPath = configDir.resolve(CONFIG_FOLDER).resolve(CONFIG_FILE);
        reload();
    }

    /** 重新读取配置文件（/randomdeathdrop reload 与 /rdd reload 会调用）。 */
    public static synchronized void reload() {
        if (configPath == null || configRoot == null) {
            return;
        }
        config = Config.load(configPath, List.of(
                configRoot.resolve(CONFIG_FILE),          // 旧位置：config/randomdeathdrop.json
                configRoot.resolve(MOD_ID + ".json")      // 更旧：config/halfdrop.json
        ));
        Config.Global g = config.global;
        LOGGER.info("[RandomDeathDrop] loaded: dropPercent={}% roundUp={} keepHotbar={} keepSelectedSlot={} "
                        + "keepArmorSlot={} keepWeaponOffhand={} xpDropPercent={} whiteList={} blackList={} players={}",
                g.dropPercent, g.roundUp, g.keepHotbar, g.keepSelectedSlot,
                g.keepArmorSlot, g.keepWeaponOffhand, g.xpDropPercent,
                g.glWhiteList.size(), g.glBlackList.size(), config.players.size());
    }

    public static Config config() {
        return config;
    }

    /**
     * 解析某个玩家生效的配置：个人字段为 null 时继承 global。
     *
     * <p>白名单/黑名单保持分层（不合并），便于按「个人 > 全局」判定：
     * personalBlack &gt; personalWhite &gt; globalBlack &gt; globalWhite。
     */
    public static Settings settingsFor(ServerPlayer player) {
        Config.Global g = config.global;
        Config.PlayerEntry entry = findEntry(player);

        int dropPercent = g.dropPercent;
        boolean roundUp = g.roundUp;
        boolean keepHotbar = g.keepHotbar;
        boolean keepSelectedSlot = g.keepSelectedSlot;
        boolean keepArmorSlot = g.keepArmorSlot;
        boolean keepWeaponOffhand = g.keepWeaponOffhand;
        int xpDropPercent = g.xpDropPercent;

        Set<String> personalWhite = new HashSet<>();
        Set<String> personalBlack = new HashSet<>();
        Set<String> globalWhite = new HashSet<>(g.glWhiteList);
        Set<String> globalBlack = new HashSet<>(g.glBlackList);

        if (entry != null) {
            if (entry.dropPercent != null) {
                dropPercent = entry.dropPercent;
            }
            if (entry.roundUp != null) {
                roundUp = entry.roundUp;
            }
            if (entry.keepHotbar != null) {
                keepHotbar = entry.keepHotbar;
            }
            if (entry.keepSelectedSlot != null) {
                keepSelectedSlot = entry.keepSelectedSlot;
            }
            if (entry.keepArmorSlot != null) {
                keepArmorSlot = entry.keepArmorSlot;
            }
            if (entry.keepWeaponOffhand != null) {
                keepWeaponOffhand = entry.keepWeaponOffhand;
            }
            if (entry.xpDropPercent != null) {
                xpDropPercent = entry.xpDropPercent;
            }
            if (entry.whiteList != null) {
                personalWhite.addAll(entry.whiteList);
            }
            if (entry.blackList != null) {
                personalBlack.addAll(entry.blackList);
            }
        }

        return new Settings(dropPercent, roundUp, keepHotbar, keepSelectedSlot,
                keepArmorSlot, keepWeaponOffhand, xpDropPercent,
                entry != null,
                personalWhite, personalBlack, globalWhite, globalBlack);
    }

    /** 按 UUID 或玩家名字（忽略大小写）匹配个人条目。 */
    private static Config.PlayerEntry findEntry(ServerPlayer player) {
        List<Config.PlayerEntry> entries = config.players;
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String uuid = player.getUUID().toString();
        String name = player.getGameProfile().getName();
        String nameLower = name == null ? "" : name.toLowerCase(Locale.ROOT);

        for (Config.PlayerEntry entry : entries) {
            if (entry == null || entry.id == null) {
                continue;
            }
            String id = entry.id.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (id.equalsIgnoreCase(uuid)) {
                return entry;
            }
            if (!nameLower.isEmpty() && id.toLowerCase(Locale.ROOT).equals(nameLower)) {
                return entry;
            }
        }
        return null;
    }
}
