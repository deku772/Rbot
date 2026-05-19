package app.rbot.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * PRoot 管理器 — 从 Java PRootManager (1728 行) 完整迁移而来。
 *
 * 使用 ptrace 系统调用拦截运行 Linux 环境，无需 root。
 * 与 ChrootManager 并行设计（非子类）。
 *
 * 架构参考: OpenClaw-termux-zh ProcessManager.kt
 * - 两种模式: install (--root-id) 和 gateway (--change-id=0:0 --sysvipc)
 * - PRoot 二进制伪装为 jniLibs 中的 libproot.so（W^X 绕过）
 * - Android 上需要 fake /proc 条目
 * - 环境隔离（proot 启动前清除 Android JVM 环境）
 *
 * rootfs 存储在 app 内部存储（因为 sdcard vfat/FUSE 不支持符号链接）。
 */
class PRootManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PRootManager"
        private const val FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Rbot"
        private const val FAKE_KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC PRoot-Rbot"
        const val SSH_PORT = 8022
        private const val ASTRBOT_MARKER_NAME = ".proot-astrbot-ready"
        private const val ASTRBOT_HOME_RELATIVE = "/root/astrbot"

        private var sFilesDir: String? = null

        @Volatile
        private var instance: PRootManager? = null

        fun getInstance(context: Context): PRootManager {
            return instance ?: synchronized(this) {
                instance ?: PRootManager(context.applicationContext).also {
                    instance = it
                    sFilesDir = it.filesDir
                }
            }
        }

        fun isAstrBotInstalledStatic(): Boolean {
            val dir = sFilesDir ?: return false
            return File(dir, ASTRBOT_MARKER_NAME).exists()
        }

        /** 计算 MD5 哈希（纯 Java，无需 root） */
        fun computeMd5(filePath: String): String? {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "computeMd5: file not found/readable: $filePath")
                return null
            }

            Log.i(TAG, "Computing MD5 for: $filePath (size: ${file.length()} bytes)")
            val startTime = System.currentTimeMillis()

            return try {
                val md = MessageDigest.getInstance("MD5")
                FileInputStream(filePath).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        md.update(buffer, 0, bytesRead)
                    }
                }
                val digest = md.digest()
                val result = digest.joinToString("") { "%02x".format(it) }
                Log.i(TAG, "MD5 result: $result (${System.currentTimeMillis() - startTime}ms)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "computeMd5: ${e.message}")
                null
            }
        }

        /** 读取文件最后 N 行（纯 Java，无需 root） */
        fun readTail(filePath: String, numLines: Int): String {
            return try {
                val lines = ArrayDeque<String>()
                BufferedReader(InputStreamReader(FileInputStream(filePath), "UTF-8")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lines.addLast(line!!)
                        if (lines.size > numLines) lines.removeFirst()
                    }
                }
                lines.joinToString("\n") + "\n"
            } catch (_: Exception) {
                ""
            }
        }

        /** 列出所有备份文件 */
        fun listBackups(): Array<String> {
            // PRoot 模式：备份目录在 app 内部存储，无需 root
            val backupDir = File(RbotPaths.BACKUP_DIR)
            if (!backupDir.exists()) return emptyArray()
            val files = backupDir.listFiles { _, name -> name.startsWith("astrbot_data_") && name.endsWith(".tar.gz") }
                ?: return emptyArray()
            return files.sortedByDescending { it.lastModified() }
                .map { it.absolutePath }
                .toTypedArray()
        }
    }

    // ─── 路径 ───

    val filesDir: String = context.filesDir.absolutePath
    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    val rootfsDir: String = "$filesDir/proot-rootfs"
    val configDir: String = "$filesDir/proot-config"
    val tmpDir: String = "$filesDir/proot-tmp"
    val nativeRuntimeDir: String = "$filesDir/proot-native"
    val homeDir: String = "$filesDir/proot-home"

    val rootfsMarker: String = "$filesDir/.proot-rootfs-ready"
    val astrBotMarker: String = "$filesDir/$ASTRBOT_MARKER_NAME"
    val prootPidFile: String = "$filesDir/proot-gateway.pid"
    val prootSshPidFile: String = "$filesDir/proot-ssh.pid"

    val astrBotHome: String
        get() = "$rootfsDir$ASTRBOT_HOME_RELATIVE"

    val astrBotPidFile: String
        get() = "$astrBotHome/astrbot.pid"

    val astrBotLogFile: String
        get() = "$astrBotHome/astrbot.log"

    // ─── 进程引用 ───

    private var prootGatewayProcess: Process? = null
    private var prootSshProcess: Process? = null

    // ─── 状态检查 ───

    fun isRootfsReady(): Boolean {
        return File(rootfsMarker).exists() && File("$rootfsDir/bin/bash").exists()
    }

    fun isAstrBotInstalled(): Boolean = File(astrBotMarker).exists()

    fun isAstrBotRunning(): Boolean {
        // 优先检查缓存的进程引用，避免每次启动 proot 子进程
        prootGatewayProcess?.let { proc ->
            return try {
                proc.exitValue()
                false // 进程已退出
            } catch (_: IllegalThreadStateException) {
                true // 进程仍在运行
            }
        }
        // 兜底：通过 PID 文件检查（进程可能在 app 重启前启动的）
        val pidFile = File(astrBotPidFile)
        if (!pidFile.exists()) return false
        return try {
            val pid = pidFile.readText().trim().toIntOrNull() ?: return false
            // 在 proot 环境外直接检查 /proc
            File("/proc/$pid/cmdline").exists()
        } catch (_: Exception) { false }
    }

    /** 检查 proot 二进制是否可用（不触发下载，可在 UI 线程调用） */
    fun isProotBinaryAvailable(): Boolean {
        val direct = File(nativeLibDir, "libproot.so")
        if (direct.exists() && direct.length() > 0) return true
        val runtime = File(nativeRuntimeDir, "libproot.so")
        if (runtime.exists() && runtime.length() > 0) return true
        return false
    }

    /** 解析 proot 二进制路径，缺失时自动下载 */
    fun resolveProotPath(): String {
        // 尝试 APK 内置 .so
        val direct = File(nativeLibDir, "libproot.so")
        if (direct.exists() && direct.length() > 0) {
            ensureLibTalloc()
            return direct.absolutePath
        }

        // 尝试运行时下载目录
        val runtime = File(nativeRuntimeDir, "libproot.so")
        if (runtime.exists() && runtime.length() > 0) {
            return runtime.absolutePath
        }

        // 尝试系统 proot
        val systemProot = File("/system/bin/proot")
        if (systemProot.exists() && systemProot.canExecute()) {
            Log.i(TAG, "Using system proot at: ${systemProot.absolutePath}")
            return systemProot.absolutePath
        }

        // 尝试 Termux proot
        val termuxProot = File("/data/data/com.termux/files/usr/bin/proot")
        if (termuxProot.exists() && termuxProot.canExecute()) {
            Log.i(TAG, "Using Termux proot at: ${termuxProot.absolutePath}")
            return termuxProot.absolutePath
        }

        // 二进制未找到 — 尝试运行时下载
        Log.i(TAG, "PRoot binary not found, attempting to download...")
        var downloaded = false
        for (attempt in 1..2) {
            Log.i(TAG, "Download attempt $attempt/2")
            if (ensureProotBinary()) {
                downloaded = true
                break
            }
            Log.w(TAG, "Download attempt $attempt failed, retrying...")
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
        }

        if (downloaded) {
            ensureLibTalloc()
            Log.i(TAG, "PRoot binary downloaded successfully to ${runtime.absolutePath}")
            return runtime.absolutePath
        }

        throw IllegalStateException(
            "PRoot binary not found and download failed after 2 attempts. " +
            "Checked: ${direct.absolutePath}, ${runtime.absolutePath}, " +
            "${systemProot.absolutePath}, ${termuxProot.absolutePath}. " +
            "Please check your network connection and try again, or install Termux for proot support."
        )
    }

    /** 运行时下载 proot 二进制 */
    fun ensureProotBinary(): Boolean {
        if (isProotBinaryAvailable()) return true

        File(nativeRuntimeDir).mkdirs()
        val target = File(nativeRuntimeDir, "libproot.so")

        val bestProxy = GitHubProxyManager.getBestProxy()
        var downloaded = false

        for (baseUrl in RbotPaths.PROOT_BINARY_URLS) {
            val downloadUrl = GitHubProxyManager.buildUrl(baseUrl, bestProxy)
            Log.i(TAG, "Downloading proot binary from: $downloadUrl")
            try {
                downloadFile(downloadUrl, target.absolutePath, null)
                if (target.exists() && target.length() > 1000) {
                    downloaded = true
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proot binary download failed from $downloadUrl: ${e.message}")
            }

            // 也尝试直连 URL（不用代理）
            if (downloadUrl != baseUrl) {
                try {
                    downloadFile(baseUrl, target.absolutePath, null)
                    if (target.exists() && target.length() > 1000) {
                        downloaded = true
                        break
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Direct download from $baseUrl also failed: ${e2.message}")
                }
            }
        }

        if (!downloaded) {
            Log.e(TAG, "Failed to download proot binary from all available sources")
            return false
        }

        if (target.exists() && target.length() > 0) {
            target.setExecutable(true, false)
            target.setReadable(true, false)
            Log.i(TAG, "Proot binary downloaded: ${target.absolutePath} (${target.length()} bytes)")
            return true
        }

        return false
    }

    // ─── PRoot 命令构建 ───

    /**
     * 构建 install-mode proot 命令（匹配 proot-distro 的 run_proot_cmd）。
     * 用于: apt-get, dpkg, npm install, chmod 等。
     * 简化模式: --root-id, 简单 kernel-release, 最小化 guest 环境。
     */
    fun buildInstallCommand(command: String): Array<String> {
        val flags = mutableListOf<String>()
        flags.addAll(commonProotFlags())

        // --root-id: 伪造 root 身份
        flags.add(1, "--root-id")
        // 简单 kernel-release
        flags.add(2, "--kernel-release=$FAKE_KERNEL_RELEASE")
        // 注意: install 时不使用 --sysvipc — 会导致 dpkg fork 时 SIGABRT

        // 通过 env -i 设置 guest 环境
        val talloc = ensureLibTalloc()
        val ldLibraryPath = joinPaths(talloc.parent, configDir, nativeLibDir, nativeRuntimeDir)
        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "APT::Sandbox::User=root",
            "LD_LIBRARY_PATH=$ldLibraryPath",
            "/bin/bash", "-c",
            command
        ))

        return flags.toTypedArray()
    }

    /**
     * 构建交互式 shell proot 命令（用于终端）。
     * 与 buildGatewayCommand 相同但带 --kill-on-exit，用户退出 shell 时 proot 也终止。
     */
    fun buildShellCommand(command: String): Array<String> {
        val flags = mutableListOf<String>()

        ensureProcFakes()
        ensureResolvConf()

        val prootPath = resolveProotPath()
        val procFakes = "$configDir/proc_fakes"
        val sysFakes = "$configDir/sys_fakes"

        flags.add(prootPath)
        flags.add("--link2symlink")
        flags.add("-L")
        flags.add("--kill-on-exit") // 交互式，退出 shell 时 proot 也退出
        flags.add("--rootfs=$rootfsDir")
        flags.add("--cwd=/root")
        flags.add("--change-id=0:0")
        flags.add("--sysvipc")

        val machine = getUnameMachine()
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\${machine}\\localdomain\\-1\\"
        flags.add("--kernel-release=$kernelRelease")

        // 核心设备绑定
        flags.add("--bind=/dev")
        flags.add("--bind=/dev/urandom:/dev/random")
        flags.add("--bind=/proc")
        flags.add("--bind=/proc/self/fd:/dev/fd")
        flags.add("--bind=/sys")

        // Fake /proc 条目
        flags.add("--bind=$procFakes/loadavg:/proc/loadavg")
        flags.add("--bind=$procFakes/stat:/proc/stat")
        flags.add("--bind=$procFakes/uptime:/proc/uptime")
        flags.add("--bind=$procFakes/version:/proc/version")
        flags.add("--bind=$procFakes/vmstat:/proc/vmstat")
        flags.add("--bind=$procFakes/cap_last_cap:/proc/sys/kernel/cap_last_cap")
        flags.add("--bind=$procFakes/max_user_watches:/proc/sys/fs/inotify/max_user_watches")
        flags.add("--bind=$procFakes/fips_enabled:/proc/sys/crypto/fips_enabled")

        // 共享内存
        flags.add("--bind=$rootfsDir/tmp:/dev/shm")
        // SELinux 覆写
        flags.add("--bind=$sysFakes/empty:/sys/fs/selinux")
        // Home 覆盖
        flags.add("--bind=$homeDir:/root/home")

        // DNS
        val resolvFile = File(configDir, "resolv.conf")
        if (resolvFile.exists()) {
            flags.add("--bind=${resolvFile.absolutePath}:/etc/resolv.conf")
        }

        // 存储访问
        if (hasStorageAccess()) {
            File(rootfsDir, "storage").mkdirs()
            flags.add("--bind=/storage:/storage")
            flags.add("--bind=/storage/emulated/0:/sdcard")
        }

        // 通过 env -i 设置 guest 环境
        val talloc = ensureLibTalloc()
        val ldLibraryPath = joinPaths(talloc.parent, configDir, nativeLibDir, nativeRuntimeDir)
        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LD_LIBRARY_PATH=$ldLibraryPath",
            "/bin/bash", "-c",
            command
        ))

        return flags.toTypedArray()
    }

    // ─── PRoot 命令执行 ───

    fun runInProot(command: String, timeoutSec: Int = 60): CommandResult {
        val cmd = buildProotCommand(command)
        val env = prootEnv()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            pb.directory(File("/"))

            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdout.appendLine(line)
                        }
                    }
                } catch (_: Exception) { }
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderr.appendLine(line)
                        }
                    }
                } catch (_: Exception) { }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(false, stdout.toString(), "PRoot command timed out", -1)
            }

            stdoutThread.join(2000)
            stderrThread.join(2000)

            val exitCode = process.exitValue()
            CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode)
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "PRoot execution failed", -1)
        }
    }

    fun runInProotWithProgress(
        command: String,
        timeoutSec: Int = 60,
        callback: ((String) -> Unit)? = null
    ): CommandResult {
        val cmd = buildProotCommand(command)
        val env = prootEnv()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            pb.directory(File("/"))

            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdout.appendLine(line)
                            callback?.let { handler -> handler(line!!) }
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "proot-stdout"; isDaemon = true; start() }

            Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderr.appendLine(line)
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "proot-stderr"; isDaemon = true; start() }

            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(false, stdout.toString(), "PRoot command timed out", -1)
            }

            val exitCode = process.exitValue()
            CommandResult(exitCode == 0, stdout.toString(), stderr.toString(), exitCode)
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "PRoot execution failed", -1)
        }
    }

    // ─── AstrBot 启停 ───

    fun startAstrBot(): CommandResult {
        if (!isRootfsReady()) {
            return CommandResult(false, "", "rootfs 未就绪，请先完成安装", -1)
        }

        if (!isAstrBotInstalled()) {
            return CommandResult(false, "", "AstrBot 未安装，请先完成安装步骤", -1)
        }

        // 使用与 ChrootManager 一致的 venv 路径 /root/astrbot/venv
        val pythonBin = "/root/astrbot/venv/bin/python3"
        val command = "cd /root/astrbot && " +
            "if [ ! -f data/cmd_config.json ] || [ ! -s data/cmd_config.json ]; then " +
            "  mkdir -p data && echo '{}' > data/cmd_config.json; fi && " +
            "$pythonBin main.py 2>&1"

        val cmd = buildGatewayCommand(command)
        val env = prootEnv()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            pb.directory(File("/"))

            prootGatewayProcess = pb.start()

            Thread.sleep(2000)
            try {
                prootGatewayProcess?.exitValue()
                return CommandResult(false, "", "AstrBot 进程启动后立即退出，请检查 venv 和 astrbot 是否正确安装", -1)
            } catch (_: IllegalThreadStateException) {
                // 进程仍在运行，正常
            }

            Thread {
                try {
                    BufferedReader(InputStreamReader(prootGatewayProcess?.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "[astrbot] $line")
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "astrbot-stdout"; isDaemon = true; start() }

            Thread {
                try {
                    BufferedReader(InputStreamReader(prootGatewayProcess?.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.e(TAG, "[astrbot-err] $line")
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "astrbot-stderr"; isDaemon = true; start() }

            CommandResult(true, "AstrBot 启动中", "", 0)
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "启动失败", -1)
        }
    }fun stopAstrBot(): CommandResult {
        prootGatewayProcess?.let {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        prootGatewayProcess = null
        return CommandResult(true, "AstrBot 已停止", "", 0)
    }

    // ─── SSH 服务 ───

    /** 在 PRoot 模式下启动 SSH 服务 */
    fun startSshService(): CommandResult {
        stopSshService()

        // 确保 dropbear 已安装
        runInProot("which dropbear >/dev/null 2>&1 || apt-get install -y dropbear-bin >/dev/null 2>&1", 60)

        // 确保 root 密码已设置
        runInProot(
            "echo 'root:${RbotPaths.DEFAULT_SSH_PASSWORD}' | chpasswd 2>/dev/null; " +
            "chmod 600 /etc/shadow 2>/dev/null", 10
        )

        val setupCmd =
            "mkdir -p /etc/dropbear && " +
            "[ -f /etc/dropbear/dropbear_rsa_host_key ] || dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ecdsa_host_key ] || dropbearkey -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ed25519_host_key ] || dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null; " +
            "pkill -x dropbear 2>/dev/null; sleep 1; " +
            "dropbear -r /etc/dropbear/dropbear_rsa_host_key " +
            "-r /etc/dropbear/dropbear_ecdsa_host_key " +
            "-r /etc/dropbear/dropbear_ed25519_host_key " +
            "-p $SSH_PORT -R && " +
            "echo dropbear_started > /root/.rbot-ssh-result && " +
            "exec /bin/sleep infinity"

        val cmd = buildGatewayCommand(setupCmd)
        val env = prootEnv()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)

            val process = pb.start()
            prootSshProcess = process

            // 保存 SSH proot PID
            val sshPid = getProcessPid(process)
            if (sshPid > 0) {
                writeFile(File(prootSshPidFile), sshPid.toString())
            }

            // 消费输出
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (reader.readLine() != null) { }
                    }
                } catch (_: Exception) { }
            }.apply { name = "ssh-drain"; isDaemon = true; start() }

            // 等待启动结果
            val sshMarker = File(rootfsDir, "/root/.rbot-ssh-result")
            var started = false
            for (i in 0 until 20) {
                try { Thread.sleep(500) } catch (_: Exception) { }
                if (sshMarker.exists() && sshMarker.length() > 0) {
                    val result = sshMarker.readText().trim()
                    started = result.contains("dropbear_started")
                    break
                }
            }
            sshMarker.delete()

            CommandResult(started, if (started) "dropbear_started" else "", "", if (started) 0 else 1)
        } catch (e: Exception) {
            Log.e(TAG, "startSshService failed: ${e.message}")
            CommandResult(false, "", e.message ?: "", -1)
        }
    }

    /** 停止 SSH 服务 */
    fun stopSshService(): CommandResult {
        prootSshProcess?.let {
            it.destroyForcibly()
            try { it.waitFor(3, TimeUnit.SECONDS) } catch (_: Exception) { }
        }
        prootSshProcess = null

        // 通过 PID 文件 kill
        val sshPidFile = File(prootSshPidFile)
        if (sshPidFile.exists()) {
            try {
                val sshPid = sshPidFile.readText().trim().toInt()
                if (sshPid > 0 && isPidAlive(sshPid)) {
                    killPid(sshPid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to kill SSH proot by PID: ${e.message}")
            }
            sshPidFile.delete()
        }

        return CommandResult(true, "stopped", "", 0)
    }

    /** 检查 SSH 是否在运行 */
    fun isSshRunning(): Boolean {
        if (prootSshProcess?.isAlive == true) return true

        val sshPidFile = File(prootSshPidFile)
        if (sshPidFile.exists()) {
            try {
                val sshPid = sshPidFile.readText().trim().toInt()
                return isPidAlive(sshPid)
            } catch (e: Exception) {
                Log.w(TAG, "isSshRunning: PID check failed: ${e.message}")
            }
        }
        return false
    }

    /** 获取 SSH 连接信息 */
    fun getSshInfo(): String {
        var ip = "127.0.0.1"
        try {
            var iface = NetworkInterface.getByName("wlan0")
            if (iface == null) {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (ni.isUp && !ni.isLoopback) { iface = ni; break }
                }
            }
            if (iface != null) {
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        ip = addr.hostAddress ?: ip
                        break
                    }
                }
            }
        } catch (_: Exception) { }
        return "ssh root@$ip -p $SSH_PORT"
    }

    // ─── PRoot rootfs 配置 ───

    fun configureProotRootfs() {
        val configDirFile = File(configDir)
        configDirFile.mkdirs()

        // 创建 fake /proc 条目
        File(configDir, "proc").mkdirs()
        File(configDir, "proc/version").writeText(
            "Linux version $FAKE_KERNEL_RELEASE $FAKE_KERNEL_VERSION"
        )

        // 创建 resolv.conf
        File(configDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 223.5.5.5\n"
        )
    }

    fun markAstrBotInstalled() {
        File(astrBotMarker).writeText(System.currentTimeMillis().toString())
    }

    fun markRootfsReady() {
        File(rootfsMarker).writeText(System.currentTimeMillis().toString())
    }

    /** 准备 proot 所需目录 */
    fun ensureDirectories() {
        File(rootfsDir).mkdirs()
        File(configDir).mkdirs()
        File(tmpDir).mkdirs()
        File(nativeRuntimeDir).mkdirs()
        File(homeDir).mkdirs()
        // 预创建 proot 无法 mkdir 的目录（ENOSYS）
        File("$rootfsDir/tmp").mkdirs()
        File("$rootfsDir/tmp/npm-cache").mkdirs()
        File("$rootfsDir/var/cache/apt/archives/partial").mkdirs()
        File("$rootfsDir/var/lib/apt/lists/partial").mkdirs()
        File("$rootfsDir/var/lib/dpkg/updates").mkdirs()
        File("$rootfsDir/var/lib/dpkg").mkdirs()
        File("$rootfsDir/root").mkdirs()
    }

    /** 更新 sources.list 到阿里云镜像 */
    fun updateSourcesList() {
        // 清理旧镜像配置
        val sourcesListD = File(rootfsDir, "/etc/apt/sources.list.d")
        if (sourcesListD.exists()) {
            sourcesListD.listFiles()?.forEach { it.delete() }
        }
        // ARM64 必须使用 ubuntu-ports
        writeFile(
            File(rootfsDir, "/etc/apt/sources.list"),
            "# Ubuntu 24.04 Noble - ARM64 ports (auto-configured by rbot)\n" +
            "# NOTE: ARM64 requires ports.ubuntu.com, not archive.ubuntu.com\n" +
            "deb https://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\n" +
            "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\n" +
            "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n" +
            "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-backports main restricted universe multiverse\n"
        )
        Log.i(TAG, "sources.list updated to Aliyun ubuntu-ports mirror for ARM64")
    }

    // ─── 备份与恢复 ───

    /** 创建 AstrBot 数据备份 */
    fun backupAstrBotData(callback: ChrootManager.FullProgressCallback? = null): String? {
        callback?.onProgress("准备备份...")

        // PRoot 模式：备份目录和 rootfs 都在 app 内部存储，无需 root
        val backupDir = File(RbotPaths.BACKUP_DIR)
        if (!backupDir.exists()) backupDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val backupFile = File(backupDir, "astrbot_data_$timestamp.tar.gz")

        callback?.onProgress("正在打包数据（排除虚拟环境和缓存）...")

        val tarResult = runInProot(
            "cd /root/astrbot && tar czf /root/astrbot_backup.tar.gz " +
            "--exclude='./venv' " +
            "--exclude='./__pycache__' " +
            "--exclude='./.git' " +
            "--exclude='./astrbot.log' " +
            "--exclude='./astrbot-debug.log' " +
            "--exclude='./astrbot.pid' " +
            ". 2>&1", 300
        )

        if (!tarResult.success) {
            callback?.onError("打包失败: ${tarResult.stderr}")
            return null
        }

        // 从 proot rootfs 内部存储复制到备份目录（纯 Java，无需 root）
        val srcFile = File("$rootfsDir/root/astrbot/astrbot_backup.tar.gz")
        if (!srcFile.exists()) {
            callback?.onError("打包文件未生成")
            return null
        }
        try {
            srcFile.copyTo(backupFile, overwrite = true)
            srcFile.delete()
        } catch (e: Exception) {
            callback?.onError("复制备份文件失败: ${e.message}")
            return null
        }

        if (!backupFile.exists() || backupFile.length() == 0L) {
            callback?.onError("备份文件无效或为空")
            return null
        }

        callback?.onProgress("备份完成: ${backupFile.absolutePath} (${backupFile.length()} bytes)")
        return backupFile.absolutePath
    }

    /** 从备份恢复 AstrBot 数据 */
    fun restoreAstrBotData(backupFile: String, callback: ChrootManager.FullProgressCallback? = null): Boolean {
        callback?.onProgress("检查备份...")

        // PRoot 模式：纯 Java 文件操作，无需 root
        val srcFile = File(backupFile)
        if (!srcFile.exists()) {
            callback?.onError("备份文件不存在")
            return true
        }

        callback?.onProgress("停止 AstrBot...")
        stopAstrBot()

        callback?.onProgress("正在恢复数据...")

        // 复制备份文件到 proot rootfs 内部（纯 Java）
        val destFile = File("$rootfsDir/root/astrbot/astrbot_restore.tar.gz")
        try {
            destFile.parentFile?.mkdirs()
            srcFile.copyTo(destFile, overwrite = true)
        } catch (e: Exception) {
            callback?.onError("复制备份文件失败: ${e.message}")
            return true
        }

        val tarResult = runInProot(
            "cd /root/astrbot && rm -rf ./data ./config ./plugins 2>/dev/null; " +
            "tar xzf /root/astrbot_restore.tar.gz && " +
            "rm -f /root/astrbot_restore.tar.gz", 300
        )

        if (!tarResult.success) {
            callback?.onError("恢复失败: ${tarResult.stderr}")
            return true
        }

        destFile.delete()
        callback?.onProgress("恢复完成")
        return false
    }

    // ─── 终端命令执行 ───

    fun executeInContainer(
        command: String,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit
    ): Process? {
        if (!isRootfsReady()) {
            onError("rootfs 未就绪")
            return null
        }

        val cmd = buildGatewayCommand(command)
        val env = prootEnv()

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            pb.directory(File("/"))

            val process = pb.start()

            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            onOutput(line!!)
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "terminal-stdout"; isDaemon = true; start() }

            Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            onError(line!!)
                        }
                    }
                } catch (_: Exception) { }
            }.apply { name = "terminal-stderr"; isDaemon = true; start() }

            process
        } catch (e: Exception) {
            onError("执行失败: ${e.message}")
            null
        }
    }

    // ─── 文件下载 ───

    /**
     * 使用 Java HttpURLConnection 下载文件（无需 root）。
     * 支持进度回调和 HTTP 重定向跟踪。
     */
    fun downloadFile(urlStr: String, destPath: String, callback: ChrootManager.ProgressCallback? = null) {
        val destFile = File(destPath)
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        var conn: HttpURLConnection? = null
        var input: java.io.InputStream? = null
        var output: FileOutputStream? = null

        try {
            var url = URL(urlStr)
            var c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/octet-stream")

            var responseCode = c.responseCode

            // 手动跟踪重定向（最多 5 次）
            var redirects = 0
            while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                        || responseCode == 307 || responseCode == 308)
                && redirects < 5
            ) {
                val location = c.getHeaderField("Location") ?: break
                c.disconnect()
                url = URL(url, location)
                c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("Accept", "application/octet-stream")
                responseCode = c.responseCode
                redirects++
            }
            conn = c

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP $responseCode for $urlStr")
            }

            val contentLength = c.contentLength
            input = c.inputStream
            output = FileOutputStream(destFile)

            val buffer = ByteArray(8192)
            var totalRead = 0L
            var bytesRead: Int
            var lastProgressTime = 0L

            while (input!!.read(buffer).also { bytesRead = it } != -1) {
                output!!.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (callback != null && now - lastProgressTime > 1000) {
                    val mb = totalRead / (1024 * 1024)
                    if (contentLength > 0) {
                        val percent = (totalRead * 100 / contentLength).toInt()
                        callback.onProgress("已下载 $mb MB ($percent%)")
                    } else {
                        callback.onProgress("已下载 $mb MB")
                    }
                    lastProgressTime = now
                }
            }

            output!!.flush()
            Log.i(TAG, "Download complete: $destPath ($totalRead bytes)")
        } finally {
            input?.close()
            output?.close()
            conn?.disconnect()
        }
    }

    // ─── 内部构建 ───

    private fun buildProotCommand(innerCommand: String): List<String> {
        val flags = mutableListOf<String>()
        flags.addAll(commonProotFlags())

        flags.add("--change-id=0:0")
        flags.add("--sysvipc")

        val machine = getUnameMachine()
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\${machine}\\localdomain\\-1\\"
        flags.add("--kernel-release=$kernelRelease")

        val talloc = ensureLibTalloc()
        val ldLibraryPath = joinPaths(talloc.parent, configDir, nativeLibDir, nativeRuntimeDir)
        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LD_LIBRARY_PATH=$ldLibraryPath",
            "/bin/bash", "-c",
            innerCommand
        ))

        return flags
    }

    private fun buildGatewayCommand(innerCommand: String): List<String> {
        val flags = mutableListOf<String>()
        flags.addAll(commonProotFlags())

        flags.add("--change-id=0:0")
        flags.add("--sysvipc")

        val machine = getUnameMachine()
        val kernelRelease = "\\Linux\\localhost\\$FAKE_KERNEL_RELEASE" +
            "\\$FAKE_KERNEL_VERSION\\${machine}\\localdomain\\-1\\"
        flags.add("--kernel-release=$kernelRelease")

        val talloc = ensureLibTalloc()
        val ldLibraryPath = joinPaths(talloc.parent, configDir, nativeLibDir, nativeRuntimeDir)
        flags.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LD_LIBRARY_PATH=$ldLibraryPath",
            "/bin/bash", "-c",
            innerCommand
        ))

        return flags
    }

    fun prootEnv(): Map<String, String> {
        val runtimeLibDir = File(nativeRuntimeDir, "lib").absolutePath
        return mapOf(
            "PROOT_LOADER" to File(nativeRuntimeDir, "libproot-loader.so").absolutePath,
            "LD_LIBRARY_PATH" to "$runtimeLibDir:$nativeLibDir",
            "PROOT_TMP" to tmpDir,
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to "/root",
            "PATH" to "/usr/bin:/bin",
            "TERM" to "xterm-256color"
        )
    }

    /** 通用 proot 标志（匹配 proot-distro 的 run_proot_cmd） */
    private fun commonProotFlags(): MutableList<String> {
        ensureProcFakes()
        ensureResolvConf()

        val prootPath = resolveProotPath()
        val procFakes = "$configDir/proc_fakes"
        val sysFakes = "$configDir/sys_fakes"

        val flags = mutableListOf<String>()
        flags.add(prootPath)
        flags.add("--link2symlink")
        flags.add("-L")
        flags.add("--kill-on-exit")
        flags.add("--rootfs=$rootfsDir")
        flags.add("--cwd=/root")

        // 核心设备绑定
        flags.add("--bind=/dev")
        flags.add("--bind=/dev/urandom:/dev/random")
        flags.add("--bind=/proc")
        flags.add("--bind=/proc/self/fd:/dev/fd")
        flags.add("--bind=/sys")

        // Fake /proc 条目
        flags.add("--bind=$procFakes/loadavg:/proc/loadavg")
        flags.add("--bind=$procFakes/stat:/proc/stat")
        flags.add("--bind=$procFakes/uptime:/proc/uptime")
        flags.add("--bind=$procFakes/version:/proc/version")
        flags.add("--bind=$procFakes/vmstat:/proc/vmstat")
        flags.add("--bind=$procFakes/cap_last_cap:/proc/sys/kernel/cap_last_cap")
        flags.add("--bind=$procFakes/max_user_watches:/proc/sys/fs/inotify/max_user_watches")
        flags.add("--bind=$procFakes/fips_enabled:/proc/sys/crypto/fips_enabled")

        // 共享内存
        flags.add("--bind=$rootfsDir/tmp:/dev/shm")
        // SELinux 覆写
        flags.add("--bind=$sysFakes/empty:/sys/fs/selinux")
        // Home 覆盖
        flags.add("--bind=$homeDir:/root/home")

        // DNS
        val resolvFile = File(configDir, "resolv.conf")
        if (resolvFile.exists()) {
            flags.add("--bind=${resolvFile.absolutePath}:/etc/resolv.conf")
        }

        // 存储访问
        if (hasStorageAccess()) {
            File(rootfsDir, "storage").mkdirs()
            flags.add("--bind=/storage:/storage")
            flags.add("--bind=/storage/emulated/0:/sdcard")
        }

        return flags
    }

    /** 确保 fake /proc 条目存在 */
    private fun ensureProcFakes() {
        val procFakesDir = File(configDir, "proc_fakes")
        procFakesDir.mkdirs()

        writeFile(File(procFakesDir, "loadavg"), "0.50 0.40 0.30 1/234 5678\n")
        writeFile(File(procFakesDir, "stat"),
            "cpu  12345 678 901 23456 7890 123 456 0 0 0\n" +
            "cpu0 12345 678 901 23456 7890 123 456 0 0 0\n")
        writeFile(File(procFakesDir, "uptime"), "123456.78 234567.89\n")
        writeFile(File(procFakesDir, "version"),
            "Linux version 6.17.0-PRoot-Rbot (gcc version 13.2.0) #1 SMP PREEMPT_DYNAMIC\n")
        writeFile(File(procFakesDir, "vmstat"), "nr_free_pages 123456\n")
        writeFile(File(procFakesDir, "cap_last_cap"), "40\n")
        writeFile(File(procFakesDir, "max_user_watches"), "524288\n")
        writeFile(File(procFakesDir, "fips_enabled"), "0\n")

        // 确保 sys fakes 目录存在
        File(configDir, "sys_fakes/empty").mkdirs()
    }

    /** 确保 DNS 配置存在（公开，供 SetupViewModel 调用） */
    fun ensureResolvConf() {
        val dnsConfig = "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\n"
        // Bind-mount 源
        writeFile(File(configDir, "resolv.conf"), dnsConfig)
        // Direct rootfs 复制（后备）
        val rootfsResolv = File(rootfsDir, "etc/resolv.conf")
        rootfsResolv.parentFile?.mkdirs()
        writeFile(rootfsResolv, dnsConfig)
    }

    /** 确保 libtalloc.so.2 存在于可写目录中 */
    private fun ensureLibTalloc(): File {
        val libDir = File(filesDir, "lib")
        libDir.mkdirs()
        val target = File(libDir, "libtalloc.so.2")
        if (target.exists() && target.length() > 30000) return target

        // 从 bundled assets 复制
        try {
            context.assets.open("libtalloc.so").use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) output.write(buf, 0, n)
                }
            }
            target.setExecutable(true)
            target.setReadable(true, false)
            Log.i(TAG, "libtalloc.so.2 extracted to ${target.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract libtalloc: ${e.message}")
        }
        return target
    }

    // ─── 工具方法 ───

    private fun getUnameMachine(): String {
        val arch = Build.SUPPORTED_ABIS[0]
        return when (arch) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armv7l"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> arch
        }
    }

    private fun joinPaths(vararg paths: String?): String {
        return paths.filterNotNull().joinToString(":")
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val sdcard = Environment.getExternalStorageDirectory()
            sdcard.exists() && sdcard.canRead()
        }
    }

    private fun writeFile(file: File, content: String) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write ${file.absolutePath}: ${e.message}")
        }
    }

    private fun isPidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            Os.kill(pid, 0) // signal 0 = 存在性检查
            true
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ESRCH) false // 进程不存在
            else if (e.errno == OsConstants.EPERM) true // 进程存在但无权发信号
            else false
        }
    }

    private fun killPid(pid: Int) {
        if (pid <= 0) return
        try {
            Os.kill(pid, 9) // SIGKILL
            Log.i(TAG, "Killed process $pid")
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to kill PID $pid: ${e.message}")
        }
    }

    private fun getProcessPid(process: Process): Long {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process).toLong()
        } catch (_: NoSuchFieldException) {
            try {
                val pidField = process.javaClass.getDeclaredField("mPid")
                pidField.isAccessible = true
                pidField.getInt(process).toLong()
            } catch (e2: Exception) {
                Log.w(TAG, "Could not get process PID: ${e2.message}")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get process PID via reflection: ${e.message}")
            -1
        }
    }
}
