package app.rbot;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Manages chroot lifecycle for Rbot: root command execution,
 * rootfs extraction, AstrBot installation, and chroot command execution.
 *
 * All shell commands go through `su -c` for root access.
 */
@SuppressWarnings({"SpellCustomInspection", "unused"})
public final class ChrootManager {

    private static final String TAG = "ChrootManager";
    private static final int DEFAULT_TIMEOUT_SEC = 60;
    /** Reentrant lock to prevent concurrent chroot device setup / AstrBot start */
    private static final Object sChrootLock = new Object();

    /** Package-private accessor for the chroot lock — used by BotAdapter implementations */
    static Object getChrootLock() {
        return sChrootLock;
    }

    private ChrootManager() {}

    // ─── Command result ───

    /** Shell command result record */
    public record CommandResult(
            boolean success,
            String stdout,
            String stderr,
            int exitCode
    ) {}

    // ─── Root command execution ───

    @SuppressLint("NewApi")  // publicly exposed, minSdk=24
    public static CommandResult execInChroot(String command, int timeoutSec) {
        String chrootCmd = "chroot " + RbotConstants.CHROOT_DIR +
            " /bin/bash -c " +
            shellQuote(
                // Explicit PATH so all commands (nohup, python3, pkill, pgrep, etc.)
                // are found regardless of what the chroot's /etc/profile sets.
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                // Unset ANDROID_ROOT so Python platformdirs doesn't think
                // we're in an Android app environment (it would try jnius
                // and crash with "Cannot find path to android app folder").
                "unset ANDROID_ROOT && " +
                // Ensure temp files stay inside chroot - prevent leaking to host Android paths
                "export TMPDIR=/tmp && " +
                "export TEMP=/tmp && " +
                "export TMP=/tmp && " +
                "export XDG_CACHE_HOME=/root/.cache && " +
                "export XDG_CONFIG_HOME=/root/.config && " +
                "export XDG_DATA_HOME=/root/.local/share && " +
                "export XDG_STATE_HOME=/root/.local/state && " +
                command
            );
        return execRoot(chrootCmd, timeoutSec);
    }

    /** Execute a command as root via `su -c` */
    public static CommandResult execRoot(String command) {
        return execRoot(command, DEFAULT_TIMEOUT_SEC);
    }

    /** Execute a command as root with custom timeout */
    public static CommandResult execRoot(String command, int timeoutSec) {
        return exec(new String[]{"su", "-c", command}, timeoutSec);
    }

    // ─── Root access check ───

