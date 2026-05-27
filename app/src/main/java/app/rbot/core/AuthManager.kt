package app.rbot.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * 授权模式管理器。
 *
 * 检测是否有 root 权限（su 二进制可用）。
 */
class AuthManager private constructor() {

    enum class AuthMode {
        /** su 二进制可用（Magisk/KernelSU/APatch） */
        ROOT,
        /** 无 root 权限可用 */
        UNAVAILABLE
    }

    fun interface AuthCallback {
        fun onAuthChanged(mode: AuthMode)
    }

    var currentMode: AuthMode = AuthMode.UNAVAILABLE
        private set

    var detectedMode: AuthMode = AuthMode.UNAVAILABLE
        private set

    private var callback: AuthCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // ─── 公开 API ───

    fun init(context: Context) {
        appContext = context.applicationContext
        // 注意：不在此处调用 Shell.setDefaultBuilder()！
        // ChrootManager.init 已配置了 FLAG_MOUNT_MASTER 和自定义 su 路径，
        // 此处覆盖会导致 libsu 回退到非 root shell。
        Shell.enableVerboseLogging = false
        detectAndSetMode()
    }

    /**
     * 轻量初始化 — 仅用 isSuBinaryPresent() 快速检测，不在主线程执行 su 命令。
     * 适用于 Application.onCreate() 等不能阻塞的场景。
     * 后续由 HomeViewModel.refreshState() 完成完整检测。
     */
    fun initLightweight(context: Context) {
        appContext = context.applicationContext
        if (ChrootManager.isSuBinaryPresent()) {
            // su 二进制存在，暂时标记 ROOT，后续完整检测会确认
            Log.i(TAG, "Lightweight init: su binary present, tentatively ROOT")
            detectedMode = AuthMode.ROOT
            updateMode(AuthMode.ROOT)
        } else {
            // 无 su 二进制 → UNAVAILABLE
            Log.i(TAG, "Lightweight init: no su binary → UNAVAILABLE")
            detectedMode = AuthMode.UNAVAILABLE
            updateMode(AuthMode.UNAVAILABLE)
        }
    }

    fun detectAndSetMode() {
        // 自动检测：使用 libsu Shell.isAppGrantedRoot() 不弹窗超时
        val rootGranted = Shell.isAppGrantedRoot()
        if (rootGranted == true) {
            Log.i(TAG, "libsu: root granted → ROOT mode")
            detectedMode = AuthMode.ROOT
            updateMode(AuthMode.ROOT)
            return
        }
        // rootGranted == null → 尝试创建 shell
        if (rootGranted == null) {
            try {
                if (Shell.getShell().isRoot) {
                    Log.i(TAG, "libsu: shell created → ROOT mode")
                    detectedMode = AuthMode.ROOT
                    updateMode(AuthMode.ROOT)
                    return
                }
            } catch (_: Exception) { }
        }
        // 回退到 su 二进制检查
        if (ChrootManager.isSuBinaryPresent()) {
            Log.i(TAG, "su binary present → ROOT mode (pending)")
            detectedMode = AuthMode.ROOT
            updateMode(AuthMode.ROOT)
            return
        }
        // 无 root → UNAVAILABLE
        detectedMode = AuthMode.UNAVAILABLE
        Log.i(TAG, "No root access → UNAVAILABLE")
        updateMode(AuthMode.UNAVAILABLE)
    }

    /** 获取 Application Context（供 ChrootManager 等 core 层访问） */
    val context: Context?
        get() = appContext

    fun setCallback(cb: AuthCallback?) { callback = cb }

    fun destroy() {
        // nothing to clean up
    }

    // ─── Internal ───

    private fun updateMode(mode: AuthMode) {
        if (currentMode != mode) {
            Log.i(TAG, "Auth mode changed: $currentMode → $mode")
            currentMode = mode
            callback?.let { handler.post { it.onAuthChanged(mode) } }
        }
    }

    companion object {
        private const val TAG = "AuthManager"

        val instance: AuthManager by lazy { AuthManager() }
    }
}
