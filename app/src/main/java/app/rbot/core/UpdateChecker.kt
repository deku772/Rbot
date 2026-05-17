package app.rbot.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查器 — 合并原 UpdateChecker + UpdateManager 两个 Java 类。
 *
 * 改进:
 * - 协程替代 Thread + Handler
 * - 合并 rbot API 和 GitHub API 两个检查源
 * - throttle 逻辑保留但使用 SharedPreferences
 * - 纯数据返回，UI 展示由 ViewModel/Screen 处理
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val CHECK_URL = "https://api.rbot.app/version"
    private const val GITHUB_API_URL = "https://api.github.com/repos/deku772/Rbot/releases/latest"
    private const val APK_DOWNLOAD_URL = "https://github.com/deku772/Rbot/releases/download/%s/rbot-%s.apk"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    private const val PREFS_NAME = "rbot_update"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_DOWNLOAD_URL = "download_url"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_CHECKED_VERSION = "checked_version"

    data class UpdateInfo(
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * 检查更新（带节流）。
     * 优先使用 rbot API，回退到 GitHub API。
     * @return UpdateInfo 如有更新，null 如无更新
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = getCurrentVersion(context) ?: return@withContext null

        // 节流检查
        val lastCheckedVersion = prefs.getString(KEY_CHECKED_VERSION, null)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val elapsed = System.currentTimeMillis() - lastCheck
        val versionChanged = currentVersion != lastCheckedVersion

        if (!versionChanged && elapsed < CHECK_INTERVAL_MS) {
            // 节流内 — 从缓存返回
            return@withContext getCachedUpdate(context, prefs, currentVersion)
        }

        Log.i(TAG, "Starting update check, current=$currentVersion")

        // 尝试 rbot API
        var update = checkRbotApi(currentVersion)
        if (update == null) {
            // 回退到 GitHub API
            update = checkGitHubApi(currentVersion)
        }

        // 保存结果
        prefs.edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_CHECKED_VERSION, currentVersion)
            .apply()

        if (update != null) {
            prefs.edit()
                .putString(KEY_LATEST_VERSION, update.latestVersion)
                .putString(KEY_DOWNLOAD_URL, update.downloadUrl)
                .putString(KEY_RELEASE_NOTES, update.releaseNotes)
                .apply()
        } else {
            prefs.edit()
                .remove(KEY_LATEST_VERSION)
                .remove(KEY_DOWNLOAD_URL)
                .remove(KEY_RELEASE_NOTES)
                .apply()
        }

        return@withContext update
    }

    /**
     * 强制检查更新（忽略节流）。
     * @return Pair<是否有更新, 消息字符串>
     */
    suspend fun forceCheck(context: Context): Pair<UpdateInfo?, String> = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersion(context)
            ?: return@withContext Pair(null, "无法读取当前版本")

        var update = checkRbotApi(currentVersion)
        if (update == null) {
            update = checkGitHubApi(currentVersion)
        }

        if (update != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LATEST_VERSION, update.latestVersion)
                .putString(KEY_DOWNLOAD_URL, update.downloadUrl)
                .putString(KEY_RELEASE_NOTES, update.releaseNotes)
                .apply()
            Pair(update, "发现新版: v${update.latestVersion}")
        } else {
            Pair(null, "当前已是最新版本")
        }
    }

    /** 从缓存获取可用更新 */
    fun getAvailableUpdate(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null)
        if (latestVersion == dismissed) return null

        val currentVersion = getCurrentVersion(context) ?: return null
        if (!isNewer(latestVersion, currentVersion)) return null

        return UpdateInfo(
            latestVersion = latestVersion,
            downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, "") ?: "",
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: ""
        )
    }

    /** 忽略指定版本 */
    fun dismissVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    /** 打开下载页面 */
    fun openDownloadPage(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    // ─── 内部实现 ───

    private fun getCurrentVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { null }
    }

    private fun getCachedUpdate(context: Context, prefs: android.content.SharedPreferences, currentVersion: String): UpdateInfo? {
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null)
        if (latestVersion == dismissed) return null
        if (!isNewer(latestVersion, currentVersion)) return null
        return UpdateInfo(
            latestVersion = latestVersion,
            downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, "") ?: "",
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: ""
        )
    }

    /** rbot API 检查 */
    private fun checkRbotApi(currentVersion: String): UpdateInfo? {
        return try {
            val urlStr = "$CHECK_URL?v=$currentVersion"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            val latestVersion = json.optString("latest_version", "")
            val downloadUrl = json.optString("download_url", "")
            val notes = json.optString("release_notes", "")

            if (latestVersion.isNotEmpty() && isNewer(latestVersion, currentVersion)) {
                UpdateInfo(latestVersion, downloadUrl, notes)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "rbot API check failed: ${e.message}")
            null
        }
    }

    /** GitHub API 检查 */
    private fun checkGitHubApi(currentVersion: String): UpdateInfo? {
        return try {
            val conn = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            val latestVersion = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "")
            val downloadUrl = if (latestVersion.isNotEmpty()) {
                APK_DOWNLOAD_URL.format(latestVersion, latestVersion)
            } else ""

            if (latestVersion.isNotEmpty() && isNewer(latestVersion, currentVersion)) {
                UpdateInfo(latestVersion, downloadUrl, releaseNotes)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API check failed: ${e.message}")
            null
        }
    }

    /** 版本比较 */
    private fun isNewer(latest: String, current: String): Boolean {
        return try {
            val l = parseSemver(latest)
            val c = parseSemver(current)
            for (i in 0..2) {
                if (l[i] > c[i]) return true
                if (l[i] < c[i]) return false
            }
            false
        } catch (_: Exception) { false }
    }

    private fun parseSemver(v: String): IntArray {
        var t = v.trim()
        if (t.startsWith("v")) t = t.substring(1)
        val si = t.indexOf('-')
        val bi = t.indexOf('+')
        var ti = si
        if (bi >= 0 && (ti < 0 || bi < ti)) ti = bi
        if (ti >= 0) t = t.substring(0, ti)
        val p = t.split(".")
        return intArrayOf(
            p.getOrNull(0)?.toIntOrNull() ?: 0,
            p.getOrNull(1)?.toIntOrNull() ?: 0,
            p.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }
}
