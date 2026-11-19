package com.halfdrop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

/**
 * 配置文件（JSONC）：config/randomdeathdrop/randomdeathdrop.json。
 *
 * <p>主配置只存「开关 + 数值参数 + 玩家索引」，名单数据放在同目录：
 * <ul>
 *   <li>globalWhiteList.json / globalBlackList.json：纯 JSON 数组</li>
 *   <li>players/&lt;uuid&gt;.json：对象 { name, whiteList, blackList }</li>
 * </ul>
 *
 * <p>白名单模式（whiteList=true）：默认全部参与随机，名单内不掉落。<br>
 * 黑名单模式（blackList=true）：默认全部不掉落，名单内参与随机；名单外完全不掉。
 */
public final class Config {
    public Global global = new Global();
    public List<PlayerEntry> players = new ArrayList<>();
    public Lists lists = new Lists();

    /** 本次加载是否修正了黑白名单冲突（需要写回文件）。 */
    public transient boolean conflictFixed;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Global {
        public int dropPercent = 50;
        public boolean roundUp = true;
        public boolean keepHotbar = true;
        public boolean keepSelectedSlot = true;
        public boolean keepArmorSlot = true;
        public boolean keepWeaponOffhand = true;
        public int xpDropPercent = 100;
        /** 白名单模式开关（与 blackList 二选一）。 */
        public boolean whiteList = true;
        /** 黑名单模式开关（与 whiteList 二选一）。 */
        public boolean blackList = false;
    }

    public static final class PlayerEntry {
        /** 玩家 UUID（可空，加载时用 name 补全）。 */
        public String uuid;
        /** 玩家名（可空，加载时用 uuid 补全）。 */
        public String name;
        public Integer dropPercent;
        public Boolean roundUp;
        public Boolean keepHotbar;
        public Boolean keepSelectedSlot;
        public Boolean keepArmorSlot;
        public Boolean keepWeaponOffhand;
        public Integer xpDropPercent;
        /** 个人白名单开关，缺省继承 global。 */
        public Boolean whiteList;
        /** 个人黑名单开关，缺省继承 global。 */
        public Boolean blackList;
    }

    /** 从名单文件加载的数据。 */
    public static final class Lists {
        public List<String> globalWhite = new ArrayList<>();
        public List<String> globalBlack = new ArrayList<>();
        /** key = uuid 小写。 */
        public Map<String, PlayerList> players = new HashMap<>();
    }

    public static final class PlayerList {
        public String name;
        public List<String> white = new ArrayList<>();
        public List<String> black = new ArrayList<>();
    }

