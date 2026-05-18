package app.rbot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

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
    private TextView mSshInfoLan;
    private TextView mSshStatus;
    private TextView mSshPassword;
    private Button mSshToggleButton;
    private Button mShellButton;
    private boolean mSshStarting = false;

    private CardView mWebuiPanel;
    private TextView mWebuiUrl;
    private TextView mWebuiUrlLan;
    private TextView mWebuiStatus;
    private Button mOpenWebuiButton;

    // Navigation buttons
    private Button mNavHomeButton;
    private Button mNavLogButton;
    private Button mNavSettingsButton;
    private Button mNavManageButton;
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
    private TextView mLabelAstrbot;

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
        mWebuiUrlLan = findViewById(R.id.webui_url_lan);
        mWebuiStatus = findViewById(R.id.webui_status);
        mOpenWebuiButton = findViewById(R.id.btn_open_webui);
        mSshInfoPanel = findViewById(R.id.ssh_info_panel);
        mSshInfo = findViewById(R.id.ssh_info);
        mSshInfoLan = findViewById(R.id.ssh_info_lan);
        mSshStatus = findViewById(R.id.ssh_status);
        mSshPassword = findViewById(R.id.ssh_password);
        mSshToggleButton = findViewById(R.id.btn_ssh_toggle);
        mShellButton = findViewById(R.id.btn_open_shell);

        // Navigation buttons
        mNavHomeButton = findViewById(R.id.btn_nav_home);
        mNavLogButton = findViewById(R.id.btn_nav_log);
        mNavSettingsButton = findViewById(R.id.btn_nav_settings);
        mNavManageButton = findViewById(R.id.btn_nav_manage);
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
        mLabelAstrbot = findViewById(R.id.label_astrbot);

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
        mShellButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ShellActivity.class);
            startActivity(intent);
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
        mNavManageButton.setOnClickListener(v -> {
            // AstrBot managed from main panel — show toast with status
            BotAdapter bot = BotManager.getInstance(this).getActiveBot();
            Toast.makeText(this, bot.getName() + ": " + (bot.isRunning() ? "运行中" : bot.isInstalled() ? "已停止" : "未安装"), Toast.LENGTH_SHORT).show();
        });
        mNavPermissionsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, PermissionsActivity.class);
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

    /** Refresh status — must be called off the main thread */
    private void refreshStatusOffThread() {
        ChrootManager.FullStatus status = ChrootManager.getFullStatus();
        // Also check active bot status off-thread
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        boolean botInstalled = activeBot.isInstalled();
        boolean botRunning = activeBot.isRunning();

        // In PRoot mode, override status fields with PRoot equivalents
        AuthManager am = AuthManager.getInstance();
        final ChrootManager.FullStatus finalStatus;
        final boolean finalBotInstalled;
        final boolean finalBotRunning;

        if (am.isProotMode()) {
            PRootManager pm = PRootManager.getInstance(this);
            boolean prootRootfsReady = pm.isRootfsReady();
            boolean prootAstrBotInstalled = pm.isAstrBotInstalled();
            boolean prootAstrBotRunning = pm.isAstrBotRunning();
            finalStatus = new ChrootManager.FullStatus(
                false,                  // rootAvailable
                prootRootfsReady,       // rootfsReady
                false,                  // chrootMounted (not applicable)
                prootAstrBotInstalled,  // astrBotInstalled
                prootAstrBotRunning     // astrBotRunning
            );
            finalBotInstalled = prootAstrBotInstalled;
            finalBotRunning = prootAstrBotRunning;
        } else {
            finalStatus = status;
            finalBotInstalled = botInstalled;
            finalBotRunning = botRunning;
        }

        mHandler.post(() -> updateStatusUI(finalStatus, finalBotInstalled, finalBotRunning));
    }

    /** Update UI based on status — must be called on main thread */
    private void updateStatusUI(ChrootManager.FullStatus status, boolean botInstalled, boolean botRunning) {
        boolean rootAvailable = status.rootAvailable();
        boolean hasPrivilegedAccess = rootAvailable;
        boolean rootfsReady = status.rootfsReady();
        boolean chrootMounted = status.chrootMounted();
        boolean astrBotInstalled = status.astrBotInstalled();
        boolean astrBotRunning = status.astrBotRunning();
        AuthManager am = AuthManager.getInstance();

        // PRoot mode — always available, no root needed
        if (am.isProotMode()) {
            if (rootfsReady && astrBotInstalled) {
                // Fully installed — show status
                String botStatus = botRunning
                    ? "🤖 AstrBot: 运行中"
                    : "🤖 AstrBot: 已停止";
                mStatusText.setText("🐧 PRoot 模式（免 Root）\n" + botStatus);
                mStartButton.setEnabled(!botRunning);
                mStopButton.setEnabled(botRunning);
                mSetupButton.setVisibility(View.GONE);
                mSshInfoPanel.setVisibility(View.VISIBLE);
                mWebuiPanel.setVisibility(View.VISIBLE);

                // WebUI panel status
                mWebuiUrl.setText("http://127.0.0.1:6185");
                String lanIp = detectLanIpRaw();
                if (lanIp != null) {
                    mWebuiUrlLan.setText("局域网: http://" + lanIp + ":6185");
                    mWebuiUrlLan.setVisibility(View.VISIBLE);
                } else {
                    mWebuiUrlLan.setVisibility(View.GONE);
                }
                if (astrBotRunning) {
                    mWebuiStatus.setText("运行中");
                    mWebuiStatus.setTextColor(getColor(android.R.color.holo_green_light));
                } else {
                    mWebuiStatus.setText("未运行");
                    mWebuiStatus.setTextColor(getColor(android.R.color.holo_red_light));
                }

                updateSshPanel();
            } else {
                // Not yet installed
                mStatusText.setText("🐧 PRoot 模式（免 Root）\n📦 需要安装");
                mStartButton.setEnabled(false);
                mStopButton.setEnabled(false);
                mSetupButton.setVisibility(View.VISIBLE);
                mSetupButton.setEnabled(true);
                mSetupButton.setText("安装 AstrBot");
                mSshInfoPanel.setVisibility(View.GONE);
                mWebuiPanel.setVisibility(View.GONE);
            }
        } else if (!hasPrivilegedAccess) {
            // No root — auto-switch to PRoot mode
            am.setForceProot(true);
            mStatusText.setText("🐧 PRoot 模式（免 Root）\n📦 需要安装");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.VISIBLE);
            mSetupButton.setEnabled(true);
            mSetupButton.setText("安装 AstrBot");
            mSshInfoPanel.setVisibility(View.GONE);
            mWebuiPanel.setVisibility(View.GONE);
        } else if (!rootfsReady || !astrBotInstalled) {
            mStatusText.setText("📦 需要安装（Root 模式）");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.VISIBLE);
            mSetupButton.setEnabled(true);
            mWebuiPanel.setVisibility(View.GONE);
            mSshInfoPanel.setVisibility(View.GONE);
        } else {
            // Show status based on active bot engine
            BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
            String ubuntuStatus = chrootMounted ? "🐧 Ubuntu: 运行中" : "🐧 Ubuntu: 未挂载";

            String botStatus = botRunning
                ? "🤖 " + activeBot.getName() + ": 运行中"
                : "🤖 " + activeBot.getName() + ": 已停止";
            mStatusText.setText(ubuntuStatus + "\n" + botStatus);

            mStartButton.setEnabled(!botRunning);
            mStopButton.setEnabled(botRunning);

            mSetupButton.setVisibility(View.GONE);

            // WebUI panel: always visible after installation, update status text
            mWebuiUrl.setText("http://127.0.0.1:6185");
            String lanIp = detectLanIpRaw();
            if (lanIp != null) {
                mWebuiUrlLan.setText("局域网: http://" + lanIp + ":6185");
                mWebuiUrlLan.setVisibility(View.VISIBLE);
            } else {
                mWebuiUrlLan.setVisibility(View.GONE);
            }
            mWebuiPanel.setVisibility(View.VISIBLE);
            if ("astrbot".equals(activeBot.getId()) && astrBotRunning) {
                mWebuiStatus.setText("运行中");
                mWebuiStatus.setTextColor(getColor(android.R.color.holo_green_light));
            } else {
                mWebuiStatus.setText("未运行");
                mWebuiStatus.setTextColor(getColor(android.R.color.holo_red_light));
            }

            // SSH panel: always visible after installation
            mSshInfoPanel.setVisibility(View.VISIBLE);
            updateSshPanel();
        }

        // Update the component status dashboard
        updateComponentStatusUI(status, botInstalled, botRunning);
    }

    /**
     * Update the 2x2 component status dashboard (BINARIES / ROOTFS / BOOTSTRAP / ASTRBOT).
     * BotPocket-style status cards: shows ready/not_ready/warning/error per component.
     * Called on main thread from updateStatusUI.
     */
    /**
     * Update the 2x2 component status dashboard (BINARIES / ROOTFS / BOOTSTRAP / BOT).
     * The 4th card dynamically shows the active bot engine name and status.
     */
    private void updateComponentStatusUI(ChrootManager.FullStatus status, boolean botInstalled, boolean botRunning) {
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        // Update the 4th card label to match active bot
        if (mLabelAstrbot != null) {
            mLabelAstrbot.setText(activeBot.getName().toUpperCase());
        }
        // BINARIES: root shell access (su) or PRoot mode
        AuthManager am = AuthManager.getInstance();
        if (am.isProotMode()) {
            // In PRoot mode, binaries are always available (proot is bundled)
            mStatusBinaries.setText("PRoot 已就绪");
            mStatusBinaries.setTextColor(getColor(R.color.status_connected));
            mDotBinaries.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (status.rootAvailable()) {
            mStatusBinaries.setText("Root 已就绪");
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
        if (am.isProotMode()) {
            // In PRoot mode, no chroot mounting is needed — proot handles everything
            mStatusBootstrap.setText("无需");
            mStatusBootstrap.setTextColor(getColor(R.color.status_connected));
            mDotBootstrap.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (status.chrootMounted()) {
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

        // BOT (4th card): installed + running status — based on active bot engine
        if (botInstalled && botRunning) {
            mStatusAstrbot.setText("运行中");
            mStatusAstrbot.setTextColor(getColor(R.color.status_connected));
            mDotAstrbot.setBackgroundResource(R.drawable.ic_status_ready);
        } else if (botInstalled) {
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
        boolean useProot = AuthManager.getInstance().isProotMode();
        new Thread(() -> {
            boolean isRunning;
            String sshInfo;
            String rootPassword;
            int sshPort;
            if (useProot) {
                PRootManager pm = PRootManager.getInstance(MainActivity.this);
                isRunning = pm.isSshRunning();
                sshInfo = pm.getSshInfo();
                sshPort = PRootManager.SSH_PORT;
                rootPassword = RbotConstants.DEFAULT_SSH_PASSWORD;
            } else {
                isRunning = ChrootManager.isSshRunning();
                sshInfo = ChrootManager.getSshInfo();
                sshPort = 22;
                rootPassword = ChrootManager.getRootPassword();
            }
            mHandler.post(() -> {
                // Only show LAN address (user has built-in terminal for local access)
                String lanIp = detectLanIpRaw();
                if (lanIp != null) {
                    mSshInfo.setText("ssh root@" + lanIp + " -p " + sshPort);
                    mSshInfoLan.setVisibility(View.GONE);
                } else {
                    mSshInfo.setText("ssh root@127.0.0.1 -p " + sshPort);
                    mSshInfoLan.setVisibility(View.GONE);
                }
                mSshPassword.setText("密码: " + (rootPassword.isEmpty() ? "(未设置)" : rootPassword));

                // Click to copy SSH connection string
                mSshInfo.setOnClickListener(v -> {
                    String copyStr = mSshInfo.getText().toString();
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("SSH", copyStr);
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(this, "已复制: " + copyStr, android.widget.Toast.LENGTH_SHORT).show();
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
        mStopButton.setEnabled(false);
        mStatusText.setText("🔄 启动中（可能需要 10-30 秒）...");
        
        // Log the operation
        OpLog.log("🚀 启动 AstrBot...");

        // Start foreground monitor service
        Intent monitorIntent = new Intent(this, GatewayMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent);
        } else {
            startService(monitorIntent);
        }

        // Start active bot engine
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        new Thread(() -> {
            ChrootManager.CommandResult result = activeBot.start();
            
            if (result.success()) {
                OpLog.log("✅ AstrBot 启动成功");
            } else {
                OpLog.log("❌ AstrBot 启动失败: " + result.stderr());
            }
            
            refreshStatusOffThread();
            mHandler.post(() -> {
                if (result.success()) {
                    android.widget.Toast.makeText(this, "AstrBot 启动成功", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, "启动失败: " + result.stderr(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void stopGateway() {
        mStopButton.setEnabled(false);
        mStatusText.setText("🔄 停止中...");
        
        // Log the operation
        OpLog.log("🛑 停止 AstrBot...");

        // Stop monitor service first
        stopService(new Intent(this, GatewayMonitorService.class));

        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        new Thread(() -> {
            // Kill active bot - call stop once
            ChrootManager.CommandResult result = activeBot.stop();
            
            OpLog.log("停止命令输出: " + result.stdout());
            if (!result.stderr().isEmpty()) {
                OpLog.log("停止命令错误: " + result.stderr());
            }
            
            // Wait a moment for process to die
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
            
            // Check if it's really stopped
            boolean stillRunning = activeBot.isRunning();
            
            if (!stillRunning) {
                OpLog.log("✅ AstrBot 已停止");
            } else {
                OpLog.log("⚠️ AstrBot 可能仍在运行，尝试强制停止...");
                // Try one more time with force
                activeBot.stop();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                stillRunning = activeBot.isRunning();
                if (!stillRunning) {
                    OpLog.log("✅ 强制停止成功");
                } else {
                    OpLog.log("❌ 无法停止 AstrBot，请尝试重启应用");
                }
            }

            refreshStatusOffThread();
            final boolean finalStillRunning = stillRunning;
            mHandler.post(() -> {
                if (!finalStillRunning) {
                    android.widget.Toast.makeText(this, "AstrBot 已停止", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, "AstrBot 可能仍在运行，请查看日志", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }


    // ─── WebUI URL detection ───

    /** Try to detect LAN IP of this device, fall back to localhost */
    private String detectLanIp() {
        // For the WebUI open button — always use localhost (browser is on the same device)
        return "http://127.0.0.1:6185";
    }

    /** Detect raw LAN IP address (no port, no protocol), null if not found */
    private String detectLanIpRaw() {
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
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectLanIpRaw failed: " + e.getMessage());
        }
        return null;
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

    // ─── SSH Service ───

    private void toggleSshService() {
        if (mSshStarting) return;
        boolean useProot = AuthManager.getInstance().isProotMode();

        new Thread(() -> {
            boolean isRunning = useProot
                ? PRootManager.getInstance(MainActivity.this).isSshRunning()
                : ChrootManager.isSshRunning();
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
        boolean useProot = AuthManager.getInstance().isProotMode();

        android.widget.Toast.makeText(this, "🔌 正在启动 SSH 服务..", android.widget.Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            ChrootManager.CommandResult result;
            String sshInfo;
            if (useProot) {
                result = PRootManager.getInstance(MainActivity.this).startSshService();
                sshInfo = PRootManager.getInstance(MainActivity.this).getSshInfo();
            } else {
                result = ChrootManager.startSshService();
                sshInfo = ChrootManager.getSshInfo();
            }
            boolean success = result.success() && !result.stdout().contains("error");

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                if (success) {
                    android.widget.Toast.makeText(this, "✅ SSH 服务已启动: " + sshInfo, android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, "❌ SSH 启动失败: " + result.stderr(), android.widget.Toast.LENGTH_LONG).show();
                }
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }

    private void stopSshService() {
        mSshStarting = true;
        mSshToggleButton.setEnabled(false);
        boolean useProot = AuthManager.getInstance().isProotMode();

        android.widget.Toast.makeText(this, "🔌 正在停止 SSH 服务..", android.widget.Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            ChrootManager.CommandResult result = useProot
                ? PRootManager.getInstance(MainActivity.this).stopSshService()
                : ChrootManager.stopSshService();
            boolean stopped = result.success();

            mHandler.post(() -> {
                mSshStarting = false;
                updateSshPanel();
                android.widget.Toast.makeText(this, stopped ? "✅ SSH 服务已停止" : "⚠️ SSH 停止可能失败", android.widget.Toast.LENGTH_SHORT).show();
                mSshToggleButton.setEnabled(true);
            });
        }).start();
    }
}
