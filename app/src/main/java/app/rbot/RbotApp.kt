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

        // AuthManager 初始化：用 libsu 的 isAppGrantedRoot() 快速检测
        AuthManager.instance.initLightweight(this)
    }

    companion object {
        private const val TAG = "RbotApp"
    }
}