    /** Check if root (su) is available */
    public static boolean isRootAvailable() {
        try {
            CommandResult result = execRoot("id", 5);
            return result.success && result.stdout.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Rootfs management ───

    /** Check if rootfs is extracted and ready */
    public static boolean isRootfsReady() {
        return new File(RbotConstants.ROOTFS_MARKER).exists()
            && new File(RbotConstants.CHROOT_DIR + "/bin/bash").exists();
    }

    /** Check if AstrBot is installed (marker file at chroot root, readable by app process) */
    public static boolean isAstrBotInstalled() {
        return new File(RbotConstants.ASTRBOT_MARKER).exists();
    }

    /** Create /data/rbot directory with proper permissions */
    public static boolean ensureChrootDir() {
        CommandResult result = execRoot(
            "mkdir -p " + RbotConstants.CHROOT_DIR + " && " +
            "chmod 755 " + RbotConstants.CHROOT_DIR
        );
        return !result.success;
    }

    /** Extract rootfs from tarball to /data/rbot */
    public static boolean extractRootfs(String tarballPath, ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在解压系统镜像...");

        String stagingDir = RbotConstants.CHROOT_DIR + "_staging";
        String tarCmd = buildTarExtractCommand(tarballPath, stagingDir);

        if (callback != null) callback.onProgress("正在提取到暂存区...");

        // Unmount bind-mounted devices before cleaning dirs (avoid deleting host /dev/null etc.)
        cleanupChrootDevices();

        // Backup AstrBot data to unified external storage location (survives app uninstall)
        // Must use root check — /data/rbot/root/ is root:root 700, app process can't see it
        CommandResult dataCheck = execRoot("test -d " + RbotConstants.CHROOT_DIR + "/root/astrbot/data && echo yes");
        boolean hasAstrBotData = dataCheck.success && dataCheck.stdout.trim().equals("yes");
        if (hasAstrBotData) {
            if (callback != null) callback.onProgress("检测到 AstrBot 数据，正在备份...");
            execRoot("rm -rf " + RbotConstants.EXTERNAL_DATA_BACKUP);
            execRoot("cp -r " + RbotConstants.CHROOT_DIR + "/root/astrbot/data " + RbotConstants.EXTERNAL_DATA_BACKUP);
            if (callback != null) callback.onProgress("数据已备份到: " + RbotConstants.EXTERNAL_DATA_BACKUP);
        }

        // Clean staging AND chroot dir (chroot may have partial files from a previous failed run)
        execRoot("rm -rf " + stagingDir + " && rm -rf " + RbotConstants.CHROOT_DIR + " && mkdir -p " + RbotConstants.CHROOT_DIR + " " + stagingDir);

        // Step 1: extract to staging with real-time progress
        CommandResult result = execRootWithProgress(tarCmd, 600, callback);
        if (!result.success) {
            if (callback != null) callback.onError("解压失败: " + result.stderr);
            execRoot("rm -rf " + stagingDir);
            return true;
        }

        // Handle GitHub tar.xz which extracts into ubuntu-fs/ subdirectory
        String restructureCmd =
            "if [ -d " + stagingDir + "/ubuntu-fs ]; then " +
            "  cp -r " + stagingDir + "/ubuntu-fs/. " + stagingDir + "/ && " +
            "  rm -rf " + stagingDir + "/ubuntu-fs; " +
            "fi";
        execRoot(restructureCmd);

        if (callback != null) callback.onProgress("正在同步到 chroot 目录...");

        // Step 2: copy from staging to chroot dir, following symlinks
        // -a = preserve all attributes, -L = follow symlinks (copy target not link)
        // This converts absolute symlinks like ./run/shm -> /dev/shm into real directories
        // Note: cp -aL may fail on broken symlinks (e.g., node-compile-cache) but most
        // files succeed. We append "; true" so the overall command succeeds, then check
        // that critical files actually exist rather than relying on cp's exit code.
        //
        // Progress: use a background subshell to report size changes every 5s.
        // This gives the user visual feedback during the ~2 minute copy.
        String copyCmd =
            "( " +
            "  SRC=" + stagingDir + "; " +
            "  DST=" + RbotConstants.CHROOT_DIR + "; " +
            "  TOTAL=$(du -sm $SRC 2>/dev/null | cut -f1); " +
            "  (while cp -aL $SRC/. $DST/ 2>/dev/null; do break; done) & " +
            "  CP_PID=$!; " +
            "  while kill -0 $CP_PID 2>/dev/null; do " +
            "    DONE=$(du -sm $DST 2>/dev/null | cut -f1); " +
            "    echo \"[同步] ${DONE}MB / ~${TOTAL}MB\"; " +
            "    sleep 5; " +
            "  done; " +
            "  wait $CP_PID; " +
            ") 2>&1; true";
        CommandResult copyResult = execRootWithProgress(copyCmd, 600, callback);

        // Clean up staging
        execRoot("rm -rf " + stagingDir);

        // Verify critical files exist rather than relying on cp exit code
        CommandResult verify = execRoot(
            "test -f " + RbotConstants.CHROOT_DIR + "/bin/bash && " +
            "test -f " + RbotConstants.CHROOT_DIR + "/usr/bin/env && " +
            "echo verify_ok", 10);

        if (!verify.success) {
            if (callback != null) callback.onError("同步失败(关键文件缺失): " + copyResult.stderr);
            return true;
        }

        if (!copyResult.stderr.isEmpty()) {
            if (callback != null) callback.onProgress("同步完成(部分损坏符号链接已跳过)");
        }

        // Create marker
        execRoot("touch " + RbotConstants.ROOTFS_MARKER);

        // Ensure /dev directory exists (tar excluded it)
        execRoot("mkdir -p " + RbotConstants.CHROOT_DIR + "/dev " +
                 RbotConstants.CHROOT_DIR + "/dev/pts " +
                 RbotConstants.CHROOT_DIR + "/proc " +
                 RbotConstants.CHROOT_DIR + "/sys", 5);

        if (callback != null) callback.onProgress("系统镜像解压完成");
        return false;
    }

    /** Restore AstrBot data from unified external storage backup */
    public static boolean restoreAstrBotData(ProgressCallback callback) {
        // EXTERNAL_DATA_BACKUP itself IS the data directory
        CommandResult checkResult = execRoot("test -d '" + RbotConstants.EXTERNAL_DATA_BACKUP + "' && echo exists");
        if (!checkResult.success || !checkResult.stdout.trim().equals("exists")) {
            if (callback != null) callback.onError("未找到备份数据: " + RbotConstants.EXTERNAL_DATA_BACKUP);
            return true;
        }

        if (callback != null) callback.onProgress("正在从外部存储恢复 AstrBot 数据...");

        // Ensure astrbot directory exists
        execRoot("mkdir -p " + RbotConstants.CHROOT_DIR + "/root/astrbot");

        // backupDir IS the data — remove old data and copy backup as new data
        CommandResult result = execRoot("rm -rf " + RbotConstants.ASTRBOT_HOME + "/data && cp -r '" + RbotConstants.EXTERNAL_DATA_BACKUP + "' " + RbotConstants.ASTRBOT_HOME + "/data");

        if (!result.success()) {
            if (callback != null) callback.onError("恢复数据失败: " + result.stderr());
            return true;
        }

        if (callback != null) callback.onProgress("AstrBot 数据已恢复");
        return false;
    }

    /**
     * Set up essential device nodes, filesystem mounts inside the chroot.
     * Must be called after extractRootfs and before any chroot command.
     */
    public static void setupChrootDevices(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在初始化 chroot 环境...");

        String D = RbotConstants.CHROOT_DIR;

        // Step 1: Create mount point directories
        execRoot("mkdir -p " + D + "/dev " + D + "/dev/pts " +
                              D + "/proc " + D + "/sys " + D + "/tmp", 10);

        // Step 2: Bind-mount entire /dev — ensures all device nodes have correct permissions
        // This is the standard approach used by Termux and other chroot solutions.
        // Individual bind mounts (mount --bind /dev/null) can cause permission issues
        // because apt-key and other tools need write access to /dev/null.
        execRoot("mount --bind /dev " + D + "/dev 2>/dev/null || true", 10);

        // Step 3: Mount devpts — must use new instance with proper permissions for apt/ssh
        execRoot(
            "mount -t devpts -o newinstance,ptmxmode=0666 devpts " + D + "/dev/pts 2>/dev/null || " +
            "mount -t devpts devpts " + D + "/dev/pts 2>/dev/null; " +
            "chmod 666 " + D + "/dev/pts/ptmx 2>/dev/null || true; " +
            "rm -f " + D + "/dev/ptmx 2>/dev/null; " +
            "ln -sf pts/ptmx " + D + "/dev/ptmx; " +
            "echo pts_done", 15);

        // Step 4: Mount /proc and /sys
        String fsCmd =
            "mount -t proc proc " + D + "/proc; " +
            "mount -t sysfs sysfs " + D + "/sys; " +
            "echo fs_done";

        execRoot(fsCmd, 15);

        // Step 5: Mount tmpfs on /tmp for apt/mktemp to work properly
        execRoot(
            "chmod 1777 " + D + "/tmp 2>/dev/null; " +
            "mount -t tmpfs -o size=512M,mode=1777 tmpfs " + D + "/tmp 2>/dev/null || true; " +
            "echo tmp_done", 10);

        // Step 6: Copy host's DNS config into chroot
        execRoot(
            "rm -f " + D + "/etc/resolv.conf 2>/dev/null; " +
            "cp /etc/resolv.conf " + D + "/etc/resolv.conf 2>/dev/null || " +
            "echo 'nameserver 223.5.5.5' > " + D + "/etc/resolv.conf; " + 
            "echo 'nameserver 8.8.8.8' >> " + D + "/etc/resolv.conf; " +
            "echo dns_done", 10);

        if (callback != null) {
            callback.onProgress("chroot 环境初始化完成");
        }
    }

    /** Install AstrBot inside chroot (full pipeline: setup → apt update → apt install → git clone → pip) */
    public static boolean installAstrBot(ProgressCallback callback) {
        if (setupChrootEnvironment(callback)) return true;
        if (aptUpdate(callback)) return true;
        if (aptInstallDeps(callback)) return true;
        if (cloneAstrBot(callback)) return true;
        if (pipInstallDeps(callback)) return true;

        if (callback != null) callback.onProgress("✅ AstrBot 安装完成");
        return false;
    }

    /** Step 1: Setup chroot environment (mount devices, proc, sys, pts) */
    public static boolean setupChrootEnvironment(ProgressCallback callback) {
        setupChrootDevices(callback);
        // Fix /dev/null permissions and ensure gpgv is installed for apt to work
        fixDevNull(callback);
        ensureGpgv(callback);
        // Switch to Chinese mirror early so all apt operations are fast
        switchToChineseMirror(callback);
        return false;  // setupChrootDevices doesn't return failure
    }

    /** Fix /dev/null — common issue after rootfs extract where /dev/null is a regular file with wrong permissions */
    private static void fixDevNull(ProgressCallback callback) {
        // Verify /dev/null works inside chroot
        CommandResult check = execInChroot("echo test > /dev/null 2>&1 && echo ok || echo broken", 5);
        if (check.success() && check.stdout().trim().contains("ok")) {
            return; // /dev/null works fine
        }

        if (callback != null) callback.onProgress("修复 /dev/null 权限...");

        String D = RbotConstants.CHROOT_DIR;
        // Unmount first (may be stale bind mount)
        execRoot("umount " + D + "/dev/null 2>/dev/null", 5);
        // Remove broken file/node
        execRoot("rm -f " + D + "/dev/null", 5);
        // Recreate and bind mount
        execRoot("touch " + D + "/dev/null && mount --bind /dev/null " + D + "/dev/null", 5);

        // Verify again
        CommandResult verify = execInChroot("echo test > /dev/null 2>&1 && echo ok || echo broken", 5);
        if (verify.success() && verify.stdout().trim().contains("ok")) {
            if (callback != null) callback.onProgress("/dev/null 已修复");
        } else {
            if (callback != null) callback.onProgress("⚠️ /dev/null 修复可能失败，部分操作可能出错");
        }
    }

    /** Ensure gpgv is installed — required by apt for repository verification */
    private static void ensureGpgv(ProgressCallback callback) {
        // Check if gpgv exists in chroot
        CommandResult check = execInChroot("which gpgv 2>/dev/null && echo exists || echo missing", 5);
        if (check.success() && check.stdout().trim().contains("exists")) {
            return; // gpgv already installed
        }

        if (callback != null) callback.onProgress("安装 gpgv (签名验证工具)...");

        // Install gpgv without verification (chicken-and-egg problem)
        CommandResult result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "apt update --allow-unauthenticated 2>/dev/null; " +
            "apt install -y --allow-unauthenticated gpgv 2>&1", 60, callback);

        if (result.success()) {
            if (callback != null) callback.onProgress("gpgv 安装完成");
        } else {
            if (callback != null) callback.onProgress("⚠️ gpgv 安装失败，后续 apt 操作可能受限");
        }
    }

    /** Switch apt sources to Aliyun mirror for faster downloads in China.
     *  Detects Ubuntu version from /etc/os-release and replaces sources.list.
     */
    private static void switchToChineseMirror(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在切换国内镜像源...");

        // Check if already using Chinese mirror
        CommandResult check = execInChroot("grep -q 'aliyun\\|tuna\\|ustc\\|163' /etc/apt/sources.list 2>/dev/null && echo already", 10);
        if (check.success() && check.stdout().trim().contains("already")) {
            if (callback != null) callback.onProgress("已使用国内镜像源，跳过");
            return;
        }

        // Detect Ubuntu codename (e.g., "noble" for 24.04)
        CommandResult codenameResult = execInChroot(
            "grep '^UBUNTU_CODENAME=' /etc/os-release 2>/dev/null | cut -d= -f2 || " +
            "lsb_release -cs 2>/dev/null || echo noble", 10);
        String codename = codenameResult.stdout().trim();
        if (codename.isEmpty()) codename = "noble";

        // Write Aliyun mirror sources.list (arm64 uses ports.ubuntu.com, but Aliyun mirrors that too)
        String sourcesContent =
            "# Aliyun mirror - auto-configured by Rbot\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + " main restricted universe multiverse\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + "-updates main restricted universe multiverse\n" +
            "deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + "-security main restricted universe multiverse\n";

        // Write via host root (more reliable than chroot for file operations)
        String tmpFile = RbotConstants.RBOT_TMP + "/sources.list";
        execRoot("mkdir -p " + RbotConstants.RBOT_TMP, 5);
        try {
            java.io.FileWriter fw = new java.io.FileWriter(tmpFile);
            fw.write(sourcesContent);
            fw.close();
        } catch (java.io.IOException e) {
            // Fallback: write via echo
            execRoot("echo 'deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + " main restricted universe multiverse\n" +
                "deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + "-updates main restricted universe multiverse\n" +
                "deb http://mirrors.aliyun.com/ubuntu-ports/ " + codename + "-security main restricted universe multiverse\n' > " + tmpFile, 10);
        }
        execRoot("cp " + tmpFile + " " + RbotConstants.CHROOT_DIR + "/etc/apt/sources.list && rm -f " + tmpFile, 10);

        if (callback != null) callback.onProgress("已切换到阿里云镜像源 (" + codename + ")");
    }

    /** Step 2: apt update inside chroot */
    public static boolean aptUpdate(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在更新软件源...");

        // Kill stale apt processes and clean locks from previous failed runs
        execInChroot(
            "pkill -9 apt 2>/dev/null; pkill -9 dpkg 2>/dev/null; " +
            "rm -f /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock 2>/dev/null; " +
            "sleep 1; echo locks_cleaned", 10);

        CommandResult result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "mkdir -p /var/lib/apt/lists/partial /var/lib/dpkg && " +
            "if [ ! -f /var/lib/dpkg/status ]; then touch /var/lib/dpkg/status; fi && " +
            "apt update --allow-unauthenticated", 60, callback);

        if (!result.success) {
            if (callback != null) callback.onError("软件源更新失败: " + result.stderr);
            return true;
        }
        if (callback != null) callback.onProgress("软件源更新完成");
        return false;
    }

