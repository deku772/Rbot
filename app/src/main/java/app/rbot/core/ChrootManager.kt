package app.rbot.core

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chroot 管理器。
 *
 * 管理 chroot 生命周期：root 命令执行、rootfs 提取、AstrBot 安装、chroot 内命令执行、SSH 服务。
 */
object ChrootManager {

    private const val TAG = "ChrootManager"
    private const val DEFAULT_TIMEOUT_SEC = 60
    private const val PROGRESS_DEBOUNCE_MS = 10_000L

    /** 可重入锁 — 防止并发 chroot 设备初始化 / AstrBot 启动 */
    private val chrootLock = Any()

    // ─── 进度回调 ───

    fun interface ProgressCallback {
        fun onProgress(message: String)
    }

    /** 错误回调扩展 — 使用 onProgress 传递错误消息，前缀 "❌ " */
    fun interface ErrorCallback {
        fun onError(error: String)
    }

    /** 组合回调接口 */
    interface FullProgressCallback : ProgressCallback, ErrorCallback

    /** 简易 FullProgressCallback 实现 */
    class SimpleFullCallback(
        private val onProgressFn: (String) -> Unit,
        private val onErrorFn: (String) -> Unit = {}
    ) : FullProgressCallback {
        override fun onProgress(message: String) = onProgressFn(message)
        override fun onError(error: String) = onErrorFn(error)
    }

    // ─── 状态数据类 ───

    data class FullStatus(
        val rootAvailable: Boolean,
        val rootfsReady: Boolean,
        val chrootMounted: Boolean,
        val astrBotInstalled: Boolean,
        val astrBotRunning: Boolean
    )

    // ─── Root 命令执行 (libsu) ───

    init {
        // 配置 libsu：启用 verbose 日志便于调试
        Shell.enableVerboseLogging = true

        // 调试：输出当前进程的 PATH 和 uid
        Log.i(TAG, "ChrootManager init: uid=${android.os.Process.myUid()}, PATH=${System.getenv("PATH")}")
        LogHub.log("ChrootManager 初始化: uid=${android.os.Process.myUid()}")

        // 查找 su 的完整路径。
        //
        // KernelSU Next 的 kernel_umount 功能通过 mount namespace 在 app 进程中
        // 隐藏 su 二进制，导致 File.exists() 和 Runtime.exec() 都找不到 su。
        // 此时需要用户在 KernelSU 管理器中为 app 关闭 "内核自动卸载"（kernel_umount）。
        //
        // 策略：先尝试已知路径 exec，如果都失败，记录检测到的环境信息供上层判断。
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        )

