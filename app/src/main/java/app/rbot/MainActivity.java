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
    private CardView mSshInfoPanel;
    private TextView mSshInfo;
    private TextView mSshStatus;
    private TextView mSshPassword;
    private Button mSshToggleButton;
    private boolean mSshStarting = false;
    private ScrollView mLogScrollView;
    private Button mClearLogButton;
    private CardView mWebuiPanel;
    private TextView mWebuiUrl;
    private Button mOpenWebuiButton;

    // Navigation buttons
    private Button mNavHomeButton;
    private Button mNavLogButton;
    private Button mNavSettingsButton;
    private Button mNavPermissionsButton;

    // Component status dashboard (2x2 grid)
    private TextView mStatusBinaries;
    private View mDotBinaries;
    private TextView mStatusRootfs;
    private View mDotRootfs;
    private TextView mStatusBootstrap;
    private View mDotBootstrap;
    private TextView mStatusAstrbot;
    private View mDotAstrbot;

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
        mClearLogButton = findViewById(R.id.btn_clear_log);
        mStartButton = findViewById(R.id.btn_start);
        mStopButton = findViewById(R.id.btn_stop);
        mSetupButton = findViewById(R.id.btn_setup);
        mWebuiPanel = findViewById(R.id.webui_panel);
        mWebuiUrl = findViewById(R.id.webui_url);
        mOpenWebuiButton = findViewById(R.id.btn_open_webui);
        mSshInfoPanel = findViewById(R.id.ssh_info_panel);
        mSshInfo = findViewById(R.id.ssh_info);
        mSshStatus = findViewById(R.id.ssh_status);
        mSshPassword = findViewById(R.id.ssh_password);
        mSshToggleButton = findViewById(R.id.btn_ssh_toggle);

        // Navigation buttons
        mNavHomeButton = findViewById(R.id.btn_nav_home);
        mNavLogButton = findViewById(R.id.btn_nav_log);
        mNavSettingsButton = findViewById(R.id.btn_nav_settings);
        mNavPermissionsButton = findViewById(R.id.btn_nav_permissions);

        // Component status dashboard — IDs are in the main screen layout
        mStatusBinaries = findViewById(R.id.status_binaries);
        mDotBinaries = findViewById(R.id.dot_binaries);
        mStatusRootfs = findViewById(R.id.status_rootfs);
        mDotRootfs = findViewById(R.id.dot_rootfs);
        mStatusBootstrap = findViewById(R.id.status_bootstrap);
        mDotBootstrap = findViewById(R.id.dot_bootstrap);
        mStatusAstrbot = findViewById(R.id.status_astrbot);
        mDotAstrbot = findViewById(R.id.dot_astrbot);

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
        mClearLogButton.setOnClickListener(v -> {
            mLogText.setText("");
            mLastLogTail = "";
        });

        // Navigation buttons
        mNavHomeButton.setOnClickListener(v -> {
            // Already on home — do nothing
        });
        mNavLogButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LogActivity.class);
            startActivity(intent);
        });
        mNavSettingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        mNavPermissionsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, PermissionsActivity.class);
            startActivity(intent);
        });
    }



    @Override
    protected void onResume() {
        super.onResume();
        // Add a clear separator when returning to the activity
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        appendToLog("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        appendToLog("  📱 Rbot 返回前台 — " + timestamp);
        appendToLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
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
        mHandler.post(() -> updateStatusUI(status));
    }

    /** Update UI based on status — must be called on main thread */
    private void updateStatusUI(ChrootManager.FullStatus status) {
        boolean rootAvailable = status.rootAvailable();
        boolean rootfsReady = status.rootfsReady();
        boolean chrootMounted = status.chrootMounted();
        boolean astrBotInstalled = status.astrBotInstalled();
        boolean astrBotRunning = status.astrBotRunning();
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
            // Update SSH panel
            updateSshPanel();
        }

        // Update the component status dashboard
        updateComponentStatusUI(status);
    }

    /**
     * Update the 2x2 component status dashboard (BINARIES / ROOTFS / BOOTSTRAP / ASTRBOT).
     * BotPocket-style status cards: shows ready/not_ready/warning/error per component.
     * Called on main thread from updateStatusUI.
     */
    private void updateComponentStatusUI(ChrootManager.FullStatus status) {
        // BINARIES: root shell access (su works)
        if (status.rootAvailable()) {
            mStatusBinaries.setText("已就绪");
            mStatusBinaries.setTextColor(getColor(R.color.status_connected));
            mDotBinaries.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mStatusBinaries.setText("不可用");
            mStatusBinaries.setTextColor(getColor(R.color.status_disconnected));
            mDotBinaries.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // ROOTFS: system image extracted
        if (status.rootfsReady()) {
            mStatusRootfs.setText("已就绪");
            mStatusRootfs.setTextColor(getColor(R.color.status_connected));
            mDotRootfs.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mStatusRootfs.setText("未安装");
            mStatusRootfs.setTextColor(getColor(R.color.status_disconnected));
            mDotRootfs.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // BOOTSTRAP: chroot environment mounted (proc/sys/dev/pts)
        if (status.chrootMounted()) {
            mStatusBootstrap.setText("运行中");
            mStatusBootstrap.setTextColor(getColor(R.color.status_connected));
            mDotBootstrap.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (status.rootfsReady()) {
            // Mounted but chroot not set up yet
            mStatusBootstrap.setText("待初始化");
            mStatusBootstrap.setTextColor(getColor(R.color.status_warning));
            mDotBootstrap.setBackgroundResource(R.drawable.ic_status_warning);
        } else {
            mStatusBootstrap.setText("未就绪");
            mStatusBootstrap.setTextColor(getColor(R.color.status_disconnected));
            mDotBootstrap.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // ASTRBOT: installed + running status
        if (status.astrBotInstalled() && status.astrBotRunning()) {
            mStatusAstrbot.setText("运行中");
            mStatusAstrbot.setTextColor(getColor(R.color.status_connected));
            mDotAstrbot.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (status.astrBotInstalled()) {
            mStatusAstrbot.setText("已安装");
            mStatusAstrbot.setTextColor(getColor(R.color.status_warning));
            mDotAstrbot.setBackgroundResource(R.drawable.ic_status_warning);
        } else if (status.rootfsReady()) {
            mStatusAstrbot.setText("待安装");
            mStatusAstrbot.setTextColor(getColor(R.color.status_disconnected));
            mDotAstrbot.setBackgroundResource(R.drawable.ic_status_not_ready);
        } else {
            mStatusAstrbot.setText("未就绪");
            mStatusAstrbot.setTextColor(getColor(R.color.status_disconnected));
            mDotAstrbot.setBackgroundResource(R.drawable.ic_status_not_ready);
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
                    appendToLog("启动失败: " + result.stderr());
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
                                // Insert new lines at the top (newest-first order)
                                String[] deltaLines = delta.toString().split("\n");
                                StringBuilder reversed = new StringBuilder();
                                for (int i = deltaLines.length - 1; i >= 0; i--) {
                                    if (!deltaLines[i].isEmpty()) {
                                        reversed.append(deltaLines[i]).append("\n");
                                    }
                                }
                                String oldText = mLogText.getText().toString();
                                mLogText.setText(reversed.toString() + oldText);
                                mLastLogTail = newTail;

                                // Trim from the bottom (oldest lines) when exceeding limit
                                String text = mLogText.getText().toString();
                                String[] allLines = text.split("\n");
                                if (allLines.length > MAX_LOG_LINES) {
                                    int keepCount = MAX_LOG_LINES;
                                    StringBuilder kept = new StringBuilder();
                                    for (int i = 0; i < keepCount; i++) {
                                        kept.append(allLines[i]).append("\n");
                                    }
                                    mLogText.setText(kept.toString());
                                }

                                // Always scroll to top to see newest
                                mLogScrollView.post(() -> mLogScrollView.scrollTo(0, 0));
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



    private void appendToLog(String message) {
        // Insert at top (newest-first order)
        String oldText = mLogText.getText().toString();
        mLogText.setText(message + "\n" + oldText);
        mLogScrollView.post(() -> mLogScrollView.scrollTo(0, 0));
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