    /** Step 3: apt install dependencies inside chroot */
    public static boolean aptInstallDeps(ProgressCallback callback) {
        // Ubuntu 24.04 ships Python 3.12 natively (meets AstrBot's 3.12+ requirement).
        // No deadsnakes PPA needed. Check each dependency group before installing.

        String[] depChecks = {
            "python3 --version",                           // Python 3
            "python3 -m venv --help >/dev/null 2>&1",     // python3-venv
            "pip3 --version",                              // pip
            "git --version",                               // git
            "curl --version",                              // curl
            "ssh -V 2>&1",                                 // openssh-server
            "gpgv --version 2>&1",                         // gpgv
            "locale -a 2>/dev/null | grep -q en_US",      // locales
        };

        String[] depNames = {
            "Python 3", "python3-venv", "pip3", "git", "curl", "SSH", "gpgv", "locales"
        };

        boolean allPresent = true;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < depChecks.length; i++) {
            CommandResult check = execInChroot(depChecks[i] + " && echo ok", 10);
            if (!check.success || !check.stdout.trim().endsWith("ok")) {
                allPresent = false;
                missing.append(depNames[i]).append(" ");
            }
        }

        if (allPresent) {
            if (callback != null) callback.onProgress("所有系统依赖已存在，跳过安装");
            return false;
        }

