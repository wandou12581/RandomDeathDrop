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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置文件（JSONC，支持 // 与 /* *&#47; 注释）：config/randomdeathdrop/randomdeathdrop.json。
 *
 * <p>读取时会先剥离注释再交给 Gson；文件不存在时会生成带完整注释与示例的模板。
 * 若找到旧位置的配置（config/randomdeathdrop.json 或 config/halfdrop.json），
 * 会迁移到新位置（旧文件删除、保留 .bak 备份），并保留旧文件里的值。
 */
public final class Config {
    public Global global = new Global();
    public List<PlayerEntry> players = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Global {
        public int dropPercent = 50;
        public boolean roundUp = true;
        public boolean keepHotbar = true;
        public boolean keepSelectedSlot = true;
        public boolean keepArmorSlot = true;
        public boolean keepWeaponOffhand = true;
        /** 经验掉落百分比（按等级），0 = 不掉，100 = 原版全掉。 */
        public int xpDropPercent = 100;
        /** 全局白名单（gl = global）。 */
        public List<String> glWhiteList = new ArrayList<>();
        /** 全局黑名单（gl = global）。 */
        public List<String> glBlackList = new ArrayList<>();
    }

    public static final class PlayerEntry {
        /** 玩家名字或 UUID。 */
        public String id = "";
        /** null = 继承 global。 */
        public Integer dropPercent;
        public Boolean roundUp;
        public Boolean keepHotbar;
        public Boolean keepSelectedSlot;
        public Boolean keepArmorSlot;
        public Boolean keepWeaponOffhand;
        public Integer xpDropPercent;
        /** 个人白名单：写 "glWhiteList" 关键字继承全局，否则独立。 */
        public List<String> whiteList;
        /** 个人黑名单：写 "glBlackList" 关键字继承全局，否则独立。 */
        public List<String> blackList;
    }

    /**
     * 读取配置；文件不存在时按顺序尝试从 legacyCandidates 迁移，全部没有则生成默认模板。
     */
    public static Config load(Path path, List<Path> legacyCandidates) {
        try {
            if (Files.isRegularFile(path)) {
                return parse(Files.readString(path, StandardCharsets.UTF_8));
            }

            for (Path legacy : legacyCandidates) {
                if (legacy == null || legacy.equals(path) || !Files.isRegularFile(legacy)) {
                    continue;
                }
                Config migrated = parse(Files.readString(legacy, StandardCharsets.UTF_8));
                Path backup = legacy.resolveSibling(legacy.getFileName() + ".bak");
                try {
                    Files.copy(legacy, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                    // 备份失败不阻塞迁移
                }
                try {
                    Files.delete(legacy);
                } catch (Exception ignored) {
                    // 删除失败也无所谓，新文件会优先被读取
                }
                writeTemplate(path, migrated);
                HalfdropCore.LOGGER.info("[RandomDeathDrop] migrated config {} -> {}",
                        legacy.getFileName(), path);
                return migrated;
            }

            Config cfg = new Config();
            writeTemplate(path, cfg);
            return cfg;
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] could not read/write config {}", path, e);
            return new Config();
        }
    }

    private static Config parse(String raw) {
        JsonElement root = JsonParser.parseString(stripComments(raw));

        // 更早的扁平结构（顶层直接是 dropPercent 等）：把能认的字段映射到 global
        if (root.isJsonObject() && !root.getAsJsonObject().has("global")) {
            JsonObject obj = root.getAsJsonObject();
            Config cfg = new Config();
            cfg.global.dropPercent = optInt(obj, "dropPercent", 50);
            cfg.global.roundUp = optBool(obj, "roundUp", true);
            cfg.global.keepHotbar = optBool(obj, "keepHotbar", true);
            cfg.global.keepSelectedSlot = optBool(obj, "keepSelectedSlot", true);
            normalize(cfg);
            return cfg;
        }

        Config cfg = GSON.fromJson(root, Config.class);
        if (cfg == null) {
            cfg = new Config();
        }
        if (cfg.global == null) {
            cfg.global = new Global();
        }
        if (cfg.players == null) {
            cfg.players = new ArrayList<>();
        }
        migrateLegacyKeepXp(root, cfg);
        migrateLegacyLists(root, cfg);
        normalize(cfg);
        return cfg;
    }

