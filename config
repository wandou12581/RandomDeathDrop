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
    "dropPercent": 50,

    // 除不尽时的取整方向
    // true  = 向上取整（例9 格 × 50% = 4.5 ， 掉 5 格）
    // false = 向下取整（例9 格 × 50% = 4.5 ，掉 4 格）
    // 默认值：true
    "roundUp": true,

    // 是否保留整个快捷栏（9 格）
    // true  = 快捷栏不掉落，也不参与随机
    // false = 快捷栏参与随机（配合 keepSelectedSlot 可保留手持格）
    // 默认值：true
    "keepHotbar": true,

    // 仅在 keepHotbar = false 时生效：是否保留当前手持的那一格
    // 默认值：true
    "keepSelectedSlot": true,

    // 是否保留盔甲栏（4 格）
    // true  = 保留盔甲栏，不掉落也不参与随机
    // false = 盔甲参与随机掉落
    // 默认值：true
    "keepArmorSlot": true,

    // 是否保留副手
    // true  = 保留副手，不掉落也不参与随机
    // false = 副手参与随机掉落
    // 默认值：true
    "keepWeaponOffhand": true,

    // 是否保留经验
    // true  = 死亡不掉经验
    // false = 经验全部掉落
    // 默认值：false
    "keepXP": false,

    // 白名单：列表里的物品不会掉落，也不计入随机数
    // 格式：物品 ID（小写），可省略命名空间（默认 minecraft:）
    // 例：["minecraft:diamond", "netherite_ingot", "diamond"]
    "whiteList": []
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
    /*
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
