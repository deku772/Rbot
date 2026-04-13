package app.rbot;

/**
 * BotAdapter implementation for Hermes Agent.
 * Ref: https://github.com/NousResearch/hermes-agent
 *
 * Installation: uses the official install.sh script via curl | bash
 *   - Installs uv, Python 3.11, Node.js 22, Playwright, etc.
 *   - All via `curl -fsSL <install.sh> | bash -- --skip-setup`
 *   - We pass --skip-setup to avoid interactive wizard (user configures later)
 *
 * Directory layout (after official install):
 *   ~/.hermes/hermes-agent/  - git repo + venv
 *   ~/.local/bin/hermes      - symlink to hermes binary
 *   ~/.hermes/               - config, logs, sessions, etc.
 */
public class HermesAdapter extends BotAdapter {

    /** Hermes home directory inside chroot */
    private static final String HERMES_HOME = "/root/.hermes";

    /** Hermes agent repo directory inside chroot (official install location) */
    private static final String HERMES_REPO = HERMES_HOME + "/hermes-agent";

    /** Hermes venv inside the repo directory */
    private static final String HERMES_VENV = HERMES_REPO + "/venv";

    /** PID file for hermes gateway */
    private static final String HERMES_PID_FILE = HERMES_HOME + "/gateway.pid";

    /** Log file for hermes gateway */
    private static final String HERMES_LOG_FILE = HERMES_HOME + "/logs/gateway.log";

    /** Marker file indicating Hermes is installed (at chroot root so app process can read) */
    private static final String HERMES_MARKER = RbotConstants.CHROOT_DIR + "/.rbot-hermes-ready";

    /** Official install script URL */
    private static final String INSTALL_SCRIPT_URL =
        "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh";

    public HermesAdapter(android.content.Context context) {
        super(context);
    }

    @Override
    public String getId() {
        return ID_HERMES;
    }

    @Override
    public String getName() {
        return "Hermes Agent";
    }

    @Override
    public String getStatusLabel() {
        return "HERMES";
    }

    @Override
    public boolean isInstalled() {
        // Use marker file (app-process-visible) — works even when chroot is not mounted
        return new java.io.File(HERMES_MARKER).exists();
    }

    @Override
    public boolean isRunning() {
        // Check PID file + process existence
        ChrootManager.CommandResult result = ChrootManager.execInChroot(
            "PIDFILE=" + HERMES_PID_FILE + "; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(/usr/bin/cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -0 $SPECPID 2>/dev/null && echo running; " +
            "else echo not_running; fi", 5);
        return result.success() && result.stdout().trim().equals("running");
    }

    @Override
    public boolean install(ChrootManager.ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在运行 Hermes 官方安装脚本...");
        if (callback != null) callback.onProgress("将自动安装 uv、Python 3.11、Node.js 22、Playwright 等依赖");

        // Ensure chroot is mounted before installing
        if (!ChrootManager.isChrootMounted()) {
            ChrootManager.setupChrootEnvironment(callback);
        }

        // Ensure git and curl are available first
        ChrootManager.CommandResult depCheck = ChrootManager.execInChrootWithProgress(
            "apt-get update -qq && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git curl ca-certificates 2>&1",
            120, callback);
        // Don't fail hard — the install script itself will check

        // Run the official install script with --skip-setup (non-interactive)
        // The script handles: uv, Python 3.11, Node.js 22, git clone, venv, pip install, Playwright, etc.
        // PATH must include ~/.local/bin for uv to work (the script installs uv there)
        String installCmd =
            "export PATH=/root/.local/bin:$PATH && " +
            "curl -fsSL " + INSTALL_SCRIPT_URL + " | bash -- --skip-setup 2>&1";

        ChrootManager.CommandResult result =
            ChrootManager.execInChrootWithProgress(installCmd, 1800, callback);
        // 1800s = 30min timeout — the install can take a while on slow networks

        if (!result.success()) {
            // Check if it actually succeeded despite non-zero exit (some warnings cause exit 1)
            ChrootManager.CommandResult checkResult = ChrootManager.execInChroot(
                "test -x /root/.local/bin/hermes && echo ok || echo missing", 5);
            if (!checkResult.success() || !checkResult.stdout().trim().equals("ok")) {
                if (callback != null) callback.onError("Hermes 安装失败: " + result.stderr());
                return true; // failure
            }
        }

        // Create marker file
        ChrootManager.execRoot("touch " + HERMES_MARKER);

        if (callback != null) callback.onProgress("✅ Hermes Agent 安装完成");
        return false; // success
    }

