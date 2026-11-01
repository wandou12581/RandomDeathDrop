package com.halfdrop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
        public boolean keepXP = false;
        public List<String> whiteList = new ArrayList<>();
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
        public Boolean keepXP;
        /** null = 只用 global 白名单；非 null = global ∪ 本列表。 */
        public List<String> whiteList;
        /** 个人黑名单：命中即覆盖白名单、正常参与随机。 */
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
        normalize(cfg);
        return cfg;
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
                    .replace("{{keepXP}}", Boolean.toString(cfg.global.keepXP))
                    .replace("{{whiteList}}", GSON.toJson(cfg.global.whiteList))
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
        return "    " + inner + ",\n";
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
        cfg.global.dropPercent = Math.max(0, Math.min(100, cfg.global.dropPercent));
        cfg.global.whiteList = normalizeIds(cfg.global.whiteList);

        for (PlayerEntry e : cfg.players) {
            if (e == null) {
                continue;
            }
            if (e.id == null) {
                e.id = "";
            }
            if (e.dropPercent != null) {
                e.dropPercent = Math.max(0, Math.min(100, e.dropPercent));
            }
            e.whiteList = e.whiteList == null ? null : normalizeIds(e.whiteList);
            e.blackList = e.blackList == null ? null : normalizeIds(e.blackList);
        }
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
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static final String TEMPLATE = """
// ============================================================
//  死亡随机掉落（RandomDeathDrop）配置文件
//  修改后执行 /randomdeathdrop reload 或 /rdd reload 生效（需要 OP）
//  本文件支持 // 单行注释和 /* */ 块注释
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

    // 是否保留经验
    // true  = 死亡不掉经验
    // false = 经验全部掉落
    // 默认值：false
    "keepXP": {{keepXP}},

    // 白名单：列表里的物品不会掉落，也不计入随机数
    // 格式：物品 ID（小写），可省略命名空间（默认 minecraft:）
    // 例：["minecraft:diamond", "netherite_ingot", "diamond"]
    "whiteList": {{whiteList}}
  },

  // ============================================================
  // 个人设置：按玩家覆盖 global，优先级高于 global
  //   - id       玩家名字（忽略大小写）或 UUID
  //   - 只写要覆盖的字段，没写的自动继承 global
  //   - blackList 个人黑名单，优先级最高：即使 global.whiteList
  //     保护了某物品，只要这里写了，该玩家死亡时它仍会参与随机
  //   - 不需要个人设置时保持空数组 [] 即可
  //   - 个人设置可添加多名玩家（数组里复制多个 {} 块即可）
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
      "keepXP": true,
      "whiteList": ["minecraft:enchanted_golden_apple"],
      "blackList": ["minecraft:netherite_ingot"]
    },
    {
      // 示例 玩家2：按 UUID，只覆盖一部分字段
      "id": "9b7a6ec0-e2c4-4445-a266-6781e921c853",
      "dropPercent": 100
    }
    */
  ]
}
""";
}
