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
    private Button mStartButton;
    private Button mStopButton;
    private Button mSetupButton;
    private CardView mSshInfoPanel;
    private TextView mSshInfo;
    private TextView mSshStatus;
    private TextView mSshPassword;
    private Button mSshToggleButton;
    private boolean mSshStarting = false;
    private CardView mWebuiPanel;
    private TextView mWebuiUrl;
    private Button mOpenWebuiButton;

    // Navigation buttons
    private Button mNavHomeButton;
    private Button mNavTerminalButton;
    private Button mNavLogButton;
    private Button mNavSettingsButton;

    // Component status dashboard (2x2 grid)
    private TextView mStatusBinaries;
    private View mDotBinaries;
    private TextView mStatusRootfs;
    private View mDotRootfs;
    private TextView mStatusBootstrap;
    private View mDotBootstrap;
    private TextView mStatusAstrbot;
    private View mDotAstrbot;
    private TextView mStatusAstrbotLabel;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private RbotService mService;
    private boolean mBound = false;

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
        mNavTerminalButton = findViewById(R.id.btn_nav_terminal);
        mNavLogButton = findViewById(R.id.btn_nav_log);
        mNavSettingsButton = findViewById(R.id.btn_nav_settings);

        // Component status dashboard — IDs are in the main screen layout
        mStatusBinaries = findViewById(R.id.status_binaries);
        mDotBinaries = findViewById(R.id.dot_binaries);
        mStatusRootfs = findViewById(R.id.status_rootfs);
        mDotRootfs = findViewById(R.id.dot_rootfs);
        mStatusBootstrap = findViewById(R.id.status_bootstrap);
        mDotBootstrap = findViewById(R.id.dot_bootstrap);
        mStatusAstrbot = findViewById(R.id.status_astrbot);
        mDotAstrbot = findViewById(R.id.dot_astrbot);
        mStatusAstrbotLabel = findViewById(R.id.label_status_bot);

        createNotificationChannel();

        // Bind buttons with null-checks (layout may not have all buttons)
        if (mStartButton != null) mStartButton.setOnClickListener(v -> startGateway());
        if (mStopButton != null) mStopButton.setOnClickListener(v -> stopGateway());
        if (mSetupButton != null) mSetupButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
        });
        if (mOpenWebuiButton != null) mOpenWebuiButton.setOnClickListener(v -> {
            String url = mWebuiUrl != null ? mWebuiUrl.getText().toString() : "";
            if (!url.isEmpty()) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            }
        });
        if (mSshToggleButton != null) mSshToggleButton.setOnClickListener(v -> toggleSshService());

        // Navigation buttons
        if (mNavHomeButton != null) mNavHomeButton.setOnClickListener(v -> {
            // Already on home — do nothing
        });
        if (mNavTerminalButton != null) mNavTerminalButton.setOnClickListener(v -> {
            // Check if chroot is mounted before opening terminal
            if (!ChrootManager.isChrootMounted()) {
                android.widget.Toast.makeText(this, "请先启动后再使用终端", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Intent shellIntent = new Intent(this, ShellActivity.class);
            startActivity(shellIntent);
        });
        if (mNavLogButton != null) mNavLogButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LogActivity.class);
            startActivity(intent);
        });
        if (mNavSettingsButton != null) mNavSettingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
    }



    @Override
    protected void onResume() {
        super.onResume();
        // Bind to RbotService
        Intent intent = new Intent(this, RbotService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        new Thread(this::refreshStatusOffThread).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
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
        // Get active bot status off thread
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        boolean botInstalled = activeBot.isInstalled();
        boolean botRunning = activeBot.isRunning();
        mHandler.post(() -> updateStatusUI(status, activeBot, botInstalled, botRunning));
    }

    /** Update UI based on status — must be called on main thread */
    private void updateStatusUI(ChrootManager.FullStatus status, BotAdapter activeBot, boolean botInstalled, boolean botRunning) {
        boolean rootAvailable = status.rootAvailable();
        boolean rootfsReady = status.rootfsReady();
        boolean chrootMounted = status.chrootMounted();
        
        if (mStatusText == null) return; // Guard against missing views
        
        if (!rootAvailable) {
            mStatusText.setText("⚠ 需要 Root 权限");
            setButtonsEnabled(false, false);
            hidePanels();
        } else if (!rootfsReady || !botInstalled) {
            mStatusText.setText("📦 需要安装");
            setButtonsEnabled(false, false);
            hidePanels();
            if (mSetupButton != null) {
                mSetupButton.setVisibility(View.VISIBLE);
                mSetupButton.setEnabled(true);
            }
        } else {
            // Show dynamic status: Ubuntu + active bot
            String ubuntuStatus = chrootMounted ? "🐧 Ubuntu: 运行中" : "🐧 Ubuntu: 未挂载";
            String botStatus = botRunning ? "🤖 " + activeBot.getName() + ": 运行中" : "🤖 " + activeBot.getName() + ": 已停止";
            mStatusText.setText(ubuntuStatus + "\n" + botStatus);

            setButtonsEnabled(!botRunning, botRunning);
            if (mSetupButton != null) mSetupButton.setVisibility(View.GONE);

            // WebUI panel: only for bots that have it (AstrBot, not Hermes)
            if (activeBot.getId().equals(BotAdapter.ID_ASTRBOT)) {
                if (mWebuiPanel != null) {
                    if (botRunning) {
                        String webuiUrl = detectLanIp();
                        if (mWebuiUrl != null) mWebuiUrl.setText(webuiUrl);
                        mWebuiPanel.setVisibility(View.VISIBLE);
                    } else {
                        mWebuiPanel.setVisibility(View.GONE);
                    }
                }
            } else {
                // Hermes has no WebUI
                if (mWebuiPanel != null) mWebuiPanel.setVisibility(View.GONE);
            }

            if (mSshInfoPanel != null) mSshInfoPanel.setVisibility(View.VISIBLE);
            updateSshPanel();
        }

        // Update the component status dashboard
        updateComponentStatusUI(status, activeBot, botInstalled, botRunning);

        // Dynamically update the 4th status card label to match active bot
        if (mStatusAstrbotLabel != null) {
            mStatusAstrbotLabel.setText(activeBot.getStatusLabel());
        }
    }

    private void setButtonsEnabled(boolean startEnabled, boolean stopEnabled) {
        if (mStartButton != null) mStartButton.setEnabled(startEnabled);
        if (mStopButton != null) mStopButton.setEnabled(stopEnabled);
    }

    private void hidePanels() {
        if (mWebuiPanel != null) mWebuiPanel.setVisibility(View.GONE);
        if (mSshInfoPanel != null) mSshInfoPanel.setVisibility(View.GONE);
    }

    /**
     * Update the 2x2 component status dashboard (BINARIES / ROOTFS / BOOTSTRAP / BOT).
     * BotPocket-style status cards: shows ready/not_ready/warning/error per component.
     * Called on main thread from updateStatusUI.
     */
    private void updateComponentStatusUI(ChrootManager.FullStatus status, BotAdapter activeBot, boolean botInstalled, boolean botRunning) {
        if (mStatusBinaries == null) return; // Guard against missing views

        // BINARIES: root shell access (su works)
        if (status.rootAvailable()) {
            mStatusBinaries.setText("已就绪");
            mStatusBinaries.setTextColor(getColor(R.color.status_connected));
            if (mDotBinaries != null) mDotBinaries.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mStatusBinaries.setText("不可用");
            mStatusBinaries.setTextColor(getColor(R.color.status_disconnected));
            if (mDotBinaries != null) mDotBinaries.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // ROOTFS: system image extracted
        if (status.rootfsReady()) {
            mStatusRootfs.setText("已就绪");
            mStatusRootfs.setTextColor(getColor(R.color.status_connected));
            if (mDotRootfs != null) mDotRootfs.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mStatusRootfs.setText("未安装");
            mStatusRootfs.setTextColor(getColor(R.color.status_disconnected));
            if (mDotRootfs != null) mDotRootfs.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // BOOTSTRAP: chroot environment mounted (proc/sys/dev/pts)
        if (status.chrootMounted()) {
            mStatusBootstrap.setText("运行中");
            mStatusBootstrap.setTextColor(getColor(R.color.status_connected));
            if (mDotBootstrap != null) mDotBootstrap.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (status.rootfsReady()) {
            mStatusBootstrap.setText("待初始化");
            mStatusBootstrap.setTextColor(getColor(R.color.status_warning));
            if (mDotBootstrap != null) mDotBootstrap.setBackgroundResource(R.drawable.ic_status_warning);
        } else {
            mStatusBootstrap.setText("未就绪");
            mStatusBootstrap.setTextColor(getColor(R.color.status_disconnected));
            if (mDotBootstrap != null) mDotBootstrap.setBackgroundResource(R.drawable.ic_status_not_ready);
        }

        // Active Bot: installed + running status (dynamic based on active bot)
        if (botInstalled && botRunning) {
            mStatusAstrbot.setText("运行中");
            mStatusAstrbot.setTextColor(getColor(R.color.status_connected));
            if (mDotAstrbot != null) mDotAstrbot.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (botInstalled) {
            mStatusAstrbot.setText("已安装");
            mStatusAstrbot.setTextColor(getColor(R.color.status_warning));
            if (mDotAstrbot != null) mDotAstrbot.setBackgroundResource(R.drawable.ic_status_warning);
        } else if (status.rootfsReady()) {
            mStatusAstrbot.setText("待安装");
            mStatusAstrbot.setTextColor(getColor(R.color.status_disconnected));
            if (mDotAstrbot != null) mDotAstrbot.setBackgroundResource(R.drawable.ic_status_not_ready);
        } else {
            mStatusAstrbot.setText("未就绪");
            mStatusAstrbot.setTextColor(getColor(R.color.status_disconnected));
            if (mDotAstrbot != null) mDotAstrbot.setBackgroundResource(R.drawable.ic_status_not_ready);
        }
    }

    private void updateSshPanel() {
        if (mSshInfo == null || mSshStatus == null || mSshPassword == null || mSshToggleButton == null) return;

        new Thread(() -> {
            boolean isRunning = ChrootManager.isSshRunning();
            String sshInfo = ChrootManager.getSshInfo();
            String rootPassword = ChrootManager.getRootPassword();
            mHandler.post(() -> {
                mSshInfo.setText(sshInfo);
                mSshPassword.setText("密码: " + (rootPassword.isEmpty() ? "(未设置)" : rootPassword));

                // Click to copy SSH connection string (long-press for help)
                mSshInfo.setOnClickListener(v -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("SSH", sshInfo);
                    clipboard.setPrimaryClip(clip);
                    String ip = sshInfo.replace("root@", "");
                    android.widget.Toast.makeText(this,
                        "已复制: " + sshInfo + "\n⚠️ 如遇 host key 错误: ssh-keygen -R " + ip,
                        android.widget.Toast.LENGTH_LONG).show();
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
        if (mStartButton != null) mStartButton.setEnabled(false);
        if (mStatusText != null) mStatusText.setText("🔄 启动中...");

        // Start foreground monitor service
        Intent monitorIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent);
        } else {
            startService(monitorIntent);
        }

        // Start via active bot adapter (BotManager)
        // Full chroot init (mount + SSH) before starting the bot
        new Thread(() -> {
            BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
            OpLog.log("启动 " + activeBot.getName() + "...");

            // Ensure chroot environment is fully set up (includes SSH startup)
            if (!ChrootManager.isChrootMounted()) {
                OpLog.progress("正在初始化 chroot 环境...");
                ChrootManager.setupChrootEnvironment(new ChrootManager.ProgressCallback() {
                    @Override public void onProgress(String msg) {
                        OpLog.progress(msg);
                    }
                    @Override public void onError(String err) {
                        OpLog.error(err);
                    }
                });
            } else {
                // Already mounted — just ensure SSH is running
                if (!ChrootManager.isSshRunning()) {
                    OpLog.progress("启动 SSH 服务...");
                    ChrootManager.startSshService(new ChrootManager.ProgressCallback() {
                        @Override public void onProgress(String msg) { OpLog.progress(msg); }
                        @Override public void onError(String err) { OpLog.error(err); }
                    });
                }
            }

            ChrootManager.CommandResult result = activeBot.start();

            // Verify SSH after bot start (bot's start() may remount /dev, killing sshd)
            if (!ChrootManager.isSshRunning()) {
                OpLog.progress("SSH 服务意外停止，重新启动...");
                ChrootManager.startSshService(null);
            }

            refreshStatusOffThread();
            if (!result.success()) {
                OpLog.error(activeBot.getName() + " 启动失败: " + result.stderr());
            } else {
                OpLog.log(activeBot.getName() + " 已启动");
            }
        }).start();
    }

    private void stopGateway() {
        if (mStopButton != null) mStopButton.setEnabled(false);
        if (mStatusText != null) mStatusText.setText("🔄 停止中...");

        // Stop monitor service
        stopService(new Intent(this, GatewayMonitorService.class));

        // Stop via active bot adapter (BotManager)
        new Thread(() -> {
            BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
            OpLog.log("停止 " + activeBot.getName() + "...");
            ChrootManager.CommandResult result = activeBot.stop();
            refreshStatusOffThread();
            if (!result.success()) {
                OpLog.error(activeBot.getName() + " 停止失败: " + result.stderr());
            } else {
                OpLog.log(activeBot.getName() + " 已停止");
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
        channel.setDescription("Rbot 运行状态");
        manager.createNotificationChannel(channel);
    }



    private void appendToLog(String message) {
        // No log viewer on MainActivity — use Toast for important messages
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
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
        OpLog.log("启动 SSH 服务...");

        new Thread(() -> {
            ChrootManager.CommandResult result = ChrootManager.startSshService(new ChrootManager.ProgressCallback() {
                @Override public void onProgress(String msg) { OpLog.progress(msg); }
                @Override public void onError(String err) { OpLog.error(err); }
            });
            boolean success = result.success() && !result.stdout().contains("error");

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                if (success) {
                    String sshInfo = ChrootManager.getSshInfo();
                    OpLog.log("SSH 服务已启动: " + sshInfo);
                } else {
                    OpLog.error("SSH 启动失败: " + result.stderr());
                }
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }

    private void stopSshService() {
        mSshStarting = true;
        mSshToggleButton.setEnabled(false);
        OpLog.log("停止 SSH 服务...");

        new Thread(() -> {
            ChrootManager.CommandResult result = ChrootManager.stopSshService();
            boolean stopped = result.success();

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                OpLog.log(stopped ? "SSH 服务已停止" : "⚠️ SSH 停止可能失败");
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }
}