    @Override
    public boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex) {
        // Clean up existing installation thoroughly
        if (callback != null) callback.onProgress("正在清理旧安装...");
        ChrootManager.execInChroot(
            "rm -rf " + HERMES_HOME + " /root/.local/bin/hermes " +
            "/root/.local/share/hermes " +
            "/root/.cache/hermes " +
            "/root/.config/hermes", 30);
        ChrootManager.execRoot("rm -f " + HERMES_MARKER);

        // Re-run the official install script
        return install(callback);
    }

    @Override
    public ChrootManager.CommandResult start() {
        synchronized (ChrootManager.getChrootLock()) {
            // Note: chroot setup is handled by the caller (MainActivity.startGateway)
            if (!ChrootManager.isChrootMounted()) {
                ChrootManager.setupChrootDevices(null);
            }
            stop(); // kill any existing

            // Ensure SSH is running after setupChrootDevices (which may remount /dev)
            if (!ChrootManager.isSshRunning()) {
                ChrootManager.startSshService(null);
            }

            // Check if Hermes is actually installed before trying to start
            if (!isInstalled()) {
                return new ChrootManager.CommandResult(false, "",
                    "Hermes 未安装，请先在设置中安装 Hermes Agent", 1);
            }

            // Official install puts hermes at ~/.local/bin/hermes
            String hermesBin = "/root/.local/bin/hermes";

            // Verify the hermes binary exists before executing
            ChrootManager.CommandResult binCheck = ChrootManager.execInChroot(
                "test -x " + hermesBin + " && echo ok || echo missing", 5);
            if (!binCheck.success() || !binCheck.stdout().trim().equals("ok")) {
                return new ChrootManager.CommandResult(false, "",
                    "Hermes 可执行文件不存在或无执行权限 (" + hermesBin + ")，请重新安装", 1);
            }

            // Ensure log directory exists
            ChrootManager.execInChroot("mkdir -p " + HERMES_HOME + "/logs", 5);

            // Build start command with full PATH including ~/.local/bin for hermes subprocess calls
            // Official install adds ~/.local/bin to PATH in .bashrc/.profile
            String startCmd =
                "cd /root && " +
                "export HOME=/root && " +
                "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                "mkdir -p " + HERMES_HOME + "/logs && " +
                "setsid " + hermesBin + " gateway start > " + HERMES_LOG_FILE + " 2>&1 & " +
                "echo $! > " + HERMES_PID_FILE + " && " +
                "/bin/sleep 5 && " +
                "if kill -0 $(/usr/bin/cat " + HERMES_PID_FILE + ") 2>/dev/null; then " +
                "  echo started; " +
                "else " +
                "  /usr/bin/cat " + HERMES_LOG_FILE + " 2>/dev/null; " +
                "  echo 'Hermes failed to start'; exit 1; " +
                "fi";

            return ChrootManager.execInChroot(startCmd, 30);
        }
    }

    @Override
    public ChrootManager.CommandResult stop() {
        // Kill from outside chroot (host paths are prefixed with CHROOT_DIR)
        ChrootManager.execRoot(
            "PIDFILE=" + RbotConstants.CHROOT_DIR + HERMES_PID_FILE + "; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(/system/bin/cat $PIDFILE 2>/dev/null || cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -9 $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "echo host_killed", 10);

        // pkill from host
        ChrootManager.execRoot("pkill -9 -f 'hermes gateway' 2>/dev/null || true; echo pkill_done", 5);

        // Kill from inside chroot
        String stopCmd =
            "PIDFILE=" + HERMES_PID_FILE + "; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(/usr/bin/cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -9 $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "pkill -9 -f 'hermes gateway' 2>/dev/null || true; " +
            "/bin/sleep 1; " +
            "echo stopped";
        return ChrootManager.execInChroot(stopCmd, 15);
    }

    @Override
    public String getWebUIUrl() {
        return "http://127.0.0.1:8080";
    }

    @Override
    public String getLogFile() {
        return HERMES_LOG_FILE;
    }

    @Override
    public String getPidFile() {
        return HERMES_PID_FILE;
    }

    @Override
    public void setBootStart(boolean enable) {
        // TODO: manage termux-boot script for hermes
    }

    @Override
    public boolean isBootStartEnabled() {
        // TODO: check termux-boot script
        return false;
    }

    @Override
    public String getHomePath() {
        return HERMES_HOME;
    }

    @Override
    public String getInstallCommand() {
        return "curl -fsSL <install.sh> | bash -- --skip-setup";
    }

    @Override
    public String getStartCommand() {
        return "hermes gateway start";
    }

    @Override
    public String getSupportedPlatforms() {
        return "微信/飞书/企业微信/钉钉/Telegram/Discord/Slack";
    }
}
