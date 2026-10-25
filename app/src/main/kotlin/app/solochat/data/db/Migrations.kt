package app.solochat.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * ## 几条纪律（都是这个库的性质决定的）
 *
 * 1. **原则上只增不改**：这是唯一的聊天记录，清库等于数据丢失。所以
 *    `SoloChatDatabase` 刻意没用 `fallbackToDestructiveMigration()` ——
 *    那会把"用户数据丢光"变成一个静默的默认行为。
 * 2. **迁移跑在一个事务里**：失败会整体回滚、版本号不动，于是"改错了要重装"
 *    不会把数据弄坏（装回去仍然能打开、仍然在 v1）。
 * 3. **没有自动化测试**：Room 验迁移的 `MigrationTestHelper` 走 instrumentation，
 *    而这个项目刻意不引 Robolectric、也没有 androidTest（见 `app/build.gradle.kts`）。
 *    所以每一条迁移只能靠"导出 schema + 手写 SQL + 在**有真实数据的真机**上装一遍"
 *    来验。**这是这个仓库里最需要小心的一处** —— 写错了 App 直接打不开。
 *
 * v7→v8 是经用户明确要求的唯一元数据调整：只把旧默认人物卡标记为普通卡，保留卡片和关联数据。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 记忆按人设分开：`personaId = ''` 表示通用（哪张人设都能看到）。
        //
        // `ALTER TABLE` 必须带 `NOT NULL DEFAULT ''`：实体那头是非空 String，
        // 而老行必须有值。而那句 DEFAULT **不需要**在实体上声明成
        // `@ColumnInfo(defaultValue=...)` —— Room 的 schema 校验只在"实体声明了
        // 默认值"时才去比对数据库里的默认值（声明了反而更严、更容易踩坑）。
        db.execSQL("ALTER TABLE memory ADD COLUMN personaId TEXT NOT NULL DEFAULT ''")

        // 再**尽量回填**：能从来源会话推出人设的，就归给那张卡。
        //
        // 为什么值得回填：这些是用户真实攒下来的记忆，全变成"通用"就等于
        // "小默知道只跟阿岩说过的事" —— 那正是这次要修的问题。
        // 推不出来的（手工加的、来源会话已删的）就留成通用：
        // **宁可多看一条，也不要让用户发现记忆凭空少了。**
        db.execSQL(
            "UPDATE memory SET personaId = (" +
                "SELECT personaId FROM conversation WHERE conversation.id = memory.sourceConversationId" +
                ") WHERE sourceConversationId != '' AND EXISTS (" +
                "SELECT 1 FROM conversation WHERE conversation.id = memory.sourceConversationId" +
                ")"
        )
    }
}

