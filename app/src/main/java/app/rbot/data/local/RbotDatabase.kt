package app.rbot.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import app.rbot.data.local.dao.BotConfigDao
import app.rbot.data.local.dao.OpLogDao
import app.rbot.data.local.entity.BotConfigEntity
import app.rbot.data.local.entity.OpLogEntity

/**
 * Room 数据库 — 替代旧版 SharedPreferences + OpLog 文件写入。
 * 提供类型安全的持久化存储和 Flow 响应式查询。
 */
@Database(
    entities = [
        BotConfigEntity::class,
        OpLogEntity::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class RbotDatabase : RoomDatabase() {
    abstract fun botConfigDao(): BotConfigDao
    abstract fun opLogDao(): OpLogDao
}
