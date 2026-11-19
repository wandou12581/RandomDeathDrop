package com.halfdrop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台无关的模组核心：持有配置、补全玩家 uuid/name、按玩家解析生效设置、支持热重载。
 */
public final class HalfdropCore {
    public static final String MOD_ID = "halfdrop";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String CONFIG_FOLDER = "randomdeathdrop";
    private static final String CONFIG_FILE = "randomdeathdrop.json";

    private static Path configRoot;
    private static Path configPath;
    private static Config config = new Config();
    private static volatile boolean refreshed;

    /** usercache 缓存：name(小写) → UUID 列表；uuid → name。 */
    private static Map<String, List<UUID>> cacheByName = new HashMap<>();
    private static Map<String, String> cacheByUuid = new HashMap<>();

    private HalfdropCore() {
    }

    public static void init(Path configDir) {
        configRoot = configDir;
        configPath = configDir.resolve(CONFIG_FOLDER).resolve(CONFIG_FILE);
        reload();
    }

    /** 重新读取配置与名单文件（/randomdeathdrop reload 与 /rdd reload 会调用）。 */
    public static synchronized void reload() {
        reload(null);
    }

    /** 重新读取配置与名单文件；server 非空时顺带补全玩家并生成名单文件。 */
    public static synchronized void reload(MinecraftServer server) {
        if (configPath == null) {
            return;
        }
        config = Config.load(configPath);
        refreshed = false;
        if (server != null) {
            refreshPlayers(server);
            refreshed = true;
        }
        Config.Global g = config.global;
        LOGGER.info("[RandomDeathDrop] 配置已加载：whiteList={} blackList={} players={}",
                g.whiteList, g.blackList, config.players.size());
    }

    public static Config config() {
        return config;
    }

    /**
     * 补全玩家 uuid/name、处理重复、生成缺失的 players/&lt;uuid&gt;.json。
     * 需要服务器已启动（读 usercache）。
     */
    private static synchronized void refreshPlayers(MinecraftServer server) {
        if (config == null || config.players == null || config.players.isEmpty()) {
            return;
        }
        loadUsercache(server);
        Set<String> seen = new HashSet<>();
        for (Config.PlayerEntry entry : config.players) {
            if (entry == null) {
                continue;
            }
            boolean hasUuid = entry.uuid != null && !entry.uuid.isEmpty();
            boolean hasName = entry.name != null && !entry.name.isEmpty();
            if (!hasUuid && hasName) {
                String resolved = resolveUuid(entry.name);
                if (resolved != null) {
                    entry.uuid = resolved;
                    LOGGER.debug("[RandomDeathDrop] [DEBUG] 已为玩家 \"{}\" 补全 uuid: {}", entry.name, resolved);
                } else {
                    LOGGER.warn("[RandomDeathDrop] [WARN] 无法找到玩家 \"{}\" 的 uuid，个人配置暂不生效；"
                            + "该玩家进服后请执行 /rdd reload 尝试再次补全，若再次失败请手动补全！", entry.name);
                }
            } else if (hasUuid && !hasName) {
                String resolved = cacheByUuid.get(entry.uuid);
                if (resolved != null) {
                    entry.name = resolved;
                    LOGGER.debug("[RandomDeathDrop] [DEBUG] 已为 uuid {} 补全玩家名: {}", entry.uuid, resolved);
                } else {
                    LOGGER.warn("[RandomDeathDrop] [WARN] 无法找到 uuid {} 对应的玩家名，个人配置暂不生效", entry.uuid);
                }
            }
            if (entry.uuid == null || entry.uuid.isEmpty()) {
                continue;
            }
            String uuidKey = entry.uuid.toLowerCase(Locale.ROOT);
            if (seen.contains(uuidKey)) {
                LOGGER.warn("[RandomDeathDrop] [WARN] 玩家 \"{}\" 的 uuid {} 与已有条目重复，已忽略该玩家的个人配置",
                        entry.name == null ? "" : entry.name, entry.uuid);
                entry.uuid = null;
                continue;
            }
            seen.add(uuidKey);
            ensurePlayerListFile(uuidKey, entry.name);
        }
        // 补全后的 uuid/name 写回 config 的 players 段（保留注释）
        if (configPath != null) {
            Config.writePlayersSection(configPath, config.players);
        }
    }

    /** 生成 players/&lt;uuid&gt;.json（不存在时），并把按 name 暂存的旧名单迁移到 uuid 键。 */
    private static void ensurePlayerListFile(String uuidKey, String name) {
        Path dir = configPath.getParent();
        if (dir == null) {
            return;
        }
        Path playersDir = dir.resolve("players");
        Path file = playersDir.resolve(uuidKey + ".json");
        Config.PlayerList existing = config.lists.players.get(uuidKey);
        if (Files.exists(file)) {
            if (existing == null) {
                // 文件已存在但没读进来（可能刚被创建），补读
                Config.PlayerList pl = Config.readPlayerListFile(file);
                if (pl != null) {
                    config.lists.players.put(uuidKey, pl);
                }
            }
            return;
        }
        Config.PlayerList pl = existing;
        if (pl == null && name != null && !name.isEmpty()) {
            // 迁移：旧配置按 name 暂存的名单
            pl = config.lists.players.remove(name.toLowerCase(Locale.ROOT));
        }
        if (pl == null) {
            pl = new Config.PlayerList();
        }
        pl.name = name == null ? pl.name : name;
        config.lists.players.put(uuidKey, pl);
        Config.writePlayerListFile(file, pl);
    }

