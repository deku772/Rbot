package app.andbott;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Manages chroot lifecycle for BotDrop: root command execution,
 * rootfs extraction, AstrBot installation, and chroot command execution.

 * All shell commands go through `su -c` for root access.
 */
@SuppressWarnings({"SpellCustomInspection", "unused"})
public final class ChrootManager {

    private static final String TAG = "ChrootManager";
    private static final int DEFAULT_TIMEOUT_SEC = 60;
    /** Reentrant lock to prevent concurrent chroot device setup / AstrBot start */
    private static final Object sChrootLock = new Object();

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
        String chrootCmd = "chroot " + BotDropConstants.CHROOT_DIR +
            " /bin/bash -c " +
            shellQuote(
                // Explicit PATH so all commands (nohup, python3, pkill, pgrep, etc.)
                // are found regardless of what the chroot's /etc/profile sets.
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                // Unset ANDROID_ROOT so Python platformdirs doesn't think
                // we're in an Android app environment (it would try jnius
                // and crash with "Cannot find path to android app folder").
                "unset ANDROID_ROOT && " +
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
        return new File(BotDropConstants.ROOTFS_MARKER).exists()
            && new File(BotDropConstants.CHROOT_DIR + "/bin/bash").exists();
    }

    /** Check if AstrBot is installed (marker file at chroot root, readable by app process) */
    public static boolean isAstrBotInstalled() {
        return new File(BotDropConstants.ASTRBOT_MARKER).exists();
    }

    /** Create /data/botdrop directory with proper permissions */
    public static boolean ensureChrootDir() {
        CommandResult result = execRoot(
            "mkdir -p " + BotDropConstants.CHROOT_DIR + " && " +
            "chmod 755 " + BotDropConstants.CHROOT_DIR
        );
        return !result.success;
    }

    /** Extract rootfs from tarball to /data/botdrop */
    public static boolean extractRootfs(String tarballPath, ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在解压系统镜像...");

        String stagingDir = BotDropConstants.CHROOT_DIR + "_staging";
        String tarCmd = buildTarExtractCommand(tarballPath, stagingDir);

        if (callback != null) callback.onProgress("正在提取到暂存区...");

        // Unmount bind-mounted devices before cleaning dirs (avoid deleting host /dev/null etc.)
        cleanupChrootDevices();

        // Clean staging AND chroot dir (chroot may have partial files from a previous failed run)
        execRoot("rm -rf " + stagingDir + " && rm -rf " + BotDropConstants.CHROOT_DIR + " && mkdir -p " + BotDropConstants.CHROOT_DIR + " " + stagingDir);

        // Step 1: extract to staging
        CommandResult result = execRoot(tarCmd, 600);
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
        CommandResult copyResult = execRoot(
            "cp -aL " + stagingDir + "/. " + BotDropConstants.CHROOT_DIR + "/ 2>&1; true",
            600);

        // Clean up staging
        execRoot("rm -rf " + stagingDir);

        // Verify critical files exist rather than relying on cp exit code
        CommandResult verify = execRoot(
            "test -f " + BotDropConstants.CHROOT_DIR + "/bin/bash && " +
            "test -f " + BotDropConstants.CHROOT_DIR + "/usr/bin/env && " +
            "echo verify_ok", 10);

        if (!verify.success) {
            if (callback != null) callback.onError("同步失败(关键文件缺失): " + copyResult.stderr);
            return true;
        }

        if (!copyResult.stderr.isEmpty()) {
            if (callback != null) callback.onProgress("同步完成(部分损坏符号链接已跳过)");
        }

        // Create marker
        execRoot("touch " + BotDropConstants.ROOTFS_MARKER);

        if (callback != null) callback.onProgress("系统镜像解压完成");
        return false;
    }

