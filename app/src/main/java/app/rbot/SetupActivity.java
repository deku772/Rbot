package app.rbot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Setup activity for chroot-based AstrBot installation.
 * Supports selective reinstall: user can choose which steps to re-run.
 */
public class SetupActivity extends AppCompatActivity {

    private static final String TAG = "SetupActivity";

    // Step constants
    public static final int STEP_INSTALL = 0;
    public static final int STEP_API_KEY = 1;
    public static final int STEP_CHANNEL = 2;

    // Intent extras
    public static final String EXTRA_START_STEP = "start_step";
    public static final String EXTRA_CHANNEL_PLATFORM = "channel_platform";

    private TextView mTitleText;
    private TextView mStepText;
    private TextView mLogText;
    private ProgressBar mProgressBar;
    private ScrollView mLogScrollView;
    private Button mActionButton;
    private LinearLayout mReinstallOptions;
    private CheckBox mCbReinstallRootfs;
    private CheckBox mCbReinstallDeps;
    private CheckBox mCbReinstallAstrbot;

    private boolean mInstalling = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_setup_minimal);

        mTitleText = findViewById(R.id.setup_title);
        mStepText = findViewById(R.id.setup_step_text);
        mLogText = findViewById(R.id.setup_log_text);
        mProgressBar = findViewById(R.id.setup_progress);
        mLogScrollView = findViewById(R.id.setup_log_scroll);
        mActionButton = findViewById(R.id.setup_action_button);
        mReinstallOptions = findViewById(R.id.reinstall_options);
        mCbReinstallRootfs = findViewById(R.id.cb_reinstall_rootfs);
        mCbReinstallDeps = findViewById(R.id.cb_reinstall_deps);
        mCbReinstallAstrbot = findViewById(R.id.cb_reinstall_astrbot);

        mActionButton.setOnClickListener(v -> startInstallation());

        int startStep = getIntent().getIntExtra(EXTRA_START_STEP, -1);
        if (startStep == STEP_INSTALL) {
            startInstallation();
        } else {
            updateStatusUI();
        }
    }

    private void updateStatusUI() {
        boolean rootfsReady = ChrootManager.isRootfsReady();
        boolean astrBotInstalled = ChrootManager.isAstrBotInstalled();

        if (!rootfsReady && !astrBotInstalled) {
            mReinstallOptions.setVisibility(View.GONE);
            mActionButton.setText("开始安装");
            mStepText.setText("尚未安装");
        } else {
            mReinstallOptions.setVisibility(View.VISIBLE);
            mStepText.setText(
                "系统镜像: " + (rootfsReady ? "✅" : "❌") + "  " +
                "AstrBot: " + (astrBotInstalled ? "✅" : "❌"));

            if (!rootfsReady) mCbReinstallRootfs.setChecked(true);
            if (!astrBotInstalled) mCbReinstallAstrbot.setChecked(true);

            mActionButton.setText("执行选中的步骤");
        }
    }

    private void startInstallation() {
        if (mInstalling) return;
        mInstalling = true;

        mActionButton.setEnabled(false);
        mActionButton.setText("安装中...");
        mTitleText.setText("正在安装 Rbot");
        mProgressBar.setVisibility(View.VISIBLE);
        mReinstallOptions.setVisibility(View.GONE);
        mLogText.setText("");

        boolean reinstallRootfs = mCbReinstallRootfs.isChecked();
        boolean reinstallDeps = mCbReinstallDeps.isChecked();
        boolean reinstallAstrbot = mCbReinstallAstrbot.isChecked();

        boolean needRootfs = !ChrootManager.isRootfsReady() || reinstallRootfs;
        boolean needDeps = reinstallDeps || needRootfs;
        boolean needAstrbot = !ChrootManager.isAstrBotInstalled() || reinstallAstrbot;

        if (!needRootfs && !needDeps && !needAstrbot) {
            Toast.makeText(this, "所有组件已安装，正在跳转...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(SetupActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        new Thread(() -> {
            // ─── Step 0: Root check ───
            appendLog("🔍 检查 Root 权限...");
            if (!ChrootManager.isRootAvailable()) {
                runOnUiThread(() -> {
                    appendLog("❌ 未获取 Root 权限");
                    finishInstall("需要 Root 权限", "重试");
                    Toast.makeText(this, "请授予 Root 权限后重试", Toast.LENGTH_LONG).show();
                });
                return;
            }
            appendLog("✅ Root 权限已获取");
            runOnUiThread(() -> mStepText.setText("Root ✅"));

            // ─── Step 1: Extract rootfs (if needed) ───
            if (needRootfs) {
                appendLog("📦 查找系统镜像...");
                String tarballPath = findRootfsTarball();
                if (tarballPath == null) {
                    appendLog("⬇️ 下载系统镜像中（首次需下载约 200MB）...");
                    runOnUiThread(() -> mStepText.setText("下载系统镜像..."));
                    tarballPath = downloadRootfs();
                    if (tarballPath == null) {
                        runOnUiThread(() -> {
                            appendLog("❌ 系统镜像下载失败");
                            finishInstall("下载失败", "重试");
                            Toast.makeText(this, "请检查网络连接后重试", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                }
                appendLog("✅ 系统镜像就绪: " + tarballPath);
                runOnUiThread(() -> mStepText.setText("镜像 ✅"));

                appendLog("📂 解压系统镜像（约 1-3 分钟）...");
                runOnUiThread(() -> mStepText.setText("解压系统镜像..."));

                if (ChrootManager.ensureChrootDir()) {
                    runOnUiThread(() -> {
                        appendLog("❌ 无法创建 /data/rbot 目录");
                        finishInstall("目录创建失败", "重试");
                    });
                    return;
                }

                ChrootManager.ProgressCallback extractCb = makeCallback();
                if (ChrootManager.extractRootfs(tarballPath, extractCb)) {
                    runOnUiThread(() -> finishInstall("解压失败", "重试"));
                    return;
                }
                appendLog("✅ 系统镜像解压完成");
                runOnUiThread(() -> mStepText.setText("解压 ✅"));
            } else {
                appendLog("⏭️ 系统镜像已存在，跳过解压");
            }

            // ─── Step 2: Setup chroot environment ───
            appendLog("🔧 初始化 chroot 环境...");
            runOnUiThread(() -> mStepText.setText("初始化 chroot..."));
            ChrootManager.setupChrootEnvironment(makeCallback());

            // ─── Step 3: apt update (if deps needed) ───
            if (needDeps) {
                appendLog("📡 更新软件源...");
                runOnUiThread(() -> mStepText.setText("更新软件源..."));
                if (ChrootManager.aptUpdate(makeCallback())) {
                    runOnUiThread(() -> finishInstall("软件源更新失败", "重试"));
                    return;
                }
                appendLog("✅ 软件源更新完成");

                appendLog("📦 安装系统依赖...");
                runOnUiThread(() -> mStepText.setText("安装系统依赖..."));
                if (ChrootManager.aptInstallDeps(makeCallback())) {
                    runOnUiThread(() -> finishInstall("依赖安装失败", "重试"));
                    return;
                }
                appendLog("✅ 系统依赖安装完成");

                // ─── Step 3b: Set root password ───
                appendLog("🔐 设置 root 密码...");
                runOnUiThread(() -> mStepText.setText("设置 root 密码..."));
                String defaultPassword = generateRandomPassword();
                if (ChrootManager.setRootPassword(defaultPassword)) {
                    appendLog("✅ root 密码已设置: " + defaultPassword);
                    appendLog("   (请妥善保存，用于 SSH 登录)");
                } else {
                    appendLog("⚠️ root 密码设置失败，SSH 可能无法登录");
                }
            } else {
                appendLog("⏭️ 系统依赖已存在，跳过安装");
            }

            // ─── Step 4: Clone AstrBot (if needed) ───
            if (needAstrbot) {
                appendLog("🤖 克隆 AstrBot...");
                runOnUiThread(() -> mStepText.setText("克隆 AstrBot..."));
                if (ChrootManager.cloneAstrBot(makeCallback())) {
                    runOnUiThread(() -> finishInstall("AstrBot 克隆失败", "重试"));
                    return;
                }

                appendLog("🐍 安装 Python 依赖...");
                runOnUiThread(() -> mStepText.setText("安装 Python 依赖..."));
                if (ChrootManager.pipInstallDeps(makeCallback())) {
                    runOnUiThread(() -> finishInstall("Python 依赖安装失败", "重试"));
                    return;
                }
                appendLog("✅ AstrBot 安装完成");
            } else {
                appendLog("⏭️ AstrBot 已安装，跳过");
            }

            // ─── All done ───
            runOnUiThread(() -> {
                mStepText.setText("安装完成 ✅");
                mProgressBar.setVisibility(View.GONE);
                mTitleText.setText("安装完成");
                mInstalling = false;

                appendLog("➡️ 正在跳转到主界面...");
                Intent intent = new Intent(SetupActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    // ─── Rootfs tarball finding ───

    private String findRootfsTarball() {
        if (new java.io.File(RbotConstants.LOCAL_ROOTFS_SRC).exists()) {
            appendLog("  使用本地镜像: " + RbotConstants.LOCAL_ROOTFS_SRC);
            cacheRootfsIfNeeded(RbotConstants.LOCAL_ROOTFS_SRC);
            return RbotConstants.LOCAL_ROOTFS_SRC;
        }
        if (new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists()) {
            appendLog("  使用缓存镜像: " + RbotConstants.SDCARD_ROOTFS_CACHE);
            return RbotConstants.SDCARD_ROOTFS_CACHE;
        }
        return null;
    }

    private String downloadRootfs() {
        ChrootManager.execRoot("mkdir -p " + RbotConstants.SDCARD_CACHE_DIR);

        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "curl -L --progress-bar -o " + RbotConstants.SDCARD_ROOTFS_CACHE +
            " '" + RbotConstants.GITHUB_ROOTFS_URL + "'", 600);

        if (result.success() && new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists()) {
            long size = new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).length();
            if (size > 10 * 1024 * 1024) {
                return RbotConstants.SDCARD_ROOTFS_CACHE;
            }
        }

        ChrootManager.execRoot("rm -f " + RbotConstants.SDCARD_ROOTFS_CACHE);
        return null;
    }

    private void cacheRootfsIfNeeded(String srcPath) {
        java.io.File cached = new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE);
        if (!cached.exists()) {
            ChrootManager.execRoot("mkdir -p " + RbotConstants.SDCARD_CACHE_DIR +
                " && cp '" + srcPath + "' " + RbotConstants.SDCARD_ROOTFS_CACHE);
        }
    }

    // ─── UI helpers ───

    private ChrootManager.ProgressCallback makeCallback() {
        return new ChrootManager.ProgressCallback() {
            @Override
            public void onProgress(String message) {
                appendLog("  " + message);
            }

            @Override
            public void onError(String error) {
                appendLog("  ❌ " + error);
            }
        };
    }

    private void finishInstall(String stepText, String buttonText) {
        mStepText.setText(stepText);
        mActionButton.setEnabled(true);
        mActionButton.setText(buttonText);
        mProgressBar.setVisibility(View.GONE);
        mInstalling = false;
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            mLogText.append(message + "\n");
            mLogScrollView.post(() -> mLogScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    /** Generate a random 8-character password */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
