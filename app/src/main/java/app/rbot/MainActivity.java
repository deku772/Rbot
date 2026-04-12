package app.rbot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import app.rbot.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Main activity for Rbot: status + start/stop + log viewer.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "rbot_gateway";

    private TextView mStatusText;
    private TextView mLogText;
    private Button mStartButton;
    private Button mStopButton;
    private Button mSetupButton;
    private Button mBackupButton;
    private Button mRestoreButton;
    private CardView mSshInfoPanel;
    private TextView mSshInfo;
    private TextView mSshStatus;
    private TextView mSshPassword;
    private Button mSshToggleButton;
    private boolean mSshStarting = false;
    private ScrollView mLogScrollView;
    private boolean mBackupInProgress = false;
    private CardView mWebuiPanel;
    private TextView mWebuiUrl;
    private Button mOpenWebuiButton;

    // Tab views
    private Button mTabLogButton;
    private Button mTabManageButton;
    private View mLogPanel;
    private View mManagePanel;

    // Manage buttons
    private Button mReinstallDepsButton;
    private Button mReinstallAstrbotButton;
    private Button mResetPasswordButton;
    private Button mRepairEnvButton;
    private Button mCleanInstallButton;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private RbotService mService;
    private boolean mBound = false;
    private boolean mLogPolling = false;
    /** Last tail output — used to detect new content */
    private String mLastLogTail = "";
    /** Lock to prevent concurrent poll runs from interleaving */
    private final Object mLogLock = new Object();
    /** Max log buffer lines (to prevent unbounded growth) */
    private static final int MAX_LOG_LINES = 200;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RbotService.LocalBinder binder = (RbotService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            new Thread(MainActivity.this::refreshStatusOffThread).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusText = findViewById(R.id.status_text);
        mLogText = findViewById(R.id.log_text);
        mLogScrollView = findViewById(R.id.log_scroll);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mSetupButton = findViewById(R.id.btn_setup);
        mWebuiPanel = findViewById(R.id.webui_panel);
        mWebuiUrl = findViewById(R.id.webui_url);
        mOpenWebuiButton = findViewById(R.id.btn_open_webui);
        mBackupButton = findViewById(R.id.btn_backup);
        mRestoreButton = findViewById(R.id.btn_restore);
        mSshInfoPanel = findViewById(R.id.ssh_info_panel);
        mSshInfo = findViewById(R.id.ssh_info);
        mSshStatus = findViewById(R.id.ssh_status);
        mSshPassword = findViewById(R.id.ssh_password);
        mSshToggleButton = findViewById(R.id.btn_ssh_toggle);

        // Tab views
        mTabLogButton = findViewById(R.id.btn_tab_log);
        mTabManageButton = findViewById(R.id.btn_tab_manage);
        mLogPanel = findViewById(R.id.panel_log);
        mManagePanel = findViewById(R.id.panel_manage);

        // Manage buttons
        mReinstallDepsButton = findViewById(R.id.btn_reinstall_deps);
        mReinstallAstrbotButton = findViewById(R.id.btn_reinstall_astrbot);
        mResetPasswordButton = findViewById(R.id.btn_reset_password);
        mRepairEnvButton = findViewById(R.id.btn_repair_env);
        mCleanInstallButton = findViewById(R.id.btn_clean_install);

        createNotificationChannel();

        mStartButton.setOnClickListener(v -> startGateway());
        mStopButton.setOnClickListener(v -> stopGateway());
        mSetupButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
        });
        mOpenWebuiButton.setOnClickListener(v -> {
            String url = mWebuiUrl.getText().toString();
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        });
        mSshToggleButton.setOnClickListener(v -> toggleSshService());
        mBackupButton.setOnClickListener(v -> showBackupDialog());
        mRestoreButton.setOnClickListener(v -> showRestoreDialog());

        // Tab buttons
        mTabLogButton.setOnClickListener(v -> switchTab(TAB_LOG));
        mTabManageButton.setOnClickListener(v -> switchTab(TAB_MANAGE));

        // Manage buttons
        mReinstallDepsButton.setOnClickListener(v -> showReinstallDepsDialog());
        mReinstallAstrbotButton.setOnClickListener(v -> showReinstallAstrbotDialog());
        mResetPasswordButton.setOnClickListener(v -> showResetPasswordDialog());
        mRepairEnvButton.setOnClickListener(v -> showRepairEnvDialog());
        mCleanInstallButton.setOnClickListener(v -> showCleanInstallDialog());
    }

    private static final int TAB_LOG = 0;
    private static final int TAB_MANAGE = 1;
    private int mCurrentTab = TAB_LOG;

    private void switchTab(int tab) {
        mCurrentTab = tab;
        if (tab == TAB_LOG) {
            mLogPanel.setVisibility(View.VISIBLE);
            mManagePanel.setVisibility(View.GONE);
            mTabLogButton.setBackgroundResource(R.drawable.botdrop_button_green_bg);
            mTabLogButton.setTextColor(getColor(android.R.color.white));
            mTabManageButton.setBackgroundResource(R.drawable.botdrop_button_outline_bg);
            mTabManageButton.setTextColor(getColor(R.color.botdrop_accent));
        } else {
            mLogPanel.setVisibility(View.GONE);
            mManagePanel.setVisibility(View.VISIBLE);
            mTabLogButton.setBackgroundResource(R.drawable.botdrop_button_outline_bg);
            mTabLogButton.setTextColor(getColor(R.color.botdrop_accent));
            mTabManageButton.setBackgroundResource(R.drawable.botdrop_button_green_bg);
            mTabManageButton.setTextColor(getColor(android.R.color.white));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bind to RbotService
        Intent intent = new Intent(this, RbotService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        new Thread(this::refreshStatusOffThread).start();
        startLogPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogPolling();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // ─── Status ───

    /** Refresh status — must be called off the main thread (runs root commands) */
    private void refreshStatusOffThread() {
        ChrootManager.FullStatus status = ChrootManager.getFullStatus();
        mHandler.post(() -> updateStatusUI(status.rootAvailable(), status.rootfsReady(),
            status.chrootMounted(), status.astrBotInstalled(), status.astrBotRunning()));
    }

    /** Update UI based on status — must be called on main thread */
    private void updateStatusUI(boolean rootAvailable, boolean rootfsReady, boolean chrootMounted,
                                boolean astrBotInstalled, boolean astrBotRunning) {
        if (!rootAvailable) {
            mStatusText.setText("⚠ 需要 Root 权限");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.GONE);
            mWebuiPanel.setVisibility(View.GONE);
            mSshInfoPanel.setVisibility(View.GONE);
        } else if (!rootfsReady || !astrBotInstalled) {
            mStatusText.setText("📦 需要安装");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.VISIBLE);
            mSetupButton.setEnabled(true);
            mWebuiPanel.setVisibility(View.GONE);
            mSshInfoPanel.setVisibility(View.GONE);
            mBackupButton.setVisibility(View.GONE);
            mRestoreButton.setVisibility(View.GONE);
        } else {
            // Show dual status: Ubuntu + AstrBot
            String ubuntuStatus = chrootMounted ? "🐧 Ubuntu: 运行中" : "🐧 Ubuntu: 未挂载";
            String astrbotStatus = astrBotRunning ? "🤖 AstrBot: 运行中" : "🤖 AstrBot: 已停止";
            mStatusText.setText(ubuntuStatus + "\n" + astrbotStatus);

            mStartButton.setEnabled(!astrBotRunning);
            mStopButton.setEnabled(astrBotRunning);

            mSetupButton.setVisibility(View.GONE);

            if (astrBotRunning) {
                String webuiUrl = detectLanIp();
                mWebuiUrl.setText(webuiUrl);
                mWebuiPanel.setVisibility(View.VISIBLE);
            } else {
                mWebuiPanel.setVisibility(View.GONE);
            }

            mSshInfoPanel.setVisibility(View.VISIBLE);
            mBackupButton.setVisibility(View.VISIBLE);
            mRestoreButton.setVisibility(View.VISIBLE);
            mBackupButton.setEnabled(!mBackupInProgress);
            // Update SSH panel
            updateSshPanel();
        }
    }

    private void updateSshPanel() {
        new Thread(() -> {
            boolean isRunning = ChrootManager.isSshRunning();
            String sshInfo = ChrootManager.getSshInfo();
            String rootPassword = ChrootManager.getRootPassword();
            mHandler.post(() -> {
                mSshInfo.setText(sshInfo);
                mSshPassword.setText("密码: " + (rootPassword.isEmpty() ? "(未设置)" : rootPassword));

                // Click to copy SSH connection string
                mSshInfo.setOnClickListener(v -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("SSH", sshInfo);
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(this, "已复制: " + sshInfo, android.widget.Toast.LENGTH_SHORT).show();
                });
                if (isRunning) {
                    mSshStatus.setText("运行中");
                    mSshStatus.setTextColor(getColor(android.R.color.holo_green_light));
                    mSshToggleButton.setText("停止 SSH");
                } else {
                    mSshStatus.setText("已停止");
                    mSshStatus.setTextColor(getColor(android.R.color.holo_red_light));
                    mSshToggleButton.setText("启动 SSH");
                }
            });
        }).start();
    }

    // ─── Gateway control ───

    private void startGateway() {
        mStartButton.setEnabled(false);
        mStatusText.setText("🔄 启动中...");

        // Start foreground monitor service
        Intent monitorIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent);
        } else {
            startService(monitorIntent);
        }

        // Also start via ChrootManager directly
        new Thread(() -> {
            ChrootManager.CommandResult result = ChrootManager.startAstrBot();
            refreshStatusOffThread();
            mHandler.post(() -> {
                if (!result.success()) {
                    mLogText.append("启动失败: " + result.stderr() + "\n");
                }
            });
        }).start();
    }

    private void stopGateway() {
        mStopButton.setEnabled(false);
        mStatusText.setText("🔄 停止中...");

        // Stop monitor service
        stopService(new Intent(this, GatewayMonitorService.class));

        new Thread(() -> {
            // Kill AstrBot
            ChrootManager.stopAstrBot();

            // Verify it's dead - wait up to 3 seconds
            for (int i = 0; i < 6; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {}
                if (!ChrootManager.isAstrBotRunning()) {
                    break;
                }
                // If still running, kill again
                ChrootManager.stopAstrBot();
            }

            refreshStatusOffThread();
        }).start();
    }

    // ─── Log polling ───

    private void startLogPolling() {
        if (mLogPolling) return;
        mLogPolling = true;
        pollLog();
    }

    private void stopLogPolling() {
        mLogPolling = false;
        mHandler.removeCallbacks(mLogPollRunnable);
    }

    private final Runnable mLogPollRunnable = this::pollLog;

    private void pollLog() {
        if (!mLogPolling) return;

        new Thread(() -> {
            try {
                ChrootManager.CommandResult result = ChrootManager.execRoot(
                    "tail -n 50 '" + RbotConstants.ASTRBOT_LOG_FILE + "' 2>/dev/null", 5);

                if (result.success() && !result.stdout().trim().isEmpty()) {
                    String newTail = result.stdout();
                    mHandler.post(() -> {
                        synchronized (mLogLock) {
                            if (newTail.equals(mLastLogTail)) return;

                            boolean hadSelection = mLogText.hasSelection();
                            boolean wasAtEnd = !hadSelection &&
                                (mLogText.getText().length() == 0 ||
                                mLogScrollView.getScrollY() >= mLogScrollView.getMaxScrollAmount() - 50);

                            String current = mLogText.getText().toString();
                            StringBuilder delta = new StringBuilder();
                            String[] newLines = newTail.split("\n");
                            String[] curLines = current.split("\n");

                            int startIdx = 0;
                            if (!curLines[curLines.length - 1].isEmpty()) {
                                outer:
                                for (int i = Math.max(0, newLines.length - curLines.length - 1);
                                     i < newLines.length; i++) {
                                    for (int j = Math.max(0, curLines.length - newLines.length + i - 1);
                                         j < curLines.length; j++) {
                                        if (newLines[i].equals(curLines[j])) {
                                            startIdx = i + 1;
                                            break outer;
                                        }
                                    }
                                }
                            }

                            for (int i = startIdx; i < newLines.length; i++) {
                                delta.append(newLines[i]).append("\n");
                            }

                            if (delta.length() > 0) {
                                mLogText.append(delta);
                                mLastLogTail = newTail;

                                String text = mLogText.getText().toString();
                                String[] allLines = text.split("\n");
                                if (allLines.length > MAX_LOG_LINES) {
                                    int cutPos = text.indexOf(allLines[allLines.length - MAX_LOG_LINES]);
                                    mLogText.setText(text.substring(Math.max(0, cutPos)));
                                }

                                if (wasAtEnd && !hadSelection) {
                                    mLogScrollView.post(() ->
                                        mLogScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                                }
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Log poll error: " + e.getMessage());
            }

            if (mLogPolling) {
                mHandler.postDelayed(mLogPollRunnable, 5000);
            }
        }).start();
    }

    // ─── WebUI URL detection ───

    /** Try to detect LAN IP of this device, fall back to localhost */
    private String detectLanIp() {
        try {
            java.net.NetworkInterface iface = java.net.NetworkInterface.getByName("wlan0");
            if (iface == null) {
                java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface ni = interfaces.nextElement();
                    if (ni.isUp() && !ni.isLoopback()) {
                        iface = ni;
                        break;
                    }
                }
            }
            if (iface != null) {
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return "http://" + addr.getHostAddress() + ":6185";
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectLanIp failed: " + e.getMessage());
        }
        return "http://127.0.0.1:6185";
    }

    // ─── Notification channel ───

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Rbot 服务状态",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Rbot AstrBot 运行状态");
        manager.createNotificationChannel(channel);
    }

    // ─── Backup & Restore ───

    private void showBackupDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("备份 AstrBot 数据")
            .setMessage("将备份配置、数据库、插件数据到存储卡。\n\n确认继续？")
            .setPositiveButton("开始备份", (dialog, which) -> startBackup())
            .setNegativeButton("取消", null)
            .show();
    }

    private void startBackup() {
        if (mBackupInProgress) return;
        mBackupInProgress = true;
        mBackupButton.setEnabled(false);

        appendToLog("\n📦 开始备份 AstrBot 数据...\n");

        new Thread(() -> {
            ChrootManager.ProgressCallback callback = new ChrootManager.ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    mHandler.post(() -> appendToLog("  " + message));
                }

                @Override
                public void onError(String error) {
                    mHandler.post(() -> appendToLog("  ❌ " + error));
                }
            };

            String backupPath = ChrootManager.backupAstrBotData(callback);

            mHandler.post(() -> {
                mBackupInProgress = false;
                mBackupButton.setEnabled(true);
                if (backupPath != null) {
                    appendToLog("✅ 备份完成: " + backupPath.replace(RbotConstants.BACKUP_DIR + "/", ""));
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("备份成功")
                        .setMessage("备份文件已保存到:\n" + backupPath)
                        .setPositiveButton("确定", null)
                        .show();
                } else {
                    appendToLog("❌ 备份失败");
                }
            });
        }).start();
    }

    private void showRestoreDialog() {
        String[] backups = ChrootManager.listBackups();
        if (backups.length == 0) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("恢复备份")
                .setMessage("没有找到备份文件。\n\n备份文件应位于:\n" + RbotConstants.BACKUP_DIR)
                .setPositiveButton("确定", null)
                .show();
            return;
        }

        // Format backup names for display (remove full path)
        String[] displayNames = new String[backups.length];
        for (int i = 0; i < backups.length; i++) {
            displayNames[i] = backups[i].substring(backups[i].lastIndexOf('/') + 1);
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要恢复的备份")
            .setItems(displayNames, (dialog, which) -> {
                String selectedBackup = backups[which];
                confirmRestore(selectedBackup, displayNames[which]);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmRestore(String backupPath, String displayName) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认恢复")
            .setMessage("将从以下备份恢复:\n" + displayName + "\n\n⚠️ 当前数据将被覆盖！\n恢复后 AstrBot 将自动重启。")
            .setPositiveButton("确认恢复", (dialog, which) -> startRestore(backupPath))
            .setNegativeButton("取消", null)
            .show();
    }

    private void startRestore(String backupPath) {
        if (mBackupInProgress) return;
        mBackupInProgress = true;
        mRestoreButton.setEnabled(false);

        appendToLog("\n📂 开始恢复 AstrBot 数据...\n");

        new Thread(() -> {
            ChrootManager.ProgressCallback callback = new ChrootManager.ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    mHandler.post(() -> appendToLog("  " + message));
                }

                @Override
                public void onError(String error) {
                    mHandler.post(() -> appendToLog("  ❌ " + error));
                }
            };

            boolean failed = ChrootManager.restoreAstrBotData(backupPath, callback);

            mHandler.post(() -> {
                mBackupInProgress = false;
                mRestoreButton.setEnabled(true);
                if (!failed) {
                    appendToLog("✅ 恢复完成");
                    // Restart AstrBot if it was running
                    ChrootManager.FullStatus status = ChrootManager.getFullStatus();
                    if (status.astrBotRunning()) {
                        appendToLog("🔄 正在重启 AstrBot...");
                        ChrootManager.startAstrBot();
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("恢复成功")
                        .setMessage("数据已恢复，AstrBot 已重启。")
                        .setPositiveButton("确定", null)
                        .show();
                } else {
                    appendToLog("❌ 恢复失败");
                }
                refreshStatusOffThread();
            });
        }).start();
    }

    private void appendToLog(String message) {
        mLogText.append(message + "\n");
        mLogScrollView.post(() -> mLogScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ─── Manage Functions ───

    private void showReinstallDepsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重装系统依赖")
            .setMessage("将重新运行 apt update 和安装 Python 3.13、SSH 等依赖。\n\n如果 AstrBot 正在运行，将会先停止。")
            .setPositiveButton("开始重装", (dialog, which) -> {
                dialog.dismiss(); // Close dialog immediately
                switchTab(TAB_LOG);
                appendToLog("\n🔄 开始重装系统依赖...");
                new Thread(() -> {
                    // Stop AstrBot if running
                    ChrootManager.FullStatus status = ChrootManager.getFullStatus();
                    if (status.astrBotRunning()) {
                        mHandler.post(() -> appendToLog("  ⏹ 正在停止 AstrBot..."));
                        ChrootManager.stopAstrBot();
                    }

                    ChrootManager.ProgressCallback cb = new ChrootManager.ProgressCallback() {
                        @Override public void onProgress(String msg) {
                            mHandler.post(() -> appendToLog("  " + msg));
                        }
                        @Override public void onError(String err) {
                            mHandler.post(() -> appendToLog("  ❌ " + err));
                        }
                    };
                    if (ChrootManager.aptUpdate(cb)) {
                        mHandler.post(() -> appendToLog("❌ 软件源更新失败"));
                    } else if (ChrootManager.aptInstallDeps(cb)) {
                        mHandler.post(() -> appendToLog("❌ 依赖安装失败"));
                    } else {
                        mHandler.post(() -> appendToLog("✅ 系统依赖重装完成"));
                    }
                    refreshStatusOffThread();
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showReinstallAstrbotDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重装 AstrBot")
            .setMessage("将删除 /root/astrbot 目录并重新克隆安装。\n\n⚠️ 注意：data 目录（配置、数据库）会被保留，但自定义修改会丢失。")
            .setPositiveButton("开始重装", (dialog, which) -> {
                switchTab(TAB_LOG);
                appendToLog("\n🤖 开始重装 AstrBot...");
                new Thread(() -> {
                    ChrootManager.stopAstrBot();
                    ChrootManager.execInChroot("rm -rf /root/astrbot", 30);
                    ChrootManager.ProgressCallback cb = new ChrootManager.ProgressCallback() {
                        @Override public void onProgress(String msg) {
                            mHandler.post(() -> appendToLog("  " + msg));
                        }
                        @Override public void onError(String err) {
                            mHandler.post(() -> appendToLog("  ❌ " + err));
                        }
                    };
                    if (ChrootManager.cloneAstrBot(cb)) {
                        mHandler.post(() -> appendToLog("❌ AstrBot 克隆失败"));
                    } else if (ChrootManager.pipInstallDeps(cb)) {
                        mHandler.post(() -> appendToLog("❌ Python 依赖安装失败"));
                    } else {
                        mHandler.post(() -> {
                            appendToLog("✅ AstrBot 重装完成");
                            appendToLog("🔄 正在启动 AstrBot...");
                            ChrootManager.startAstrBot();
                            refreshStatusOffThread();
                        });
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showResetPasswordDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("留空则自动生成随机密码");
        input.setSingleLine();

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重置 root 密码")
            .setMessage("设置新的 root 密码（用于 SSH 登录）：")
            .setView(input)
            .setPositiveButton("设置", (dialog, which) -> {
                String password = input.getText().toString().trim();
                if (password.isEmpty()) {
                    password = generateRandomPassword();
                }
                final String finalPassword = password;
                new Thread(() -> {
                    if (ChrootManager.setRootPassword(finalPassword)) {
                        mHandler.post(() -> {
                            appendToLog("\n🔐 root 密码已重置: " + finalPassword);
                            updateSshPanel();
                        });
                    } else {
                        mHandler.post(() -> appendToLog("\n❌ root 密码重置失败"));
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showRepairEnvDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("修复环境")
            .setMessage("将执行以下操作：\n• 重新挂载 chroot 设备节点\n• 修复 apt/dpkg 状态\n• 重启 SSH 服务（如果正在运行）\n\n不会删除任何数据。")
            .setPositiveButton("开始修复", (dialog, which) -> {
                switchTab(TAB_LOG);
                appendToLog("\n🛠️ 开始修复环境...");
                new Thread(() -> {
                    ChrootManager.setupChrootDevices(null);
                    appendToLog("  ✅ chroot 设备已重新挂载");
                    ChrootManager.execInChroot(
                        "dpkg --configure -a 2>/dev/null; apt --fix-broken install -y 2>/dev/null; echo done", 60);
                    appendToLog("  ✅ apt/dpkg 状态已修复");
                    if (ChrootManager.isSshRunning()) {
                        ChrootManager.stopSshService();
                        ChrootManager.startSshService();
                        appendToLog("  ✅ SSH 服务已重启");
                    }
                    mHandler.post(() -> {
                        appendToLog("✅ 环境修复完成");
                        updateSshPanel();
                    });
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showCleanInstallDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ 完全重装")
            .setMessage("这将删除所有数据，包括：\n• 系统镜像 (/data/rbot)\n• AstrBot 代码和配置\n• 所有备份文件\n\n此操作不可恢复！")
            .setPositiveButton("我确定要删除所有数据", (dialog, which) -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("最后确认")
                    .setMessage("你真的确定吗？所有数据将永久丢失。")
                    .setPositiveButton("是的，删除所有数据", (d2, w2) -> {
                        switchTab(TAB_LOG);
                        appendToLog("\n⚠️ 开始完全重装...");
                        new Thread(() -> {
                            ChrootManager.stopAstrBot();
                            ChrootManager.stopSshService();
                            ChrootManager.cleanupChrootDevices();
                            ChrootManager.execRoot("rm -rf " + RbotConstants.CHROOT_DIR);
                            ChrootManager.execRoot("rm -f " + RbotConstants.ROOTFS_MARKER);
                            ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);
                            mHandler.post(() -> {
                                appendToLog("✅ 所有数据已清除");
                                appendToLog("➡️ 正在跳转到安装界面...");
                                Intent intent = new Intent(this, SetupActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            });
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ─── SSH Service ───

    private void toggleSshService() {
        if (mSshStarting) return;

        new Thread(() -> {
            boolean isRunning = ChrootManager.isSshRunning();
            mHandler.post(() -> {
                if (isRunning) {
                    stopSshService();
                } else {
                    startSshService();
                }
            });
        }).start();
    }

    private void startSshService() {
        mSshStarting = true;
        mSshToggleButton.setEnabled(false);
        appendToLog("\n🔌 正在启动 SSH 服务...");

        new Thread(() -> {
            ChrootManager.CommandResult result = ChrootManager.startSshService();
            boolean success = result.success() && !result.stdout().contains("error");

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                if (success) {
                    String sshInfo = ChrootManager.getSshInfo();
                    appendToLog("✅ SSH 服务已启动: " + sshInfo);
                } else {
                    appendToLog("❌ SSH 启动失败: " + result.stderr());
                }
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }

    private void stopSshService() {
        mSshStarting = true;
        mSshToggleButton.setEnabled(false);
        appendToLog("\n🔌 正在停止 SSH 服务...");

        new Thread(() -> {
            ChrootManager.CommandResult result = ChrootManager.stopSshService();
            boolean stopped = result.success();

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                appendToLog(stopped ? "✅ SSH 服务已停止" : "⚠️ SSH 停止可能失败");
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }
}
