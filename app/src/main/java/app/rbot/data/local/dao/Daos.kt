package app.rbot.data.local.dao

import androidx.room.*
import app.rbot.data.local.entity.BotConfigEntity
import app.rbot.data.local.entity.OpLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Bot 配置 DAO
 */
@Dao
interface BotConfigDao {
    @Query("SELECT * FROM bot_configs WHERE isActive = 1 LIMIT 1")
    fun getActiveBot(): Flow<BotConfigEntity?>

    @Query("SELECT * FROM bot_configs")
    fun getAllBots(): Flow<List<BotConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bot: BotConfigEntity)

    @Query("UPDATE bot_configs SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE bot_configs SET isActive = 1 WHERE id = :botId")
    suspend fun setActive(botId: String)
}

/**
 * 操作日志 DAO
 */
@Dao
interface OpLogDao {
    @Query("SELECT * FROM op_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 500): Flow<List<OpLogEntity>>

    @Insert
    suspend fun insert(log: OpLogEntity)

    @Query("DELETE FROM op_logs WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("DELETE FROM op_logs")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM op_logs")
    suspend fun count(): Int
}
