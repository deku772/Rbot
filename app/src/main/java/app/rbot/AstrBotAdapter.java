package app.rbot;

/**
 * BotAdapter implementation for AstrBot.
 * Wraps existing ChrootManager static methods — no duplication.
 */
public class AstrBotAdapter extends BotAdapter {

    public AstrBotAdapter(android.content.Context context) {
        super(context);
    }

    @Override
    public String getId() {
        return ID_ASTRBOT;
    }

    @Override
    public String getName() {
        return "AstrBot";
    }

    @Override
    public String getStatusLabel() {
        return "ASTRBOT";
    }

    @Override
    public boolean isInstalled() {
        return ChrootManager.isAstrBotInstalled();
    }

    @Override
    public boolean isRunning() {
        return ChrootManager.isAstrBotRunning();
    }

    @Override
    public boolean install(ChrootManager.ProgressCallback callback) {
        return ChrootManager.installAstrBot(callback);
    }

    @Override
    public boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex) {
        // Stop first
        ChrootManager.stopAstrBot();

        // Backup data
        if (callback != null) callback.onProgress("备份 AstrBot 数据...");
        ChrootManager.execInChroot(
            "if [ -d /root/astrbot/data ]; then " +
            "  cp -a /root/astrbot/data /root/astrbot_data_backup; " +
            "fi && rm -rf /root/astrbot", 60);

        // Remove marker
        ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);

        // Clone
        if (ChrootManager.cloneAstrBotWithProxy(callback, version, proxyIndex)) {
            if (callback != null) callback.onError("AstrBot 克隆失败");
            return true;
        }

        // pip install
        if (ChrootManager.pipInstallDeps(callback)) {
            if (callback != null) callback.onError("Python 依赖安装失败");
            return true;
        }

        // Restore data
        ChrootManager.execInChroot(
            "if [ -d /root/astrbot_data_backup ]; then " +
            "  cp -a /root/astrbot_data_backup/. /root/astrbot/data/ 2>/dev/null; " +
            "  rm -rf /root/astrbot_data_backup; " +
            "fi", 60);

        return false;
    }

    @Override
    public ChrootManager.CommandResult start() {
        return ChrootManager.startAstrBot();
    }

    @Override
    public ChrootManager.CommandResult stop() {
        return ChrootManager.stopAstrBot();
    }

    @Override
    public String getWebUIUrl() {
        return "http://127.0.0.1:6185";
    }

    @Override
    public String getLogFile() {
        // Return chroot-relative path (e.g. "/root/astrbot/astrbot.log")
        // Callers prepend CHROOT_DIR to get the host path.
        // ASTRBOT_LOG_FILE is already a host path, so strip the CHROOT_DIR prefix.
        return RbotConstants.ASTRBOT_LOG_FILE.substring(RbotConstants.CHROOT_DIR.length());
    }

    @Override
    public String getPidFile() {
        // Return chroot-relative path — strip CHROOT_DIR prefix
        return RbotConstants.ASTRBOT_PID_FILE.substring(RbotConstants.CHROOT_DIR.length());
    }

    @Override
    public void setBootStart(boolean enable) {
        // TODO: implement via termux-boot script management
    }

    @Override
    public boolean isBootStartEnabled() {
        // TODO: check termux-boot script
        return false;
    }

    @Override
    public String getHomePath() {
        return RbotConstants.ASTRBOT_HOME;
    }

    @Override
    public String getInstallCommand() {
        return "pip install astrbot";
    }

    @Override
    public String getStartCommand() {
        return "python -m astrbot";
    }

    @Override
    public String getSupportedPlatforms() {
        return "QQ（原生）";
    }
}
