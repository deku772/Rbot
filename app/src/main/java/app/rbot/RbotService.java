package app.rbot;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background service for managing the Rbot chroot environment.
 * Delegates all root/chroot operations to {@link ChrootManager}.
 *
 * Responsibilities:
 * - Rootfs extraction and AstrBot installation
 * - AstrBot start/stop/restart
 * - Running status checks
 * - Foreground service lifecycle for Android keep-alive
 */
public class RbotService extends Service {

    private static final String TAG = "RbotService";

    private final IBinder mBinder = new LocalBinder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public class LocalBinder extends Binder {
        public RbotService getService() {
            return RbotService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }

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

    public interface CommandCallback {
        void onResult(CommandResult result);
    }

    public interface InstallProgressCallback {
        void onStepStart(int step, String message);
        void onStepComplete(int step);
        void onError(String error);
        void onComplete();
    }

    // ─── Installation status checks (static, no service binding needed) ───

    /** Check if root is available */
    public static boolean isRootAvailable() {
        return ChrootManager.isRootAvailable();
    }

    /** Check if rootfs is extracted and ready */
    public static boolean isRootfsReady() {
        return ChrootManager.isRootfsReady();
    }

    /** Check if AstrBot is installed */
    public static boolean isAstrBotInstalled() {
        return ChrootManager.isAstrBotInstalled();
    }

    /** For backward compatibility with LauncherActivity routing logic */
    public static boolean isBootstrapInstalled() {
        // In chroot mode, "bootstrap" = rootfs ready
        return isRootfsReady();
    }

    // ─── Install flow ───

    /**
     * Full installation flow:
     * Step 0: Check root access
     * Step 1: Find rootfs tarball (3-tier fallback)
     * Step 2: Extract rootfs to /data/rbot
     * Step 3: Install AstrBot inside chroot
     */
    public void installAstrBot(InstallProgressCallback callback) {
        if (!safeExecute(mExecutor, () -> {
            // Step 0: Check root
            mHandler.post(() -> callback.onStepStart(0, "检查 Root 权限..."));
            if (!ChrootManager.isRootAvailable()) {
                mHandler.post(() -> callback.onError("未获取 Root 权限，请授予 Rbot Root 权限后重试"));
                return;
            }
            mHandler.post(() -> callback.onStepComplete(0));

            // Step 1: Find rootfs tarball
            mHandler.post(() -> callback.onStepStart(1, "查找系统镜像..."));
            String tarballPath = findRootfsTarball();
            if (tarballPath == null) {
                // Try downloading from GitHub
                mHandler.post(() -> callback.onStepStart(1, "下载系统镜像中（约 458MB）..."));
                tarballPath = downloadRootfs();
                if (tarballPath == null) {
                    mHandler.post(() -> callback.onError("系统镜像下载失败，请检查网络连接"));
                    return;
                }
            }
            mHandler.post(() -> callback.onStepComplete(1));

            // Step 2: Extract rootfs
            mHandler.post(() -> callback.onStepStart(2, "解压系统镜像（约 1-3 分钟）..."));
            if (ChrootManager.ensureChrootDir()) {
                mHandler.post(() -> callback.onError("无法创建 /data/rbot 目录"));
                return;
            }
            if (ChrootManager.extractRootfs(tarballPath, new ChrootManager.ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    mHandler.post(() -> callback.onStepStart(2, message));
                }
                @Override
                public void onError(String error) {
                    mHandler.post(() -> callback.onError(error));
                }
            })) {
                return;
            }
            mHandler.post(() -> callback.onStepComplete(2));

            // Step 3: Install AstrBot
            mHandler.post(() -> callback.onStepStart(3, "安装 AstrBot..."));
            if (ChrootManager.installAstrBot(new ChrootManager.ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    mHandler.post(() -> callback.onStepStart(3, message));
                }
                @Override
                public void onError(String error) {
                    mHandler.post(() -> callback.onError(error));
                }
            })) {
                return;
            }
            mHandler.post(() -> callback.onStepComplete(3));

            mHandler.post(callback::onComplete);
        })) {
            mHandler.post(() -> callback.onError("Service is shutting down"));
        }
    }

    // ─── Gateway lifecycle ───

    public void startGateway(CommandCallback callback) {
        safeExecuteWithResult(callback, () -> {
            ChrootManager.CommandResult result = ChrootManager.startAstrBot();
            return new CommandResult(result.success(), result.stdout(), result.stderr(), result.exitCode());
        });
    }

    public void stopGateway(CommandCallback callback) {
        safeExecuteWithResult(callback, () -> {
            ChrootManager.CommandResult result = ChrootManager.stopAstrBot();
            return new CommandResult(result.success(), result.stdout(), result.stderr(), result.exitCode());
        });
    }

    public void restartGateway(CommandCallback callback) {
        stopGateway(result -> {
            mHandler.postDelayed(() -> startGateway(callback), 1000);
        });
    }

    public void isGatewayRunning(CommandCallback callback) {
        safeExecuteWithResult(callback, () -> {
            boolean running = ChrootManager.isAstrBotRunning();
            return new CommandResult(true, running ? "running" : "stopped", "", 0);
        });
    }

    public void getGatewayStatus(CommandCallback callback) {
        isGatewayRunning(callback);
    }

    public void getGatewayUptime(CommandCallback callback) {
        safeExecuteWithResult(callback, () -> {
            ChrootManager.CommandResult result = ChrootManager.execInChroot(
                "if [ -f /root/astrbot/astrbot.pid ] && kill -0 $(cat /root/astrbot/astrbot.pid) 2>/dev/null; then " +
                "  ps -p $(cat /root/astrbot/astrbot.pid) -o etime= 2>/dev/null || echo '—'; " +
                "else echo '—'; fi", 10);
            return new CommandResult(result.success(), result.stdout(), result.stderr(), result.exitCode());
        });
    }

    // ─── Reinstall environment ───

    public void reinstallEnvironment(CommandCallback callback) {
        safeExecuteWithResult(callback, () -> {
            ChrootManager.stopAstrBot();
            ChrootManager.CommandResult result = ChrootManager.execRoot(
                "rm -rf " + RbotConstants.CHROOT_DIR + "/*");
            return new CommandResult(result.success(), result.stdout(), result.stderr(), result.exitCode());
        });
    }

    // ─── Update check stub ───

    public boolean isUpdateInProgress() {
        return false;
    }

    // ─── Internal helpers ───

    private String findRootfsTarball() {
        String cachePath = RbotConstants.SDCARD_ROOTFS_CACHE;
        if (new java.io.File(cachePath).exists()) {
            Log.i(TAG, "Found local rootfs: " + cachePath);
            return cachePath;
        }
        Log.i(TAG, "No local rootfs found at: " + cachePath);
        return null;
    }

    private String downloadRootfs() {
        ChrootManager.execRoot("mkdir -p " + RbotConstants.SDCARD_CACHE_DIR);

        String downloadUrl = GitHubProxyManager.buildUrl(RbotConstants.GITHUB_ROOTFS_URL);

        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "curl -L --progress-bar -o " + RbotConstants.SDCARD_ROOTFS_CACHE +
            " '" + downloadUrl + "'", 600);

        if (result.success() && new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists()) {
            long size = new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).length();
            if (size > 10 * 1024 * 1024) {
                return RbotConstants.SDCARD_ROOTFS_CACHE;
            }
        }

        ChrootManager.execRoot("rm -f " + RbotConstants.SDCARD_ROOTFS_CACHE);
        return null;
    }

    private boolean safeExecute(ExecutorService executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "Executor rejected task: " + e.getMessage());
            return false;
        }
    }

    private void safeExecuteWithResult(CommandCallback callback, java.util.function.Supplier<CommandResult> task) {
        if (!safeExecute(mExecutor, () -> {
            CommandResult result = task.get();
            mHandler.post(() -> callback.onResult(result));
        })) {
            mHandler.post(() -> callback.onResult(
                new CommandResult(false, "", "Service executor is shut down", -1)));
        }
    }
}