    private static void loadUsercache(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            // 用配置目录反推服务器根（NeoForge 的 getWorldPath 可能返回带 .\ 的路径）
            Path serverDir = configRoot != null ? configRoot.getParent() : null;
            if (serverDir == null) {
                serverDir = server.getWorldPath(LevelResource.ROOT).getParent();
                if (serverDir == null) {
                    return;
                }
                serverDir = serverDir.toAbsolutePath().normalize();
            }
            Path cache = serverDir.resolve("usercache.json");
            LOGGER.info("[RandomDeathDrop] usercache 检查：serverDir={} 存在={}", serverDir, Files.isRegularFile(cache));
            if (!Files.isRegularFile(cache)) {
                return;
            }
            String raw = Files.readString(cache, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonArray()) {
                return;
            }
            Map<String, List<UUID>> byName = new HashMap<>();
            Map<String, String> byUuid = new HashMap<>();
            for (JsonElement item : el.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                if (!obj.has("name") || !obj.has("uuid")) {
                    continue;
                }
                String name = obj.get("name").getAsString();
                String uuidStr = obj.get("uuid").getAsString();
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(uuid);
                    byUuid.putIfAbsent(uuid.toString(), name);
                } catch (IllegalArgumentException ignored) {
                    // 坏 uuid 跳过
                }
            }
            cacheByName = byName;
            cacheByUuid = byUuid;
        } catch (Exception e) {
            LOGGER.warn("[RandomDeathDrop] 读取 usercache 失败", e);
        }
    }

    /** name → uuid：优先正版（v4），其次离线 UUID。 */
    private static String resolveUuid(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        List<UUID> candidates = cacheByName.get(name.toLowerCase(Locale.ROOT));
        if (candidates != null) {
            for (UUID u : candidates) {
                if (u.version() == 4) {
                    return u.toString();
                }
            }
            for (UUID u : candidates) {
                if (u.equals(offline) || u.version() == 3) {
                    return u.toString();
                }
            }
        }
        return offline.toString();
    }

    /** 按 uuid 或玩家名匹配个人条目（uuid 优先）。 */
    private static Config.PlayerEntry findEntry(ServerPlayer player) {
        List<Config.PlayerEntry> entries = config.players;
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String uuid = player.getStringUUID().toLowerCase(Locale.ROOT);
        String name = player.getGameProfile().getName();
        String nameLower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (Config.PlayerEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.uuid != null && entry.uuid.toLowerCase(Locale.ROOT).equals(uuid)) {
                return entry;
            }
            if (entry.name != null && !nameLower.isEmpty()
                    && entry.name.toLowerCase(Locale.ROOT).equals(nameLower)) {
                return entry;
            }
        }
        return null;
    }

    public static Settings settingsFor(ServerPlayer player) {
        // 懒补全：有服务器且未补全过时，先补 uuid/name、生成玩家名单文件
        if (!refreshed) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                refreshPlayers(server);
                refreshed = true;
            }
        }
        Config.Global g = config.global;
        Config.PlayerEntry entry = findEntry(player);
        String uuid = player.getStringUUID().toLowerCase(Locale.ROOT);

        int dropPercent = g.dropPercent;
        boolean roundUp = g.roundUp;
        boolean keepHotbar = g.keepHotbar;
        boolean keepSelectedSlot = g.keepSelectedSlot;
        boolean keepArmorSlot = g.keepArmorSlot;
        boolean keepWeaponOffhand = g.keepWeaponOffhand;
        int xpDropPercent = g.xpDropPercent;
        boolean whiteMode = g.whiteList;
        boolean blackMode = g.blackList;

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
                whiteMode = entry.whiteList;
            }
            if (entry.blackList != null) {
                blackMode = entry.blackList;
            }
        }
        if (whiteMode && blackMode) {
            blackMode = false;
        }

        List<String> whiteList;
        List<String> blackList;
        if (entry != null) {
            // 名单文件按补全后的 uuid 命名（可能与玩家实际登录 uuid 不同，如正版/离线同名）
            String listKey = entry.uuid != null && !entry.uuid.isEmpty()
                    ? entry.uuid.toLowerCase(Locale.ROOT) : uuid;
            Config.PlayerList pl = config.lists.players.get(listKey);
            List<String> personalWhite = pl == null ? List.of() : pl.white;
            List<String> personalBlack = pl == null ? List.of() : pl.black;
            whiteList = resolveList(config.lists.globalWhite, personalWhite, "globalwhitelist");
            blackList = resolveList(config.lists.globalBlack, personalBlack, "globalblacklist");
        } else {
            whiteList = config.lists.globalWhite;
            blackList = config.lists.globalBlack;
        }

        return new Settings(dropPercent, roundUp, keepHotbar, keepSelectedSlot,
                keepArmorSlot, keepWeaponOffhand, xpDropPercent,
                whiteMode, blackMode, whiteList, blackList);
    }

    /** 个人名单 + 关键字继承 → 生效名单（"!item" 保留，由 Settings 判定时排除）。 */
    static List<String> resolveList(List<String> global, List<String> personal, String keyword) {
        List<String> base = new ArrayList<>();
        if (personal == null || personal.isEmpty()) {
            return base;
        }
        boolean inherit = personal.contains(keyword);
        if (inherit) {
            base.addAll(global);
        }
        for (String item : personal) {
            if (!item.equals(keyword)) {
                base.add(item);
            }
        }
        return base;
    }
}
