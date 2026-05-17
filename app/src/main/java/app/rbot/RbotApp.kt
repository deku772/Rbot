package app.rbot

import android.app.Application
import android.util.Log
import app.rbot.core.AuthManager
import app.rbot.core.ChrootManager
import app.rbot.core.RbotPaths
import dagger.hilt.android.HiltAndroidApp

/**
 * Rbot Application — Hilt 入口点。
 * 替代旧版 RbotApplication，使用 Hilt 自动注入而非手动单例。
 */
@HiltAndroidApp
class RbotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Rbot starting (modern architecture)")

        // AuthManager 仍需手动初始化（Shizuku 监听器需在 Application 生命周期注册）
        AuthManager.instance.init(this)

        // 确保临时目录存在（原版 RbotApplication 也有此调用）
        ChrootManager.execRoot("mkdir -p ${RbotPaths.RBOT_TMP}")
    }

    companion object {
        private const val TAG = "RbotApp"
    }
}