/**
 * v2 → v3：把 **`sentToModel` 这个字段从"死字段"启用**，顺手把老数据标对。
 *
 * ## 为什么必须有这一条（不然是静默的双向事故）
 *
 * `sentToModel` 在此之前是个**死字段**：全仓库只有两处写死 `false`，没有任何地方读它
 * （唯一读它的是调试接口 `/api/messages`）。而"手动（两段）发送"这个功能把它启用成
 * **"这条用户消息有没有被某一轮取走"**，并且让模型上下文**排除未推送的用户消息**
 * （`recentMessages` vs `modelVisibleMessages`）。
 *
 * 于是如果不迁移，**库里所有历史用户消息都是 `sentToModel = 0`**，后果有两个方向：
 *
 * 1. 模型上下文里**看不见用户以前说过的任何话** —— 它只看得见自己的发言，
 *    会开始把好几轮当成一轮回答，而这是文档里记过的那类最难查的故障。
 * 2. 切回自动模式时的"补推代发"逻辑会把**整场老会话的用户消息**当成代发，
 *    一打开会话就凭空起一轮 —— 每次打开一次，花一次钱，多一段角色自说自话。
 *
 * 这一条迁移把既有的用户消息全部标成"已推送"，也就是"它们早就交给过模型了"。
 * 这是**事实**：这个字段启用之前，`recentMessages` 一视同仁地把它们全喂给了模型。
 *
 * ## 为什么不做成 App 启动时的一次性修补（那个更省事）
 *
 * 那样会有一个窗口：DB 已经打开、修补还没跑完，而用户已经点进某个会话 ——
 * 上面第 2 条事故就在这个窗口里发生。迁移在 **DB 第一次打开时、任何查询之前**
 * 跑完，没有窗口。
 *
 * 注意只动 `role = 'user'`：assistant / system 的行**本来就该是 0**
 * （上下文的过滤条件是 `role != 'user' OR sentToModel = 1`），改了反而没意义。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE message SET sentToModel = 1 WHERE role = 'user'")
    }
}

/**
 * v3 → v4：人物卡扩成七段，多出**面对面专用的三段素材**。
 *
 * ## 为什么要加这三列
 *
 * 面对面模式的表达手段只有 `act`（动作）与 `scene`（环境），而这两样**都需要素材**：
 * 动作库（她紧张时会做什么）、环境库（她待的地方长什么样）。原来人设只有
 * 「它是谁 / 怎么说话 / 关系与边界 / 只有你知道的事」四段文字，模型手里没有这些东西，
 * 只能每轮自己发明一个动作 —— 表现就是"这个人每轮都做不同的小动作，不像一个人"。
 * 另外当面说话和打字本来就不一样（更短、更碎、更多停顿），原来也只有一段 voice。
 *
 * ## 为什么是 ALTER 而不是重建表
 *
 * 只加列、不改语义，`ALTER TABLE ADD COLUMN` 就够，而且不会碰任何既有数据 ——
 * 这条迁移**一个字符的用户数据都不动**（对比 `MIGRATION_2_3` 那条要 UPDATE）。
 *
 * `NOT NULL DEFAULT ''` 是必须的（实体那头是非空 String，老行必须有值），
 * 而那句 DEFAULT **不需要**在实体上声明成 `@ColumnInfo(defaultValue=...)` —— 理由同
 * [MIGRATION_1_2]（Room 只在"实体声明了默认值"时才去比对数据库里的默认值）。
 *
 * ⚠️ **这条迁移没有自动化测试**（见文件头的第 3 条纪律）。它已经拿真机上那份 v3 库
 * 离线跑过一遍（Python `sqlite3`：列定义、行数、`integrity_check`），但**没有在真机上
 * 装过包**。装的时候盯一眼启动日志里有没有 FATAL。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE persona ADD COLUMN sceneVoice TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE persona ADD COLUMN mannerisms TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE persona ADD COLUMN surroundings TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v4 → v5：人物卡再加一段 **`depiction`（怎么描写动作与景物）**。
 *
 * ## 为什么不留着 v4 直接改（明明 v4 没上过真机）
 *
 * v4 那条迁移的"没在真机上装过包"只是**我们这边**的取证缺口，不代表 v4 没出去过：
 * 带 v4 的 APK 已经交付，用户完全可能已经装上并写过数据。把新列折进 `MIGRATION_3_4`
 * 会让那台设备的库停在"版本 4、但没有这一列"的状态 —— Room 打开时会做 schema 校验，
 * 结果是**升级后直接打不开 App**，而那正是这个文件头上第 1 条纪律要防的事。
 *
 * 多一条迁移的成本只是一行注册；赌"用户没装过"的成本是打不开 App。所以分开加。
 *
 * 内容上与 v4 那条同性质：只加列、一个字符的用户数据都不动，老卡上是空串
 * （语义 ="这个人没写这一段"，提示词里那一段整个不出现）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE persona ADD COLUMN depiction TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) { SCHEMA_V6_SQL.forEach(db::execSQL) }
}

/** 只追加导入元数据，不回写或重建既有业务表。 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) { SCHEMA_V7_SQL.forEach(db::execSQL) }
}

/**
 * v7 → v8：旧版出厂「小默」不再作为出厂卡。
 *
 * 只调整稳定旧 ID `default` 对应卡片的 builtin 标记；保留卡片内容、ID、记忆和会话关联。
 * 这样覆盖安装后它会作为普通人物卡保留，用户可自行编辑或删除。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE persona SET builtin = 0 WHERE id = 'default' AND builtin = 1")
    }
}

/** v8→v9：只追加会话模型关联和调用配置快照表。 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) { SCHEMA_V9_SQL.forEach(db::execSQL) }
}

/** 全部迁移，交给 `Room.databaseBuilder(...).addMigrations(*ALL_MIGRATIONS)`。 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
