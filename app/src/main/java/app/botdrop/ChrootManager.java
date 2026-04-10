package app.botdrop;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Manages chroot lifecycle for BotDrop: root command execution,
 * rootfs extraction, AstrBot installation, and chroot command execution.
 *
 * All shell commands go through `su -c` for root access.
 */
public final class ChrootManager {

    private static final String TAG = "ChrootManager";
    private static final int DEFAULT_TIMEOUT_SEC = 60;

    private ChrootManager() {}

    // ─── Command result ───

    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    // ─── Root command execution ───

    /** Execute a command as root via `su -c` */
    public static CommandResult execRoot(String command) {
        return execRoot(command, DEFAULT_TIMEOUT_SEC);
    }

    /** Execute a command as root with custom timeout */
    public static CommandResult execRoot(String command, int timeoutSec) {
        return exec(new String[]{"su", "-c", command}, timeoutSec);
    }

    /** Execute a command inside the chroot via `su -c chroot /data/botdrop /bin/bash -c "..."` */
    public static CommandResult execInChroot(String command) {
        return execInChroot(command, DEFAULT_TIMEOUT_SEC);
    }

    /** Execute a command inside the chroot with custom timeout */
    public static CommandResult execInChroot(String command, int timeoutSec) {
        String chrootCmd = "chroot " + BotDropConstants.CHROOT_DIR + " /bin/bash -c " +
            shellQuote(command);
        return execRoot(chrootCmd, timeoutSec);
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

    /** Check if AstrBot is installed */
    public static boolean isAstrBotInstalled() {
        return new File(BotDropConstants.ASTRBOT_MARKER).exists()
            && new File(BotDropConstants.ASTRBOT_HOME).isDirectory();
    }

    /** Create /data/botdrop directory with proper permissions */
    public static boolean ensureChrootDir() {
        CommandResult result = execRoot(
            "mkdir -p " + BotDropConstants.CHROOT_DIR + " && " +
            "chmod 755 " + BotDropConstants.CHROOT_DIR
        );
        return result.success;
    }

    /** Extract rootfs from tarball to /data/botdrop */
    public static boolean extractRootfs(String tarballPath, ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在解压系统镜像...");

        // Check tarball type: .tar.gz uses -xzf, .tar.xz uses -xf
        String tarCmd;
        if (tarballPath.endsWith(".tar.gz") || tarballPath.endsWith(".tgz")) {
            tarCmd = "tar -xzf '" + tarballPath + "' -C " + BotDropConstants.CHROOT_DIR;
        } else {
            tarCmd = "tar -xf '" + tarballPath + "' -C " + BotDropConstants.CHROOT_DIR;
        }

        CommandResult result = execRoot(tarCmd, 600); // 10 min timeout for extraction
        if (!result.success) {
            if (callback != null) callback.onError("解压失败: " + result.stderr);
            return false;
        }

        // Handle GitHub tar.xz which extracts into ubuntu-fs/ subdirectory
        String restructureCmd =
            "if [ -d " + BotDropConstants.CHROOT_DIR + "/ubuntu-fs ]; then " +
            "  cp -r " + BotDropConstants.CHROOT_DIR + "/ubuntu-fs/. " + BotDropConstants.CHROOT_DIR + "/ && " +
            "  rm -rf " + BotDropConstants.CHROOT_DIR + "/ubuntu-fs; " +
            "fi";
        execRoot(restructureCmd);

        // Create marker
        execRoot("touch " + BotDropConstants.ROOTFS_MARKER);

        if (callback != null) callback.onProgress("系统镜像解压完成");
        return true;
    }

    /** Install AstrBot inside chroot */
    public static boolean installAstrBot(ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在安装 AstrBot 依赖...");

        String installCmd =
            "export DEBIAN_FRONTEND=noninteractive && " +
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
            // Fix apt/dpkg metadata if missing
            "mkdir -p /var/lib/apt/lists/partial /var/lib/dpkg && " +
            "if [ ! -f /var/lib/dpkg/status ]; then touch /var/lib/dpkg/status; fi && " +
            // Install dependencies
            "apt update && apt install -y python3 python3-pip git curl && " +
            // Clone AstrBot
            "if [ ! -d /root/astrbot ]; then " +
            "  git clone https://github.com/Soulter/AstrBot.git /root/astrbot; " +
            "fi && " +
            // Install AstrBot dependencies
            "cd /root/astrbot && pip install -r requirements.txt && " +
            // Create marker
            "touch /root/astrbot/.botdrop-astrbot-ready";

        CommandResult result = execInChroot(installCmd, 600);
        if (!result.success) {
            if (callback != null) callback.onError("AstrBot 安装失败: " + result.stderr);
            return false;
        }

        if (callback != null) callback.onProgress("AstrBot 安装完成");
        return true;
    }

    // ─── AstrBot lifecycle ───

    /** Start AstrBot inside chroot in background */
    public static CommandResult startAstrBot() {
        // Kill any existing process first
        stopAstrBot();

        // Create wrapper script that runs AstrBot and saves PID
        String wrapperScript =
            "#!/bin/bash\n" +
            "cd /root/astrbot\n" +
            "nohup uv run main.py > /root/astrbot/astrbot.log 2>&1 &\n" +
            "echo $! > /root/astrbot/astrbot.pid\n";

        // Write wrapper script
        execRoot("cat > " + BotDropConstants.BOTDROP_TMP + "/astrbot-start.sh << 'EOF'\n" +
            wrapperScript + "EOF\n" +
            "chmod 755 " + BotDropConstants.BOTDROP_TMP + "/astrbot-start.sh");

        // Execute inside chroot
        String startCmd = "chroot " + BotDropConstants.CHROOT_DIR + " /bin/bash " +
            BotDropConstants.BOTDROP_TMP + "/astrbot-start.sh";

        // But wait — the script path must be accessible inside the chroot.
        // Better approach: pass the script content directly via bash -c
        String chrootBashCmd =
            "cd /root/astrbot && " +
            "nohup uv run main.py > /root/astrbot/astrbot.log 2>&1 & " +
            "echo $! > /root/astrbot/astrbot.pid && " +
            "sleep 3 && " +
            "if kill -0 $(cat /root/astrbot/astrbot.pid) 2>/dev/null; then " +
            "  echo started; " +
            "else " +
            "  echo 'AstrBot failed to start'; exit 1; " +
            "fi";

        return execInChroot(chrootBashCmd, 30);
    }

    /** Stop AstrBot */
    public static CommandResult stopAstrBot() {
        String stopCmd =
            "if [ -f /root/astrbot/astrbot.pid ]; then " +
            "  kill $(cat /root/astrbot/astrbot.pid) 2>/dev/null || true; " +
            "  rm -f /root/astrbot/astrbot.pid; " +
            "fi; " +
            "pkill -f 'uv run main.py' 2>/dev/null || true; " +
            "echo stopped";
        return execInChroot(stopCmd, 15);
    }

    /** Check if AstrBot is running */
    public static boolean isAstrBotRunning() {
        CommandResult result = execInChroot(
            "if [ -f /root/astrbot/astrbot.pid ] && kill -0 $(cat /root/astrbot/astrbot.pid) 2>/dev/null; then " +
            "  echo running; " +
            "elif pgrep -f 'uv run main.py' >/dev/null 2>&1; then " +
            "  echo running; " +
            "else " +
            "  echo stopped; " +
            "fi", 10);
        return result.success && result.stdout.trim().equals("running");
    }

    // ─── Progress callback ───

    public interface ProgressCallback {
        void onProgress(String message);
        void onError(String error);
    }

    // ─── Internal: process execution ───

    private static CommandResult exec(String[] command, int timeoutSec) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        Process process = null;

        try {
            Log.d(TAG, "Executing: " + String.join(" ", command));
            process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

            // Read stdout
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

            // Read stderr
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
                process.destroyForcibly();
                return new CommandResult(false, stdout.toString(),
                    "Command timed out after " + timeoutSec + "s", -1);
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            exitCode = process.exitValue();
            return new CommandResult(exitCode == 0, stdout.toString(),
                stderr.toString(), exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + e.getMessage());
            if (process != null) process.destroy();
            return new CommandResult(false, stdout.toString(), e.getMessage(), -1);
        }
    }

    /** Quote a string for safe inclusion in a bash -c argument */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
