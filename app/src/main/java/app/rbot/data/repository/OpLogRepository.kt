package app.rbot.data.repository

import app.rbot.data.local.dao.OpLogDao
import app.rbot.data.local.entity.OpLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日志仓库 — 统一管理操作日志的读写。
 * 替代旧版 OpLog 的 root echo 写文件方式。
 */
@Singleton
class OpLogRepository @Inject constructor(
    private val opLogDao: OpLogDao
) {
    /** 获取最近日志（Flow 响应式） */
    fun getRecentLogs(limit: Int = 500): Flow<List<OpLogEntity>> {
        return opLogDao.getRecentLogs(limit)
    }

    /** 记录日志 */
    suspend fun log(message: String, isError: Boolean = false, category: String = "general") {
        withContext(Dispatchers.IO) {
            opLogDao.insert(OpLogEntity(message = message, isError = isError, category = category))
            // 自动清理超过 2000 条的旧日志
            val count = opLogDao.count()
            if (count > 2000) {
                val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7天前
                opLogDao.deleteOlderThan(cutoff)
            }
        }
    }

    /** 记录错误日志 */
    suspend fun error(message: String, category: String = "general") {
        log(message, isError = true, category = category)
    }

    /** 清空所有日志 */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            opLogDao.clearAll()
        }
    }
}
