package app.rbot.core

import android.content.Context
import app.rbot.core.AuthManager.AuthMode

/**
 * Bot 管理桥接层 — 合并旧版 BotManager + BotAdapter + AstrBotAdapter。
 *
 * 根据 AuthManager 的当前模式，自动路由到 ChrootManager 或 PRootManager。
 * 使用密封类统一模型，避免多层继承。
 */
class BotBridge private constructor(private val context: Context) {

    /** Bot 运行状态 — 统一的状态模型 */
    enum class State {
        NOT_INSTALLED,
        INSTALLING,
        READY,
        RUNNING,
        UPDATING,
        ERROR
    }

    /** Bot 信息 — 替代旧版 BotInfo */
    data class BotInfo(
        val engine: BotEngine,
        val isInstalled: Boolean,
        val isRunning: Boolean,
        val state: State
    )

    /** 组件安装状态 */
    enum class ComponentState { NOT_INSTALLED, INSTALLING, READY, ERROR }

    data class ComponentStatus(
        val binaries: ComponentState = ComponentState.NOT_INSTALLED,
        val rootfs: ComponentState = ComponentState.NOT_INSTALLED,
        val bootstrap: ComponentState = ComponentState.NOT_INSTALLED,
        val astrbot: ComponentState = ComponentState.NOT_INSTALLED
    ) {
        val allReady: Boolean
            get() = binaries == ComponentState.READY &&
                rootfs == ComponentState.READY &&
                bootstrap == ComponentState.READY &&
                astrbot == ComponentState.READY
    }

    private val prootManager: PRootManager
        get() = PRootManager.getInstance(context)

    private fun useProot(): Boolean = AuthManager.instance.isProotMode

    // ─── Active Bot ───

    fun getActiveBot(): BotInfo {
        val engine = BotEngine.ASTRBOT
        val installed = isBotInstalled()
        val running = isBotRunning()
        val state = when {
            running -> State.RUNNING
            installed -> State.READY
            else -> State.NOT_INSTALLED
        }
        return BotInfo(engine, installed, running, state)
    }

    // ─── 状态检查 ───

    fun isBotInstalled(): Boolean {
        return if (useProot()) prootManager.isAstrBotInstalled()
        else ChrootManager.isAstrBotInstalled()
    }

    fun isBotRunning(): Boolean {
        return if (useProot()) prootManager.isAstrBotRunning()
        else ChrootManager.isAstrBotRunning()
    }

    fun isRootfsReady(): Boolean {
        return if (useProot()) prootManager.isRootfsReady()
        else ChrootManager.isRootfsReady()
    }

    fun getComponentStatus(): ComponentStatus {
        return if (useProot()) {
            ComponentStatus(
                binaries = if (true) ComponentState.READY else ComponentState.NOT_INSTALLED,
                rootfs = if (prootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
                bootstrap = if (prootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
                astrbot = if (prootManager.isAstrBotInstalled()) ComponentState.READY else ComponentState.NOT_INSTALLED
            )
        } else {
            ComponentStatus(
                binaries = ComponentState.READY,
                rootfs = if (ChrootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
                bootstrap = if (ChrootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
                astrbot = if (ChrootManager.isAstrBotInstalled()) ComponentState.READY else ComponentState.NOT_INSTALLED
            )
        }
    }

    // ─── 生命周期控制 ───

    fun startBot(): CommandResult {
        LogHub.log("正在启动 Bot...")
        val result = if (useProot()) prootManager.startAstrBot()
        else ChrootManager.startAstrBot()
        if (!result.success) {
            LogHub.error("Bot 启动失败: ${result.stderr.take(80)}")
        }
        return result
    }

    fun stopBot(): CommandResult {
        LogHub.log("正在停止 Bot...")
        return if (useProot()) prootManager.stopAstrBot()
        else ChrootManager.stopAstrBot()
    }

    fun restartBot(): CommandResult {
        LogHub.log("正在重启 Bot...")
        stopBot()
        Thread.sleep(1000)
        return startBot()
    }

    // ─── 访问器 ───

    fun getWebUIUrl(): String = BotEngine.ASTRBOT.webUIUrl

    fun getLogFile(): String {
        return if (useProot()) prootManager.astrBotLogFile
        else RbotPaths.ASTRBOT_LOG_FILE.substringAfter(RbotPaths.CHROOT_DIR)
    }

    fun getHomePath(): String {
        return if (useProot()) prootManager.astrBotHome
        else RbotPaths.ASTRBOT_HOME
    }

    companion object {
        private var instance: BotBridge? = null

        fun getInstance(context: Context): BotBridge {
            return instance ?: BotBridge(context.applicationContext).also { instance = it }
        }
    }
}
