package app.rbot.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.*
import app.rbot.data.model.SetupUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * 安装向导 ViewModel — 替代旧版 SetupActivity。
 * 使用协程 + StateFlow 替代 Thread + Handler + wait/notify。
 * 对话框通过 state 驱动（showProxyPicker / showVersionPicker / showRestorePrompt）。
 *
 * 安装路径：Chroot（有 root）Ubuntu rootfs + chroot →
 * ChrootManager.aptUpdate / aptInstallDeps / cloneAstrBot / pipInstallDeps
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botBridge: BotBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    // ─── 用户选择结果（由 UI 回调设置） ───

    private var selectedProxyIndex: Int = 0
    private var selectedVersion: String = ""
    private var shouldRestore: Boolean = false

    // ─── 对话框状态 ───

    private val _proxyPickerState = MutableStateFlow<ProxyPickerState?>(null)
    val proxyPickerState: StateFlow<ProxyPickerState?> = _proxyPickerState.asStateFlow()

    private val _versionPickerState = MutableStateFlow<VersionPickerState?>(null)
    val versionPickerState: StateFlow<VersionPickerState?> = _versionPickerState.asStateFlow()

    private val _restorePromptState = MutableStateFlow(false)
    val restorePromptState: StateFlow<Boolean> = _restorePromptState.asStateFlow()

    init {
        detectEnvironment()
    }

    /** 自动检测现有环境 */
    private fun detectEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val rootfsReady = ChrootManager.isRootfsReady()
            val botInstalled = botBridge.isBotInstalled()
            val depsOk = if (rootfsReady) {
                ChrootManager.checkDepsPresent()
            } else false

            _uiState.value = SetupUiState(
                isDetecting = false,
                rootfsReady = rootfsReady,
                botInstalled = botInstalled,
                depsInstalled = depsOk,
                reinstallRootfs = !rootfsReady,
                reinstallDeps = !depsOk,
                reinstallBot = !botInstalled
            )
        }
    }

    /** 更新复选框选项 */
    fun updateReinstallOption(
        reinstallRootfs: Boolean? = null,
        reinstallDeps: Boolean? = null,
        reinstallBot: Boolean? = null
    ) {
        _uiState.value = _uiState.value.copy(
            reinstallRootfs = reinstallRootfs ?: _uiState.value.reinstallRootfs,
            reinstallDeps = reinstallDeps ?: _uiState.value.reinstallDeps,
            reinstallBot = reinstallBot ?: _uiState.value.reinstallBot
        )
    }

    // ─── 安装入口 ───

    fun startInstallation(
        reinstallRootfs: Boolean = false,
        reinstallDeps: Boolean = false,
        reinstallBot: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isInstalling = true,
                errorMessage = null,
                isComplete = false,
                currentStep = 0,
                progress = 0f
            )

            try {
                runInstall(reinstallRootfs, reinstallDeps, reinstallBot)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isInstalling = false,
                    errorMessage = e.message
                )
            }
        }
    }

    // ─── 用户回调 ───

    fun onProxySelected(index: Int) {
        selectedProxyIndex = index
        _proxyPickerState.value = null
    }

    fun onVersionSelected(version: String, proxyIndex: Int) {
        selectedVersion = version
        selectedProxyIndex = proxyIndex
        _versionPickerState.value = null
    }

    fun onVersionPickerCancelled() {
        selectedVersion = ""
        _versionPickerState.value = null
    }

    fun onRestoreConfirmed(restore: Boolean) {
        shouldRestore = restore
        _restorePromptState.value = false
    }

    // ─── 核心安装流水线 ───

    private suspend fun runInstall(
        reinstallRootfs: Boolean,
        reinstallDeps: Boolean,
        reinstallBot: Boolean
    ) {
        // ─── Step 0: 权限检查 ───
        updateStep(0, "检查权限...")
        if (ChrootManager.isSuBinaryPresent()) {
            appendLog("检测到 su 二进制，Chroot 模式")
        } else {
            appendLog("未检测到 su 二进制，仍尝试 Chroot 模式")
        }
        val testResult = ChrootManager.execRoot("id", 10)
        val isRoot = testResult.success && testResult.stdout.contains("uid=0")
        if (!isRoot) {
            if (ChrootManager.isKernelSuUmountActive()) {
                appendLog("检测到 KernelSU 环境，但 su 被隐藏（kernel_umount）")
                _uiState.value = _uiState.value.copy(
                    isInstalling = false,
                    errorMessage = "KernelSU 的「内核自动卸载」功能隐藏了 su。\n\n" +
                        "请在 KernelSU 管理器中关闭「卸载模块」(kernel_umount)。"
                )
            } else {
                appendLog("Root 权限不可用")
                _uiState.value = _uiState.value.copy(
                    isInstalling = false,
                    errorMessage = "未获取 Root 权限，无法继续安装。\n\n" +
                        "你可以：\n" +
                        "• 授予 Root 权限后重新打开本应用\n" +
                        "• 使用免 Root 方案：从 GitHub 下载 AstrBot 3.0.6 独立版，或使用 AstrBot 官方 App"
                )
            }
            return
        }
        appendLog("Root 权限验证通过 (uid=0)")

        // ─── Step 1: 网络测试 + 自动选择最快代理 ───
        updateStep(1, "测试网络...")
        appendLog("测试 GitHub 连接...")
        val proxyResults = GitHubProxyManager.testProxies()
        val bestProxy = proxyResults.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }
        selectedProxyIndex = bestProxy?.index ?: 0
        appendLog("最快线路: ${bestProxy?.name ?: "直连"} (GitHub)")

        // ─── Step 2: 系统镜像 ───
        val needRootfs = !ChrootManager.isRootfsReady() || reinstallRootfs

        if (needRootfs) {
            // 删除旧标记
            ChrootManager.execRoot("rm -f ${RbotPaths.ROOTFS_MARKER}")

            updateStep(2, "准备系统镜像...")
            // Chroot 模式：下载 Ubuntu rootfs
            val rootfsOk = awaitChrootRootfs()
            if (!rootfsOk) return

            // 检查是否需要恢复备份
            val backupExists = ChrootManager.execRoot(
                "test -f '${RbotPaths.EXTERNAL_DATA_BACKUP}.tar.gz' -o -d '${RbotPaths.EXTERNAL_DATA_BACKUP}' && echo yes"
            )
            if (backupExists.success && backupExists.stdout.trim() == "yes") {
                shouldRestore = false
                _restorePromptState.value = true
                while (_restorePromptState.value) { kotlinx.coroutines.delay(100) }
                if (shouldRestore) {
                    appendLog("正在恢复备份数据...")
                    ChrootManager.restoreAstrBotDataFromDefault(
                        ChrootManager.SimpleFullCallback(
                            onProgressFn = { appendLog(it) },
                            onErrorFn = { appendLog("恢复失败: $it") }
                        )
                    )
                }
            }
        } else {
            appendLog("系统镜像已存在，跳过")
            ChrootManager.setupChrootEnvironment(
                ChrootManager.SimpleFullCallback(
                    onProgressFn = { appendLog(it) },
                    onErrorFn = { appendLog(it) }
                )
            )
        }

        // ─── Step 2b: 初始化 chroot 环境（rootfs 新解压后必须执行）───
        if (needRootfs) {
            appendLog("正在初始化 chroot 环境...")
            ChrootManager.setupChrootEnvironment(
                ChrootManager.SimpleFullCallback(
                    onProgressFn = { appendLog(it) },
                    onErrorFn = { appendLog(it) }
                )
            )
        }

        // ─── Step 3: 安装依赖 ───
        val needDeps = reinstallDeps || needRootfs
        if (needDeps) {
            updateStep(3, "安装依赖...")
            // Chroot 模式：apt install
            val callback = ChrootManager.SimpleFullCallback(
                onProgressFn = { appendLog(it) },
                onErrorFn = { appendLog("错误: $it") }
            )
            if (ChrootManager.aptUpdate(callback)) {
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "软件源更新失败")
                return
            }
            if (ChrootManager.aptInstallDeps(callback)) {
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "依赖安装失败")
                return
            }
            appendLog("依赖安装完成")
        }

        // ─── Step 4: 安装 AstrBot ───
        val needBot = !botBridge.isBotInstalled() || reinstallBot
        if (needBot) {
            // 删除旧标记
            ChrootManager.execRoot("rm -f ${RbotPaths.ASTRBOT_MARKER}")

            updateStep(4, "安装 AstrBot...")
            appendLog("安装 AstrBot 最新版...")

            // Chroot 模式：git clone + pip install
            val callback = ChrootManager.SimpleFullCallback(
                onProgressFn = { appendLog(it) },
                onErrorFn = { appendLog("错误: $it") }
            )
            if (ChrootManager.cloneAstrBotWithProxy(callback, null, selectedProxyIndex)) {
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "AstrBot 克隆失败")
                return
            }
            if (ChrootManager.pipInstallDeps(callback)) {
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "依赖安装失败")
                return
            }
            appendLog("AstrBot 安装完成")
        }

        // ─── 完成 ───
        _uiState.value = _uiState.value.copy(
            isInstalling = false,
            isComplete = true,
            currentStep = 4,
            progress = 1f
        )
        appendLog("安装完成！")
    }

    // ─── Chroot 模式 rootfs 下载与提取 ───

    private suspend fun awaitChrootRootfs(): Boolean {
        // Step 2a: 检查本地缓存
        appendLog("检查本地缓存...")
        val localCache = File(RbotPaths.SDCARD_ROOTFS_CACHE)
        var tarballPath: String? = null

        if (localCache.exists() && localCache.length() > 400 * 1024 * 1024) {
            appendLog("找到本地缓存: ${localCache.length() / (1024 * 1024)} MB")
            val md5 = computeMd5(localCache.absolutePath)
            when {
                md5 == RbotPaths.ROOTFS_MD5 -> {
                    appendLog("MD5 校验通过")
                    tarballPath = localCache.absolutePath
                }
                md5 != null -> {
                    appendLog("MD5 不匹配，删除缓存重新下载")
                    localCache.delete()
                }
                else -> {
                    appendLog("MD5 无法计算，但文件大小正常，继续使用")
                    tarballPath = localCache.absolutePath
                }
            }
        }

        // Step 2b: 下载
        if (tarballPath == null) {
            appendLog("下载系统镜像...")
            val downloadUrl = GitHubProxyManager.buildUrl(RbotPaths.GITHUB_ROOTFS_URL, selectedProxyIndex)
            val destPath = RbotPaths.SDCARD_ROOTFS_CACHE

            try {
                ChrootManager.execRoot("mkdir -p ${RbotPaths.SDCARD_CACHE_DIR}")
                val curlResult = ChrootManager.execRoot(
                    "curl -L --progress-bar -o '$destPath' '$downloadUrl'", 600
                )
                if (!curlResult.success) {
                    appendLog("代理下载失败，尝试直连...")
                    ChrootManager.execRoot(
                        "curl -L --progress-bar -o '$destPath' '${RbotPaths.GITHUB_ROOTFS_URL}'", 600
                    )
                }

                val md5 = computeMd5(destPath)
                when {
                    md5 == RbotPaths.ROOTFS_MD5 -> {
                        appendLog("MD5 校验通过")
                        tarballPath = destPath
                    }
                    md5 != null -> {
                        appendLog("MD5 不匹配（$md5），文件可能损坏")
                        File(destPath).delete()
                        _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "下载文件 MD5 不匹配")
                        return false
                    }
                    else -> {
                        appendLog("MD5 无法计算，但文件存在，继续")
                        tarballPath = destPath
                    }
                }
            } catch (e: Exception) {
                appendLog("下载失败: ${e.message}")
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "系统镜像下载失败: ${e.message}")
                return false
            }
        }

        // Step 2c: 提取
        appendLog("解压系统镜像...")
        val callback = ChrootManager.SimpleFullCallback(
            onProgressFn = { appendLog(it) },
            onErrorFn = { appendLog("错误: $it") }
        )
        if (ChrootManager.extractRootfs(tarballPath!!, callback)) {
            _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "系统镜像解压失败")
            return false
        }

        appendLog("系统镜像就绪")
        return true
    }

    // ─── 工具方法 ───

    /** 计算文件 MD5，返回小写十六进制字符串；失败返回 null */
    private fun computeMd5(path: String): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val file = File(path)
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateStep(step: Int, label: String) {
        _uiState.value = _uiState.value.copy(
            currentStep = step,
            stepLabel = label,
            progress = (step + 1) / 5f
        )
    }

    private fun appendLog(message: String) {
        _logFlow.tryEmit(message)
    }
}

// ─── 对话框状态类（顶层，方便 Screen 引用） ───

data class ProxyPickerState(
    val proxies: List<GitHubProxyManager.ProxyInfo>,
    val selectedIndex: Int
)

data class VersionPickerState(
    val versions: List<String>,
    val proxies: List<GitHubProxyManager.ProxyInfo>,
    val selectedVersionIndex: Int = 0,
    val selectedProxyIndex: Int = 0
)

// ─── GitHubProxyManager 扩展：获取版本列表 ───

private suspend fun GitHubProxyManager.fetchReleases(proxyIndex: Int, maxCount: Int): List<String> {
    return try {
        val apiUrl = buildUrl("https://api.github.com/repos/AstrBotDevs/AstrBot/tags?per_page=$maxCount", proxyIndex)
        val conn = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Rbot/4.0")

        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().readText()
            val arr = org.json.JSONArray(json)
            (0 until minOf(arr.length(), maxCount)).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name", "")
                name.takeIf { it.isNotEmpty() }
            }
        } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
