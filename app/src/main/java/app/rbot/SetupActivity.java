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
        // Check both PRoot and Chroot rootfs status independently
        PRootManager pm = PRootManager.getInstance(this);
        boolean prootRootfsReady = pm.isRootfsReady();
        boolean chrootRootfsReady = ChrootManager.isRootfsReady();
        boolean rootfsReady = prootRootfsReady || chrootRootfsReady;
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
        // Run proxy test in background — if it times out, default to gh-proxy
        final int[] bestProxy = {0};
        Thread proxyThread = new Thread(() -> {
            bestProxy[0] = GitHubProxyManager.testProxies();
        });
        proxyThread.start();
        try { proxyThread.join(15000); } catch (InterruptedException ignored) {}
        if (proxyThread.isAlive()) {
            appendLog("⚠️ 代理测试超时，默认使用 gh-proxy");
            bestProxy[0] = 1; // default to first gh-proxy
        } else {
            appendLog("✅ 最快线路: " + GitHubProxyManager.getProxyName(bestProxy[0]));
        }
        int selectedProxy = bestProxy[0]; // copy for use below

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
            proxySpinner.setSelection(selectedProxy);
            proxySpinner.setPadding(48, 24, 48, 24);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 24, 48, 0);

            // Show all test results
            StringBuilder results = new StringBuilder("测速结果：\n");
            for (int i = 0; i < proxyDisplayNames.length; i++) {
                results.append("  ").append(proxyDisplayNames[i]).append("\n");
            }
            results.append("\n推荐: ").append(GitHubProxyManager.getProxyName(selectedProxy));
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
            
            // PRoot 模式下，如果 sdcard 文件无法直接读取（权限问题），
            // 尝试通过 Java NIO 复制到内部存储后再校验
            if (tarballPath == null && useProot) {
                String sdcardPath = RbotConstants.SDCARD_ROOTFS_CACHE;
                java.io.File sdcardFile = new java.io.File(sdcardPath);
                if (sdcardFile.exists() && sdcardFile.length() > 400 * 1024 * 1024) {
                    appendLog("  sdcard 镜像存在但可能无法直接读取，复制到内部存储...");
                    java.io.File localFile = new java.io.File(getFilesDir(), "rootfs-cache.tar.gz");
                    try (java.io.InputStream is = new java.io.FileInputStream(sdcardFile);
                         java.io.OutputStream os = new java.io.FileOutputStream(localFile)) {
                        long copied = is.transferTo(os);
                        appendLog("  已复制 " + (copied / 1024 / 1024) + " MB 到内部存储");
                        tarballPath = localFile.getAbsolutePath();
                    } catch (Exception e) {
                        appendLog("  复制失败: " + e.getMessage());
                    }
                }
            }
            
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
            // PRoot mode — configure rootfs (sources.list, apt config, DNS, etc.)
            appendLog("🔧 配置 PRoot 环境...");
            runOnUiThread(() -> mStepText.setText("配置 PRoot..."));
            pm.configureProotRootfs();
            // Always update sources.list to use Tsinghua mirror (even if rootfs existed)
            pm.updateSourcesList();
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
                // First test network connectivity inside proot
                appendLog("🌐 测试 PRoot 容器网络...");
                ChrootManager.CommandResult netTest = pm.runInProot(
                    "ping -c 1 -W 3 8.8.8.8 2>&1 || echo PING_FAIL", 10);
                appendLog("  网络测试: " + netTest.stdout().trim());
                ChrootManager.CommandResult dnsTest = pm.runInProot(
                    "getent hosts mirrors.tuna.tsinghua.edu.cn 2>&1 || echo DNS_FAIL", 10);
                appendLog("  DNS 测试: " + dnsTest.stdout().trim());
                
                ChrootManager.CommandResult aptResult = pm.runInProotWithProgress(
                    "apt update --allow-unauthenticated -o Debug::Acquire::http=yes", 120, makeCallback());
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
        String sdcardPath = RbotConstants.SDCARD_ROOTFS_CACHE;
        java.io.File sdcardFile = new java.io.File(sdcardPath);
        boolean useProot = AuthManager.getInstance().isProotMode();
        
        if (sdcardFile.exists()) {
            long fileSize = sdcardFile.length();
            appendLog("  发现 sdcard 镜像: " + sdcardPath + " (" + (fileSize / 1024 / 1024) + " MB)");
            
            // 基本文件大小检查（rootfs 应该至少 400MB）
            if (fileSize < 400 * 1024 * 1024) {
                appendLog("  ⚠️ 文件太小（可能下载不完整），将重新下载");
                sdcardFile.delete();
                return null;
            }
            
            // PRoot 模式下，sdcard 文件可能因 Android 分区存储限制无法直接读取。
            // 先尝试复制到 app 内部可写目录，再校验+解压。
            if (useProot) {
                java.io.File localFile = new java.io.File(getFilesDir(), "rootfs-cache.tar.gz");
                if (!localFile.exists() || localFile.length() != fileSize) {
                    appendLog("  📋 PRoot 模式：复制镜像到内部存储（约 1-3 分钟）...");
                    try (java.io.InputStream is = new java.io.FileInputStream(sdcardFile);
                         java.io.OutputStream os = new java.io.FileOutputStream(localFile)) {
                        long total = 0;
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            total += n;
                        }
                        appendLog("  ✅ 已复制 " + (total / 1024 / 1024) + " MB");
                    } catch (Exception e) {
                        appendLog("  ⚠️ sdcard 复制失败: " + e.getClass().getSimpleName());
                        appendLog("  将尝试通过系统 cat 命令复制...");
                        // Fallback: 用 /system/bin/cat 绕过存储限制
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{
                                "/system/bin/cat", sdcardPath
                            });
                            try (java.io.InputStream is = p.getInputStream();
                                 java.io.OutputStream os = new java.io.FileOutputStream(localFile)) {
                                long total = 0;
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    os.write(buf, 0, n);
                                    total += n;
                                }
                                p.waitFor();
                                appendLog("  ✅ 已复制 " + (total / 1024 / 1024) + " MB（via cat）");
                            }
                        } catch (Exception e2) {
                            appendLog("  ❌ 复制也失败: " + e2.getClass().getSimpleName());
                            appendLog("  请授予存储权限后重试，或手动下载镜像");
                            return null;
                        }
                    }
                }
                if (localFile.exists() && localFile.length() > 400 * 1024 * 1024) {
                    sdcardPath = localFile.getAbsolutePath();
                    sdcardFile = localFile;
                    appendLog("  使用内部存储镜像: " + sdcardPath);
                } else {
                    appendLog("  ❌ 内部存储镜像不可用，需要下载");
                }
            }
            
            appendLog("  校验镜像 MD5...");
            Boolean md5Result = verifyRootfsMd5(sdcardPath);
            if (Boolean.TRUE.equals(md5Result)) {
                appendLog("  ✅ 镜像校验通过");
                return sdcardPath;
            }
            
            if (md5Result == null) {
                appendLog("  ⚠️ MD5 校验跳过（文件不可读），但大小合理，使用本地镜像");
                return sdcardPath;
            }
            
            appendLog("  ⚠️ MD5 不匹配，期望: " + RbotConstants.ROOTFS_MD5);
            sdcardFile.delete();
        }
        
        appendLog("  本地未找到镜像，检测路径: " + RbotConstants.SDCARD_ROOTFS_CACHE);
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
                Boolean md5Ok = verifyRootfsMd5(RbotConstants.SDCARD_ROOTFS_CACHE);
                if (Boolean.TRUE.equals(md5Ok)) {
                    return RbotConstants.SDCARD_ROOTFS_CACHE;
                }
                if (md5Ok == null) {
                    appendLog("  ⚠️ MD5 校验跳过，使用下载文件");
                    return RbotConstants.SDCARD_ROOTFS_CACHE;
                }
                appendLog("  ⚠️ 下载文件 MD5 不完整，重新下载");
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
            Boolean md5Ok = verifyRootfsMd5(RbotConstants.SDCARD_ROOTFS_CACHE);
            if (Boolean.TRUE.equals(md5Ok) || md5Ok == null) {
                return RbotConstants.SDCARD_ROOTFS_CACHE;
            }
            appendLog("  ⚠️ 下载文件校验失败（可能不完整）");
        }
        ChrootManager.execRoot("rm -f " + RbotConstants.SDCARD_ROOTFS_CACHE);
        return null;
    }

    /**
     * Extract rootfs tarball (.tar.gz) for PRoot mode into app internal storage.
     * Pure Java implementation — no external commands needed.
     * Android toybox tar doesn't support --no-same-owner, so we parse tar ourselves.
     * Returns true on success, false on failure.
     */
    private boolean extractProotRootfs(String tarballPath) {
        PRootManager pm = PRootManager.getInstance(this);
        String rootfsDir = pm.getRootfsDir();

        // Clean any previous extraction
        deleteRecursively(new java.io.File(rootfsDir));
        new java.io.File(rootfsDir).mkdirs();

        appendLog("📂 解压系统镜像（约 2-5 分钟）...");
        try {
            int fileCount = extractTarGz(tarballPath, rootfsDir);
            appendLog("✅ 已提取 " + fileCount + " 个文件");
        } catch (Exception e) {
            appendLog("❌ 解压异常: " + e.getMessage());
            return false;
        }

        // Verify critical files
        if (!new java.io.File(rootfsDir + "/bin/bash").exists()) {
            appendLog("❌ 解压后关键文件缺失（/bin/bash 不存在）");
            return false;
        }

        appendLog("✅ 系统镜像解压完成");
        return true;
    }

    /**
     * Extract a .tar.gz file to the given directory using pure Java.
     * Handles: regular files ('0'/'\0'), directories ('5'), symlinks ('2'),
     *          GNU long filenames ('L'), and Pax headers ('x').
     * Returns the number of entries extracted.
     */
    private int extractTarGz(String gzPath, String destDir) throws Exception {
        int fileCount = 0;
        long lastProgressTime = 0;
        String pendingLongName = null;  // GNU long filename from '././@LongLink' entry

        try (java.io.InputStream raw = new java.io.FileInputStream(gzPath);
             java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(raw, 65536)) {

            byte[] header = new byte[512];
            byte[] fileBuf = new byte[8192];

            while (true) {
                // Read exactly 512 bytes for tar header
                int totalRead = 0;
                while (totalRead < 512) {
                    int r = gzis.read(header, totalRead, 512 - totalRead);
                    if (r == -1) return fileCount; // clean EOF
                    totalRead += r;
                }

                // Two consecutive zero blocks = end of archive
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) { allZero = false; break; }
                }
                if (allZero) return fileCount;

                // Parse type flag (offset 156, 1 byte)
                char typeFlag = (char) header[156];
                if (typeFlag == 0) typeFlag = '0'; // old-style tar uses '\0' for regular files

                // Parse size (offset 124, 12 bytes, octal ASCII, null-terminated)
                String sizeStr = new String(header, 124, 11, java.nio.charset.StandardCharsets.US_ASCII).trim();
                long size = 0;
                try {
                    size = Long.parseLong(sizeStr, 8);
                } catch (NumberFormatException e) {
                    size = 0;
                }

                // Parse name (offset 0, 100 bytes, null-terminated)
                String name = readTarField(header, 0, 100);

                // Parse link name (offset 157, 100 bytes)
                String linkName = readTarField(header, 157, 100);

                // Parse prefix (offset 345, 155 bytes, POSIX.1-2001)
                String prefix = readTarField(header, 345, 155);

                // Build full path
                String fullPath = name;
                if (!prefix.isEmpty() && !prefix.equals("./")) {
                    fullPath = prefix + "/" + name;
                }

                // Skip leading ./
                if (fullPath.startsWith("./")) {
                    fullPath = fullPath.substring(2);
                }

                // Handle GNU long filename
                if (typeFlag == 'L') {
                    // Content of this entry is the long filename (next iteration uses it)
                    pendingLongName = readLongContent(gzis, size);
                    skipPadding(gzis, size);
                    fileCount++;
                    continue;
                }

                // Handle Pax extended header
                if (typeFlag == 'x') {
                    // Content has key=value pairs, we skip but could parse if needed
                    skipLongContent(gzis, size);
                    skipPadding(gzis, size);
                    fileCount++;
                    continue;
                }

                // Apply pending long filename
                if (pendingLongName != null) {
                    fullPath = pendingLongName.startsWith("./") ? pendingLongName.substring(2) : pendingLongName;
                    pendingLongName = null;
                }

                if (fullPath.isEmpty()) continue;

                java.io.File destFile = new java.io.File(destDir, fullPath);

                switch (typeFlag) {
                    case '5': // Directory
                        destFile.mkdirs();
                        break;

                    case '2': // Symlink
                        destFile.getParentFile().mkdirs();
                        if (!linkName.isEmpty()) {
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    destFile.toPath(),
                                    java.nio.file.Paths.get(linkName));
                            } catch (Exception e) {
                                // Symlink may fail, skip
                            }
                        }
                        break;

                    case '0': // Regular file
                    case '7': // contiguous file (treat as regular)
                    case '\0': // old-style regular file
                        destFile.getParentFile().mkdirs();
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                            long remaining = size;
                            while (remaining > 0) {
                                int toRead = (int) Math.min(fileBuf.length, remaining);
                                int bytesRead = gzis.read(fileBuf, 0, toRead);
                                if (bytesRead == -1) break;
                                fos.write(fileBuf, 0, bytesRead);
                                remaining -= bytesRead;
                            }
                        }
                        skipPadding(gzis, size);
                        // Set execute permission if mode has any execute bit
                        if (header[100] != 0) {
                            String modeField = new String(header, 100, 7, java.nio.charset.StandardCharsets.US_ASCII).trim();
                            try {
                                int mode = Integer.parseInt(modeField, 8);
                                if ((mode & 0111) != 0) destFile.setExecutable(true);
                            } catch (NumberFormatException ignored) {}
                        }
                        break;

                    default:
                        // Unknown type — skip data blocks
                        skipLongContent(gzis, size);
                        skipPadding(gzis, size);
                        break;
                }

                fileCount++;
                long now = System.currentTimeMillis();
                if (fileCount % 2000 == 0 && now - lastProgressTime > 2000) {
                    appendLog("  已提取 " + fileCount + " 个文件...");
                    lastProgressTime = now;
                }
            }
        }
    }

    /** Read a null-terminated, space-padded field from tar header */
    private String readTarField(byte[] buf, int offset, int maxLen) {
        int end = offset;
        int limit = offset + maxLen;
        while (end < limit && buf[end] != 0) end++;
        // Trim trailing spaces and nulls
        while (end > offset && (buf[end - 1] == 0 || buf[end - 1] == ' ')) end--;
        return new String(buf, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Read the content of a long filename entry (used for GNU tar 'L' type) */
    private String readLongContent(java.io.InputStream in, long size) throws Exception {
        byte[] content = new byte[(int) size];
        int totalRead = 0;
        while (totalRead < size) {
            int r = in.read(content, totalRead, (int) size - totalRead);
            if (r == -1) break;
            totalRead += r;
        }
        // The long filename is null-terminated
        int len = 0;
        while (len < content.length && content[len] != 0) len++;
        return new String(content, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Skip content bytes for Pax header or other entries with data */
    private void skipLongContent(java.io.InputStream in, long size) throws Exception {
        long remaining = size;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int r = in.read(buf, 0, toRead);
            if (r == -1) break;
            remaining -= r;
        }
    }

    /** Skip padding to 512-byte boundary after a data block */
    private void skipPadding(java.io.InputStream in, long dataSize) throws Exception {
        long padding = (512 - (dataSize % 512)) % 512;
        if (padding > 0) {
            long skipped = 0;
            while (skipped < padding) {
                long s = in.skip(padding - skipped);
                if (s <= 0) break;
                skipped += s;
            }
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

    /** Verify rootfs tarball MD5 using pure Java (no root required).
     *  Returns: true = match, false = mismatch, null = skipped */
    private Boolean verifyRootfsMd5(String path) {
        appendLog("  计算文件 MD5（纯 Java，无 root 需求）...");
        String javaMd5 = PRootManager.computeMd5(path);
        if (javaMd5 != null) {
            boolean match = javaMd5.equalsIgnoreCase(RbotConstants.ROOTFS_MD5);
            if (!match) {
                appendLog("  ❌ MD5 不匹配:");
                appendLog("    期望: " + RbotConstants.ROOTFS_MD5);
                appendLog("    实际: " + javaMd5);
            } else {
                appendLog("  ✅ MD5 校验通过");
            }
            return match;
        }
        // Java MD5 失败（文件不可读等），跳过校验让用户决定
        appendLog("  ⚠️ MD5 计算失败（文件可能不可读），跳过校验");
        return null;
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
