package app.rbot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bot 配置实体 — 持久化到 Room 数据库。
 * 替代旧版 SharedPreferences 的零散存储。
 */
@Entity(tableName = "bot_configs")
data class BotConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val isActive: Boolean = false,
    val installedVersion: String? = null,
    val lastStartTime: Long? = null,
    val lastStopTime: Long? = null,
    val webUIUrl: String = "",
    val supportedPlatforms: String = ""
)

/**
 * 操作日志实体 — 替代旧版 OpLog 的文件写入。
 * 使用 Room 存储更可靠（不依赖 root 写文件）。
 */
@Entity(tableName = "op_logs")
data class OpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
    val category: String = "general"
)

/**
 * 代理配置实体
 */
@Entity(tableName = "proxy_configs")
data class ProxyConfigEntity(
    @PrimaryKey val autoIndex: Int = 0,
    val name: String,
    val template: String,
    val lastLatencyMs: Int = -1,
    val isCustom: Boolean = false
)
