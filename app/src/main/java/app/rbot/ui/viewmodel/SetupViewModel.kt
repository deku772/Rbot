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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject

/**
 * 安装向导 ViewModel — 替代旧版 SetupActivity（1050+ 行）。
 * 使用协程 + StateFlow 替代 Thread + Handler + wait/notify。
 * 对话框通过 state 驱动（showProxyPicker / showVersionPicker / showRestorePrompt）。
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

    private val prootManager: PRootManager
        get() = PRootManager.getInstance(context)

    /** 判断是否使用 PRoot 模式安装 */
    private fun useProot(): Boolean {
        // AuthManager.detectAndSetMode() 已经尊重用户选择
        // 用户选了 chroot → currentMode=ROOT → isProotMode=false
        // 即使 su 暂未授权，也走 chroot 路径，安装命令会触发 su 授权弹窗
        return AuthManager.instance.isProotMode
    }

    // ─── 用户选择结果（由 UI 回调设置） ───

    private var selectedProxyIndex: Int = 0
    private var selectedVersion: String = ""
    private var shouldRestore: Boolean = false

    // ─── 对话框状态 ───

    /** 代理选择对话框 */
    private val _proxyPickerState = MutableStateFlow<ProxyPickerState?>(null)
    val proxyPickerState: StateFlow<ProxyPickerState?> = _proxyPickerState.asStateFlow()

    /** 版本选择对话框 */
    private val _versionPickerState = MutableStateFlow<VersionPickerState?>(null)
    val versionPickerState: StateFlow<VersionPickerState?> = _versionPickerState.asStateFlow()

    /** 恢复备份提示 */
    private val _restorePromptState = MutableStateFlow(false)
    val restorePromptState: StateFlow<Boolean> = _restorePromptState.asStateFlow()

    init {
        detectEnvironment()
    }

    /** 自动检测现有环境 */
    private fun detectEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val isProot = useProot()
            val rootfsReady = if (isProot) prootManager.isRootfsReady() else ChrootManager.isRootfsReady()
            val botInstalled = botBridge.isBotInstalled()

            _uiState.value = SetupUiState(
                isDetecting = false,
                rootfsReady = rootfsReady,
                botInstalled = botInstalled,
                useProot = isProot,
                // 缺失的组件自动勾选安装
                reinstallRootfs = !rootfsReady,
                reinstallDeps = false,
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
        val isProot = useProot()

        // ─── Step 0: 权限检查 ───
        updateStep(0, "检查权限...")
        if (isProot) {
            appendLog("PRoot 免 Root 模式")
        } else if (!ChrootManager.isPrivilegedAccessAvailable()) {
            _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "需要 Root 或 Shizuku 权限")
            return
        }
        appendLog("权限检查通过")

        // ─── Step 1: 网络测试 + 代理选择 ───
        updateStep(1, "测试网络...")
        appendLog("测试 GitHub 连接...")
        val proxyResults = GitHubProxyManager.testProxies()
        val bestProxy = proxyResults.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }
        appendLog("最快线路: ${bestProxy?.name ?: "直连"}")

        // 弹出代理选择对话框（替代 wait/notify 阻塞）
        selectedProxyIndex = bestProxy?.index ?: 0
        _proxyPickerState.value = ProxyPickerState(
            proxies = proxyResults,
            selectedIndex = selectedProxyIndex
        )
        // 等待用户选择
        while (_proxyPickerState.value != null) {
            kotlinx.coroutines.delay(100)
        }
        appendLog("已选择代理: ${GitHubProxyManager.getProxyName(selectedProxyIndex)}")

        // PRoot 模式: 确保二进制可用
        if (isProot) {
            appendLog("检查 PRoot 二进制...")
            if (!prootManager.isProotBinaryAvailable()) {
                appendLog("下载 PRoot...")
                withContext(Dispatchers.IO) {
                    prootManager.ensureProotBinary()
                }
            }
            prootManager.ensureDirectories()
        }

        // ─── Step 2: Rootfs ───
        val needRootfs = if (isProot) !prootManager.isRootfsReady() || reinstallRootfs
                         else !ChrootManager.isRootfsReady() || reinstallRootfs

        if (needRootfs) {
            // 删除旧标记
            if (isProot) {
                File(prootManager.rootfsMarker).delete()
            } else {
                ChrootManager.execRoot("rm -f ${RbotPaths.ROOTFS_MARKER}")
            }

            updateStep(2, "准备系统镜像...")
            awaitRootfs(isProot)

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
                    if (isProot) {
                        prootManager.restoreAstrBotData(
                            "${RbotPaths.EXTERNAL_DATA_BACKUP}.tar.gz",
                            ChrootManager.SimpleFullCallback(
                                onProgressFn = { appendLog(it) },
                                onErrorFn = { appendLog("恢复失败: $it") }
                            )
                        )
                    } else {
                        ChrootManager.restoreAstrBotDataFromDefault(
                            ChrootManager.SimpleFullCallback(
                                onProgressFn = { appendLog(it) },
                                onErrorFn = { appendLog("恢复失败: $it") }
                            )
                        )
                    }
                }
            }
        } else {
            appendLog("系统镜像已存在，跳过")
            // 即使 rootfs 已存在，也要确保 chroot 环境初始化
            if (!isProot) {
                ChrootManager.setupChrootEnvironment(
                    ChrootManager.SimpleFullCallback(
                        onProgressFn = { appendLog(it) },
                        onErrorFn = { appendLog(it) }
                    )
                )
            }
        }

        // ─── Step 3: 安装依赖 ───
        val needDeps = reinstallDeps || needRootfs
        if (needDeps) {
            updateStep(3, "安装依赖...")
            if (isProot) {
                // PRoot 网络 + apt
                appendLog("测试容器网络...")
                val netTest = prootManager.runInProot(
                    "ping -c 1 -W 3 8.8.8.8 2>/dev/null && echo NET_OK || echo NET_FAIL", 10
                )
                if (netTest.stdout.contains("NET_FAIL")) {
                    appendLog("容器网络不可用，尝试修复 DNS...")
                    prootManager.ensureResolvConf()
                }

                appendLog("更新软件源...")
                prootManager.updateSourcesList()
                val aptResult = prootManager.runInProot(
                    "apt update --fix-missing --allow-unauthenticated 2>&1", 180
                )
                if (!aptResult.success) {
                    appendLog("apt update 部分失败（可继续）")
                }

                appendLog("安装系统依赖...")
                val depResult = prootManager.runInProot(
                    "apt install -y --allow-unauthenticated " +
                    "python3 python3-venv python3-pip python3-dev " +
                    "git curl wget ca-certificates locales build-essential " +
                    "dropbear-bin software-properties-common gpgv",
                    300
                )
                if (!depResult.success) {
                    appendLog("依赖安装失败: ${depResult.stderr.take(200)}")
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "依赖安装失败")
                    return
                }

                // locale + SSH 密码
                prootManager.runInProot("locale-gen en_US.UTF-8", 30)
                val password = RbotPaths.DEFAULT_SSH_PASSWORD
                prootManager.runInProot(
                    "HASH=\\$(openssl passwd -6 -salt rbotsalt '$password') && " +
                    "sed -i \"s|^root:[^:]*:|root:\\\$HASH:|\" /etc/shadow && " +
                    "printf '%s' '$password' > /root/.rbot_pass && chmod 600 /root/.rbot_pass",
                    15
                )
            } else {
                // Chroot 模式: 使用 ChrootManager 的完整流水线
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
            }
            appendLog("依赖安装完成")
        }

        // ─── Step 4: 安装 AstrBot ───
        val needBot = !botBridge.isBotInstalled() || reinstallBot
        if (needBot) {
            // 删除旧标记
            if (isProot) {
                File(prootManager.astrBotMarker).delete()
                prootManager.runInProot("rm -rf /root/astrbot", 30)
            } else {
                ChrootManager.execRoot("rm -f ${RbotPaths.ASTRBOT_MARKER}")
            }

            updateStep(4, "安装 AstrBot...")

            // 获取版本列表 → 弹出版本选择对话框
            appendLog("获取 AstrBot 版本列表...")
            val releases = try {
                GitHubProxyManager.fetchReleases(selectedProxyIndex, 5)
            } catch (_: Exception) {
                emptyList()
            }
            val versionOptions = listOf("最新版 (main)") + releases

            _versionPickerState.value = VersionPickerState(
                versions = versionOptions,
                proxies = GitHubProxyManager.testProxies().let {
                    // 如果之前没测过就用已有的
                    if (_proxyPickerState.value == null) emptyList() else it
                },
                selectedVersionIndex = 0,
                selectedProxyIndex = selectedProxyIndex
            )
            while (_versionPickerState.value != null) {
                kotlinx.coroutines.delay(100)
            }
            // selectedVersion 和 selectedProxyIndex 此时已由用户回调设置

            val version = if (selectedVersion.isEmpty() || selectedVersion == "最新版 (main)") null else selectedVersion
            appendLog("安装 AstrBot ${version ?: "最新版"}...")

            val callback = ChrootManager.SimpleFullCallback(
                onProgressFn = { appendLog(it) },
                onErrorFn = { appendLog("错误: $it") }
            )

            if (isProot) {
                if (ChrootManager.cloneAstrBotWithProxy(callback, version, selectedProxyIndex)) {
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "AstrBot 克隆失败")
                    return
                }
                if (ChrootManager.pipInstallDeps(callback)) {
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "依赖安装失败")
                    return
                }
            } else {
                if (ChrootManager.cloneAstrBotWithProxy(callback, version, selectedProxyIndex)) {
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "AstrBot 克隆失败")
                    return
                }
                if (ChrootManager.pipInstallDeps(callback)) {
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "依赖安装失败")
                    return
                }
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

    // ─── Rootfs 下载与提取 ───

    private suspend fun awaitRootfs(isProot: Boolean) {
        // Step 2a: 检查本地缓存
        appendLog("检查本地缓存...")
        val localCache = File(RbotPaths.SDCARD_ROOTFS_CACHE)
        var tarballPath: String? = null

        if (localCache.exists() && localCache.length() > 400 * 1024 * 1024) {
            appendLog("找到本地缓存: ${localCache.length() / (1024 * 1024)} MB")
            // MD5 校验
            val md5 = PRootManager.computeMd5(localCache.absolutePath)
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

        // Step 2b: PRoot 模式下复制到内部存储
        if (tarballPath != null && isProot) {
            val destFile = File(context.filesDir, "rootfs-cache.tar.gz")
            if (!destFile.exists() || destFile.length() != localCache.length()) {
                appendLog("复制缓存到内部存储...")
                try {
                    withContext(Dispatchers.IO) {
                        FileInputStream(localCache).use { fis ->
                            FileOutputStream(destFile).use { fos ->
                                fis.channel.transferTo(0, fis.channel.size(), fos.channel)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // fallback: /system/bin/cat
                    try {
                        val catResult = ChrootManager.execRoot(
                            "/system/bin/cat '${localCache.absolutePath}' > '${destFile.absolutePath}'", 60
                        )
                        if (!catResult.success) destFile.delete()
                    } catch (_: Exception) {
                        destFile.delete()
                    }
                }
                if (destFile.exists() && destFile.length() > 0) {
                    tarballPath = destFile.absolutePath
                }
            } else {
                tarballPath = destFile.absolutePath
            }
        }

        // Step 2c: 下载
        if (tarballPath == null) {
            appendLog("下载系统镜像...")
            val downloadUrl = GitHubProxyManager.buildUrl(RbotPaths.GITHUB_ROOTFS_URL, selectedProxyIndex)
            val destPath = if (isProot) "${context.filesDir}/rootfs-cache.tar.gz"
                           else RbotPaths.SDCARD_ROOTFS_CACHE

            try {
                if (isProot) {
                    // PRoot: Java HTTP 下载
                    prootManager.downloadFile(downloadUrl, destPath) { msg ->
                        appendLog(msg)
                    }
                } else {
                    // Chroot: curl
                    ChrootManager.execRoot("mkdir -p ${RbotPaths.SDCARD_CACHE_DIR}")
                    val curlResult = ChrootManager.execRoot(
                        "curl -L --progress-bar -o '$destPath' '$downloadUrl'", 600
                    )
                    if (!curlResult.success) {
                        // 尝试直连
                        appendLog("代理下载失败，尝试直连...")
                        ChrootManager.execRoot(
                            "curl -L --progress-bar -o '$destPath' '${RbotPaths.GITHUB_ROOTFS_URL}'", 600
                        )
                    }
                }

                // MD5 校验
                val md5 = PRootManager.computeMd5(destPath)
                when {
                    md5 == RbotPaths.ROOTFS_MD5 -> {
                        appendLog("MD5 校验通过")
                        tarballPath = destPath
                    }
                    md5 != null -> {
                        appendLog("MD5 不匹配（$md5），文件可能损坏")
                        File(destPath).delete()
                        _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "下载文件 MD5 不匹配")
                        return
                    }
                    else -> {
                        appendLog("MD5 无法计算，但文件存在，继续")
                        tarballPath = destPath
                    }
                }
            } catch (e: Exception) {
                appendLog("下载失败: ${e.message}")
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "系统镜像下载失败: ${e.message}")
                return
            }
        }

        // Step 2d: 提取
        if (isProot) {
            appendLog("解压系统镜像（PRoot 模式）...")
            prootManager.ensureDirectories()
            val extractOk = extractTarGz(tarballPath!!, prootManager.rootfsDir)
            if (!extractOk) {
                // 回退到 proot 内 tar
                appendLog("Java 解压失败，尝试 proot tar...")
                val cacheFile = File(tarballPath)
                val destInRootfs = "${prootManager.rootfsDir}/rootfs-cache.tar.gz"
                withContext(Dispatchers.IO) {
                    FileInputStream(cacheFile).use { fis ->
                        FileOutputStream(destInRootfs).use { fos ->
                            fis.channel.transferTo(0, fis.channel.size(), fos.channel)
                        }
                    }
                }
                val tarResult = prootManager.runInProot(
                    "tar xzf /rootfs-cache.tar.gz -C / && rm -f /rootfs-cache.tar.gz", 600
                )
                if (!tarResult.success) {
                    _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "系统镜像解压失败")
                    return
                }
            }
            prootManager.configureProotRootfs()
            prootManager.updateSourcesList()
            prootManager.markRootfsReady()
        } else {
            appendLog("解压系统镜像（Chroot 模式）...")
            val callback = ChrootManager.SimpleFullCallback(
                onProgressFn = { appendLog(it) },
                onErrorFn = { appendLog("错误: $it") }
            )
            if (ChrootManager.extractRootfs(tarballPath!!, callback)) {
                _uiState.value = _uiState.value.copy(isInstalling = false, errorMessage = "系统镜像解压失败")
                return
            }
        }
        appendLog("系统镜像就绪")
    }

    // ─── 纯 Java tar.gz 提取器（PRoot 模式，无需 root） ───

    private suspend fun extractTarGz(tarballPath: String, destDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var fileCount = 0
                var lastProgressTime = 0L

                val tarFile = File(tarballPath)
                if (!tarFile.exists()) return@withContext false

                // 清理旧目录
                val dest = File(destDir)
                if (dest.exists()) dest.deleteRecursively()
                dest.mkdirs()

                GZIPInputStream(BufferedInputStream(FileInputStream(tarFile), 65536)).use { gzis ->
                    val buf = ByteArray(512)
                    var longName: String? = null

                    while (true) {
                        val headerRead = gzis.readNBytes(buf, 0, 512)
                        if (headerRead < 512) break

                        val type = buf[124 + 156].toInt().toChar()
                        var name = longName ?: String(buf, 0, 100).trimEnd('\u0000')
                        longName = null

                        // GNU long filename
                        if (type == 'L') {
                            val size = String(buf, 124, 12).trim('\u0000', ' ').toInt(8)
                            val nameBuf = ByteArray(size)
                            gzis.readNBytes(nameBuf, 0, size)
                            longName = String(nameBuf).trimEnd('\u0000')
                            // Pad to 512
                            val pad = (512 - size % 512) % 512
                            if (pad > 0) gzis.skip(pad.toLong())
                            // Next header is the actual file
                            continue
                        }

                        // Skip Pax extended headers
                        if (type == 'x' || type == 'g') {
                            val size = String(buf, 124, 12).trim('\u0000', ' ').toLong(8)
                            val padded = (size + 511) / 512 * 512
                            gzis.skip(padded)
                            continue
                        }

                        // Parse size
                        val sizeStr = String(buf, 124, 12).trim('\u0000', ' ')
                        val size = if (sizeStr.isNotBlank()) sizeStr.toLong(8) else 0L

                        // Prefix handling
                        val prefix = String(buf, 345, 155).trimEnd('\u0000')
                        if (prefix.isNotEmpty()) name = "$prefix/$name"

                        val targetFile = File(dest, name)

                        when (type) {
                            '0', '\u0000' -> { // Regular file
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { fos ->
                                    var remaining = size
                                    val copyBuf = ByteArray(65536)
                                    while (remaining > 0) {
                                        val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                                        val read = gzis.read(copyBuf, 0, toRead)
                                        if (read < 0) break
                                        fos.write(copyBuf, 0, read)
                                        remaining -= read
                                    }
                                }
                                // Execute permission from mode
                                val modeStr = String(buf, 100, 8).trim('\u0000', ' ')
                                if (modeStr.isNotBlank()) {
                                    val mode = modeStr.toInt(8)
                                    if ((mode and 0b001001001) != 0) targetFile.setExecutable(true, false)
                                }
                            }
                            '5' -> { // Directory
                                targetFile.mkdirs()
                            }
                            '2' -> { // Symlink
                                val linkName = String(buf, 157, 100).trimEnd('\u0000')
                                try {
                                    targetFile.parentFile?.mkdirs()
                                    java.nio.file.Files.createSymbolicLink(
                                        targetFile.toPath(), java.nio.file.Paths.get(linkName)
                                    )
                                } catch (_: Exception) { }
                            }
                        }

                        // Pad to 512-byte boundary
                        val padded = (size + 511) / 512 * 512
                        val skip = padded - size
                        if (skip > 0) gzis.skip(skip)

                        fileCount++
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime > 2000) {
                            appendLog("已提取 $fileCount 个文件...")
                            lastProgressTime = now
                        }
                    }
                }

                // Verify critical file
                val bashFile = File(dest, "bin/bash")
                if (!bashFile.exists()) {
                    appendLog("关键的 /bin/bash 不存在，解压可能不完整")
                    return@withContext false
                }

                appendLog("解压完成: $fileCount 个文件")
                true
            } catch (e: Exception) {
                appendLog("Java 解压失败: ${e.message}")
                false
            }
        }
    }

    // ─── 工具方法 ───

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


