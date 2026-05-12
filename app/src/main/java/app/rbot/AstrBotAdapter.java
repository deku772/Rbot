package app.rbot;

import android.content.Context;

/**
 * BotAdapter implementation for AstrBot.
 * Routes to ChrootManager or PRootManager based on current AuthMode.
 */
public class AstrBotAdapter extends BotAdapter {

    public AstrBotAdapter(android.content.Context context) {
        super(context);
    }

    /** Dynamic check — always reads current mode instead of caching at construction time */
    private boolean useProot() {
        return AuthManager.getInstance().isProotMode();
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
        if (useProot()) {
            return PRootManager.getInstance(mContext).isAstrBotInstalled();
        }
        return ChrootManager.isAstrBotInstalled();
    }

    @Override
    public boolean isRunning() {
        if (useProot()) {
            return PRootManager.getInstance(mContext).isAstrBotRunning();
        }
        return ChrootManager.isAstrBotRunning();
    }

    @Override
    public boolean install(ChrootManager.ProgressCallback callback) {
        if (useProot()) {
            return installProot(callback);
        }
        return ChrootManager.installAstrBot(callback);
    }

    /** PRoot installation pipeline */
    private boolean installProot(ChrootManager.ProgressCallback callback) {
        PRootManager pm = PRootManager.getInstance(mContext);

        // Step 1: Configure rootfs for proot
        if (callback != null) callback.onProgress("配置 PRoot 环境...");
        pm.configureProotRootfs();

        // Step 2: apt update
        if (callback != null) callback.onProgress("更新软件源...");
        ChrootManager.CommandResult aptResult = pm.runInProotWithProgress(
            "apt update --allow-unauthenticated", 60, callback);
        if (!aptResult.success()) {
            if (callback != null) callback.onError("软件源更新失败: " + aptResult.stderr());
            return true;
        }

        // Step 3: apt install deps
        if (callback != null) callback.onProgress("安装系统依赖...");
        ChrootManager.CommandResult depResult = pm.runInProotWithProgress(
            "apt install -y --allow-unauthenticated " +
            "python3 python3-venv python3-pip python3-dev " +
            "git curl wget gpgv coreutils procps dropbear-bin " +
            "ca-certificates software-properties-common locales build-essential",
            120, callback);
        if (!depResult.success()) {
            if (callback != null) callback.onError("依赖安装失败: " + depResult.stderr());
            return true;
        }

        // Step 4: locale
        pm.runInProot("locale-gen en_US.UTF-8", 30);

        // Step 5: clone AstrBot
        if (callback != null) callback.onProgress("正在克隆 AstrBot...");
        String proxyUrl = GitHubProxyManager.buildUrl(
            "https://github.com/AstrBotDevs/AstrBot.git",
            GitHubProxyManager.getBestProxy());
        ChrootManager.CommandResult cloneResult = pm.runInProotWithProgress(
            "git clone --depth 1 " + proxyUrl + " /root/astrbot", 60, callback);
        if (!cloneResult.success()) {
            if (callback != null) callback.onError("AstrBot 克隆失败: " + cloneResult.stderr());
            return true;
        }

        // Step 6: pip install
        if (callback != null) callback.onProgress("安装 Python 依赖...");
        ChrootManager.CommandResult pipResult = pm.runInProotWithProgress(
            "python3 -m venv /root/astrbot/venv && " +
            "/root/astrbot/venv/bin/pip install --upgrade pip && " +
            "cd /root/astrbot && /root/astrbot/venv/bin/pip install -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt",
            600, callback);
        if (!pipResult.success()) {
            if (callback != null) callback.onError("Python 依赖安装失败: " + pipResult.stderr());
            return true;
        }

        // Step 7: set default SSH password
        if (callback != null) callback.onProgress("配置 SSH 访问...");
        pm.runInProot(
            "HASH=$(openssl passwd -6 -salt rbotsalt '" + RbotConstants.DEFAULT_SSH_PASSWORD + "') && " +
            "if grep -q '^root:' /etc/shadow; then " +
            "  sed -i \"s|^root:[^:]*:|root:$HASH:|\" /etc/shadow; " +
            "else " +
            "  echo \"root:$HASH:19000:0:99999:7:::\" >> /etc/shadow; " +
            "fi && chmod 600 /etc/shadow && " +
            "printf '%s' '" + RbotConstants.DEFAULT_SSH_PASSWORD + "' > /root/.rbot_pass && chmod 600 /root/.rbot_pass && " +
            "sed -i 's/groups)/groups 2\\/dev\\/null)/' /etc/bash.bashrc 2>/dev/null || true",
            15);

        pm.markAstrBotInstalled();
        if (callback != null) callback.onProgress("✅ AstrBot 安装完成（PRoot 模式）");
        return false;
    }

