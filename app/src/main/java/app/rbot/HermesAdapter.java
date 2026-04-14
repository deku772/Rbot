package app.rbot;

/**
 * BotAdapter implementation for Hermes Agent.
 * Delegates all lifecycle to ChrootManager static methods.
 */
public class HermesAdapter extends BotAdapter {

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
        return ChrootManager.isHermesInstalled();
    }

    @Override
    public boolean isRunning() {
        return ChrootManager.isHermesRunning();
    }

    @Override
    public boolean install(ChrootManager.ProgressCallback callback) {
        return ChrootManager.installHermes(callback);
    }

    @Override
    public boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex) {
        ChrootManager.stopHermes();
        if (callback != null) callback.onProgress("删除旧 Hermes...");
        ChrootManager.execInChroot("rm -rf " + RbotConstants.HERMES_HOME, 30);
        ChrootManager.execRoot("rm -f " + RbotConstants.HERMES_MARKER);
        return install(callback);
    }

    @Override
    public ChrootManager.CommandResult start() {
        return ChrootManager.startHermes();
    }

    @Override
    public ChrootManager.CommandResult stop() {
        return ChrootManager.stopHermes();
    }

    @Override
    public String getWebUIUrl() {
        return "http://127.0.0.1:8080";
    }

    @Override
    public String getLogFile() {
        return RbotConstants.HERMES_LOG_FILE;
    }

    @Override
    public String getPidFile() {
        return RbotConstants.HERMES_PID_FILE;
    }

    @Override
    public void setBootStart(boolean enable) {
        // TODO: implement via termux-boot script
    }

    @Override
    public boolean isBootStartEnabled() {
        return false;
    }

    @Override
    public String getHomePath() {
        return RbotConstants.HERMES_HOME;
    }

    @Override
    public String getInstallCommand() {
        return "curl -fsSL .../install.sh | bash";
    }

    @Override
    public String getStartCommand() {
        return "hermes gateway";
    }

    @Override
    public String getSupportedPlatforms() {
        return "Telegram · Discord · 钉钉 · 飞书";
    }
}