    /**
     * Set up essential device nodes, filesystem mounts inside the chroot.
     * Must be called after extractRootfs and before any chroot command.
     * <p>
     * Strategy: use {@code mount --bind} for device nodes to avoid SELinux denials
     * that block mknod-created nodes. Also mount /proc, /sys, /dev/pts which are
     * required by apt, git, and most Linux tools.
     */
    public static void setupChrootDevices(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在初始化 chroot 环境...");

        String D = BotDropConstants.CHROOT_DIR;

        // Step 1: Clean up stale symlinks/files, create mount point directories
        String prepCmd =
            "rm -rf " + D + "/dev/null " + D + "/dev/zero " +
                       D + "/dev/random " + D + "/dev/urandom " +
                       D + "/dev/tty " +
                       D + "/dev/pts " + D + "/proc " + D + "/sys 2>/dev/null; " +
            "mkdir -p " + D + "/dev " + D + "/dev/pts " +
                         D + "/proc " + D + "/sys; " +
            // Create empty regular files as mount --bind targets
            "touch " + D + "/dev/null " + D + "/dev/zero " +
                   D + "/dev/random " + D + "/dev/urandom " +
                   D + "/dev/tty; " +
            "echo prep_done";

        execRoot(prepCmd, 15);

        // Step 2: Bind-mount host device nodes into chroot
        String bindCmd =
            "mount --bind /dev/null    " + D + "/dev/null; " +
            "mount --bind /dev/zero    " + D + "/dev/zero; " +
            "mount --bind /dev/random  " + D + "/dev/random; " +
            "mount --bind /dev/urandom " + D + "/dev/urandom; " +
            "mount --bind /dev/tty     " + D + "/dev/tty; " +
            "echo bind_done";

        execRoot(bindCmd, 15);

        // Step 3: Mount /dev/pts — try bind first (SELinux friendly), fall back to new mount
        String ptsResult = execRoot(
            "mount --bind /dev/pts " + D + "/dev/pts 2>/dev/null || " +
            "mount -t devpts devpts " + D + "/dev/pts 2>/dev/null; " +
            "echo pts_done", 15).stdout.trim();

        // Step 4: Mount /proc and /sys (essential for apt, ps, network info, etc.)
        String fsCmd =
            "mount -t proc proc " + D + "/proc; " +
            "mount -t sysfs sysfs " + D + "/sys; " +
            "echo fs_done";

        execRoot(fsCmd, 15);

        // Step 5: Copy host's DNS config into chroot (essential for apt/git networking)
        execRoot(
            "rm -f " + D + "/etc/resolv.conf 2>/dev/null; " +
            "cp /etc/resolv.conf " + D + "/etc/resolv.conf 2>/dev/null || " +
            "echo 'nameserver 8.8.8.8' > " + D + "/etc/resolv.conf; " +
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
        return false;  // setupChrootDevices doesn't return failure
    }

    /** Step 2: apt update inside chroot */
    public static boolean aptUpdate(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在更新软件源...");

        CommandResult result = execInChroot(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "mkdir -p /var/lib/apt/lists/partial /var/lib/dpkg && " +
            "if [ ! -f /var/lib/dpkg/status ]; then touch /var/lib/dpkg/status; fi && " +
            "apt update --allow-unauthenticated", 120);

        if (!result.success) {
            if (callback != null) callback.onError("软件源更新失败: " + result.stderr);
            return true;
        }
        if (callback != null) callback.onProgress("软件源更新完成");
        return false;
    }

    /** Step 3: apt install dependencies inside chroot */
    public static boolean aptInstallDeps(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在安装依赖 (python3, pip, git, curl, gpgv)...");

        CommandResult result = execInChroot(
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "apt install -y --allow-unauthenticated python3 python3-pip git curl gpgv coreutils procps", 300);

        if (!result.success) {
            if (callback != null) callback.onError("依赖安装失败: " + result.stderr);
            return true;
        }
        if (callback != null) callback.onProgress("依赖安装完成");
        return false;
    }

    /** Step 4: clone AstrBot inside chroot from GitHub */
    public static boolean cloneAstrBot(ProgressCallback callback) {
        // Already cloned? Skip.
        CommandResult check = execInChroot(
            "test -d /root/astrbot && test -f /root/astrbot/main.py && echo exists", 10);

        if (check.success && check.stdout.trim().equals("exists")) {
            if (callback != null) callback.onProgress("AstrBot 已存在，跳过克隆");
            return false;
        }

        if (callback != null) callback.onProgress("正在克隆 AstrBot...");

        CommandResult result = execInChroot(
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "export GIT_TERMINAL_PROMPT=0 && " +
            "git clone --depth 1 https://github.com/AstrBotDevs/AstrBot.git /root/astrbot", 300);

        if (!result.success) {
            if (callback != null) callback.onError("AstrBot 克隆失败: " + result.stderr);
            return true;
        }
        if (callback != null) callback.onProgress("AstrBot 克隆完成");
        return false;
    }

    /** Step 5: pip install AstrBot dependencies */
    public static boolean pipInstallDeps(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在安装 Python 依赖...");

        CommandResult result = execInChroot(
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            "cd /root/astrbot && pip install -r requirements.txt", 300);

        if (!result.success) {
            if (callback != null) callback.onError("Python 依赖安装失败: " + result.stderr);
            return true;
        }

        // Mark as installed
        execRoot("touch " + BotDropConstants.ASTRBOT_MARKER);
        if (callback != null) callback.onProgress("Python 依赖安装完成");
        return false;
    }

    /**
     * Unmount bind-mounted device nodes, filesystems from chroot.
     * Call before stopping or re-extracting rootfs to avoid stale mounts.
     * Unmount in reverse order: filesystems first, then devices.
     */
    public static void cleanupChrootDevices() {
        String D = BotDropConstants.CHROOT_DIR;
        execRoot(
            "umount " + D + "/sys 2>/dev/null; " +
            "umount " + D + "/proc 2>/dev/null; " +
            "umount " + D + "/dev/pts 2>/dev/null; " +
            "umount " + D + "/dev/tty 2>/dev/null; " +
            "umount " + D + "/dev/urandom 2>/dev/null; " +
            "umount " + D + "/dev/random 2>/dev/null; " +
            "umount " + D + "/dev/zero 2>/dev/null; " +
            "umount " + D + "/dev/null 2>/dev/null; " +
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

        // Use python3 directly (uv may not be installed in chroot;
        // dependencies are already installed via pip in installAstrBot)
        // Use setsid so the Python process becomes a proper daemon and $! gives
        // the Python PID (not nohup's PID, which would exit immediately).
        String chrootBashCmd =
            "cd /root/astrbot && " +
            "setsid python3 main.py > /root/astrbot/astrbot.log 2>&1 & " +
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

    /** Stop AstrBot */
    public static CommandResult stopAstrBot() {
        // Try pid file first, then fall back to ps+pkill
        String stopCmd =
            "PIDFILE=/root/astrbot/astrbot.pid; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "if which pkill >/dev/null 2>&1; then " +
            "  pkill -f 'python3.*main.py' 2>/dev/null || true; " +
            "else " +
            "  for pid in $(ps -eo pid,cmd 2>/dev/null | grep 'python3.*main.py' | grep -v grep | awk '{print $1}'); do " +
            "    kill $pid 2>/dev/null || true; " +
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
        return result.success && result.stdout.trim().equals("running");
    }

    /**
     * Result of a batched status check.
     * All boolean fields are false on failure.
     */
    public record FullStatus(
        boolean rootAvailable,
        boolean rootfsReady,
        boolean astrBotInstalled,
        boolean astrBotRunning
    ) {}

    /**
     * Get all status values in a single su call — avoids thread explosion
     * from repeated separate su invocations.
     */
    public static FullStatus getFullStatus() {
        CommandResult idResult = execRoot("id", 5);
        boolean rootAvailable = idResult.success && idResult.stdout.contains("uid=0");
        boolean rootfsReady = isRootfsReady();
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
            astrBotRunning = runningResult.success && runningResult.stdout.trim().equals("running");
        }
        return new FullStatus(rootAvailable, rootfsReady, astrBotInstalled, astrBotRunning);
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

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
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
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stderr read error: " + e.getMessage());
                }
            });

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                }
                return new CommandResult(false, stdout.toString(),
                    "Command timed out after " + timeoutSec + "s", -1);
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

    /**
     * Build the appropriate tar extract command based on the tarball's
     * actual compression format (detected via `file` command).
     */
    private static String buildTarExtractCommand(String tarballPath, String stagingDir) {
        CommandResult detect = execRoot("file '" + tarballPath + "'", 10);
        String info = detect.success ? detect.stdout.toLowerCase() : "";

        if (info.contains("gzip") || info.contains("zlib")) {
            return "tar -xzf '" + tarballPath + "' -C " + stagingDir;
        } else if (info.contains("xz")) {
            return "tar -xf '" + tarballPath + "' -C " + stagingDir;
        } else if (info.contains("zstd")) {
            return "tar -I zstd -xf '" + tarballPath + "' -C " + stagingDir;
        } else {
            return "tar -xf '" + tarballPath + "' -C " + stagingDir;
        }
    }
}