    /** 读取配置；文件不存在时生成模板。 */
    public static Config load(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return parse(Files.readString(path, StandardCharsets.UTF_8), path);
            }
            Config cfg = new Config();
            writeTemplate(path, cfg);
            return cfg;
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] could not read/write config {}", path, e);
            return new Config();
        }
    }

    private static Config parse(String raw, Path path) {
        JsonElement root = JsonParser.parseString(stripComments(raw));
        Config cfg = new Config();
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            boolean legacy = isLegacy(obj);
            if (legacy) {
                migrateLegacy(obj, cfg);
            } else {
                Config parsed = GSON.fromJson(obj, Config.class);
                if (parsed != null) {
                    cfg = parsed;
                    if (cfg.global == null) {
                        cfg.global = new Global();
                    }
                    if (cfg.players == null) {
                        cfg.players = new ArrayList<>();
                    }
                }
            }
            loadListFiles(path, cfg);
            if (legacy) {
                // 迁移：把旧数组写进名单文件
                writeListFiles(path, cfg, false);
                HalfdropCore.LOGGER.info("[RandomDeathDrop] 检测到旧版配置，已迁移：名单数组 → 名单文件，id → name");
                writeTemplate(path, cfg);
            }
        }
        normalize(cfg);
        if (cfg.conflictFixed && path != null) {
            // 冲突修正写回文件（模板重写，仅此异常场景）
            writeTemplate(path, cfg);
        }
        return cfg;
    }

    /** 判断是否是旧格式（1.4.x：global.glWhiteList 数组 / players[].id）。 */
    private static boolean isLegacy(JsonObject root) {
        if (root.has("global") && root.get("global").isJsonObject()) {
            JsonObject g = root.getAsJsonObject("global");
            if (g.has("glWhiteList") || g.has("glBlackList")) {
                return true;
            }
        }
        if (root.has("players") && root.get("players").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("players");
            for (JsonElement e : arr) {
                if (e != null && e.isJsonObject()) {
                    JsonObject p = e.getAsJsonObject();
                    if (p.has("id") || isJsonList(p.get("whiteList")) || isJsonList(p.get("blackList"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isJsonList(JsonElement e) {
        return e != null && e.isJsonArray();
    }

    /** 旧 1.4.x 配置迁移。 */
    private static void migrateLegacy(JsonObject root, Config cfg) {
        Config old = GSON.fromJson(root, Config.class);
        if (old != null && old.global != null) {
            cfg.global = old.global;
            if (old.players != null) {
                cfg.players = old.players;
            }
        }
        // 旧玩家字段：id → name
        JsonArray oldPlayers = root.has("players") && root.getAsJsonArray("players") != null
                ? root.getAsJsonArray("players") : new JsonArray();
        for (int i = 0; i < cfg.players.size(); i++) {
            PlayerEntry entry = cfg.players.get(i);
            if (entry == null) {
                continue;
            }
            JsonObject raw = i < oldPlayers.size() && oldPlayers.get(i).isJsonObject()
                    ? oldPlayers.get(i).getAsJsonObject() : null;
            if (raw == null) {
                continue;
            }
            if (entry.name == null && raw.has("id") && raw.get("id").isJsonPrimitive()) {
                entry.name = raw.get("id").getAsString();
            }
            // 旧个人名单数组 → lists.players（需要 uuid，先按 name 暂存，由补全阶段落到 uuid 键）
            String key = entry.name == null ? null : entry.name.toLowerCase(Locale.ROOT);
            if (key != null && !key.isEmpty()) {
                PlayerList pl = cfg.lists.players.computeIfAbsent(key, k -> new PlayerList());
                if (pl.name == null) {
                    pl.name = entry.name;
                }
                if (raw.has("whiteList") && isJsonList(raw.get("whiteList"))) {
                    pl.white = readStringList(raw.get("whiteList"));
                }
                if (raw.has("blackList") && isJsonList(raw.get("blackList"))) {
                    pl.black = readStringList(raw.get("blackList"));
                }
            }
            entry.whiteList = null;
            entry.blackList = null;
        }
        // 旧全局数组 → 名单文件
        if (root.has("global") && root.get("global").isJsonObject()) {
            JsonObject g = root.getAsJsonObject("global");
            if (g.has("glWhiteList") && isJsonList(g.get("glWhiteList"))) {
                cfg.lists.globalWhite = readStringList(g.get("glWhiteList"));
            }
            if (g.has("glBlackList") && isJsonList(g.get("glBlackList"))) {
                cfg.lists.globalBlack = readStringList(g.get("glBlackList"));
            }
        }
        // 开关推断：白名单非空 → whiteList=true；白名单空且黑名单非空 → blackList=true
        boolean whiteNonEmpty = !cfg.lists.globalWhite.isEmpty();
        boolean blackNonEmpty = !cfg.lists.globalBlack.isEmpty();
        if (whiteNonEmpty) {
            cfg.global.whiteList = true;
            cfg.global.blackList = false;
        } else if (blackNonEmpty) {
            cfg.global.whiteList = false;
            cfg.global.blackList = true;
        } else {
            cfg.global.whiteList = true;
            cfg.global.blackList = false;
        }
        // 旧关键字 glWhiteList/glBlackList → globalWhiteList/globalBlackList
        for (PlayerList pl : cfg.lists.players.values()) {
            pl.white = migrateKeywords(pl.white);
            pl.black = migrateKeywords(pl.black);
        }
    }

    private static List<String> migrateKeywords(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("glwhitelist") || v.equals("whitelist")) {
                v = "globalwhitelist";
            } else if (v.equals("glblacklist") || v.equals("blacklist")) {
                v = "globalblacklist";
            }
            out.add(v);
        }
        return out;
    }

    /** 读取名单文件（globalWhiteList.json / globalBlackList.json / players/<uuid>.json）。 */
    private static void loadListFiles(Path configPath, Config cfg) {
        Path dir = configPath.getParent();
        if (dir == null) {
            return;
        }
        cfg.lists.globalWhite = readArray(dir.resolve("globalWhiteList.json"),
                cfg.lists.globalWhite, "globalWhiteList.json");
        cfg.lists.globalBlack = readArray(dir.resolve("globalBlackList.json"),
                cfg.lists.globalBlack, "globalBlackList.json");
        Path playersDir = dir.resolve("players");
        if (Files.isDirectory(playersDir)) {
            try (var stream = Files.list(playersDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    PlayerList pl = readPlayerList(p);
                    if (pl == null) {
                        return;
                    }
                    String file = p.getFileName().toString();
                    String key = file.substring(0, file.length() - ".json".length()).toLowerCase(Locale.ROOT);
                    PlayerList existing = cfg.lists.players.get(key);
                    if (existing != null) {
                        // 迁移阶段可能已按 name 暂存；用文件覆盖并迁移键
                        pl.name = pl.name == null ? existing.name : pl.name;
                        cfg.lists.players.remove(key);
                    }
                    cfg.lists.players.put(key, pl);
                });
            } catch (Exception e) {
                HalfdropCore.LOGGER.warn("[RandomDeathDrop] 读取 players 目录失败", e);
            }
        }
    }

    private static List<String> readArray(Path file, List<String> fallback, String name) {
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<String> list = readStringList(JsonParser.parseString(stripComments(raw)));
            return list.isEmpty() ? fallback : list;
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] [WARN] 名单文件 {} 解析失败：{}，使用空名单", name, e.getMessage());
            return fallback;
        }
    }

    /** 只重写 players 段（保留文件其余内容与数组内注释），用于补全 uuid/name 后落盘。 */
    public static void writePlayersSection(Path configPath, List<PlayerEntry> players) {
        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            int idx = raw.indexOf("\"players\"");
            if (idx < 0) {
                return;
            }
            int open = raw.indexOf('[', idx);
            if (open < 0) {
                return;
            }
            int depth = 0;
            int close = -1;
            for (int i = open; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            if (close < 0) {
                return;
            }
            String inner = raw.substring(open + 1, close);
            // 保留原有注释块（/* ... */）
            StringBuilder comments = new StringBuilder();
            int ci = 0;
            while ((ci = inner.indexOf("/*", ci)) >= 0) {
                int ce = inner.indexOf("*/", ci);
                if (ce < 0) {
                    break;
                }
                comments.append("\n").append(inner, ci, ce + 2);
                ci = ce + 2;
            }
            String json = players.isEmpty() ? "" : GSON.toJson(players).trim();
            // 去掉外层数组括号（players 段本身已包含 [ ]）
            if (json.startsWith("[") && json.endsWith("]")) {
                json = json.substring(1, json.length() - 1).trim();
            }
            String newInner = json.isEmpty() ? "" : "\n    " + json.replace("\n", "\n    ");
            newInner = newInner + comments + "\n  ";
            String out = raw.substring(0, open + 1) + newInner + raw.substring(close);
            if (!out.equals(raw)) {
                Files.writeString(configPath, out, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] 写回 players 段失败", e);
        }
    }

    /** 读取玩家名单文件（供 HalfdropCore 补全后生成/重读时使用）。 */
    public static PlayerList readPlayerListFile(Path file) {
        return readPlayerList(file);
    }

    /** 写出玩家名单文件（不存在时创建；存在时只更新 name 字段，保留名单数组）。 */
    public static void writePlayerListFile(Path file, PlayerList pl) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("name", pl.name == null ? "" : pl.name);
            obj.add("whiteList", GSON.toJsonTree(pl.white == null ? new ArrayList<String>() : pl.white));
            obj.add("blackList", GSON.toJsonTree(pl.black == null ? new ArrayList<String>() : pl.black));
            Files.writeString(file, GSON.toJson(obj) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] 写出玩家名单文件失败", e);
        }
    }

    private static PlayerList readPlayerList(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(stripComments(raw));
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            PlayerList pl = new PlayerList();
            if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
                pl.name = obj.get("name").getAsString();
            }
            pl.white = readStringList(obj.get("whiteList"));
            pl.black = readStringList(obj.get("blackList"));
            return pl;
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] [WARN] 玩家名单文件 {} 解析失败：{}，使用空名单",
                    file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** 写出名单文件（迁移用）。只写不存在的文件，不覆盖用户内容。 */
    private static void writeListFiles(Path configPath, Config cfg, boolean overwritePlayers) {
        Path dir = configPath.getParent();
        if (dir == null) {
            return;
        }
        try {
            writeArray(dir.resolve("globalWhiteList.json"), cfg.lists.globalWhite);
            writeArray(dir.resolve("globalBlackList.json"), cfg.lists.globalBlack);
            Path playersDir = dir.resolve("players");
            Files.createDirectories(playersDir);
            for (Map.Entry<String, PlayerList> e : cfg.lists.players.entrySet()) {
                String key = e.getKey();
                if (!isUuid(key)) {
                    continue; // 只有补全出 uuid 的才落盘
                }
                Path file = playersDir.resolve(key + ".json");
                if (!overwritePlayers && Files.exists(file)) {
                    continue;
                }
                PlayerList pl = e.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("name", pl.name == null ? "" : pl.name);
                obj.add("whiteList", GSON.toJsonTree(pl.white));
                obj.add("blackList", GSON.toJsonTree(pl.black));
                Files.writeString(file, GSON.toJson(obj) + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] 写出名单文件失败", e);
        }
    }

    private static void writeArray(Path file, List<String> list) {
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, GSON.toJson(list) + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] 写出名单文件 {} 失败", file.getFileName(), e);
        }
    }

    private static boolean isUuid(String key) {
        return key != null && key.length() == 36 && key.indexOf('-') > 0;
    }

    private static List<String> readStringList(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    /** 剥离 // 行注释与 /* *&#47; 块注释（字符串内的不动，支持转义）。 */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean inLine = false;
        boolean inBlock = false;
        boolean escaped = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '/') {
                    inLine = true;
                    i++;
                    continue;
                }
                if (n == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 写出带注释的模板（保留 cfg 里的值）。 */
    public static void writeTemplate(Path path, Config cfg) {
        try {
            Files.createDirectories(path.getParent());
            String out = TEMPLATE
                    .replace("{{dropPercent}}", Integer.toString(cfg.global.dropPercent))
                    .replace("{{roundUp}}", Boolean.toString(cfg.global.roundUp))
                    .replace("{{keepHotbar}}", Boolean.toString(cfg.global.keepHotbar))
                    .replace("{{keepSelectedSlot}}", Boolean.toString(cfg.global.keepSelectedSlot))
                    .replace("{{keepArmorSlot}}", Boolean.toString(cfg.global.keepArmorSlot))
                    .replace("{{keepWeaponOffhand}}", Boolean.toString(cfg.global.keepWeaponOffhand))
                    .replace("{{xpDropPercent}}", Integer.toString(cfg.global.xpDropPercent))
                    .replace("{{whiteList}}", Boolean.toString(cfg.global.whiteList))
                    .replace("{{blackList}}", Boolean.toString(cfg.global.blackList))
                    .replace("{{players}}", playersBlock(cfg.players));
            Files.writeString(path, out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] could not write config {}", path, e);
        }
    }

    /** 把已有 players 列表写进模板（放在注释示例之前）；空列表返回空串。 */
    private static String playersBlock(List<PlayerEntry> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        String raw = GSON.toJson(list).trim();
        String inner = raw.substring(1, raw.length() - 1).trim();
        inner = inner.replace("\n", "\n    ");
        return "    " + inner + "\n";
    }

    private static void normalize(Config cfg) {
        cfg.global.dropPercent = clamp(cfg.global.dropPercent);
        cfg.global.xpDropPercent = clamp(cfg.global.xpDropPercent);
        if (cfg.global.whiteList && cfg.global.blackList) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] [WARN] 白名单与黑名单同时启用，配置矛盾，已自动改为默认设置");
            cfg.global.whiteList = true;
            cfg.global.blackList = false;
            cfg.conflictFixed = true;
        }
        cfg.lists.globalWhite = normalizeIds(cfg.lists.globalWhite);
        cfg.lists.globalBlack = normalizeIds(cfg.lists.globalBlack);
        for (PlayerList pl : cfg.lists.players.values()) {
            pl.white = normalizeIds(pl.white);
            pl.black = normalizeIds(pl.black);
        }
        for (PlayerEntry e : cfg.players) {
            if (e == null) {
                continue;
            }
            if (e.dropPercent != null) {
                e.dropPercent = clamp(e.dropPercent);
            }
            if (e.xpDropPercent != null) {
                e.xpDropPercent = clamp(e.xpDropPercent);
            }
            if (e.uuid != null) {
                e.uuid = e.uuid.trim().toLowerCase(Locale.ROOT);
            }
            if (e.name != null) {
                e.name = e.name.trim();
            }
        }
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static List<String> normalizeIds(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            if (id.equals("globalwhitelist")) {
                if (!out.contains(id)) {
                    out.add(id);
                }
                continue;
            }
            if (id.equals("globalblacklist")) {
                if (!out.contains(id)) {
                    out.add(id);
                }
                continue;
            }
            boolean negate = id.startsWith("!");
            if (negate) {
                id = id.substring(1).trim();
                if (id.isEmpty()) {
                    continue;
                }
            }
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            String entry = negate ? "!" + id : id;
            if (!out.contains(entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    private static final String TEMPLATE = """
// ============================================================
//  死亡随机掉落（RandomDeathDrop）配置文件
//  修改后执行 /randomdeathdrop reload 或 /rdd reload 生效（需要 OP）
//  本文件支持 // 单行注释和 /* */ 块注释
//  名单文件：globalWhiteList.json / globalBlackList.json / players/<uuid>.json
// ============================================================

{
  "global": {
    // 掉落比例（百分比，0-100）
    // 参与随机的格子里，有物品的格子按这个比例掉落
    // 例：50 = 9 格物品时掉 5 格；0 = 一件不掉；100 = 全部掉落
    // 默认值：50
    "dropPercent": {{dropPercent}},

    // 除不尽时的取整方向
    // true  = 向上取整（例9 格 × 50% = 4.5 ， 掉 5 格）
    // false = 向下取整（例9 格 × 50% = 4.5 ，掉 4 格）
    // 默认值：true
    "roundUp": {{roundUp}},

    // 是否保留整个快捷栏（9 格）
    // true  = 快捷栏不掉落，也不参与随机
    // false = 快捷栏参与随机（配合 keepSelectedSlot 可保留手持格）
    // 默认值：true
    "keepHotbar": {{keepHotbar}},

    // 仅在 keepHotbar = false 时生效：是否保留当前手持的那一格
    // 默认值：true
    "keepSelectedSlot": {{keepSelectedSlot}},

    // 是否保留盔甲栏（4 格）
    // true  = 保留盔甲栏，不掉落也不参与随机
    // false = 盔甲参与随机掉落
    // 默认值：true
    "keepArmorSlot": {{keepArmorSlot}},

    // 是否保留副手
    // true  = 保留副手，不掉落也不参与随机
    // false = 副手参与随机掉落
    // 默认值：true
    "keepWeaponOffhand": {{keepWeaponOffhand}},

    // 经验掉落百分比（按等级，0-100）
    // 死亡时按等级掉落：8 级 × 50% = 掉 4 级，重生后剩 4 级
    // 0 = 不掉经验；100 = 原版全掉
    // 与 dropPercent 互不影响，但取整共用 roundUp
    // 默认值：100
    "xpDropPercent": {{xpDropPercent}},

    // true  = 默认全部物品参与随机掉落，只有白名单里的物品不掉落
    // false = 不启用白名单模式
    // 默认值：true
    // 支持通配符 * 和 ?；"!物品" = 从名单移除，下同
    // 黑白名单只能二选一，两个都设为 true 时：自动改为 whiteList=true、blackList=false
    // 两个都设为 false 时：全部物品都参与随机掉落（无视黑白名单里的内容了）
    "whiteList": {{whiteList}},

    // true  = 默认全部物品不掉落，只有黑名单里的物品参与随机掉落
    // false = 不启用黑名单模式
    // 注意：黑名单模式下，名单外的物品不掉落
    // 默认值：false
    "blackList": {{blackList}}
  },

  // ============================================================
  // 个人设置：按玩家覆盖 global
  //   - uuid / name 可二填一或都填：
  //       只填 name = 加载时查本地 usercache 补全 uuid
  //       只填 uuid = 加载时查本地 usercache 补全 name
  //       补全失败：个人配置暂不生效，等该玩家进服后请
  //       手动 /rdd reload 或重启服务器即可自动补全
  //   - 同名/重复 uuid：优先「name 与 usercache 一致」的条目，否则保留第一个
  //   - 个人名单在 players/<uuid>.json 中配置：
  //       名单里写 "globalWhiteList" / "globalBlackList" 关键字 = 继承全局对应名单
  //       支持通配符 * 和 ?；"!物品" = 从名单移除
  //   - 全局名单文件：globalWhiteList.json / globalBlackList.json（纯 JSON 数组）
  //       例：["globalWhiteList","*_wood", "!oak_wood"] ← 继承全局白名单配置的同时追加
  //           除橡木之外所有木头
  //   - 不需要个人设置时保持空数组 [] 即可，为空数组时 = 空名单，不继承全局白（黑）名单
  // ============================================================
  "players": [
{{players}}    /*
    // 以下为示例，数值仅演示，取消注释后请按需修改

    {
      // 示例 玩家1：按玩家名（加载时自动补全 uuid）
      "name": "Steve",
      "dropPercent": 20,
      "roundUp": false,
      "keepHotbar": false,
      "keepSelectedSlot": true,
      "keepArmorSlot": false,
      "keepWeaponOffhand": false,
      "xpDropPercent": 0,
      "whiteList": true,
      "blackList": false
    },
    {
      // 示例 玩家2：按 uuid（加载时自动补全 name），留空的配置默认继承全局设置
      "uuid": "9b7a6ec0-e2c2-4445-a266-6781e921c853",
      "whiteList": false,
      "blackList": true
    }
    */
  ]
}
""";
}
