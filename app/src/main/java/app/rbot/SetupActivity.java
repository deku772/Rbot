package app.rbot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup activity for chroot-based bot installation.
 * Interactive step-by-step: each step pauses for user confirmation.
 * Supports both AstrBot and Hermes Agent via BotManager.
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
    private RadioGroup mRgBotEngine;
    private CheckBox mCbReinstallRootfs;
    private CheckBox mCbReinstallDeps;
    private CheckBox mCbReinstallBot;
    private android.widget.EditText mEtCustomProxy;

    private BotAdapter mActiveBot;
    private BotAdapter mPendingBot; // bot selected but not yet persisted

    private boolean mInstalling = false;
    private volatile boolean mDestroyed = false;

    // Cached installation decisions (set by dialogs, consumed by install thread)
    private int mSelectedProxyIndex = 0;
    private String mSelectedAstrbotVersion = "";  // empty = latest
    private List<String> mAstrbotVersions = new ArrayList<>();
    private List<String> mAstrbotVersionArgs = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rbot_setup_minimal);

        mActiveBot = BotManager.getInstance(this).getActiveBot();

        mTitleText = findViewById(R.id.setup_title);
        mStepText = findViewById(R.id.setup_step_text);
        mLogText = findViewById(R.id.setup_log_text);
        mProgressBar = findViewById(R.id.setup_progress);
        mLogScrollView = findViewById(R.id.setup_log_scroll);
        mActionButton = findViewById(R.id.setup_action_button);
        mReinstallOptions = findViewById(R.id.reinstall_options);
        mCbReinstallRootfs = findViewById(R.id.cb_reinstall_rootfs);
        mCbReinstallDeps = findViewById(R.id.cb_reinstall_deps);
        mCbReinstallBot = findViewById(R.id.cb_reinstall_bot);
        mEtCustomProxy = findViewById(R.id.et_custom_proxy);

        // Dynamic checkbox label based on active bot
        mCbReinstallBot.setText("重装 " + mActiveBot.getName() + "（重新克隆 + 安装依赖）");

        // Show proxy section
        android.view.View proxySection = findViewById(R.id.proxy_section);
        proxySection.setVisibility(View.VISIBLE);

        mActionButton.setOnClickListener(v -> startInstallation());

        // Bot engine selection — always visible (radio group moved out of reinstall_options)
        android.view.View botEngineSection = findViewById(R.id.bot_engine_section);
        botEngineSection.setVisibility(View.GONE); // Hide bot selection since we only support AstrBot now
        mRgBotEngine = findViewById(R.id.rg_bot_engine);
        mPendingBot = mActiveBot; // default to current active bot

        // Set initial RadioGroup selection
        mRgBotEngine.check(R.id.rb_astrbot);

        // Listen for engine changes
        mRgBotEngine.setOnCheckedChangeListener((group, checkedId) -> {
            BotManager bm = BotManager.getInstance(this);
            mPendingBot = bm.getBot(BotAdapter.ID_ASTRBOT);
            mTitleText.setText("安装 AstrBot");
            mCbReinstallBot.setText("重装 AstrBot（重新克隆 + 安装依赖）");
            // Refresh status UI to show correct state for the newly selected bot
            updateStatusUI();
        });

        updateStatusUI();
    }

    private void updateStatusUI() {
        AuthManager am = AuthManager.getInstance();
        boolean useProot = am.isProotMode();
        boolean rootfsReady = useProot
            ? PRootManager.getInstance(this).isRootfsReady()
            : ChrootManager.isRootfsReady();
        // Use mPendingBot so status reflects the user's current selection, not saved preference
        boolean botInstalled = mPendingBot.isInstalled();

        if (!rootfsReady && !botInstalled) {
            mReinstallOptions.setVisibility(View.GONE);
            mActionButton.setText("开始安装");
            mStepText.setText("尚未安装");
        } else {
            mReinstallOptions.setVisibility(View.VISIBLE);
            mStepText.setText(
                "系统镜像: " + (rootfsReady ? "✅" : "❌") + "  " +
                mPendingBot.getName() + ": " + (botInstalled ? "✅" : "❌"));

            if (!rootfsReady) mCbReinstallRootfs.setChecked(true);
            if (!botInstalled) mCbReinstallBot.setChecked(true);

            // If both are ready, show "完成" button instead of "执行选中的步骤"
            if (rootfsReady && botInstalled) {
                mActionButton.setText("完成");
            } else {
                mActionButton.setText("执行选中的步骤");
            }
        }
    }

    /**
     * Main entry point — starts the interactive installation.
     * Each step shows a dialog to confirm before proceeding.
     */
    private void startInstallation() {
        if (mInstalling) return;
        mInstalling = true;

        // If button says "完成", jump directly to MainActivity
        if (mActionButton.getText().toString().equals("完成")) {
            BotManager.getInstance(this).switchTo(mPendingBot.getId());
            Toast.makeText(this, "切换到 " + mPendingBot.getName(), Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(SetupActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        mActionButton.setEnabled(true);
        mActionButton.setText("安装中...");
        mActionButton.setBackgroundResource(R.drawable.rbot_button_installing_bg);
        mActionButton.setTextColor(0xFFFFFFFF);
        mTitleText.setText("正在安装 Rbot");
        mProgressBar.setVisibility(View.VISIBLE);
        mReinstallOptions.setVisibility(View.GONE);
        mLogText.setText("");

        // Set custom proxy if provided
        String customProxy = mEtCustomProxy.getText() != null ? mEtCustomProxy.getText().toString().trim() : "";
        if (!customProxy.isEmpty()) {
            GitHubProxyManager.setCustomProxy(customProxy);
        }

        boolean reinstallRootfs = mCbReinstallRootfs.isChecked();
        boolean reinstallDeps = mCbReinstallDeps.isChecked();
        boolean reinstallBot = mCbReinstallBot.isChecked();

        // Persist the user's bot engine selection
        BotManager.getInstance(this).switchTo(mPendingBot.getId());

        // When reinstall is checked, remove markers so the steps actually execute
        AuthManager am = AuthManager.getInstance();
        boolean useProot = am.isProotMode();
        if (reinstallRootfs) {
            if (useProot) {
                new java.io.File(getFilesDir(), RbotConstants.PROOT_ROOTFS_MARKER).delete();
            } else {
                ChrootManager.execRoot("rm -f " + RbotConstants.ROOTFS_MARKER);
            }
        }
        if (reinstallBot) {
            if (useProot) {
                new java.io.File(getFilesDir(), RbotConstants.PROOT_ASTRBOT_MARKER).delete();
                PRootManager.getInstance(this).runInProot("rm -rf /root/astrbot", 30);
            } else {
                ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);
                ChrootManager.execInChroot("rm -rf " + mPendingBot.getHomePath(), 30);
            }
        }

        boolean needRootfs = useProot
            ? !PRootManager.getInstance(this).isRootfsReady() || reinstallRootfs
            : !ChrootManager.isRootfsReady() || reinstallRootfs;
        boolean needDeps = reinstallDeps;
        boolean needBot = !mPendingBot.isInstalled() || reinstallBot;

        if (!needRootfs && !needDeps && !needBot) {
            mInstalling = false;
            Toast.makeText(this, "所有组件已安装，正在跳转...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(SetupActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // Start the interactive flow on a background thread
        new Thread(() -> runInteractiveInstall(needRootfs, needDeps, needBot)).start();
    }

    /**
     * Interactive install flow — pauses at key steps for user input.
     * Uses wait/notify to block the background thread until the user responds on UI thread.
     */
    private void runInteractiveInstall(boolean needRootfs, boolean needDeps, boolean needBot) {
        AuthManager am = AuthManager.getInstance();
        boolean useProot = am.isProotMode();
        PRootManager pm = useProot ? PRootManager.getInstance(this) : null;

        // ─── Step 0: Auth check ───
        appendLog("🔍 检查权限...");
        if (useProot) {
            appendLog("✅ PRoot 免 Root 模式");
            runOnUiThread(() -> mStepText.setText("PRoot ✅"));
        } else if (!ChrootManager.isRootAvailable() && !am.isShizukuReady()) {
            runOnUiThread(() -> {
                appendLog("❌ 未获取 Root 权限");
                finishInstall("需要 Root 权限", "重试");
                Toast.makeText(this, "请授予 Root 权限或使用 PRoot 模式", Toast.LENGTH_LONG).show();
            });
            return;
        } else {
            appendLog("✅ Root 权限已获取");
            runOnUiThread(() -> mStepText.setText("Root ✅"));
        }

        // ─── Step 1: Test proxies + let user choose ───
        appendLog("🌐 测试 GitHub 连接...");
        runOnUiThread(() -> mStepText.setText("测试网络..."));
        int bestProxy = GitHubProxyManager.testProxies();
        appendLog("✅ 最快线路: " + GitHubProxyManager.getProxyName(bestProxy));

        // Show proxy selection dialog and wait for user
        final Object proxyLock = new Object();
        final boolean[] proxyConfirmed = {false};
        runOnUiThread(() -> {
            // Build proxy list with latency info
            String[] proxyDisplayNames = GitHubProxyManager.getAllProxyNamesWithLatency();
            Spinner proxySpinner = new Spinner(this);
            ArrayAdapter<String> proxyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, proxyDisplayNames);
            proxySpinner.setAdapter(proxyAdapter);
            proxySpinner.setSelection(bestProxy);
            proxySpinner.setPadding(48, 24, 48, 24);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 24, 48, 0);

            // Show all test results
            StringBuilder results = new StringBuilder("测速结果：\n");
            for (int i = 0; i < proxyDisplayNames.length; i++) {
                results.append("  ").append(proxyDisplayNames[i]).append("\n");
            }
            results.append("\n推荐: ").append(GitHubProxyManager.getProxyName(bestProxy));
            results.append("\n\n你也可以手动选择其他线路：");

            TextView label = new TextView(this);
            label.setText(results.toString());
            label.setTextSize(14);
            label.setPadding(0, 0, 0, 16);
            layout.addView(label);
            layout.addView(proxySpinner);

            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🌐 选择 GitHub 线路")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("确认", (dialog, which) -> {
                    mSelectedProxyIndex = proxySpinner.getSelectedItemPosition();
                    appendLog("  ✅ 用户选择线路: " + GitHubProxyManager.getProxyName(mSelectedProxyIndex));
                    synchronized (proxyLock) {
                        proxyConfirmed[0] = true;
                        proxyLock.notify();
                    }
                })
                .show();
        });
        synchronized (proxyLock) {
            while (!proxyConfirmed[0]) {
                try { proxyLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
        runOnUiThread(() -> mStepText.setText("网络 ✅"));

        // ─── Step 2: Extract rootfs (if needed) ───
        if (needRootfs) {
            appendLog("📦 查找系统镜像...");
            String tarballPath = findRootfsTarball();
            if (tarballPath == null) {
                appendLog("⬇️ 本地无镜像，从 GitHub 下载中（约 458MB）...");
                runOnUiThread(() -> mStepText.setText("下载系统镜像..."));
                tarballPath = downloadRootfs();
                if (tarballPath == null) {
                    runOnUiThread(() -> {
                        appendLog("❌ 系统镜像下载失败");
                        finishInstall("下载失败 — 可切换线路后重试", "重试");
                        Toast.makeText(this, "请检查网络或切换线路后重试", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }
            appendLog("✅ 系统镜像就绪: " + tarballPath);
            runOnUiThread(() -> mStepText.setText("镜像 ✅"));

            if (useProot) {
                // PRoot mode: extract rootfs to app internal storage
                appendLog("📂 解压到 PRoot 目录（约 1-3 分钟）...");
                runOnUiThread(() -> mStepText.setText("解压系统镜像..."));

                pm.ensureDirectories();
                boolean extractOk = extractProotRootfs(tarballPath);
                if (!extractOk) {
                    runOnUiThread(() -> finishInstall("解压失败", "重试"));
                    return;
                }
                pm.configureProotRootfs();
                pm.markRootfsReady();
                appendLog("✅ PRoot 系统镜像解压完成");
                runOnUiThread(() -> mStepText.setText("解压 ✅"));
            } else {
                // Chroot mode: extract to /data/rbot (needs root)
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
            }

            // Ask to restore AstrBot data if backup exists
            if (new java.io.File(RbotConstants.EXTERNAL_DATA_BACKUP).exists()) {
                final boolean[] shouldRestore = {false};
                final Object restoreLock = new Object();
                runOnUiThread(() -> {
                    new androidx.appcompat.app.AlertDialog.Builder(SetupActivity.this)
                        .setTitle("恢复 AstrBot 数据")
                        .setMessage("检测到外部存储中有 AstrBot 数据备份，是否恢复？\n路径: " + RbotConstants.EXTERNAL_DATA_BACKUP)
                        .setPositiveButton("恢复", (dialog, which) -> {
                            synchronized (restoreLock) { shouldRestore[0] = true; restoreLock.notify(); }
                        })
                        .setNegativeButton("跳过", (dialog, which) -> {
                            synchronized (restoreLock) { restoreLock.notify(); }
                        })
                        .setCancelable(false)
                        .show();
                });
                synchronized (restoreLock) {
                    try { restoreLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (shouldRestore[0]) {
                    appendLog("🔄 正在恢复 AstrBot 数据...");
                    if (ChrootManager.restoreAstrBotData(makeCallback())) {
                        appendLog("⚠️ 数据恢复失败，继续安装...");
                    } else {
                        appendLog("✅ AstrBot 数据已恢复");
                    }
                }
            }
        } else {
            appendLog("⏭️ 系统镜像已存在，跳过解压");
        }

        // ─── Step 3: Setup environment + apt ───
        if (useProot) {
            // PRoot mode — no mount setup needed, just configure rootfs
            appendLog("🔧 配置 PRoot 环境...");
            runOnUiThread(() -> mStepText.setText("配置 PRoot..."));
            pm.configureProotRootfs();
        } else {
            appendLog("🔧 初始化 chroot 环境...");
            runOnUiThread(() -> mStepText.setText("初始化 chroot..."));
            ChrootManager.setupChrootEnvironment(makeCallback());
        }

        // Always do apt update after fresh rootfs extract or when reinstalling deps
        if (needRootfs || needDeps) {
            appendLog("📡 更新软件源...");
            runOnUiThread(() -> mStepText.setText("更新软件源..."));
            if (useProot) {
                ChrootManager.CommandResult aptResult = pm.runInProotWithProgress(
                    "apt update --allow-unauthenticated", 60, makeCallback());
                if (!aptResult.success()) {
                    runOnUiThread(() -> finishInstall("软件源更新失败", "重试"));
                    return;
                }
            } else {
                if (ChrootManager.aptUpdate(makeCallback())) {
                    runOnUiThread(() -> finishInstall("软件源更新失败", "重试"));
                    return;
                }
            }
            appendLog("✅ 软件源更新完成");
        }

        if (needDeps) {
            appendLog("📦 安装系统依赖...");
            runOnUiThread(() -> mStepText.setText("安装系统依赖..."));
            if (useProot) {
                ChrootManager.CommandResult depResult = pm.runInProotWithProgress(
                    "apt install -y --allow-unauthenticated " +
                    "python3 python3-venv python3-pip python3-dev " +
                    "git curl wget gpgv coreutils procps dropbear-bin " +
                    "ca-certificates software-properties-common locales build-essential",
                    120, makeCallback());
                if (!depResult.success()) {
                    runOnUiThread(() -> finishInstall("依赖安装失败", "重试"));
                    return;
                }
                pm.runInProot("locale-gen en_US.UTF-8", 30);
            } else {
                if (ChrootManager.aptInstallDeps(makeCallback())) {
                    runOnUiThread(() -> finishInstall("依赖安装失败", "重试"));
                    return;
                }
            }
            appendLog("✅ 系统依赖安装完成");
        } else {
            appendLog("⏭️ 系统依赖已存在，跳过安装");
        }

        // Set root password after fresh rootfs (default is 'rbot', change to random)
        if (needRootfs) {
            appendLog("🔐 设置 root 密码...");
            runOnUiThread(() -> mStepText.setText("设置 root 密码..."));
            String defaultPassword = generateRandomPassword();
            if (useProot) {
                // PRoot mode: set password via proot
                ChrootManager.CommandResult passResult = pm.runInProot(
                    "HASH=$(openssl passwd -6 -salt rbotsalt '" + defaultPassword + "') && " +
                    "if grep -q '^root:' /etc/shadow; then " +
                    "  sed -i \"s|^root:[^:]*:|root:$HASH:|\" /etc/shadow; " +
                    "else " +
                    "  echo \"root:$HASH:19000:0:99999:7:::\" >> /etc/shadow; " +
                    "fi && chmod 600 /etc/shadow && " +
                    "printf '%s' '" + defaultPassword + "' > /root/.rbot_pass && chmod 600 /root/.rbot_pass",
                    15);
                if (passResult.success()) {
                    appendLog("✅ root 密码已设置: " + defaultPassword);
                    appendLog("   (请妥善保存，用于 SSH 登录)");
                } else {
                    appendLog("⚠️ root 密码设置失败，SSH 可能无法登录");
                }
            } else if (ChrootManager.setRootPassword(defaultPassword)) {
                appendLog("✅ root 密码已设置: " + defaultPassword);
                appendLog("   (请妥善保存，用于 SSH 登录)");
            } else {
                appendLog("⚠️ root 密码设置失败，SSH 可能无法登录");
            }
        }

        // ─── Step 4: Install Bot (AstrBot or Hermes) ───
        if (needBot) {
            if (mPendingBot.getId().equals(BotAdapter.ID_ASTRBOT)) {
                // AstrBot: version selection + clone + pip install
                appendLog("📡 获取 AstrBot 版本列表...");
                List<String> releases = GitHubProxyManager.fetchAstrBotReleases(5);

                mAstrbotVersions.clear();
                mAstrbotVersionArgs.clear();
                mAstrbotVersions.add("最新版 (main)");
                mAstrbotVersionArgs.add("");

                if (releases != null) {
                    for (String tag : releases) {
                        mAstrbotVersions.add(tag);
                        mAstrbotVersionArgs.add(tag);
                    }
                } else {
                    mAstrbotVersions.add("v4.22.3");
                    mAstrbotVersionArgs.add("v4.22.3");
                    appendLog("  ⚠️ 无法获取版本列表，使用默认版本");
                }

                final Object versionLock = new Object();
                final boolean[] versionConfirmed = {false};
                runOnUiThread(() -> {
                    Spinner versionSpinner = new Spinner(this);
                    ArrayAdapter<String> versionAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item,
                        mAstrbotVersions.toArray(new String[0]));
                    versionSpinner.setAdapter(versionAdapter);
                    versionSpinner.setPadding(48, 24, 48, 24);

                    Spinner proxySpinner = new Spinner(this);
                    String[] proxyDisplayNames = GitHubProxyManager.getAllProxyNamesWithLatency();
                    ArrayAdapter<String> proxyAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, proxyDisplayNames);
                    proxySpinner.setAdapter(proxyAdapter);
                    proxySpinner.setSelection(mSelectedProxyIndex);
                    proxySpinner.setPadding(48, 24, 48, 24);

                    LinearLayout layout = new LinearLayout(this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(48, 24, 48, 0);

                    TextView versionLabel = new TextView(this);
                    versionLabel.setText("选择版本：");
                    versionLabel.setTextSize(15);
                    versionLabel.setPadding(0, 0, 0, 8);
                    layout.addView(versionLabel);
                    layout.addView(versionSpinner);

                    TextView proxyLabel = new TextView(this);
                    proxyLabel.setText("\n下载线路：");
                    proxyLabel.setTextSize(15);
                    proxyLabel.setPadding(0, 8, 0, 8);
                    layout.addView(proxyLabel);
                    layout.addView(proxySpinner);

                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("🤖 安装 " + mPendingBot.getName())
                        .setMessage("确认后开始克隆安装：")
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton("确认安装", (dialog, which) -> {
                            mSelectedAstrbotVersion = mAstrbotVersionArgs.get(versionSpinner.getSelectedItemPosition());
                            mSelectedProxyIndex = proxySpinner.getSelectedItemPosition();
                            String versionDisplay = mSelectedAstrbotVersion.isEmpty() ? "最新版" : mSelectedAstrbotVersion;
                            appendLog("  ✅ 用户选择: " + versionDisplay + " | 线路: " + GitHubProxyManager.getProxyName(mSelectedProxyIndex));
                            synchronized (versionLock) {
                                versionConfirmed[0] = true;
                                versionLock.notify();
                            }
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            appendLog("  ⏭️ 用户取消 " + mActiveBot.getName() + " 安装");
                            synchronized (versionLock) { versionLock.notify(); }
                        })
                        .show();
                });
                synchronized (versionLock) {
                    while (!versionConfirmed[0]) {
                        try { versionLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }

                if (!versionConfirmed[0]) {
                    appendLog("⏭️ 跳过 " + mPendingBot.getName() + " 安装");
                } else {
                    String versionDisplay = mSelectedAstrbotVersion.isEmpty() ? "最新版" : mSelectedAstrbotVersion;
                    appendLog("🤖 克隆 " + mPendingBot.getName() + " (" + versionDisplay + ")...");
                    runOnUiThread(() -> mStepText.setText("克隆 " + mPendingBot.getName() + "..."));

                    if (ChrootManager.cloneAstrBotWithProxy(makeCallback(), mSelectedAstrbotVersion, mSelectedProxyIndex)) {
                        runOnUiThread(() -> finishInstall(mPendingBot.getName() + " 克隆失败 — 可切换线路后重试", "重试"));
                        return;
                    }

                    appendLog("🐍 安装 Python 依赖...");
                    runOnUiThread(() -> mStepText.setText("安装 Python 依赖..."));
                    if (ChrootManager.pipInstallDeps(makeCallback())) {
                        runOnUiThread(() -> finishInstall("Python 依赖安装失败", "重试"));
                        return;
                    }
                    appendLog("✅ " + mPendingBot.getName() + " 安装完成");
                    runOnUiThread(() -> {
                        mStepText.setText(mPendingBot.getName() + " 安装完成 ✅");
                        mActionButton.setText("安装完成");
                        mActionButton.setBackgroundResource(R.drawable.rbot_button_bg);
                        mActionButton.setTextColor(0xFF1A1A1A);
                    });
                }
            } else {
                // Hermes (or other bots): confirm + install via adapter
                final Object confirmLock = new Object();
                final boolean[] botConfirmed = {false};
                runOnUiThread(() -> {
                    Spinner proxySpinner = new Spinner(this);
                    String[] proxyDisplayNames = GitHubProxyManager.getAllProxyNamesWithLatency();
                    ArrayAdapter<String> proxyAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, proxyDisplayNames);
                    proxySpinner.setAdapter(proxyAdapter);
                    proxySpinner.setSelection(mSelectedProxyIndex);
                    proxySpinner.setPadding(48, 24, 48, 24);

                    LinearLayout layout = new LinearLayout(this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(48, 24, 48, 0);

                    TextView info = new TextView(this);
                    info.setText(mPendingBot.getName() + " 将从 GitHub 克隆并安装到 chroot 环境。\n" +
                        "支持平台: " + mPendingBot.getSupportedPlatforms() + "\n\n选择下载线路：");
                    info.setTextSize(14);
                    layout.addView(info);
                    layout.addView(proxySpinner);

                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("🤖 安装 " + mPendingBot.getName())
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton("确认安装", (dialog, which) -> {
                            mSelectedProxyIndex = proxySpinner.getSelectedItemPosition();
                            appendLog("  ✅ 用户选择线路: " + GitHubProxyManager.getProxyName(mSelectedProxyIndex));
                            synchronized (confirmLock) { botConfirmed[0] = true; confirmLock.notify(); }
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            appendLog("  ⏭️ 用户取消 " + mPendingBot.getName() + " 安装");
                            synchronized (confirmLock) { confirmLock.notify(); }
                        })
                        .show();
                });
                synchronized (confirmLock) {
                    while (!botConfirmed[0]) {
                        try { confirmLock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }

                if (!botConfirmed[0]) {
                    appendLog("⏭️ 跳过 " + mPendingBot.getName() + " 安装");
                } else {
                    appendLog("🤖 安装 " + mPendingBot.getName() + "...");
                    runOnUiThread(() -> mStepText.setText("安装 " + mPendingBot.getName() + "..."));

                    if (mPendingBot.install(makeCallback())) {
                        runOnUiThread(() -> finishInstall(mPendingBot.getName() + " 安装失败 — 可切换线路后重试", "重试"));
                        return;
                    }
                    appendLog("✅ " + mPendingBot.getName() + " 安装完成");
                    // Update UI so user sees immediate feedback even before "All done" section
                    runOnUiThread(() -> {
                        mStepText.setText(mPendingBot.getName() + " 安装完成 ✅");
                        mActionButton.setText("安装完成");
                        mActionButton.setBackgroundResource(R.drawable.rbot_button_bg);
                        mActionButton.setTextColor(0xFF1A1A1A);
                    });
                }
            }
        } else {
            appendLog("⏭️ " + mPendingBot.getName() + " 已安装，跳过");
        }

        // ─── All done ───
        runOnUiThread(() -> {
            mStepText.setText("安装完成 ✅");
            mProgressBar.setVisibility(View.GONE);
            mTitleText.setText("安装完成");
            mInstalling = false;
            mActionButton.setBackgroundResource(R.drawable.rbot_button_bg);
            mActionButton.setTextColor(0xFF1A1A1A);

            appendLog("➡️ 正在跳转到主界面...");
            Intent intent = new Intent(SetupActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // ─── Rootfs tarball finding ───

    private String findRootfsTarball() {
        String cachePath = RbotConstants.SDCARD_ROOTFS_CACHE;
        if (new java.io.File(cachePath).exists()) {
            appendLog("  校验本地镜像...");
            if (verifyRootfsMd5(cachePath)) {
                appendLog("  ✅ 找到本地镜像: " + cachePath);
                return cachePath;
            }
            appendLog("  ⚠️ 本地镜像校验失败（可能下载不完整），将重新下载");
            new java.io.File(cachePath).delete();
        }
        appendLog("  本地未找到镜像，检测路径: " + cachePath);
        appendLog("  你可以手动放置 ubuntu24_rbot.tar.gz 到该路径跳过下载");
        return null;
    }

    private String downloadRootfs() {
        boolean useProot = AuthManager.getInstance().isProotMode();
        String downloadUrl = GitHubProxyManager.buildUrl(RbotConstants.GITHUB_ROOTFS_URL, mSelectedProxyIndex);

        if (useProot) {
            // PRoot mode: use Java HTTP download (no root required)
            appendLog("  从 GitHub 下载中（约 458MB）...");
            try {
                PRootManager pm = PRootManager.getInstance(this);
                // Ensure parent dir exists (may need storage permission for sdcard)
                new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).getParentFile().mkdirs();
                pm.downloadFile(downloadUrl, RbotConstants.SDCARD_ROOTFS_CACHE, makeCallback());
            } catch (IOException e) {
                appendLog("  ❌ 下载失败: " + e.getMessage());
                // Fallback to direct URL
                if (!downloadUrl.equals(RbotConstants.GITHUB_ROOTFS_URL)) {
                    appendLog("  尝试直连下载...");
                    try {
                        PRootManager pm = PRootManager.getInstance(this);
                        pm.downloadFile(RbotConstants.GITHUB_ROOTFS_URL,
                            RbotConstants.SDCARD_ROOTFS_CACHE, makeCallback());
                    } catch (IOException e2) {
                        appendLog("  ❌ 直连下载也失败: " + e2.getMessage());
                        new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).delete();
                        return null;
                    }
                } else {
                    new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).delete();
                    return null;
                }
            }

            if (new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists()) {
                appendLog("  校验下载文件...");
                if (verifyRootfsMd5(RbotConstants.SDCARD_ROOTFS_CACHE)) {
                    return RbotConstants.SDCARD_ROOTFS_CACHE;
                }
                appendLog("  ⚠️ 下载文件校验失败（可能不完整）");
            }
            new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).delete();
            return null;
        }

        // Chroot mode: use curl via root
        ChrootManager.execRoot("mkdir -p " + RbotConstants.SDCARD_CACHE_DIR);
        appendLog("  从 GitHub 下载中（约 458MB）...");
        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "curl -L --progress-bar -o " + RbotConstants.SDCARD_ROOTFS_CACHE +
            " '" + downloadUrl + "'", 600);
        if (result.success() && new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists()) {
            appendLog("  校验下载文件...");
            if (verifyRootfsMd5(RbotConstants.SDCARD_ROOTFS_CACHE)) {
                return RbotConstants.SDCARD_ROOTFS_CACHE;
            }
            appendLog("  ⚠️ 下载文件校验失败（可能不完整）");
        }
        ChrootManager.execRoot("rm -f " + RbotConstants.SDCARD_ROOTFS_CACHE);
        return null;
    }

    /**
     * Extract rootfs tarball for PRoot mode into app internal storage.
     * Step 1: Decompress .gz using Java GZIPInputStream (Android toybox tar lacks -z).
     * Step 2: Extract plain .tar using system tar command.
     * Returns true on success, false on failure.
     */
    private boolean extractProotRootfs(String tarballPath) {
        PRootManager pm = PRootManager.getInstance(this);
        String rootfsDir = pm.getRootfsDir();

        // Clean any previous extraction
        deleteRecursively(new java.io.File(rootfsDir));
        new java.io.File(rootfsDir).mkdirs();

        // Step 1: gunzip (pure Java)
        appendLog("📂 解压 gzip（约 1-2 分钟）...");
        String tarPath;
        try {
            tarPath = decompressGzip(tarballPath);
            appendLog("✅ gzip 解压完成 → " + tarPath);
        } catch (Exception e) {
            appendLog("❌ gzip 解压失败: " + e.getMessage());
            return false;
        }

        // Step 2: tar extract (system command, plain tar needs no -z)
        appendLog("📂 提取 tar（约 1-2 分钟）...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/tar", "-xf", tarPath, "-C", rootfsDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read stderr for errors
            StringBuilder errOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errOutput.append(line).append("\n");
                }
            }

            boolean exited = process.waitFor(600, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                appendLog("❌ 解压超时");
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                appendLog("❌ tar 解压失败 (exit " + exitCode + "): " + errOutput.toString().trim());
                return false;
            }
        } catch (Exception e) {
            appendLog("❌ tar 解压异常: " + e.getMessage());
            return false;
        } finally {
            // Clean intermediate .tar file
            new java.io.File(tarPath).delete();
        }

        // Verify critical files
        if (!new java.io.File(rootfsDir + "/bin/bash").exists()) {
            appendLog("❌ 解压后关键文件缺失");
            return false;
        }

        appendLog("✅ 系统镜像解压完成");
        return true;
    }

    /** Decompress a .gz file to a temp file using pure Java. Returns path to decompressed file. */
    private String decompressGzip(String gzPath) throws IOException {
        String tarPath = gzPath + ".tar";
        try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(tarPath));
             java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(gzPath)))) {
            byte[] buf = new byte[8192];
            long totalWritten = 0;
            int bytesRead;
            long lastProgressMb = 0;
            while ((bytesRead = gzis.read(buf)) != -1) {
                bos.write(buf, 0, bytesRead);
                totalWritten += bytesRead;
                long mb = totalWritten / (1024 * 1024);
                if (mb > lastProgressMb && mb % 50 == 0) {
                    appendLog("  已解压 " + mb + " MB...");
                    lastProgressMb = mb;
                }
            }
        }
        return tarPath;
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
        if (mDestroyed) return;
        mStepText.setText(stepText);
        mActionButton.setEnabled(true);
        mActionButton.setText(buttonText);
        mActionButton.setBackgroundResource(R.drawable.rbot_button_bg);
        mActionButton.setTextColor(0xFF1A1A1A);
        mProgressBar.setVisibility(View.GONE);
        mInstalling = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
    }

    private void appendLog(String message) {
        if (mDestroyed) return;
        // Also write to OpLog so LogActivity can see it
        if (message.startsWith("  ") || message.startsWith("❌") || message.startsWith("⚠️")) {
            OpLog.progress(message.trim());
        } else if (message.contains("失败")) {
            OpLog.error(message.trim());
        } else {
            OpLog.log(message.trim());
        }
        runOnUiThread(() -> {
            if (mDestroyed) return;
            String oldText = mLogText.getText().toString();
            mLogText.setText(message + "\n" + oldText);
            mLogScrollView.post(() -> mLogScrollView.scrollTo(0, 0));
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

    /** Verify rootfs tarball MD5 — detects incomplete/corrupted downloads */
    private boolean verifyRootfsMd5(String path) {
        // Try Java MD5 first (works without root, always available)
        String javaMd5 = PRootManager.computeMd5(path);
        if (javaMd5 != null) {
            boolean match = javaMd5.equalsIgnoreCase(RbotConstants.ROOTFS_MD5);
            if (!match) {
                appendLog("  MD5: 期望 " + RbotConstants.ROOTFS_MD5);
                appendLog("  MD5: 实际 " + javaMd5);
            }
            return match;
        }

        // Fallback to md5sum via root (for chroot mode if Java MD5 fails)
        try {
            ChrootManager.CommandResult result = ChrootManager.execRoot("md5sum " + path, 30);
            if (result.success()) {
                String actual = result.stdout().trim().split("\\s+")[0];
                boolean match = actual.equalsIgnoreCase(RbotConstants.ROOTFS_MD5);
                if (!match) {
                    appendLog("  MD5: 期望 " + RbotConstants.ROOTFS_MD5);
                    appendLog("  MD5: 实际 " + actual);
                }
                return match;
            }
        } catch (Exception e) {
            appendLog("  ⚠️ MD5校验异常: " + e.getMessage());
        }
        return false;
    }

    /** Recursively delete a directory */
    private void deleteRecursively(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
