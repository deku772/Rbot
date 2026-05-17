package app.rbot.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.rbot.IShellService
import rikka.shizuku.Shizuku

/**
 * 授权模式管理器 — 从 Java AuthManager 迁移而来。
 *
 * 检测优先级：ROOT > SHIZUKU (UID=0) > SHIZUKU_ADB > PROOT > UNAVAILABLE
 *
 * Shizuku 相关的监听器仍在此手动管理（Hilt 不适合管理 Shizuku 生命周期）。
 */
class AuthManager private constructor() {

    enum class AuthMode {
        /** su 二进制可用（Magisk/KernelSU/APatch） */
        ROOT,
        /** Shizuku/Sui root 模式（UID 0） */
        SHIZUKU,
        /** Shizuku ADB 模式（UID != 0）— 不足够 chroot，但可 PRoot */
        SHIZUKU_ADB,
        /** PRoot 模式 — 无需 root，通过 ptrace 系统调用拦截 */
        PROOT,
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

    var forceProot: Boolean = false
        set(value) {
            field = value
            detectAndSetMode()
        }

    private var shellService: IShellService? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var serviceConnection: ServiceConnection? = null
    private var callback: AuthCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // ─── Shizuku 权限请求 ───

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku permission ${if (granted) "granted" else "denied"}")
            if (granted) bindShellService() else updateMode(AuthMode.UNAVAILABLE)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received, UID=${Shizuku.getUid()}")
        detectAndSetMode()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        if (currentMode == AuthMode.SHIZUKU) updateMode(AuthMode.UNAVAILABLE)
    }

    // ─── 公开 API ───

    fun init(context: Context) {
        appContext = context.applicationContext

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        val ctx = appContext!!
        serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShellService::class.java.name)
        ).tag("rbot_shell").version(1)

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Log.i(TAG, "ShellService connected")
                shellService = IShellService.Stub.asInterface(service)
                try {
                    val ping = shellService!!.ping()
                    val json = org.json.JSONObject(ping)
                    val stdout = json.optString("stdout", "")
                    Log.i(TAG, "ShellService ping: $stdout")
                    updateMode(if (stdout.contains("uid=0")) AuthMode.SHIZUKU else AuthMode.UNAVAILABLE)
                } catch (e: Exception) {
                    Log.e(TAG, "ShellService ping failed: ${e.message}")
                    updateMode(AuthMode.UNAVAILABLE)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "ShellService disconnected")
                shellService = null
                if (currentMode == AuthMode.SHIZUKU) updateMode(AuthMode.UNAVAILABLE)
            }
        }

        detectAndSetMode()
    }

    fun detectAndSetMode() {
        // Priority 1: Root
        if (ChrootManager.isRootAvailable()) {
            Log.i(TAG, "Root available → ROOT mode")
            unbindShellService()
            detectedMode = AuthMode.ROOT
            updateMode(if (forceProot) AuthMode.PROOT else AuthMode.ROOT)
            return
        }

        // Priority 2-3: Shizuku
        try {
            if (!isShizukuBinderAlive) throw IllegalStateException("Shizuku binder not alive")
            val uid = Shizuku.getUid()

            if (uid == 0) {
                detectedMode = AuthMode.SHIZUKU
                if (!forceProot) {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "Shizuku root, permission granted → SHIZUKU mode")
                        bindShellService()
                    } else {
                        Log.i(TAG, "Shizuku root, requesting permission")
                        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                    }
                } else {
                    updateMode(AuthMode.PROOT)
                }
                return
            }

            if (uid > 0) {
                Log.w(TAG, "Shizuku ADB mode (UID $uid) → PRoot")
                detectedMode = AuthMode.SHIZUKU_ADB
                updateMode(AuthMode.PROOT)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku check failed: ${e.message} → fallback")
        }

        // Priority 4: PRoot
        detectedMode = AuthMode.UNAVAILABLE
        Log.i(TAG, "No root access available, using PRoot mode")
        updateMode(AuthMode.PROOT)
    }

    /** 通过 Shizuku 执行命令 */
    fun execViaShizuku(command: String, timeoutSec: Int): CommandResult {
        val service = shellService
        if (service == null) {
            Log.e(TAG, "ShellService not bound")
            return CommandResult(false, "", "Shizuku 服务未连接", -1)
        }
        return try {
            val json = service.exec(command, timeoutSec)
            parseCommandResult(json)
        } catch (e: Exception) {
            Log.e(TAG, "ShellService exec failed: ${e.message}")
            CommandResult(false, "", "Shizuku 通信失败: ${e.message}", -1)
        }
    }

    /** 获取 Application Context（供 ChrootManager 等 core 层访问） */
    val context: Context?
        get() = appContext

    val isShizukuReady: Boolean
        get() = currentMode == AuthMode.SHIZUKU && shellService != null

    val isProotMode: Boolean
        get() = currentMode == AuthMode.PROOT

    val isShizukuAvailable: Boolean
        get() = try { Shizuku.getUid() == 0 } catch (_: Exception) { false }

    val isShizukuBinderAlive: Boolean
        get() = try { Shizuku.getUid() >= 0 } catch (_: Exception) { false }

    val isShizukuAdbMode: Boolean
        get() = try { Shizuku.getUid() > 0 } catch (_: Exception) { false }

    val isShizukuPermissionGranted: Boolean
        get() = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }

    fun setCallback(cb: AuthCallback?) { callback = cb }

    fun requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } else {
            bindShellService()
        }
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unbindShellService()
    }

    // ─── Internal ───

    private fun bindShellService() {
        try {
            Log.i(TAG, "Binding ShellService via Shizuku")
            val args = serviceArgs ?: return
            val conn = serviceConnection ?: return
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ShellService: ${e.message}")
            updateMode(AuthMode.UNAVAILABLE)
        }
    }

    private fun unbindShellService() {
        val args = serviceArgs ?: return
        val conn = serviceConnection ?: return
        try {
            Shizuku.unbindUserService(args, conn, false)
        } catch (_: Exception) { }
        shellService = null
    }

    private fun updateMode(mode: AuthMode) {
        if (currentMode != mode) {
            Log.i(TAG, "Auth mode changed: $currentMode → $mode")
            currentMode = mode
            ChrootManager.useShizuku = (mode == AuthMode.SHIZUKU)
            callback?.let { handler.post { it.onAuthChanged(mode) } }
        }
    }

    private fun parseCommandResult(json: String): CommandResult {
        return try {
            val obj = org.json.JSONObject(json)
            CommandResult(
                success = obj.optBoolean("success", false),
                stdout = obj.optString("stdout", ""),
                stderr = obj.optString("stderr", ""),
                exitCode = obj.optInt("exitCode", -1)
            )
        } catch (e: Exception) {
            CommandResult(false, "", "JSON parse error: ${e.message}", -1)
        }
    }

    companion object {
        private const val TAG = "AuthManager"
        private const val SHIZUKU_REQUEST_CODE = 101

        val instance: AuthManager by lazy { AuthManager() }
    }
}
