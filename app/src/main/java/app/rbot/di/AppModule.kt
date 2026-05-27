package app.rbot.di

import android.content.Context
import androidx.room.Room
import app.rbot.core.BotBridge
import app.rbot.data.local.RbotDatabase
import app.rbot.data.local.dao.BotConfigDao
import app.rbot.data.local.dao.OpLogDao
import app.rbot.data.repository.OpLogRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块 — 提供全局单例。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBotBridge(@ApplicationContext context: Context): BotBridge {
        return BotBridge.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RbotDatabase {
        return Room.databaseBuilder(
            context,
            RbotDatabase::class.java,
            "rbot-database"
        ).build()
    }

    @Provides
    fun provideBotConfigDao(db: RbotDatabase): BotConfigDao = db.botConfigDao()

    @Provides
    fun provideOpLogDao(db: RbotDatabase): OpLogDao = db.opLogDao()

    @Provides
    @Singleton
    fun provideOpLogRepository(dao: OpLogDao): OpLogRepository {
        return OpLogRepository(dao)
    }
}
