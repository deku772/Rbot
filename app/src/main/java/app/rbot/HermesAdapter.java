package app.rbot;

/**
 * BotAdapter implementation for Hermes Agent.
 * Ref: https://hermes-agent.nousresearch.com/docs/getting-started/termux
 *
 * Hermes uses:
 * - Home: ~/.hermes/ (inside chroot: /root/.hermes/)
 * - Installation: pip install -e '.[termux]' via venv at /root/hermes/venv
 * - Start: hermes gateway start
 * - Port: 8080 (gateway default)
 * - Log: /root/.hermes/gateway.log
 */
public class HermesAdapter extends BotAdapter {

    /** Hermes home directory inside chroot */
    private static final String HERMES_HOME = "/root/.hermes";

    /** Hermes venv home inside chroot */
    private static final String HERMES_VENV = "/root/hermes";

    /** PID file for hermes gateway */
    private static final String HERMES_PID_FILE = HERMES_HOME + "/gateway.pid";

    /** Log file for hermes gateway */
    private static final String HERMES_LOG_FILE = HERMES_HOME + "/gateway.log";

    /** Marker file indicating Hermes is installed (at chroot root so app process can read) */
    private static final String HERMES_MARKER = RbotConstants.CHROOT_DIR + "/.rbot-hermes-ready";

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
        // Check both venv hermes binary and marker
        ChrootManager.CommandResult venvCheck = ChrootManager.execInChroot(
            "test -f " + HERMES_VENV + "/bin/hermes && echo hermes_ok", 5);
        boolean installed = venvCheck.success() && venvCheck.stdout().trim().equals("hermes_ok");
        return installed;
    }

    @Override
    public boolean isRunning() {
        // Check PID file + process existence
        ChrootManager.CommandResult result = ChrootManager.execInChroot(
            "PIDFILE=" + HERMES_PID_FILE + "; " +
            "if [ -f \"$PIDFILE\" ]; then " +
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -0 $SPECPID 2>/dev/null && echo running; " +
            "else echo not_running; fi", 5);
        return result.success() && result.stdout().trim().equals("running");
    }

    @Override
    public boolean install(ChrootManager.ProgressCallback callback) {
        if (callback != null) callback.onProgress("正在克隆 Hermes Agent...");

        // Use streaming exec so git clone progress appears in real-time
        String cloneCmd =
            "cd /root && " +
            "git clone https://github.com/NousResearch/hermes-agent.git hermes-agent-src 2>&1";
        ChrootManager.CommandResult cloneResult =
            ChrootManager.execInChrootWithProgress(cloneCmd, 120, callback);
        if (!cloneResult.success()) {
            if (callback != null) callback.onError("Hermes 克隆失败: " + cloneResult.stderr());
            return true;
        }
        if (callback != null) callback.onProgress("正在创建虚拟环境...");

        // Create venv and upgrade pip (streaming)
        String venvCmd =
            "cd /root && " +
            "python3 -m venv " + HERMES_VENV + " && " +
            HERMES_VENV + "/bin/pip install --upgrade pip";
        ChrootManager.CommandResult venvResult =
            ChrootManager.execInChrootWithProgress(venvCmd, 60, callback);
        if (!venvResult.success()) {
            if (callback != null) callback.onError("虚拟环境创建失败: " + venvResult.stderr());
            return true;
        }

        if (callback != null) callback.onProgress("正在安装 Hermes 依赖 (termux)... 这可能需要 5-10 分钟，请耐心等待");

        // pip install with ANDROID_API_LEVEL for jiter/maturin compilation
        // execInChrootWithProgress streams output in real-time so user sees progress
        String installCmd =
            "export ANDROID_API_LEVEL=$(getprop ro.build.version.sdk) && " +
            "cd /root/hermes-agent-src && " +
            HERMES_VENV + "/bin/pip install -e '.[termux]' -c constraints-termux.txt";
        ChrootManager.CommandResult installResult =
            ChrootManager.execInChrootWithProgress(installCmd, 1200, callback);
        if (!installResult.success()) {
            if (callback != null) callback.onError("Hermes 安装失败: " + installResult.stderr());
            return true;
        }

        // Link hermes binary to venv/bin
        ChrootManager.execInChroot(
            "ln -sf /root/hermes-agent-src/hermes " + HERMES_VENV + "/bin/hermes", 10);

        // Create .hermes directory
        ChrootManager.execInChroot("mkdir -p " + HERMES_HOME, 5);

        // Touch marker
        ChrootManager.execRoot("touch " + HERMES_MARKER);

        if (callback != null) callback.onProgress("✅ Hermes Agent 安装完成");
        return false;
    }

    @Override
    public boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex) {
        // For Hermes, reinstall just means re-running install (no version selection currently)
        ChrootManager.execInChroot("rm -rf " + HERMES_VENV + " /root/hermes-agent-src", 30);
        ChrootManager.execRoot("rm -f " + HERMES_MARKER);
        return install(callback);
    }

    @Override
    public ChrootManager.CommandResult start() {
        synchronized (ChrootManager.getChrootLock()) {
            ChrootManager.setupChrootDevices(null);
            stop(); // kill any existing

            String hermesBin = HERMES_VENV + "/bin/hermes";

            String startCmd =
                "cd /root && " +
                "export HOME=/root && " +
                "setsid " + hermesBin + " gateway start > " + HERMES_LOG_FILE + " 2>&1 & " +
                "echo $! > " + HERMES_PID_FILE + " && " +
                "sleep 5 && " +
                "if kill -0 $(cat " + HERMES_PID_FILE + ") 2>/dev/null; then " +
                "  echo started; " +
                "else " +
                "  cat " + HERMES_LOG_FILE + " 2>/dev/null; " +
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
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
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
            "  SPECPID=$(cat $PIDFILE 2>/dev/null); " +
            "  [ -n \"$SPECPID\" ] && kill -9 $SPECPID 2>/dev/null; " +
            "  rm -f $PIDFILE; " +
            "fi; " +
            "pkill -9 -f 'hermes gateway' 2>/dev/null || true; " +
            "sleep 1; " +
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
        return "pip install -e '.[termux]' (via hermes-agent repo)";
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