    @Override
    public boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex) {
        if (useProot()) {
            PRootManager pm = PRootManager.getInstance(mContext);
            // Stop and remove
            pm.stopAstrBot();
            pm.runInProot("if [ -d /root/astrbot/data ]; then " +
                "  cp -a /root/astrbot/data /root/astrbot_data_backup; " +
                "fi && rm -rf /root/astrbot", 60);
            new java.io.File(pm.getAstrBotHome()).getParentFile().mkdirs();

            // Clone
            String proxyUrl = GitHubProxyManager.buildUrl(
                "https://github.com/AstrBotDevs/AstrBot.git", proxyIndex);
            String cloneCmd = "git clone --depth 1";
            if (version != null && !version.isEmpty()) cloneCmd += " --branch " + version;
            cloneCmd += " " + proxyUrl + " /root/astrbot";

            if (callback != null) callback.onProgress("正在克隆 AstrBot...");
            ChrootManager.CommandResult cloneResult = pm.runInProotWithProgress(cloneCmd, 60, callback);
            if (!cloneResult.success()) {
                if (callback != null) callback.onError("AstrBot 克隆失败");
                return true;
            }

            // pip install
            if (callback != null) callback.onProgress("安装 Python 依赖...");
            ChrootManager.CommandResult pipResult = pm.runInProotWithProgress(
                "cd /root/astrbot && /root/astrbot/venv/bin/pip install -i https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt",
                300, callback);
            if (!pipResult.success()) {
                if (callback != null) callback.onError("Python 依赖安装失败");
                return true;
            }

            // Restore data
            pm.runInProot(
                "if [ -d /root/astrbot_data_backup ]; then " +
                "  cp -a /root/astrbot_data_backup/. /root/astrbot/data/ 2>/dev/null; " +
                "  rm -rf /root/astrbot_data_backup; " +
                "fi", 60);

            return false;
        }

        // Chroot mode — existing logic
        ChrootManager.stopAstrBot();
        if (callback != null) callback.onProgress("备份 AstrBot 数据...");
        ChrootManager.execInChroot(
            "if [ -d /root/astrbot/data ]; then " +
            "  cp -a /root/astrbot/data /root/astrbot_data_backup; " +
            "fi && rm -rf /root/astrbot", 60);
        ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);
        if (ChrootManager.cloneAstrBotWithProxy(callback, version, proxyIndex)) {
            if (callback != null) callback.onError("AstrBot 克隆失败");
            return true;
        }
        if (ChrootManager.pipInstallDeps(callback)) {
            if (callback != null) callback.onError("Python 依赖安装失败");
            return true;
        }
        ChrootManager.execInChroot(
            "if [ -d /root/astrbot_data_backup ]; then " +
            "  cp -a /root/astrbot_data_backup/. /root/astrbot/data/ 2>/dev/null; " +
            "  rm -rf /root/astrbot_data_backup; " +
            "fi", 60);
        return false;
    }

    @Override
    public ChrootManager.CommandResult start() {
        if (useProot()) {
            return PRootManager.getInstance(mContext).startAstrBot();
        }
        return ChrootManager.startAstrBot();
    }

    @Override
    public ChrootManager.CommandResult stop() {
        if (useProot()) {
            return PRootManager.getInstance(mContext).stopAstrBot();
        }
        return ChrootManager.stopAstrBot();
    }

    @Override
    public String getWebUIUrl() {
        return "http://127.0.0.1:6185";
    }

    @Override
    public String getLogFile() {
        if (useProot()) {
            // PRoot rootfs is in app storage, we can read directly
            return PRootManager.getInstance(mContext).getAstrBotLogFile();
        }
        return RbotConstants.ASTRBOT_LOG_FILE.substring(RbotConstants.CHROOT_DIR.length());
    }

    @Override
    public String getPidFile() {
        if (useProot()) {
            return PRootManager.getInstance(mContext).getAstrBotPidFile();
        }
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
        if (useProot()) {
            return PRootManager.getInstance(mContext).getAstrBotHome();
        }
        return RbotConstants.ASTRBOT_HOME;
    }

    @Override
    public String getInstallCommand() {
        return "pip install astrbot";
    }

    @Override
    public String getStartCommand() {
        return "python main.py";
    }

    @Override
    public String getSupportedPlatforms() {
        return "QQ（原生）";
    }
}