    /**
     * 旧版 keepXP 字段迁移：keepXP=true 等价于 xpDropPercent=0（完全不掉），
     * keepXP=false 等价于 100（原版全掉）。
     */
    private static void migrateLegacyKeepXp(JsonElement root, Config cfg) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("global") && obj.get("global").isJsonObject()) {
            JsonObject globalObj = obj.getAsJsonObject("global");
            if (globalObj.has("keepXP") && optBool(globalObj, "keepXP", false)) {
                cfg.global.xpDropPercent = 0;
            }
        }
        if (obj.has("players") && obj.get("players").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("players");
            for (int i = 0; i < arr.size() && i < cfg.players.size(); i++) {
                JsonElement pe = arr.get(i);
                if (pe != null && pe.isJsonObject() && pe.getAsJsonObject().has("keepXP")) {
                    PlayerEntry entry = cfg.players.get(i);
                    if (entry != null && entry.xpDropPercent == null
                            && optBool(pe.getAsJsonObject(), "keepXP", false)) {
                        entry.xpDropPercent = 0;
                    }
                }
            }
        }
    }

    /** 旧字段迁移：global.whiteList/blackList → glWhiteList/glBlackList。 */
    private static void migrateLegacyLists(JsonElement root, Config cfg) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("global") || !obj.get("global").isJsonObject()) {
            return;
        }
        JsonObject g = obj.getAsJsonObject("global");
        if (!g.has("glWhiteList") && g.has("whiteList")) {
            cfg.global.glWhiteList = readStringList(g.get("whiteList"));
        }
        if (!g.has("glBlackList") && g.has("blackList")) {
            cfg.global.glBlackList = readStringList(g.get("blackList"));
        }
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
                    .replace("{{glWhiteList}}", GSON.toJson(cfg.global.glWhiteList))
                    .replace("{{glBlackList}}", GSON.toJson(cfg.global.glBlackList))
                    .replace("{{players}}", playersBlock(cfg.players));
            Files.writeString(path, out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            HalfdropCore.LOGGER.warn("[RandomDeathDrop] could not write config {}", path, e);
        }
    }

    /** 迁移时把已有的 players 列表写进模板（放在注释示例之前）；空列表返回空串。 */
    private static String playersBlock(List<PlayerEntry> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        String raw = GSON.toJson(list).trim();
        String inner = raw.substring(1, raw.length() - 1).trim();
        inner = inner.replace("\n", "\n    ");
        // 注意：末尾不能带逗号——模板里 players 数组后面是注释块，加了逗号会变成尾随逗号
        return "    " + inner + "\n";
    }

    private static int optInt(JsonObject obj, String key, int def) {
        try {
            JsonElement e = obj.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean optBool(JsonObject obj, String key, boolean def) {
        try {
            JsonElement e = obj.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private static void normalize(Config cfg) {
        cfg.global.dropPercent = clamp(cfg.global.dropPercent);
        cfg.global.xpDropPercent = clamp(cfg.global.xpDropPercent);
        cfg.global.glWhiteList = normalizeIds(cfg.global.glWhiteList);
        cfg.global.glBlackList = normalizeIds(cfg.global.glBlackList);

        for (PlayerEntry e : cfg.players) {
            if (e == null) {
                continue;
            }
            if (e.id == null) {
                e.id = "";
            }
            if (e.dropPercent != null) {
                e.dropPercent = clamp(e.dropPercent);
            }
            if (e.xpDropPercent != null) {
                e.xpDropPercent = clamp(e.xpDropPercent);
            }
            e.whiteList = e.whiteList == null ? null : normalizeIds(e.whiteList);
            e.blackList = e.blackList == null ? null : normalizeIds(e.blackList);
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
            // 关键字：引用全局名单（不当作物品 id 处理）
            if (id.equals("glwhitelist") || id.equals("glblacklist")) {
                if (!out.contains(id)) {
                    out.add(id);
                }
                continue;
            }
            // 旧关键字迁移：个人名单里的 "whiteList"/"blackList" 转为新关键字
            if (id.equals("whitelist")) {
                out.add("glwhitelist");
                continue;
            }
            if (id.equals("blacklist")) {
                out.add("glblacklist");
                continue;
            }
            // ! 前缀：从所在名单中移除该项
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
//  名单优先级：个人黑名单 > 个人白名单 > 全局黑名单 > 全局白名单
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

    // 全局白名单：列表里的物品不会掉落，也不计入随机数
    // 格式：物品 ID（小写），可省略命名空间（默认 minecraft:）
    // 支持通配符：* 匹配任意长度，? 匹配单字符
    // 例：["minecraft:diamond", "*_wood", "oak_plank?"]   // oak_plank? 匹配 oak_planks
    "glWhiteList": {{glWhiteList}},

    // 全局黑名单：优先级高于全局白名单（命中则取消保护、正常参与随机）
    // 同样支持通配符：* 和 ?
    // 例：["minecraft:netherite_ingot", "*_wood", "*_woo?"]   // ? 匹配单个字符
    "glBlackList": {{glBlackList}}
  },

  // ============================================================
  // 个人设置：按玩家覆盖 global
  //   - id       玩家名字（忽略大小写）或 UUID
  //   - 只写要覆盖的字段，没写的自动继承 global（白/黑名单除外，见下）
  //   - 白/黑名单规则：
  //       写 "glWhiteList"/"glBlackList" 关键字 = 继承全局白/黑名单
  //       不写关键字 = 只用个人项（不继承全局）
  //       名单留空/不写时 = 空名单（该玩家全部参与随机）
  //   - "!物品" = 从所在名单移除（支持通配符），如 "!*_wood"
  //   - 判定：黑名单的物品→参与随机；白名单的物品→不掉落；都没写入的→参与随机
  //   - 黑名单优先级最高：即使全局白名单保护了某物品，写了个人黑名单仍会参与随机
  //   - 不需要个人设置时保持空数组 [] 即可
  // ============================================================
  "players": [
{{players}}    /*
    // 以下为示例，数值仅演示，取消注释后请按需修改

    {
      // 示例 玩家1：按玩家名
      "id": "Steve",
      "dropPercent": 20,
      "roundUp": false,
      "keepHotbar": false,
      "keepSelectedSlot": true,
      "keepArmorSlot": false,
      "keepWeaponOffhand": false,
      "xpDropPercent": 0,
      "whiteList": ["minecraft:enchanted_golden_apple"],
      "blackList": ["minecraft:netherite_ingot"]
    },
    {
      // 示例 玩家2：按 UUID，只覆盖一部分字段；写关键字继承全局白名单
      "id": "9b7a6ec0-e2c4-4445-a266-6781e921c853",
      "dropPercent": 100,
      "whiteList": ["glWhiteList"]
    }
    */
  ]
}
""";
}
