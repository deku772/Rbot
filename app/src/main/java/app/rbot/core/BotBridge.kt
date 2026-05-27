package app.rbot.core

import android.content.Context

/**
 * Bot 管理桥接层 — 合并旧版 BotManager + BotAdapter + AstrBotAdapter。
 *
 * 通过 ChrootManager 管理 Bot 生命周期。
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
        return ChrootManager.isAstrBotInstalled()
    }

    fun isBotRunning(): Boolean {
        return ChrootManager.isAstrBotRunning()
    }

    fun isRootfsReady(): Boolean {
        return ChrootManager.isRootfsReady()
    }

    fun getComponentStatus(): ComponentStatus {
        return ComponentStatus(
            binaries = ComponentState.READY,
            rootfs = if (ChrootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
            bootstrap = if (ChrootManager.isRootfsReady()) ComponentState.READY else ComponentState.NOT_INSTALLED,
            astrbot = if (ChrootManager.isAstrBotInstalled()) ComponentState.READY else ComponentState.NOT_INSTALLED
        )
    }

    // ─── 生命周期控制 ───

    fun startBot(): CommandResult {
        LogHub.log("正在启动 Bot...")
        val result = ChrootManager.startAstrBot()
        if (!result.success) {
            LogHub.error("Bot 启动失败: ${result.stderr.take(80)}")
        }
        return result
    }

    fun stopBot(): CommandResult {
        LogHub.log("正在停止 Bot...")
        return ChrootManager.stopAstrBot()
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
        return RbotPaths.ASTRBOT_LOG_FILE.substringAfter(RbotPaths.CHROOT_DIR)
    }

    fun getHomePath(): String {
        return RbotPaths.ASTRBOT_HOME
    }

    companion object {
        private var instance: BotBridge? = null

        fun getInstance(context: Context): BotBridge {
            return instance ?: BotBridge(context.applicationContext).also { instance = it }
        }
    }
}