        if (callback != null) callback.onProgress("缺少依赖: " + missing.toString().trim() + "，正在安装...");

        // Kill stale apt/dpkg processes and clean locks
        execInChroot(
            "pkill -9 apt 2>/dev/null; pkill -9 dpkg 2>/dev/null; " +
            "rm -f /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock 2>/dev/null; " +
            "dpkg --configure -a 2>/dev/null; sleep 1; echo locks_cleaned", 15);

        CommandResult result = execInChrootWithProgress(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "apt install -y --allow-unauthenticated " +
            "python3 python3-venv python3-pip python3-dev " +
            "git curl wget gpgv coreutils procps openssh-server " +
            "ca-certificates software-properties-common locales build-essential", 60, callback);

        if (!result.success) {
            if (callback != null) callback.onError("依赖安装失败: " + result.stderr);
            return true;
        }

        // Ensure locale and SSH config are correct
        execInChroot("locale-gen en_US.UTF-8", 30);
        execInChroot(
            "mkdir -p /run/sshd && " +
            "sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config && " +
            "sed -i 's/#PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config", 15);

        if (callback != null) callback.onProgress("依赖安装完成 (Python 3.12+)");
        return false;
    }

    /** Step 4: clone AstrBot inside chroot from GitHub */
    public static boolean cloneAstrBot(ProgressCallback callback) {
        return cloneAstrBot(callback, null);
    }

    /** Step 4: clone AstrBot inside chroot from GitHub
     *  @param version git tag/branch to checkout (null = latest main)
     */
    public static boolean cloneAstrBot(ProgressCallback callback, String version) {
        return cloneAstrBotWithProxy(callback, version, GitHubProxyManager.getBestProxy());
    }

    /** Step 4: clone AstrBot with a specific proxy index */
    public static boolean cloneAstrBotWithProxy(ProgressCallback callback, String version, int proxyIndex) {
        // If marker says AstrBot is installed, skip
        if (isAstrBotInstalled()) {
            if (callback != null) callback.onProgress("AstrBot 已安装，跳过克隆");
            return false;
        }

        // Remove old directory if it exists (incomplete install, etc.)
        execInChroot("rm -rf /root/astrbot", 30);

        if (callback != null) {
            if (version != null && !version.isEmpty()) {
                callback.onProgress("正在克隆 AstrBot (" + version + ")...");
            } else {
                callback.onProgress("正在克隆 AstrBot (最新版)...");
            }
        }

        String cloneCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "export GIT_TERMINAL_PROMPT=0 && " +
            "git clone --depth 1";

        if (version != null && !version.isEmpty()) {
            cloneCmd += " --branch " + version;
        }

        // Use specified proxy for GitHub access
        String repoUrl = GitHubProxyManager.buildUrl("https://github.com/AstrBotDevs/AstrBot.git", proxyIndex);
        cloneCmd += " " + repoUrl + " /root/astrbot";

        CommandResult result = execInChrootWithProgress(cloneCmd, 60, callback);

        if (!result.success) {
            if (callback != null) callback.onError("AstrBot 克隆失败: " + result.stderr);
            return true;
        }
        if (callback != null) callback.onProgress("AstrBot 克隆完成");
        return false;
    }

    /** Step 5: pip install AstrBot dependencies */
    public static boolean pipInstallDeps(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在创建 Python 虚拟环境...");

        // Ubuntu 24.04 enforces PEP 668 — cannot pip install system-wide.
        // Create a venv at /root/astrbot/venv and install deps there.
        CommandResult venvResult = execInChrootWithProgress(
            "python3 -m venv /root/astrbot/venv && " +
            "/root/astrbot/venv/bin/pip install --upgrade pip", 60, callback);

        if (!venvResult.success) {
            if (callback != null) callback.onError("虚拟环境创建失败: " + venvResult.stderr);
            return true;
        }

        if (callback != null) callback.onProgress("正在安装 Python 依赖...");

        CommandResult result = execInChrootWithProgress(
            "export PATH=/root/astrbot/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "cd /root/astrbot && pip install -r requirements.txt", 300, callback);

        if (!result.success) {
            if (callback != null) callback.onError("Python 依赖安装失败: " + result.stderr);
            return true;
        }

        // Mark as installed
        execRoot("touch " + RbotConstants.ASTRBOT_MARKER);
        if (callback != null) callback.onProgress("Python 依赖安装完成");
        return false;
    }

    /**
     * Unmount bind-mounted device nodes, filesystems from chroot.
     */
    public static void cleanupChrootDevices() {
        String D = RbotConstants.CHROOT_DIR;
        execRoot(
            "umount " + D + "/sys 2>/dev/null; " +
            "umount " + D + "/proc 2>/dev/null; " +
            "umount " + D + "/dev/pts 2>/dev/null; " +
            "umount " + D + "/dev 2>/dev/null; " +
            "umount " + D + "/tmp 2>/dev/null; " +
            "echo cleanup_done", 15);
    }

    // ─── AstrBot lifecycle ───

    /** Start AstrBot inside chroot in background */
    public static CommandResult startAstrBot() {
        synchronized (sChrootLock) {
            // Ensure device nodes are mounted (may be lost after Android reboot)
            setupChrootDevices(null);

            // Kill any existing process first
            stopAstrBot();

        // Use venv python if available (PEP 668 on Ubuntu 24.04), fallback to system python3
        // Use setsid so the Python process becomes a proper daemon and $! gives
        // the Python PID (not nohup's PID, which would exit immediately).
        String pythonBin = "python3"; // fallback
        CommandResult venvCheck = execInChroot("test -f /root/astrbot/venv/bin/python3 && echo venv_ok", 5);
        if (venvCheck.success() && venvCheck.stdout().trim().equals("venv_ok")) {
            pythonBin = "/root/astrbot/venv/bin/python3";
        }

        String chrootBashCmd =
            "cd /root/astrbot && " +
            "setsid " + pythonBin + " main.py > /root/astrbot/astrbot.log 2>&1 & " +
            "echo $! > /root/astrbot/astrbot.pid && " +
            "sleep 5 && " +
            "if kill -0 $(cat /root/astrbot/astrbot.pid) 2>/dev/null; then " +
            "  echo started; " +
            "else " +
            "  cat /root/astrbot/astrbot.log 2>/dev/null; " +
            "  echo 'AstrBot failed to start'; exit 1; " +
            "fi";

            return execInChroot(chrootBashCmd, 30);
        }
    }

    /** Stop AstrBot - kills from both inside and outside chroot */
    public static CommandResult stopAstrBot() {
        // First try to stop from outside chroot (in case chroot is broken)
        // Kill by PID file content (read from outside)
        execRoot(
            "PIDFILE=" + RbotConstants.CHROOT_DIR + "/root/astrbot/astrbot.pid; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -9 $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "echo host_killed", 10);

        // Also try pkill from host side
        execRoot("pkill -9 -f 'python3.*main.py' 2>/dev/null || true; echo pkill_done", 5);

        // Then try from inside chroot as backup
        String stopCmd =
            "PIDFILE=/root/astrbot/astrbot.pid; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -9 $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "if which pkill >/dev/null 2>&1; then " +
            "  pkill -9 -f 'python3.*main.py' 2>/dev/null || true; " +
            "else " +
            "  for pid in $(ps -eo pid,cmd 2>/dev/null | grep 'python3.*main.py' | grep -v grep | awk '{print $1}'); do " +
            "    kill -9 $pid 2>/dev/null || true; " +
            "  done; " +
            "fi; " +
            "sleep 1; " +
            "echo stopped";
        return execInChroot(stopCmd, 15);
    }

    /** Check if AstrBot is running */
    public static boolean isAstrBotRunning() {
        CommandResult result = execInChroot(
            "PIDFILE=/root/astrbot/astrbot.pid; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -0 $SPECPID 2>/dev/null && { echo running; exit 0; }; " +
            "fi; " +
            "if which pgrep >/dev/null 2>&1; then " +
            "  pgrep -f 'python3.*main.py' >/dev/null 2>&1 && { echo running; exit 0; }; " +
            "else " +
            "  ps -eo pid,cmd 2>/dev/null | grep 'python3.*main.py' | grep -v grep >/dev/null 2>&1 && { echo running; exit 0; }; " +
            "fi; " +
            "echo stopped", 10);
        return result.success() && result.stdout().trim().equals("running");
    }

    /**
     * Result of a batched status check.
     */
    public record FullStatus(
        boolean rootAvailable,
        boolean rootfsReady,
        boolean chrootMounted,
        boolean astrBotInstalled,
        boolean astrBotRunning
    ) {}

    /** Check if chroot filesystems are mounted (proc/sys/dev/pts) */
    public static boolean isChrootMounted() {
        // Check if /proc in chroot is mounted (simple check)
        CommandResult result = execRoot(
            "mount | grep -q '" + RbotConstants.CHROOT_DIR + "/proc ' && echo mounted || echo not_mounted", 5);
        return result.success() && result.stdout().trim().equals("mounted");
    }

    /**
     * Get all status values in a single su call.
     */
    public static FullStatus getFullStatus() {
        CommandResult idResult = execRoot("id", 5);
        boolean rootAvailable = idResult.success() && idResult.stdout().contains("uid=0");
        boolean rootfsReady = isRootfsReady();
        boolean chrootMounted = isChrootMounted();
        boolean astrBotInstalled = isAstrBotInstalled();
        boolean astrBotRunning = false;
        if (rootfsReady && astrBotInstalled) {
            CommandResult runningResult = execInChroot(
                "if [ -f /root/astrbot/astrbot.pid ] && kill -0 $(cat /root/astrbot/astrbot.pid) 2>/dev/null; then " +
                "  echo running; " +
                "elif pgrep -f 'python3 main.py' >/dev/null 2>&1; then " +
                "  echo running; " +
                "else " +
                "  echo stopped; " +
                "fi", 10);
            astrBotRunning = runningResult.success() && runningResult.stdout().trim().equals("running");
        }
        return new FullStatus(rootAvailable, rootfsReady, chrootMounted, astrBotInstalled, astrBotRunning);
    }

    // ─── Backup & Restore ───

    /**
     * Create a backup of AstrBot data directory.
     * Backup: /sdcard/rbot/backups/astrbot_data_backup/{timestamp}/
     * @param callback Progress callback for UI updates
     * @return Backup directory path on success, null on failure
     */
    public static String backupAstrBotData(ProgressCallback callback) {
        if (callback != null) callback.onProgress("准备备份...");

        // Ensure chroot environment is set up (mounts /dev, /dev/pts, /proc, /sys, /tmp)
        setupChrootEnvironment(callback);

        // Ensure backup directory exists
        CommandResult mkdirResult = execRoot("mkdir -p " + RbotConstants.BACKUP_DIR);
        if (!mkdirResult.success) {
            if (callback != null) callback.onError("无法创建备份目录");
            return null;
        }

        // Generate timestamped backup directory
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String backupDir = RbotConstants.BACKUP_DIR + "/astrbot_data_" + timestamp;

        if (callback != null) callback.onProgress("正在复制 data 目录...");

        // Direct cp -r from chroot data to backup dir (no tar compression)
        CommandResult cpResult = execRoot(
            "cp -r " + RbotConstants.ASTRBOT_HOME + "/data '" + backupDir + "'");

        if (!cpResult.success) {
            if (callback != null) callback.onError("复制失败: " + cpResult.stderr);
            return null;
        }

        if (callback != null) callback.onProgress("备份完成: " + backupDir);
        return backupDir;
    }

    /**
     * Restore AstrBot data from a backup directory.
     * @param backupDir Full path to the backup directory
     * @param callback Progress callback for UI updates
     * @return true on failure, false on success
     */
    public static boolean restoreAstrBotData(String backupDir, ProgressCallback callback) {
        if (callback != null) callback.onProgress("检查备份...");

        // Verify backup directory exists
        CommandResult checkResult = execRoot("test -d '" + backupDir + "' && echo exists");
        if (!checkResult.success || !checkResult.stdout.trim().equals("exists")) {
            if (callback != null) callback.onError("备份目录不存在");
            return true;
        }

        if (callback != null) callback.onProgress("停止 AstrBot...");
        stopAstrBot();

        if (callback != null) callback.onProgress("正在恢复 data 目录...");

        // backupDir IS the data directory — copy its contents into ASTRBOT_HOME/data/
        CommandResult cpResult = execRoot(
            "rm -rf " + RbotConstants.ASTRBOT_HOME + "/data && cp -r -f '" + backupDir + "' " + RbotConstants.ASTRBOT_HOME + "/data");

        if (!cpResult.success) {
            if (callback != null) callback.onError("恢复失败: " + cpResult.stderr);
            return true;
        }

        if (callback != null) callback.onProgress("恢复完成");
        return false;
    }

    /**
     * List all available backup directories.
     * @return Array of backup directory paths, sorted by modification time (newest first)
     */
    public static String[] listBackups() {
        CommandResult result = execRoot(
            "ls -1dt " + RbotConstants.BACKUP_DIR + "/astrbot_data_* 2>/dev/null || echo none");

        if (!result.success || result.stdout.trim().equals("none")) {
            return new String[0];
        }

        return result.stdout.trim().split("\n");
    }

    /**
     * Delete a backup file.
     * @param backupPath Full path to the backup file
     * @return true on success
     */
    public static boolean deleteBackup(String backupPath) {
        CommandResult result = execRoot("rm -f '" + backupPath + "'");
        return result.success;
    }

    // ─── SSH Service ───

    /** Start SSH service in chroot */
    public static CommandResult startSshService() {
        // Ensure ssh directory exists with proper permissions
        execInChroot("mkdir -p /var/run/sshd && chmod 755 /var/run/sshd", 5);
        
        // Start sshd
        String startCmd = "/usr/sbin/sshd -D &";
        return execInChroot(startCmd, 10);
    }

    /** Stop SSH service */
    public static CommandResult stopSshService() {
        String stopCmd = "if pgrep -x sshd >/dev/null 2>&1; then pkill -x sshd && echo stopped; else echo not_running; fi";
        return execInChroot(stopCmd, 10);
    }

    /** Check if SSH service is running */
    public static boolean isSshRunning() {
        CommandResult result = execInChroot("pgrep -x sshd >/dev/null 2>&1 && echo running || echo stopped", 5);
        return result.success && result.stdout.trim().equals("running");
    }

    /** Get SSH connection info: root@IP */
    public static String getSshInfo() {
        // Get LAN IP address - try multiple Android-compatible methods
        String ip = "127.0.0.1";
        CommandResult ipResult = execRoot(
            "ifconfig wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d: -f2 || " +
            "ip addr show wlan0 2>/dev/null | grep -oP 'inet \\K[0-9.]+' || " +
            "ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \\K[0-9.]+' || " +
            "ip route get 8.8.8.8 2>/dev/null | grep -oP 'src \\K[0-9.]+' || " +
            "getprop dhcp.wlan0.ipaddress 2>/dev/null || " +
            "echo 127.0.0.1", 5);
        if (ipResult.success() && !ipResult.stdout().trim().isEmpty()) {
            String detected = ipResult.stdout().trim();
            // Reject loopback/empty
            if (!detected.equals("127.0.0.1") && !detected.equals("::1") && detected.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
                ip = detected;
            }
        }

        return "root@" + ip;
    }

    /** Get root password from chroot (stored in /root/.rbot_pass) */
    public static String getRootPassword() {
        CommandResult result = execInChroot("cat /root/.rbot_pass 2>/dev/null || echo ''", 5);
        return result.success() ? result.stdout().trim() : "";
    }

    /** Set root password in chroot */
    public static boolean setRootPassword(String password) {
        if (password == null || password.isEmpty()) return false;
        // Save to file for app to read
        CommandResult saveResult = execInChroot(
            "echo '" + password.replace("'", "'\"'\"'") + "' > /root/.rbot_pass && chmod 600 /root/.rbot_pass", 5);
        // Set actual system password
        CommandResult passResult = execInChroot(
            "echo 'root:" + password.replace("'", "'\"'\"'") + "' | chpasswd", 5);
        return saveResult.success() && passResult.success();
    }

    // ─── Progress callback ───

    public interface ProgressCallback {
        void onProgress(String message);
        void onError(String error);
    }

    // ─── Internal: process execution ───

    @SuppressLint("NewApi")  // waitFor(timeout, TimeUnit) and destroyForcibly() require API 26
    private static CommandResult exec(String[] command, int timeoutSec) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try {
            Log.d(TAG, "Executing: " + String.join(" ", command));

            final Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

            // Adaptive timeout: reset idle timer on any output
            final long[] lastActivity = {System.currentTimeMillis()};
            final long startTime = System.currentTimeMillis();
            final long maxWallTime = timeoutSec * 3 * 1000L;
            final long idleTimeout = timeoutSec * 1000L;

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stdout read error: " + e.getMessage());
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stderr read error: " + e.getMessage());
                }
            });

            stdoutThread.start();
            stderrThread.start();

            // Wait with adaptive timeout: reset on any output
            while (process.isAlive()) {
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                long idle = now - lastActivity[0];

                if (elapsed > maxWallTime) {
                    Log.w(TAG, "Process exceeded max wall time (" + (maxWallTime/1000) + "s), killing");
                    process.destroyForcibly();
                    return new CommandResult(false, stdout.toString(),
                        "命令超过最大运行时间 (" + (maxWallTime/1000) + "s)", -1);
                }

                if (idle > idleTimeout) {
                    Log.w(TAG, "Process idle for " + (idle/1000) + "s, killing");
                    process.destroyForcibly();
                    return new CommandResult(false, stdout.toString(),
                        "命令超时 (" + (idle/1000) + "s 无输出)", -1);
                }

                Thread.sleep(500);
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, stdout.toString(),
                stderr.toString(), exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + e.getMessage());
            return new CommandResult(false, stdout.toString(), e.getMessage(), -1);
        }
    }

    /** Quote a string for safe inclusion in a bash -c argument */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ─── Internal: process execution with real-time progress ───

    /**
     * Execute a command as root and push real-time progress lines to callback.
     * Reads both stdout and stderr, pushes meaningful lines via
     * {@link ProgressCallback#onProgress}, and resets idle timer on any output.
     */
    @SuppressLint("NewApi")
    private static CommandResult execRootWithProgress(String command, int timeoutSec, ProgressCallback callback) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try {
            Log.d(TAG, "Executing (progress): " + command);

            final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start();

            // Track last activity time — reset on each progress line
            final long[] lastActivity = {System.currentTimeMillis()};
            final long startTime = System.currentTimeMillis();
            // Max total wall time: timeoutSec * 3 (generous upper bound)
            final long maxWallTime = timeoutSec * 3 * 1000L;
            // Idle timeout: timeoutSec (no output for this long = truly stuck)
            final long idleTimeout = timeoutSec * 1000L;

            // Read stdout in background — also push progress lines to callback
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                        // Push progress lines from stdout too (pip, cp -v, etc.)
                        if (callback != null && isProgressLine(line)) {
                            callback.onProgress(line.trim());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stdout read error: " + e.getMessage());
                }
            });

            // Read stderr in background — push progress lines to callback, reset activity timer
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                        // Push meaningful progress lines to callback
                        if (callback != null && isProgressLine(line)) {
                            callback.onProgress(line.trim());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stderr read error: " + e.getMessage());
                }
            });

            stdoutThread.start();
            stderrThread.start();

            // Wait for process with adaptive timeout:
            // - If the process is still producing output, keep waiting (up to maxWallTime)
            // - If no output for idleTimeout, treat as stuck and kill
            while (process.isAlive()) {
                long now = System.currentTimeMillis();
                long elapsed = now - startTime;
                long idle = now - lastActivity[0];

                if (elapsed > maxWallTime) {
                    // Total wall time exceeded — hard kill
                    Log.w(TAG, "Process exceeded max wall time (" + (maxWallTime/1000) + "s), killing");
                    process.destroyForcibly();
                    return new CommandResult(false, stdout.toString(),
                        "命令超过最大运行时间 (" + (maxWallTime/1000) + "s)", -1);
                }

                if (idle > idleTimeout) {
                    // No output for idleTimeout — process is stuck
                    Log.w(TAG, "Process idle for " + (idle/1000) + "s, killing");
                    process.destroyForcibly();
                    return new CommandResult(false, stdout.toString(),
                        "命令超时 (" + (idle/1000) + "s 无输出)", -1);
                }

                Thread.sleep(1000); // Check every second
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, stdout.toString(),
                stderr.toString(), exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + e.getMessage());
            return new CommandResult(false, stdout.toString(), e.getMessage(), -1);
        }
    }

    /** Execute a chroot command with real-time progress (package-visible for HermesAdapter) */
    static CommandResult execInChrootWithProgress(String command, int timeoutSec, ProgressCallback callback) {
        String chrootCmd = "chroot " + RbotConstants.CHROOT_DIR +
            " /bin/bash -c " +
            shellQuote(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "unset ANDROID_ROOT && " +
                "export TMPDIR=/tmp && " +
                "export TEMP=/tmp && " +
                "export TMP=/tmp && " +
                "export XDG_CACHE_HOME=/root/.cache && " +
                "export XDG_CONFIG_HOME=/root/.config && " +
                "export XDG_DATA_HOME=/root/.local/share && " +
                "export XDG_STATE_HOME=/root/.local/state && " +
                command
            );
        return execRootWithProgress(chrootCmd, timeoutSec, callback);
    }

    /**
     * Filter stderr lines to identify progress-worthy output.
     * Shows most meaningful output, only skips truly noisy/repetitive lines.
     */
    private static boolean isProgressLine(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        String l = line.trim();
        // Skip very short lines (likely noise like single chars)
        if (l.length() < 3) return false;
        // Skip common noise patterns
        if (l.startsWith("debconf:")) return false;
        // Skip pip's "Requirement already satisfied" spam (too many lines)
        if (l.startsWith("Requirement already satisfied:")) return false;
        // Skip empty progress dots / carriage returns
        if (l.equals(".") || l.equals("..") || l.equals("...")) return false;
        // Show everything else — it's better to see too much than nothing at all
        return true;
    }

    /**
     * Build the appropriate tar extract command based on the tarball's
     * actual compression format (detected via `file` command).
     * Uses -v (verbose) for progress since Android toybox tar lacks --checkpoint.
     */
    private static String buildTarExtractCommand(String tarballPath, String stagingDir) {
        CommandResult detect = execRoot("file '" + tarballPath + "'", 10);
        String info = detect.success ? detect.stdout.toLowerCase() : "";

        String tarBase;
        if (info.contains("gzip") || info.contains("zlib")) {
            tarBase = "tar -xzf '" + tarballPath + "'";
        } else if (info.contains("xz")) {
            tarBase = "tar -xf '" + tarballPath + "'";
        } else if (info.contains("zstd")) {
            tarBase = "tar -I zstd -xf '" + tarballPath + "'";
        } else {
            tarBase = "tar -xf '" + tarballPath + "'";
        }

        // Android toybox tar does not support --checkpoint; use -v for progress instead
        return tarBase + " -v -C " + stagingDir;
    }
}