        val suPath = suPaths.firstOrNull { path ->
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(path, "-c", "echo ok"))
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exitCode = proc.waitFor()
                Log.d(TAG, "su test: $path → exit=$exitCode output=[$output]")
                exitCode == 0 && output == "ok"
            } catch (e: Exception) {
                Log.d(TAG, "su test: $path → ${e.message}")
                false
            }
        }

        val builder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setTimeout(30)

        if (suPath != null) {
            // 使用完整路径启动 su，绕过 PATH 查找和 mount namespace 隔离
            builder.setCommands(suPath, "--mount-master")
            Log.i(TAG, "libsu 配置: 使用 su 路径 $suPath（exec 验证通过）")
            LogHub.log("Root 权限: su 路径 $suPath")
        } else {
            // 所有已知路径都不可执行 — 可能是 KernelSU kernel_umount 隐藏了 su
            // 不设置 setCommands，让 libsu 走默认逻辑（会回退到非 root shell）
            Log.i(TAG, "libsu 配置: 未找到可执行的 su（可能被 KernelSU kernel_umount 隐藏）")
            LogHub.error("未找到可执行的 su，请在 KernelSU 管理器中关闭 kernel_umount")
        }

        Shell.setDefaultBuilder(builder)
    }

    /**
     * 通过 libsu 执行 root 命令。
     * 统一使用 libsu 主 shell 会话（已授权），不再按超时长度分流。
     *
     * libsu 的 setTimeout 仅控制 shell 握手超时，不限制命令执行时长，
     * 因此长短命令都走同一条路径，避免创建新 shell 实例导致 root 权限丢失。
     */
    fun execRoot(command: String, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): CommandResult {
        return execRootLibsu(command)
    }

    /**
     * 用 libsu 执行 root 命令（主 shell 会话）。
     *
     * KernelSU 的 su 在某些情况下不会继承正常的 PATH，导致 chroot 等命令找不到。
     * 在每条命令前加上 PATH 导出确保安全。
     */
    private fun execRootLibsu(command: String): CommandResult {
        return try {
            // 确保 PATH 包含系统基本路径，防止 KernelSU su 环境下 PATH 为空
            val safeCommand = "export PATH=/system/bin:/system/xbin:/sbin:\${PATH}; $command"
            val result = Shell.cmd(safeCommand).exec()
            val success = result.isSuccess
            val stdout = result.out.joinToString("\n")
            val stderr = result.err.joinToString("\n")
            val exitCode = result.code
            if (!success) {
                Log.w(TAG, "execRoot FAILED: cmd=[${command.take(80)}] exit=$exitCode err=[${stderr.take(80)}]")
                LogHub.error("execRoot 失败: ${command.take(40)} → err=${stderr.take(60)}")
            }
            CommandResult(success, stdout, stderr, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "execRoot exception: cmd=[${command.take(80)}] ${e.message}")
            LogHub.error("execRoot 异常: ${e.message}")
            CommandResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    fun execInChroot(command: String, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): CommandResult {
        val chrootCmd = "/system/bin/chroot ${RbotPaths.CHROOT_DIR} /bin/bash -c " +
            shellQuote(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "export HOME=/root && " +
                "unset ANDROID_ROOT && " +
                "export TMPDIR=/tmp && export TEMP=/tmp && export TMP=/tmp && " +
                "export XDG_CACHE_HOME=/root/.cache && " +
                "export XDG_CONFIG_HOME=/root/.config && " +
                "export XDG_DATA_HOME=/root/.local/share && " +
                "export XDG_STATE_HOME=/root/.local/state && " +
                command
            )
        return execRoot(chrootCmd, timeoutSec)
    }

    // ─── Root 检测 ───

    /** 检测 root 是否可用 — 使用 libsu，不会弹窗超时 */
    fun isRootAvailable(): Boolean {
        val granted = Shell.isAppGrantedRoot()
        if (granted != null) return granted
        // granted == null → 还没创建过 shell，尝试同步创建
        return try {
            Shell.getShell().isRoot
        } catch (_: Exception) { false }
    }

    /**
     * 检测是否处于 KernelSU kernel_umount 环境。
     *
     * 判定逻辑：/system/bin/su 在 root 的 mount namespace 中存在，
     * 但 app 进程中 Runtime.exec 找不到 → 说明被 kernel_umount 隐藏了。
     * 此时应提示用户在 KernelSU 管理器中关闭 "内核自动卸载"。
     */
    fun isKernelSuUmountActive(): Boolean {
        // 如果 libsu 主 shell 是 root，则 umount 没有阻碍我们
        try {
            val shell = Shell.getShell()
            if (shell.isRoot) return false
        } catch (_: Exception) { }

        // su 不可执行且 isSuBinaryPresent 返回 false → 很可能是 umount
        return !isSuBinaryPresent()
    }

    /** 检测 su 二进制是否存在 — 尝试实际执行，不依赖 File.exists() */
    fun isSuBinaryPresent(): Boolean {
        // 先检查 libsu 缓存
        val granted = Shell.isAppGrantedRoot()
        if (granted == true) return true
        if (granted == false) {
            // libsu 已经确认无 root，但可能是因为首次 shell 还没创建
            // 再尝试一次实际执行 su
        }
        // 尝试实际执行 su — 比 File.exists()/canExecute() 更可靠
        // 因为 Magisk mount namespace 隐藏下文件不可见但仍可执行
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        )
        return suPaths.any { path ->
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(path, "-c", "echo ok"))
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exitCode = proc.waitFor()
                exitCode == 0 && output == "ok"
            } catch (_: Exception) { false }
        }
    }

    fun isPrivilegedAccessAvailable(): Boolean {
        return isRootAvailable()
    }

    // ─── Rootfs 管理 ───

    fun isRootfsReady(): Boolean {
        // /data/rbot/ 是 root 目录，app 进程无权限直接 File.exists()
        val result = execRoot("test -f ${RbotPaths.ROOTFS_MARKER} && test -f ${RbotPaths.CHROOT_DIR}/bin/bash && echo yes", 5)
        return result.success && result.stdout.trim() == "yes"
    }

    fun isAstrBotInstalled(): Boolean {
        if (AuthManager.instance.isProotMode) {
            return PRootManager.isAstrBotInstalledStatic()
        }
        val result = execRoot("test -f ${RbotPaths.ASTRBOT_MARKER} && echo yes", 5)
        return result.success && result.stdout.trim() == "yes"
    }

    fun ensureChrootDir(): Boolean {
        val result = execRoot(
            "mkdir -p ${RbotPaths.CHROOT_DIR} && chmod 755 ${RbotPaths.CHROOT_DIR}"
        )
        return result.success
    }

    /** 提取 rootfs 从 tarball 到 /data/rbot */
    fun extractRootfs(tarballPath: String, callback: FullProgressCallback? = null): Boolean {
        callback?.onProgress("正在解压系统镜像...")

        val stagingDir = "${RbotPaths.CHROOT_DIR}_staging"
        val tarCmd = buildTarExtractCommand(tarballPath, stagingDir)

        callback?.onProgress("正在提取到暂存区...")

        // 先卸载 bind-mounted 设备（避免删除宿主 /dev/null 等）
        cleanupChrootDevices()

        // 备份 AstrBot 数据（如有）
        val dataCheck = execRoot("test -d ${RbotPaths.CHROOT_DIR}/root/astrbot/data && echo yes")
        val hasAstrBotData = dataCheck.success && dataCheck.stdout.trim() == "yes"
        if (hasAstrBotData) {
            callback?.onProgress("检测到 AstrBot 数据，正在备份...")
            val installBackupFile = "${RbotPaths.EXTERNAL_DATA_BACKUP}.tar.gz"
            execRoot("mkdir -p ${RbotPaths.BACKUP_DIR}")
            execRoot(
                "cd ${RbotPaths.ASTRBOT_HOME} && tar czf '$installBackupFile' " +
                    "--exclude='./venv' --exclude='./__pycache__' --exclude='./.git' " +
                    "--exclude='./astrbot.log' --exclude='./astrbot-debug.log' --exclude='./astrbot.pid' .",
                300
            )
            callback?.onProgress("数据已备份到: $installBackupFile")
        }

        // 清理暂存区和 chroot 目录
        execRoot("rm -rf $stagingDir && rm -rf ${RbotPaths.CHROOT_DIR} && mkdir -p ${RbotPaths.CHROOT_DIR} $stagingDir")

        // Step 1: 解压到暂存区（458MB tar.gz 在手机上需要 3-5 分钟；使用 600s 超时）
        val result = execRoot(tarCmd, 600)
        if (!result.success) {
            callback?.onError("解压失败: ${result.stderr}")
            execRoot("rm -rf $stagingDir")
            return true // true = failure
        }

        // 处理 GitHub tar.xz 解压到 ubuntu-fs/ 子目录的情况
        val restructureCmd =
            "if [ -d $stagingDir/ubuntu-fs ]; then " +
            "  cp -r $stagingDir/ubuntu-fs/. $stagingDir/ && " +
            "  rm -rf $stagingDir/ubuntu-fs; " +
            "fi"
        execRoot(restructureCmd)

        callback?.onProgress("正在同步到 chroot 目录...")

        // Step 2: 从暂存区复制到 chroot 目录（跟随符号链接）
        val copyCmd =
            "( " +
            "  SRC=$stagingDir; " +
            "  DST=${RbotPaths.CHROOT_DIR}; " +
            "  TOTAL=\$(du -sm \$SRC 2>/dev/null | cut -f1); " +
            "  (while cp -aL \$SRC/. \$DST/ 2>/dev/null; do break; done) & " +
            "  CP_PID=\$!; " +
            "  while kill -0 \$CP_PID 2>/dev/null; do " +
            "    DONE=\$(du -sm \$DST 2>/dev/null | cut -f1); " +
            "    echo \"[同步] \${DONE}MB / ~\${TOTAL}MB\"; " +
            "    sleep 5; " +
            "  done; " +
            "  wait \$CP_PID; " +
            ") 2>&1; true"
        val copyResult = execRootWithProgress(copyCmd, 600, callback)

        // 清理暂存区
        execRoot("rm -rf $stagingDir")

        // 验证关键文件存在
        val verify = execRoot(
            "test -f ${RbotPaths.CHROOT_DIR}/bin/bash && " +
            "test -f ${RbotPaths.CHROOT_DIR}/usr/bin/env && " +
            "echo verify_ok", 10
        )

        if (!verify.success) {
            callback?.onError("同步失败(关键文件缺失): ${copyResult.stderr}")
            return true
        }

        if (copyResult.stderr.isNotEmpty()) {
            callback?.onProgress("同步完成(部分损坏符号链接已跳过)")
        }

        // 创建标记
        execRoot("touch ${RbotPaths.ROOTFS_MARKER}")

        // 确保 /dev 目录存在
        execRoot(
            "mkdir -p ${RbotPaths.CHROOT_DIR}/dev " +
            "${RbotPaths.CHROOT_DIR}/dev/pts " +
            "${RbotPaths.CHROOT_DIR}/proc " +
            "${RbotPaths.CHROOT_DIR}/sys", 5
        )

        callback?.onProgress("系统镜像解压完成")
        return false // false = success
    }

    /** 从统一外部存储备份恢复 AstrBot 数据（无参数版本 — extractRootfs 内部调用） */
    fun restoreAstrBotDataFromDefault(callback: FullProgressCallback? = null): Boolean {
        val tarBackup = "${RbotPaths.EXTERNAL_DATA_BACKUP}.tar.gz"
        val tarCheck = execRoot("test -f '$tarBackup' && echo exists")

        if (tarCheck.success && tarCheck.stdout.trim() == "exists") {
            callback?.onProgress("正在从备份恢复 AstrBot 数据...")
            execRoot("mkdir -p ${RbotPaths.ASTRBOT_HOME}")
            val result = execRoot(
                "cd ${RbotPaths.ASTRBOT_HOME} && tar xzf '$tarBackup'", 300
            )
            if (!result.success) {
                callback?.onError("恢复数据失败: ${result.stderr}")
                return true
            }
            callback?.onProgress("AstrBot 数据已恢复")
            return false
        }

        // 回退：旧版目录备份
        val checkResult = execRoot("test -d '${RbotPaths.EXTERNAL_DATA_BACKUP}' && echo exists")
        if (!checkResult.success || checkResult.stdout.trim() != "exists") {
            callback?.onError("未找到备份数据: $tarBackup")
            return true
        }

        callback?.onProgress("正在从外部存储恢复 AstrBot 数据...")
        execRoot("mkdir -p ${RbotPaths.ASTRBOT_HOME}")
        val result = execRoot(
            "cp -r '${RbotPaths.EXTERNAL_DATA_BACKUP}' ${RbotPaths.ASTRBOT_HOME}/data", 300
        )
        if (!result.success) {
            callback?.onError("恢复数据失败: ${result.stderr}")
            return true
        }

        callback?.onProgress("AstrBot 数据已恢复")
        return false
    }

    // ─── Chroot 设备初始化 ───

    /** 设置 chroot 环境中的设备节点、文件系统挂载 */
    fun setupChrootDevices(callback: FullProgressCallback? = null) {
        callback?.onProgress("正在初始化 chroot 环境...")

        val D = RbotPaths.CHROOT_DIR

        // Step 1: 创建挂载点目录
        execRoot("mkdir -p $D/dev $D/dev/pts $D/proc $D/sys $D/tmp", 10)

        // Step 2: Bind-mount 整个 /dev
        execRoot("mount --bind /dev $D/dev 2>/dev/null || true", 10)

        // Step 3: 挂载 devpts
        execRoot(
            "mount -t devpts -o newinstance,ptmxmode=0666 devpts $D/dev/pts 2>/dev/null || " +
            "mount -t devpts devpts $D/dev/pts 2>/dev/null; " +
            "chmod 666 $D/dev/pts/ptmx 2>/dev/null || true; " +
            "rm -f $D/dev/ptmx 2>/dev/null; " +
            "ln -sf pts/ptmx $D/dev/ptmx; " +
            "echo pts_done", 15
        )

        // Step 4: 挂载 /proc 和 /sys
        execRoot(
            "mount -t proc proc $D/proc; " +
            "mount -t sysfs sysfs $D/sys; " +
            "echo fs_done", 15
        )

        // Step 5: 挂载 tmpfs 到 /tmp
        execRoot(
            "chmod 1777 $D/tmp 2>/dev/null; " +
            "mount -t tmpfs -o size=512M,mode=1777 tmpfs $D/tmp 2>/dev/null || true; " +
            "echo tmp_done", 10
        )

        // Step 6: 复制宿主 DNS 配置
        execRoot(
            "rm -f $D/etc/resolv.conf 2>/dev/null; " +
            "cp /etc/resolv.conf $D/etc/resolv.conf 2>/dev/null || " +
            "echo 'nameserver 223.5.5.5' > $D/etc/resolv.conf; " +
            "echo 'nameserver 8.8.8.8' >> $D/etc/resolv.conf; " +
            "echo dns_done", 10
        )

        // Step 7: 创建 .hushlogin
        execRoot(
            "mkdir -p $D/root; " +
            "touch $D/root/.hushlogin; " +
            "echo hushlogin_done", 5
        )

        callback?.onProgress("chroot 环境初始化完成")
    }

    /** 清理 chroot 设备挂载 */
    fun cleanupChrootDevices() {
        val D = RbotPaths.CHROOT_DIR
        execRoot(
            "umount $D/sys 2>/dev/null; " +
            "umount $D/proc 2>/dev/null; " +
            "umount $D/dev/pts 2>/dev/null; " +
            "umount $D/dev 2>/dev/null; " +
            "umount $D/tmp 2>/dev/null; " +
            "echo cleanup_done", 15
        )
    }

    /** 检查 chroot 文件系统是否已挂载 */
    fun isChrootMounted(): Boolean {
        val result = execRoot(
            "mount | grep -q '${RbotPaths.CHROOT_DIR}/proc ' && echo mounted || echo not_mounted", 5
        )
        return result.success && result.stdout.trim() == "mounted"
    }

    // ─── AstrBot 安装流水线 ───

    /** 安装 AstrBot（完整流水线：setup → apt update → apt install → git clone → pip） */
    fun installAstrBot(callback: FullProgressCallback? = null): Boolean {
        if (setupChrootEnvironment(callback)) return true
        if (aptUpdate(callback)) return true
        if (aptInstallDeps(callback)) return true
        if (cloneAstrBot(callback)) return true
        if (pipInstallDeps(callback)) return true

        callback?.onProgress("AstrBot 安装完成")
        return false
    }

    /** Step 1: 设置 chroot 环境 */
    fun setupChrootEnvironment(callback: FullProgressCallback? = null): Boolean {
        setupChrootDevices(callback)
        fixDevNull(callback)
        ensureGpgv(callback)
        switchToChineseMirror(callback)
        setDefaultSshPassword(callback)
        return false
    }

    /** 在 rootfs 安装时设置默认 SSH 密码 */
    private fun setDefaultSshPassword(callback: FullProgressCallback? = null) {
        callback?.onProgress("配置 SSH 访问...")

        val password = RbotPaths.DEFAULT_SSH_PASSWORD

        val result = execInChroot(
            "HASH=\$(openssl passwd -6 -salt rbotsalt '$password') && " +
            "mkdir -p /root && " +
            "if grep -q '^root:' /etc/shadow; then " +
            "  sed -i \"s|^root:[^:]*:|root:\$HASH:|\" /etc/shadow; " +
            "else " +
            "  echo \"root:\$HASH:19000:0:99999:7:::\" >> /etc/shadow; " +
            "fi && " +
            "chmod 600 /etc/shadow && " +
            "echo 'Password set successfully'", 15
        )

        if (result.success && result.stdout.contains("Password set successfully")) {
            execInChroot(
                "printf '%s' ${shellQuote(password)} > /root/.rbot_pass && chmod 600 /root/.rbot_pass", 5
            )
            callback?.onProgress("SSH 密码已设置: $password")
        } else {
            callback?.onProgress("SSH 密码配置失败: ${result.stderr}")
        }
    }

    /** 修复 /dev/null — 解压后常见问题 */
    private fun fixDevNull(callback: FullProgressCallback? = null) {
        val check = execInChroot("echo test > /dev/null 2>&1 && echo ok || echo broken", 5)
        if (check.success && check.stdout.trim().contains("ok")) return

        callback?.onProgress("修复 /dev/null 权限...")

        val D = RbotPaths.CHROOT_DIR
        execRoot("umount $D/dev/null 2>/dev/null", 5)
        execRoot("rm -f $D/dev/null", 5)
        execRoot("touch $D/dev/null && mount --bind /dev/null $D/dev/null", 5)

        val verify = execInChroot("echo test > /dev/null 2>&1 && echo ok || echo broken", 5)
        if (verify.success && verify.stdout.trim().contains("ok")) {
            callback?.onProgress("/dev/null 已修复")
        } else {
            callback?.onProgress("/dev/null 修复可能失败，部分操作可能出错")
        }
    }

    /** 确保 gpgv 已安装 — apt 仓库验证所需 */
    private fun ensureGpgv(callback: FullProgressCallback? = null) {
        val check = execInChroot("which gpgv 2>/dev/null && echo exists || echo missing", 5)
        if (check.success && check.stdout.trim().contains("exists")) return

        callback?.onProgress("安装 gpgv (签名验证工具)...")

        val result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "apt update --allow-unauthenticated 2>/dev/null; " +
            "apt install -y --allow-unauthenticated gpgv 2>&1", 60, callback
        )

        if (result.success) {
            callback?.onProgress("gpgv 安装完成")
        } else {
            callback?.onProgress("gpgv 安装失败，后续 apt 操作可能受限")
        }
    }

    /** 切换 apt 源到阿里云镜像 */
    private fun switchToChineseMirror(callback: FullProgressCallback? = null) {
        callback?.onProgress("正在切换国内镜像源...")

        val check = execInChroot(
            "grep -q 'aliyun\\|tuna\\|ustc\\|163' /etc/apt/sources.list 2>/dev/null && echo already", 10
        )
        if (check.success && check.stdout.trim().contains("already")) {
            callback?.onProgress("已使用国内镜像源，跳过")
            return
        }

        // 检测 Ubuntu 代号
        val codenameResult = execInChroot(
            "grep '^UBUNTU_CODENAME=' /etc/os-release 2>/dev/null | cut -d= -f2 || " +
            "lsb_release -cs 2>/dev/null || echo noble", 10
        )
        var codename = codenameResult.stdout.trim()
        if (codename.isEmpty()) codename = "noble"

        val sourcesContent =
            "# Aliyun mirror - auto-configured by Rbot\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ $codename main restricted universe multiverse\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ $codename-updates main restricted universe multiverse\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ $codename-security main restricted universe multiverse\n"

        val tmpFile = "${RbotPaths.RBOT_TMP}/sources.list"
        execRoot("mkdir -p ${RbotPaths.RBOT_TMP}", 5)
        try {
            FileWriter(tmpFile).use { it.write(sourcesContent) }
        } catch (_: Exception) {
            execRoot(
                "echo 'deb http://mirrors.aliyun.com/ubuntu-ports/ $codename main restricted universe multiverse\n" +
                "deb http://mirrors.aliyun.com/ubuntu-ports/ $codename-updates main restricted universe multiverse\n" +
                "deb http://mirrors.aliyun.com/ubuntu-ports/ $codename-security main restricted universe multiverse\n' > $tmpFile", 10
            )
        }
        execRoot("cp $tmpFile ${RbotPaths.CHROOT_DIR}/etc/apt/sources.list && rm -f $tmpFile", 10)

        callback?.onProgress("已切换到阿里云镜像源 ($codename)")
    }

    /** Step 2: apt update */
    fun aptUpdate(callback: FullProgressCallback? = null): Boolean {
        callback?.onProgress("正在更新软件源...")

        // 清理残留锁
        execInChroot(
            "pkill -9 apt 2>/dev/null; pkill -9 dpkg 2>/dev/null; " +
            "rm -f /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock 2>/dev/null; " +
            "sleep 1; echo locks_cleaned", 10
        )

        val result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "mkdir -p /var/lib/apt/lists/partial /var/lib/dpkg && " +
            "if [ ! -f /var/lib/dpkg/status ]; then touch /var/lib/dpkg/status; fi && " +
            "apt update --allow-unauthenticated", 60, callback
        )

        if (!result.success) {
            callback?.onError("软件源更新失败: ${result.stderr}")
            return true
        }
        callback?.onProgress("软件源更新完成")
        return false
    }

    /** Step 3: apt install 依赖 */
    /** 轻量检测依赖是否已安装（不触发安装） */
    fun checkDepsPresent(): Boolean {
        val depChecks = arrayOf(
            "python3 --version",
            "python3 -m venv --help >/dev/null 2>&1",
            "pip3 --version",
            "git --version",
            "curl --version",
            "locale -a 2>/dev/null | grep -q en_US"
        )
        for (check in depChecks) {
            val result = execInChroot("$check && echo ok", 10)
            if (!result.success || !result.stdout.trim().endsWith("ok")) {
                return false
            }
        }
        return true
    }

    fun aptInstallDeps(callback: FullProgressCallback? = null): Boolean {
        val depChecks = arrayOf(
            "python3 --version",
            "python3 -m venv --help >/dev/null 2>&1",
            "pip3 --version",
            "git --version",
            "curl --version",
            "ssh -V 2>&1",
            "gpgv --version 2>&1",
            "locale -a 2>/dev/null | grep -q en_US"
        )
        val depNames = arrayOf(
            "Python 3", "python3-venv", "pip3", "git", "curl", "SSH", "gpgv", "locales"
        )

        var allPresent = true
        val missing = StringBuilder()
        for (i in depChecks.indices) {
            val check = execInChroot("${depChecks[i]} && echo ok", 10)
            if (!check.success || !check.stdout.trim().endsWith("ok")) {
                allPresent = false
                missing.append(depNames[i]).append(" ")
            }
        }

        if (allPresent) {
            callback?.onProgress("所有系统依赖已存在，跳过安装")
            return false
        }

        callback?.onProgress("缺少依赖: ${missing.toString().trim()}，正在安装...")

        // 清理残留锁
        execInChroot(
            "pkill -9 apt 2>/dev/null; pkill -9 dpkg 2>/dev/null; " +
            "rm -f /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; sleep 1; echo locks_cleaned", 15
        )

        val result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "apt install -y --allow-unauthenticated " +
            "python3 python3-venv python3-pip python3-dev " +
            "git curl wget gpgv coreutils procps openssh-server " +
            "ca-certificates software-properties-common locales build-essential", 60, callback
        )

        if (!result.success) {
            callback?.onError("依赖安装失败: ${result.stderr}")
            return true
        }

        // 配置 locale 和 SSH
        execInChroot("locale-gen en_US.UTF-8", 30)
        execInChroot(
            "mkdir -p /run/sshd && " +
            "sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config && " +
            "sed -i 's/#PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config && " +
            "sed -i 's/UsePAM yes/UsePAM no/' /etc/ssh/sshd_config && " +
            "grep -q '^StrictModes' /etc/ssh/sshd_config || echo 'StrictModes no' >> /etc/ssh/sshd_config", 15
        )

        callback?.onProgress("依赖安装完成 (Python 3.12+)")
        return false
    }

    /** Step 4: 克隆 AstrBot */
    fun cloneAstrBot(callback: FullProgressCallback? = null, version: String? = null): Boolean {
        return cloneAstrBotWithProxy(callback, version, GitHubProxyManager.getBestProxy())
    }

    /** Step 4: 使用指定代理克隆 AstrBot */
    fun cloneAstrBotWithProxy(
        callback: FullProgressCallback? = null,
        version: String? = null,
        proxyIndex: Int = GitHubProxyManager.getBestProxy()
    ): Boolean {
        // PRoot 模式委托给 PRootManager
        if (AuthManager.instance.isProotMode) {
            val pm = PRootManager.getInstance(AuthManager.instance.context ?: return true)

            if (pm.isAstrBotInstalled()) {
                callback?.onProgress("AstrBot 已安装，跳过克隆")
                return false
            }

            pm.runInProot("rm -rf /root/astrbot", 30)

            callback?.onProgress(
                if (!version.isNullOrEmpty()) "正在克隆 AstrBot ($version)..."
                else "正在克隆 AstrBot (最新版)..."
            )

            var cloneCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "export GIT_TERMINAL_PROMPT=0 && git clone --depth 1"
            if (!version.isNullOrEmpty()) cloneCmd += " --branch $version"

            val repoUrl = GitHubProxyManager.buildUrl(
                "https://github.com/AstrBotDevs/AstrBot.git", proxyIndex
            )
            cloneCmd += " $repoUrl /root/astrbot"

            val result = pm.runInProotWithProgress(cloneCmd, 60, callback?.let { cb -> { msg -> cb.onProgress(msg) } })
            if (!result.success) {
                callback?.onError("AstrBot 克隆失败: ${result.stderr}")
                return true
            }
            callback?.onProgress("AstrBot 克隆完成")
            return false
        }

        // Chroot 模式
        execInChroot("rm -rf /root/astrbot", 30)

        callback?.onProgress(
            if (!version.isNullOrEmpty()) "正在克隆 AstrBot ($version)..."
            else "正在克隆 AstrBot (最新版)..."
        )

        var cloneCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "export GIT_TERMINAL_PROMPT=0 && git clone --depth 1"
        if (!version.isNullOrEmpty()) cloneCmd += " --branch $version"

        val repoUrl = GitHubProxyManager.buildUrl(
            "https://github.com/AstrBotDevs/AstrBot.git", proxyIndex
        )
        cloneCmd += " $repoUrl /root/astrbot"

        val result = execInChrootWithProgress(cloneCmd, 60, callback)
        if (!result.success) {
            callback?.onError("AstrBot 克隆失败: ${result.stderr}")
            return true
        }
        callback?.onProgress("AstrBot 克隆完成")
        return false
    }

    /** Step 5: pip 安装依赖 */
    fun pipInstallDeps(callback: FullProgressCallback? = null): Boolean {
        callback?.onProgress("正在创建 Python 虚拟环境...")

        if (AuthManager.instance.isProotMode) {
            val pm = PRootManager.getInstance(AuthManager.instance.context ?: return true)

            val venvResult = pm.runInProotWithProgress(
                "python3 -m venv /root/astrbot/venv && " +
                "/root/astrbot/venv/bin/pip install --upgrade pip", 60,
                callback?.let { cb -> { msg -> cb.onProgress(msg) } }
            )
            if (!venvResult.success) {
                callback?.onError("虚拟环境创建失败: ${venvResult.stderr}")
                return true
            }

            callback?.onProgress("正在安装 Python 依赖...")

            val result = pm.runInProotWithProgress(
                "export PATH=/root/astrbot/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "cd /root/astrbot && pip install -r requirements.txt", 300,
                callback?.let { cb -> { msg -> cb.onProgress(msg) } }
            )
            if (!result.success) {
                callback?.onError("Python 依赖安装失败: ${result.stderr}")
                return true
            }
        } else {
            // Chroot 模式
            val venvResult = execInChrootWithProgress(
                "python3 -m venv /root/astrbot/venv && " +
                "/root/astrbot/venv/bin/pip install --upgrade pip", 60, callback
            )
            if (!venvResult.success) {
                callback?.onError("虚拟环境创建失败: ${venvResult.stderr}")
                return true
            }

            callback?.onProgress("正在安装 Python 依赖...")

            val result = execInChrootWithProgress(
                "export PATH=/root/astrbot/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "cd /root/astrbot && pip install -r requirements.txt", 300, callback
            )
            if (!result.success) {
                callback?.onError("Python 依赖安装失败: ${result.stderr}")
                return true
            }
        }

        // 标记安装完成
        if (AuthManager.instance.isProotMode) {
            PRootManager.getInstance(AuthManager.instance.context ?: return false).markAstrBotInstalled()
        } else {
            execRoot("touch ${RbotPaths.ASTRBOT_MARKER}")
        }
        callback?.onProgress("Python 依赖安装完成")
        return false
    }

    // ─── AstrBot 生命周期 ───

    /** 启动 AstrBot（chroot 内后台运行） */
    fun startAstrBot(): CommandResult = synchronized(chrootLock) {
        LogHub.log("正在启动 AstrBot...")
        // 确保设备节点已挂载（Android 重启后可能丢失）
        setupChrootDevices(null)

        // 先停止已有进程
        stopAstrBot()

        // Fix cmd_config.json before starting AstrBot
        execInChroot(
            "python3 -c 'import json,os;f=\"/root/astrbot/data/cmd_config.json\";s=os.path.getsize(f) if os.path.exists(f) else 0;c=open(f).read().strip() if s>0 else \"\";[open(f,\"w\").write(json.dumps({})) for _ in [1] if not c or all(x==chr(0) for x in c)]' 2>/dev/null;echo ok",
            5
        )

        // 使用 venv python（如可用）
        var pythonBin = "python3"
        val venvCheck = execInChroot("test -f /root/astrbot/venv/bin/python3 && echo venv_ok", 5)
        if (venvCheck.success && venvCheck.stdout.trim() == "venv_ok") {
            pythonBin = "/root/astrbot/venv/bin/python3"
        }

        val startCmd =
            "cd /root/astrbot && " +
            "rm -f /root/astrbot/astrbot.pid && " +
            "nohup $pythonBin main.py >> /root/astrbot/astrbot.log 2>&1 & " +
            "echo \$! > /root/astrbot/astrbot.pid && " +
            "echo launched_\$(cat /root/astrbot/astrbot.pid)"

        val launchResult = execInChroot(startCmd, 10)
        val launchedPid = launchResult.stdout.trim().removePrefix("launched_")
        Log.d(TAG, "[startAstrBot] launch stdout=${launchResult.stdout.trim()} stderr=${launchResult.stderr.trim()}")

        if (!launchResult.success || !launchResult.stdout.contains("launched_")) {
            LogHub.error("AstrBot 启动失败: ${launchResult.stderr.take(80)}")
            return CommandResult(false, launchResult.stdout, launchResult.stderr, 1)
        }

        // 从宿主侧等待进程启动并验证（sleep 在宿主环境可用）
        val checkCmd =
            "PIDFILE=${RbotPaths.ASTRBOT_PID_FILE}; " +
            "PID=\$(cat \$PIDFILE 2>/dev/null); " +
            "COUNT=0; " +
            "while [ \$COUNT -lt 5 ]; do " +
            "  if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then " +
            "    echo started_\$PID; exit 0; " +
            "  fi; " +
            "  PID=\$(cat \$PIDFILE 2>/dev/null); " +
            "  /bin/sleep 1; " +
            "  COUNT=\$((COUNT + 1)); " +
            "done; " +
            "echo 'FAIL_PID='\$PID': ' \$(tail -5 ${RbotPaths.CHROOT_DIR}/root/astrbot/astrbot.log 2>/dev/null); exit 1"

        val checkResult = execRoot(checkCmd, 15)
        val started = checkResult.success && checkResult.stdout.contains("started_")
        Log.d(TAG, "[startAstrBot] check stdout=${checkResult.stdout.trim()}")
        if (started) {
            LogHub.log("AstrBot 已启动 (PID ${launchedPid})")
        } else {
            LogHub.error("AstrBot 启动验证失败: ${checkResult.stderr.take(80)}")
        }
        return CommandResult(started, checkResult.stdout, checkResult.stderr, if (started) 0 else 1)
    }

    /** 停止 AstrBot — 从宿主和 chroot 两侧同时 kill */
    fun stopAstrBot(): CommandResult {
        val sb = StringBuilder()

        // Step 1: 通过 PID 文件 kill
        val r1 = execRoot(
            "PIDFILE=${RbotPaths.ASTRBOT_PID_FILE}; " +
            "if [ -f \"\$PIDFILE\" ]; then " +
            "  PID=\$(cat \$PIDFILE 2>/dev/null); " +
            "  if [ -n \"\$PID\" ]; then kill -9 \$PID 2>/dev/null && echo pid_killed_\$PID || echo pid_gone_\$PID; fi; " +
            "  rm -f \$PIDFILE; " +
            "fi; " +
            "echo pid_done", 10
        )
        sb.append("PID: ").append(r1.stdout.trim())

        // Step 2: pkill by pattern（排除自身 PID）
        val r2 = execRoot(
            "MYPID=\$\$; " +
            "PIDS=\$(pgrep -f 'python.*main\\.py' 2>/dev/null | grep -v \"^\$MYPID\$\"); " +
            "if [ -n \"\$PIDS\" ]; then kill -9 \$PIDS 2>/dev/null && echo pkill_ok || echo pkill_fail; " +
            "else echo pkill_none; fi; " +
            "echo pkill_done", 5
        )
        sb.append(", PKill: ").append(r2.stdout.trim())

        // Step 3: chroot 内 pkill 作为后备
        val r3 = execInChroot(
            "pkill -9 -f 'python.*main\\.py' 2>/dev/null && echo chroot_ok || echo chroot_none; " +
            "rm -f /root/astrbot/astrbot.pid; " +
            "echo chroot_done", 10
        )
        sb.append(", Chroot: ").append(r3.stdout.trim())

        Log.d(TAG, "[stopAstrBot] $sb")
        LogHub.log("AstrBot 已停止")
        return CommandResult(true, sb.toString(), "", 0)
    }

    /** 检查 AstrBot 是否在运行（宿主端检测，无需 chroot） */
    fun isAstrBotRunning(): Boolean {
        val r1 = execRoot(
            "PIDFILE=${RbotPaths.ASTRBOT_PID_FILE}; " +
            "if [ -f \"\$PIDFILE\" ]; then " +
            "  PID=\$(cat \$PIDFILE 2>/dev/null); " +
            "  [ -n \"\$PID\" ] && kill -0 \$PID 2>/dev/null && echo running && exit 0; " +
            "fi; " +
            // 注意: pgrep -f 会匹配到自身命令字符串，必须排除自身 PID
            "MYPID=\$\$; " +
            "FOUND=\$(pgrep -f 'python.*main\\.py' 2>/dev/null | grep -v \"^\$MYPID\$\" | head -1); " +
            "if [ -n \"\$FOUND\" ]; then echo running; else echo stopped; fi", 10
        )
        val running = r1.success && r1.stdout.trim() == "running"
        Log.d(TAG, "[isAstrBotRunning] running=$running stdout='${r1.stdout.trim()}'")
        return running
    }

    /** 获取完整状态 */
    /** 获取完整状态（带缓存，避免轮询风暴） */
    @Volatile
    private var cachedStatus: FullStatus? = null
    private var lastStatusTime = 0L
    private const val STATUS_CACHE_MS = 10_000L // 10秒缓存

    fun getFullStatus(force: Boolean = false): FullStatus {
        val now = System.currentTimeMillis()
        if (!force && cachedStatus != null && now - lastStatusTime < STATUS_CACHE_MS) {
            return cachedStatus!!
        }

        val rootAvailable = isRootAvailable()
        val rootfsReady = isRootfsReady()
        var chrootMounted = false
        val astrBotInstalled = isAstrBotInstalled()
        var astrBotRunning = false

        if (rootAvailable) {
            chrootMounted = isChrootMounted()
            if (rootfsReady && astrBotInstalled) {
                val runningResult = execRoot(
                    "PIDFILE=${RbotPaths.ASTRBOT_PID_FILE}; " +
                    "if [ -f \"\$PIDFILE\" ]; then " +
                    "  PID=\$(cat \$PIDFILE 2>/dev/null); " +
                    "  [ -n \"\$PID\" ] && kill -0 \$PID 2>/dev/null && echo running && exit 0; " +
                    "fi; " +
                    "MYPID=\$\$; " +
                    "FOUND=\$(pgrep -f 'python.*main\\.py' 2>/dev/null | grep -v \"^\$MYPID\$\" | head -1); " +
                    "if [ -n \"\$FOUND\" ]; then echo running; else echo stopped; fi", 10
                )
                astrBotRunning = runningResult.success && runningResult.stdout.trim() == "running"
            }
        }
        val status = FullStatus(rootAvailable, rootfsReady, chrootMounted, astrBotInstalled, astrBotRunning)
        cachedStatus = status
        lastStatusTime = now
        return status
    }

    // ─── 备份与恢复 ───

    /** 创建 AstrBot 数据备份 */
    fun backupAstrBotData(callback: FullProgressCallback? = null): String? {
        if (AuthManager.instance.isProotMode) {
            return PRootManager.getInstance(
                AuthManager.instance.context ?: return null
            ).backupAstrBotData(callback)
        }

        callback?.onProgress("准备备份...")

        val mkdirResult = execRoot("mkdir -p ${RbotPaths.BACKUP_DIR}")
        if (!mkdirResult.success) {
            callback?.onError("无法创建备份目录")
            return null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val backupFile = "${RbotPaths.BACKUP_DIR}/astrbot_data_$timestamp.tar.gz"

        callback?.onProgress("正在打包数据（排除虚拟环境和缓存）...")

        val tarResult = execInChroot(
            "cd /root/astrbot && tar czf - " +
            "--exclude='./venv' " +
            "--exclude='./__pycache__' " +
            "--exclude='./.git' " +
            "--exclude='./astrbot.log' " +
            "--exclude='./astrbot-debug.log' " +
            "--exclude='./astrbot.pid' " +
            ". | cat > $backupFile", 300
        )

        if (!tarResult.success) {
            callback?.onProgress("重试：使用主机端打包...")
            val hostResult = execRoot(
                "cd ${RbotPaths.ASTRBOT_HOME} && tar czf '$backupFile' " +
                "--exclude='./venv' " +
                "--exclude='./__pycache__' " +
                "--exclude='./.git' " +
                "--exclude='./astrbot.log' " +
                "--exclude='./astrbot-debug.log' " +
                "--exclude='./astrbot.pid' " +
                ".", 300
            )
            if (!hostResult.success) {
                callback?.onError("打包失败: ${hostResult.stderr}")
                return null
            }
        }

        // 验证备份文件
        val checkResult = execRoot(
            "test -f '$backupFile' && stat -c '%s' '$backupFile' || echo missing", 5
        )
        if (!checkResult.success || checkResult.stdout.trim() == "missing" || checkResult.stdout.trim() == "0") {
            callback?.onError("备份文件无效或为空")
            return null
        }

        val sizeInfo = checkResult.stdout.trim()
        callback?.onProgress("备份完成: $backupFile ($sizeInfo bytes)")
        return backupFile
    }

    /** 从指定备份文件恢复 AstrBot 数据 */
    fun restoreAstrBotData(backupFile: String, callback: FullProgressCallback? = null): Boolean {
        if (AuthManager.instance.isProotMode) {
            return PRootManager.getInstance(
                AuthManager.instance.context ?: return true
            ).restoreAstrBotData(backupFile, callback)
        }

        callback?.onProgress("检查备份...")

        val checkResult = execRoot("test -f '$backupFile' && echo exists")
        if (!checkResult.success || checkResult.stdout.trim() != "exists") {
            callback?.onError("备份文件不存在")
            return true
        }

        callback?.onProgress("停止 AstrBot...")
        stopAstrBot()

        callback?.onProgress("正在恢复数据...")

        val tarResult = execRoot(
            "cd ${RbotPaths.ASTRBOT_HOME} && tar xzf '$backupFile'", 300
        )

        if (!tarResult.success) {
            callback?.onError("恢复失败: ${tarResult.stderr}")
            return true
        }

        callback?.onProgress("恢复完成")
        return false
    }

    /** 列出所有备份文件 */
    fun listBackups(): Array<String> {
        if (AuthManager.instance.isProotMode) {
            return PRootManager.listBackups()
        }

        val result = execRoot(
            "ls -1t ${RbotPaths.BACKUP_DIR}/astrbot_data_*.tar.gz 2>/dev/null || echo none"
        )

        if (!result.success || result.stdout.trim() == "none") {
            return emptyArray()
        }

        return result.stdout.trim().split("\n").toTypedArray()
    }

    /** 删除备份文件 */
    fun deleteBackup(backupPath: String): Boolean {
        val result = execRoot("rm -f '$backupPath'")
        return result.success
    }

    // ─── SSH 服务 ───

    /** 启动 SSH 服务 — 使用 dropbear（轻量级，无 privsep 问题） */
    fun startSshService(): CommandResult {
        LogHub.log("正在启动 SSH 服务...")
        setupChrootDevices(null)

        // 确保根密码已设置
        val passCheck = execInChroot(
            "[ -f /root/.rbot_pass ] && echo has_pass || echo no_pass", 5
        )
        if (passCheck.success && passCheck.stdout.trim() == "no_pass") {
            setRootPassword(RbotPaths.DEFAULT_SSH_PASSWORD)
            Log.d(TAG, "[startSshService] Set default SSH password: ${RbotPaths.DEFAULT_SSH_PASSWORD}")
        }

        // 安装 dropbear（如未安装）
        execInChroot(
            "which dropbear >/dev/null 2>&1 || apt-get install -y dropbear-bin >/dev/null 2>&1", 60
        )

        val setupCmd =
            "mkdir -p /etc/dropbear && " +
            "[ -f /etc/dropbear/dropbear_rsa_host_key ] || dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ecdsa_host_key ] || dropbearkey -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ed25519_host_key ] || dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null; " +
            "pkill -x dropbear 2>/dev/null; pkill -x sshd 2>/dev/null; sleep 1; " +
            "dropbear -r /etc/dropbear/dropbear_rsa_host_key " +
            "-r /etc/dropbear/dropbear_ecdsa_host_key " +
            "-r /etc/dropbear/dropbear_ed25519_host_key " +
            "-p 22 -R -B && echo dropbear_started; " +
            "ss -tlnp 2>/dev/null | grep ':22 ' || netstat -tlnp 2>/dev/null | grep ':22 '"

        val chrootResult = execInChroot(setupCmd, 20)

        if (chrootResult.success) {
            LogHub.log("SSH 服务已启动 (端口 22)")
        } else {
            LogHub.error("SSH 服务启动失败: ${chrootResult.stderr.take(80)}")
        }

        // 添加 iptables 规则
        val D = RbotPaths.CHROOT_DIR
        execRoot(
            "iptables -C INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null || " +
            "iptables -I INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null; " +
            "ip6tables -C INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null || " +
            "ip6tables -I INPUT -p tcp --dport 22 -j ACCEPT 2>/dev/null; " +
            "echo iptables_done", 10
        )

        return chrootResult
    }

    /** 设置 SSH 密码 */
    fun setSshPassword(password: String): CommandResult {
        val chrootCmd =
            "HASH=\$(openssl passwd -6 ${shellQuote(password)}) && " +
            "usermod -p \"\$HASH\" root && " +
            "chmod 600 /etc/shadow && " +
            "echo PASSWORD_SET_OK"
        return execInChroot(chrootCmd, 20)
    }

    /** 停止 SSH 服务 */
    fun stopSshService(): CommandResult {
        LogHub.log("正在停止 SSH 服务...")
        return execInChroot("pkill -x dropbear 2>/dev/null; pkill -x sshd 2>/dev/null; echo stopped", 10)
    }

    /** 检查 SSH 是否在运行 */
    fun isSshRunning(): Boolean {
        val result = execInChroot(
            "pgrep -x dropbear >/dev/null 2>&1 && echo running || echo stopped", 5
        )
        return result.success && result.stdout.trim() == "running"
    }

    /** 获取 SSH 连接信息 */
    fun getSshInfo(): String {
        var ip = "127.0.0.1"
        val ipResult = execRoot(
            "ifconfig wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}' | cut -d: -f2 || " +
            "ip addr show wlan0 2>/dev/null | grep -oP 'inet \\K[0-9.]+' || " +
            "ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \\K[0-9.]+' || " +
            "ip route get 8.8.8.8 2>/dev/null | grep -oP 'src \\K[0-9.]+' || " +
            "getprop dhcp.wlan0.ipaddress 2>/dev/null || " +
            "echo 127.0.0.1", 5
        )
        if (ipResult.success && ipResult.stdout.trim().isNotEmpty()) {
            val detected = ipResult.stdout.trim()
            if (detected != "127.0.0.1" && detected != "::1" &&
                detected.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                ip = detected
            }
        }
        return "ssh root@$ip"
    }

    /** 获取 root 密码（从 chroot 中 /root/.rbot_pass 读取） */
    fun getRootPassword(): String {
        val result = execInChroot("cat /root/.rbot_pass 2>/dev/null || echo ''", 5)
        return if (result.success) result.stdout.trim() else ""
    }

    /** 设置 root 密码 */
    fun setRootPassword(password: String): Boolean {
        if (password.isEmpty()) return false
        val saveResult = execInChroot(
            "printf '%s' ${shellQuote(password)} > /root/.rbot_pass && chmod 600 /root/.rbot_pass", 5
        )
        val passResult = execInChroot(
            "HASH=\$(openssl passwd -6 ${shellQuote(password)}) && usermod -p \"\$HASH\" root", 5
        )
        return saveResult.success && passResult.success
    }

    // ─── 底层命令执行 ───

    /**
     * 带实时进度推送的 root 命令执行。
     * 统一使用 libsu 主 shell + CallbackList，不再按超时长度分流。
     *
     * libsu 的 setTimeout 仅控制 shell 握手超时，不限制命令执行时长，
     * 所以长短命令都走 CallbackList 方式，无需创建新 shell。
     */
    private fun execRootWithProgress(
        command: String,
        timeoutSec: Int,
        callback: FullProgressCallback?
    ): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        return try {
            Log.d(TAG, "Executing (progress libsu): $command")

            val progressBuf = StringBuilder()
            val lastFlush = longArrayOf(System.currentTimeMillis())

            val flushBuffer = fun() {
                if (callback != null && progressBuf.isNotEmpty()) {
                    callback.onProgress(progressBuf.toString())
                    progressBuf.clear()
                    lastFlush[0] = System.currentTimeMillis()
                }
            }

            val stdoutList = object : CallbackList<String>(Shell.EXECUTOR) {
                override fun onAddElement(line: String) {
                    stdout.appendLine(line)
                    if (callback != null && isProgressLine(line)) {
                        synchronized(progressBuf) {
                            if (progressBuf.isNotEmpty()) progressBuf.append("\n")
                            progressBuf.append(line.trim())
                            if (System.currentTimeMillis() - lastFlush[0] >= PROGRESS_DEBOUNCE_MS) {
                                flushBuffer()
                            }
                        }
                    }
                }
            }

            val stderrList = object : CallbackList<String>(Shell.EXECUTOR) {
                override fun onAddElement(line: String) {
                    stderr.appendLine(line)
                    if (callback != null && isProgressLine(line)) {
                        synchronized(progressBuf) {
                            if (progressBuf.isNotEmpty()) progressBuf.append("\n")
                            progressBuf.append(line.trim())
                            if (System.currentTimeMillis() - lastFlush[0] >= PROGRESS_DEBOUNCE_MS) {
                                flushBuffer()
                            }
                        }
                    }
                }
            }

            val result = Shell.cmd(command).to(stdoutList, stderrList).exec()

            flushBuffer()

            val exitCode = result.code
            val out = stdout.toString()
            val err = stderr.toString()
            if (exitCode != 0) {
                Log.w(TAG, "execProgress exit=$exitCode err=[${err.take(80)}]")
            }
            CommandResult(exitCode == 0, out, err, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "execRootWithProgress exception: ${e.message}")
            CommandResult(false, stdout.toString(), e.message ?: "Unknown error", -1)
        }
    }

    /** chroot 内带进度的命令执行 */
    private fun execInChrootWithProgress(
        command: String,
        timeoutSec: Int,
        callback: FullProgressCallback?
    ): CommandResult {
        val chrootCmd = "/system/bin/chroot ${RbotPaths.CHROOT_DIR} /bin/bash -c " +
            shellQuote(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "export HOME=/root && " +
                "unset ANDROID_ROOT && " +
                "export TMPDIR=/tmp && export TEMP=/tmp && export TMP=/tmp && " +
                "export XDG_CACHE_HOME=/root/.cache && " +
                "export XDG_CONFIG_HOME=/root/.config && " +
                "export XDG_DATA_HOME=/root/.local/share && " +
                "export XDG_STATE_HOME=/root/.local/state && " +
                command
            )
        return execRootWithProgress(chrootCmd, timeoutSec, callback)
    }

    // ─── 工具方法 ───

    private fun shellQuote(s: String): String {
        return "'${s.replace("'", "'\\''")}'"
    }

    /** 过滤 stderr 行，识别值得显示进度的输出 */
    private fun isProgressLine(line: String?): Boolean {
        if (line == null || line.trim().isEmpty()) return false
        val l = line.trim()
        if (l.length < 3) return false
        if (l.startsWith("debconf:")) return false
        if (l.startsWith("Requirement already satisfied:")) return false
        if (l == "." || l == ".." || l == "...") return false
        return true
    }

    /**
     * 根据 tarball 压缩格式构建相应的 tar 解压命令。
     * 优先使用 `file` 命令检测；回退到文件扩展名。
     */
    private fun buildTarExtractCommand(tarballPath: String, stagingDir: String): String {
        val detect = execRoot("file '$tarballPath'", 10)
        val info = if (detect.success) detect.stdout.lowercase() else ""

        val tarBase = when {
            info.contains("gzip") || info.contains("zlib") ->
                "tar -xzf '$tarballPath'"
            info.contains("xz") ->
                "tar -xf '$tarballPath'"
            info.contains("zstd") ->
                "tar -I zstd -xf '$tarballPath'"
            else -> {
                // `file` 命令不可用 — 根据扩展名猜测
                val lower = tarballPath.lowercase()
                when {
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                        "tar -xzf '$tarballPath'"
                    lower.endsWith(".tar.xz") || lower.endsWith(".txz") ->
                        "tar -xf '$tarballPath'"
                    lower.endsWith(".tar.zst") ->
                        "tar -I zstd -xf '$tarballPath'"
                    else ->
                        "tar -xf '$tarballPath'"
                }
            }
        }

        return "$tarBase -v -C $stagingDir"
    }
}
