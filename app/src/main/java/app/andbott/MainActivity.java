package app.andbott;

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

import app.andbott.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Minimal main activity for BotDrop: status + start/stop + log viewer.
 * Replaces the old DashboardActivity monolith.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static final String NOTIFICATION_CHANNEL_ID = "botdrop_gateway";

    private TextView mStatusText;
    private TextView mLogText;
    private Button mStartButton;
    private Button mStopButton;
    private Button mSetupButton;
    private Button mTerminalButton;
    private ScrollView mLogScrollView;
    private CardView mWebuiPanel;
    private TextView mWebuiUrl;
    private Button mOpenWebuiButton;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private BotDropService mService;
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
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
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
        mTerminalButton = findViewById(R.id.btn_terminal);

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
        mTerminalButton.setOnClickListener(v -> {
            // Launch terminal — note: TerminalActivity was removed during refactor.
            // Show a toast for now since terminal integration requires rebuild.
            android.widget.Toast.makeText(this, "终端功能需要完整编译后使用", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bind to BotDropService
        Intent intent = new Intent(this, BotDropService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        // Refresh status off main thread (root commands block)
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
            status.astrBotInstalled(), status.astrBotRunning()));
    }

    /** Update UI based on status — must be called on main thread */
    private void updateStatusUI(boolean rootAvailable, boolean rootfsReady, boolean astrBotInstalled, boolean astrBotRunning) {
        if (!rootAvailable) {
            mStatusText.setText("⚠ 需要 Root 权限");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.GONE);
            mWebuiPanel.setVisibility(View.GONE);
            mTerminalButton.setVisibility(View.GONE);
        } else if (!rootfsReady || !astrBotInstalled) {
            mStatusText.setText("📦 需要安装");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.VISIBLE);
            mSetupButton.setEnabled(true);
            mWebuiPanel.setVisibility(View.GONE);
            mTerminalButton.setVisibility(View.GONE);
        } else if (astrBotRunning) {
            mStatusText.setText("✅ 运行中");
            mStartButton.setEnabled(false);
            mStopButton.setEnabled(true);
            mSetupButton.setVisibility(View.GONE);
            // Show WebUI panel — try to detect LAN IP, fall back to localhost
            String webuiUrl = detectLanIp();
            mWebuiUrl.setText(webuiUrl);
            mWebuiPanel.setVisibility(View.VISIBLE);
            mTerminalButton.setVisibility(View.VISIBLE);
        } else {
            mStatusText.setText("⏹ 已停止");
            mStartButton.setEnabled(true);
            mStopButton.setEnabled(false);
            mSetupButton.setVisibility(View.GONE);
            mWebuiPanel.setVisibility(View.GONE);
            mTerminalButton.setVisibility(View.VISIBLE);
        }
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
            // Refresh full status after start attempt
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
            ChrootManager.stopAstrBot();
            // Refresh full status after stop
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
                    "tail -n 50 '" + BotDropConstants.ASTRBOT_LOG_FILE + "' 2>/dev/null", 5);

                if (result.success() && !result.stdout().trim().isEmpty()) {
                    String newTail = result.stdout();
                    mHandler.post(() -> {
                        synchronized (mLogLock) {
                            if (newTail.equals(mLastLogTail)) return;  // No change

                            // Detect if user is reading history
                            boolean hadSelection = mLogText.hasSelection();
                            boolean wasAtEnd = !hadSelection &&
                                (mLogText.getText().length() == 0 ||
                                mLogScrollView.getScrollY() >= mLogScrollView.getMaxScrollAmount() - 50);

                            // Compute delta: what is in newTail but not at the end of current text
                            String current = mLogText.getText().toString();
                            StringBuilder delta = new StringBuilder();
                            String[] newLines = newTail.split("\n");
                            String[] curLines = current.split("\n");

                            // Find starting point of new content
                            int startIdx = 0;
                            if (!curLines[curLines.length - 1].isEmpty()) {
                                // Try to find overlap: last line(s) of current in newTail
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

                                // Trim to MAX_LOG_LINES
                                String text = mLogText.getText().toString();
                                String[] allLines = text.split("\n");
                                if (allLines.length > MAX_LOG_LINES) {
                                    int cutPos = text.indexOf(allLines[allLines.length - MAX_LOG_LINES]);
                                    mLogText.setText(text.substring(Math.max(0, cutPos)));
                                }

                                // Auto-scroll only if was at end and not reading history
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
                // Try any interface with an IPv4 address
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
            "BotDrop 服务状态",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("BotDrop AstrBot 运行状态");
        manager.createNotificationChannel(channel);
    }
}
