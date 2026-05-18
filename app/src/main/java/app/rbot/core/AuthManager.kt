package app.rbot.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * 授权模式管理器。
 *
 * 检测优先级：ROOT > PROOT
 *
 * 用户可通过 UserModeChoice 显式选择运行模式，或走自动检测。
 */
class AuthManager private constructor() {

    enum class AuthMode {
        /** su 二进制可用（Magisk/KernelSU/APatch） */
        ROOT,
        /** PRoot 模式 — 无需 root，通过 ptrace 系统调用拦截 */
        PROOT,
        /** 无 root 权限可用 */
        UNAVAILABLE
    }

    /** 用户对运行模式的显式选择 */
    enum class UserModeChoice {
        /** 尚未选择，走自动检测 */
        AUTO,
        /** 用户明确选择 Chroot（Root）模式 */
        CHROOT,
        /** 用户明确选择 PRoot 模式 */
        PROOT
    }

    fun interface AuthCallback {
        fun onAuthChanged(mode: AuthMode)
    }

    var currentMode: AuthMode = AuthMode.UNAVAILABLE
        private set

    var detectedMode: AuthMode = AuthMode.UNAVAILABLE
        private set

    var userChoice: UserModeChoice = UserModeChoice.AUTO
        set(value) {
            field = value
            detectAndSetMode()
        }

    /** 兼容旧字段 */
    var forceProot: Boolean
        get() = userChoice == UserModeChoice.PROOT
        set(value) {
            userChoice = if (value) UserModeChoice.PROOT else UserModeChoice.CHROOT
        }

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
        // libsu 自动处理 mount namespace 问题，isSuBinaryPresent() 使用 Shell.isAppGrantedRoot()
        if (userChoice == UserModeChoice.PROOT) {
            detectedMode = AuthMode.UNAVAILABLE
            updateMode(AuthMode.PROOT)
        } else if (ChrootManager.isSuBinaryPresent()) {
            // su 二进制存在，暂时标记 ROOT，后续完整检测会确认
            Log.i(TAG, "Lightweight init: su binary present, tentatively ROOT")
            detectedMode = AuthMode.ROOT
            updateMode(AuthMode.ROOT)
        } else if (userChoice == UserModeChoice.CHROOT) {
            // 用户选了 chroot 但没 su 二进制 — 仍标记 ROOT，安装时会触发授权弹窗
            Log.i(TAG, "Lightweight init: user chose Chroot, tentatively ROOT")
            detectedMode = AuthMode.ROOT
            updateMode(AuthMode.ROOT)
        } else {
            // AUTO 模式且无 su → PRoot
            Log.i(TAG, "Lightweight init: no su binary → PRoot mode")
            detectedMode = AuthMode.UNAVAILABLE
            updateMode(AuthMode.PROOT)
        }
    }

    fun detectAndSetMode() {
        when (userChoice) {
            UserModeChoice.CHROOT -> {
                // 用户明确选了 Chroot → 无条件走 chroot 路径
                if (ChrootManager.isRootAvailable()) {
                    Log.i(TAG, "User chose Chroot, root available → ROOT mode")
                    detectedMode = AuthMode.ROOT
                    updateMode(AuthMode.ROOT)
                } else {
                    // su 暂未授权或 su 二进制位置不在 PATH —
                    // 仍然设为 ROOT 模式，安装流程中执行 su 命令时会触发授权弹窗
                    Log.i(TAG, "User chose Chroot → ROOT mode (pending su authorization)")
                    detectedMode = AuthMode.ROOT
                    updateMode(AuthMode.ROOT)
                }
                return
            }

            UserModeChoice.PROOT -> {
                // 用户明确选了 PRoot → 直接 PRoot
                Log.i(TAG, "User chose PRoot → PRoot mode")
                detectedMode = AuthMode.UNAVAILABLE
                updateMode(AuthMode.PROOT)
                return
            }

            UserModeChoice.AUTO -> {
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
                // 无 root → PRoot
                detectedMode = AuthMode.UNAVAILABLE
                Log.i(TAG, "No root access → PRoot mode")
                updateMode(AuthMode.PROOT)
            }
        }
    }

    /** 获取 Application Context（供 ChrootManager 等 core 层访问） */
    val context: Context?
        get() = appContext

    val isProotMode: Boolean
        get() = currentMode == AuthMode.PROOT

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
