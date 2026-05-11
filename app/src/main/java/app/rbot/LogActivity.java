package app.rbot;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen log viewer page.
 * Shows both operation logs (app-initiated actions) and bot runtime logs.
 * BotPocket-style: clean layout with clear button, monospace font.
 * Newest entries at top (reverse chronological).
 */
public class LogActivity extends AppCompatActivity {

    private TextView mLogText;
    private ScrollView mLogScrollView;
    private Button mClearLogButton;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mLogPolling = false;
    private String mLastOpLogTail = "";
    private String mLastBotLogTail = "";
    private final Object mLogLock = new Object();
    private static final int MAX_LOG_LINES = 500;
    private String mActiveBotName;
    private String mBotLogFile;
    private boolean mUseProot;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        mLogText = findViewById(R.id.log_text);
        mLogScrollView = findViewById(R.id.log_scroll);
        mClearLogButton = findViewById(R.id.btn_clear_log);

        // Get active bot info for dynamic log path
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        mActiveBotName = activeBot.getName();
        mUseProot = AuthManager.getInstance().isProotMode();
        if (mUseProot) {
            // PRoot mode: log file is in app internal storage, can be read directly
            mBotLogFile = PRootManager.getInstance(this).getAstrBotLogFile();
        } else {
            // Chroot mode: log file is under /data/rbot, needs root to read
            mBotLogFile = RbotConstants.CHROOT_DIR + activeBot.getLogFile();
        }

        mClearLogButton.setOnClickListener(v -> {
            OpLog.clear();
            mLogText.setText("日志已清理。\n\n重新操作后，日志将实时显示在这里。");
            mLastOpLogTail = "";
            mLastBotLogTail = "";
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLogPolling();
        appendToLog("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n  📋 日志页面已打开\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n", false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogPolling();
    }

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
                // 1. Read operation log (app-initiated actions)
                String opLog = OpLog.readTail(100);

                // 2. Read bot runtime log
                String botLog = "";
                if (mUseProot) {
                    // PRoot mode: read directly from app storage (no root needed)
                    botLog = PRootManager.readTail(mBotLogFile, 100);
                } else {
                    // Chroot mode: read via root shell
                    ChrootManager.CommandResult botResult = ChrootManager.execRoot(
                        "tail -n 100 '" + mBotLogFile + "' 2>/dev/null", 5);
                    if (botResult.success() && !botResult.stdout().trim().isEmpty()) {
                        botLog = botResult.stdout();
                    }
                }

                final String fOpLog = opLog;
                final String fBotLog = botLog;

                mHandler.post(() -> {
                    synchronized (mLogLock) {
                        // Check if anything changed
                        boolean opChanged = !fOpLog.equals(mLastOpLogTail);
                        boolean botChanged = !fBotLog.equals(mLastBotLogTail);
                        if (!opChanged && !botChanged) return;

                        mLastOpLogTail = fOpLog;
                        mLastBotLogTail = fBotLog;

                        // Build combined display: operation log + bot log
                        StringBuilder display = new StringBuilder();

                        // Operation log section
                        if (!fOpLog.trim().isEmpty()) {
                            display.append("═══ 操作日志 ═══\n");
                            display.append(fOpLog);
                        }

                        // Bot runtime log section
                        if (!fBotLog.trim().isEmpty()) {
                            if (display.length() > 0) display.append("\n");
                            display.append("═══ ").append(mActiveBotName).append(" 运行日志 ═══\n");
                            display.append(fBotLog);
                        }

                        if (display.length() > 0) {
                            String newText = display.toString();

                            // Remove placeholder
                            String oldText = mLogText.getText().toString();
                            if (oldText.contains("暂无日志内容") ||
                                oldText.contains("重新操作") ||
                                oldText.contains("重新启动")) {
                                oldText = "";
                            }

                            mLogText.setText(newText + oldText);

                            // Trim to max lines
                            String text = mLogText.getText().toString();
                            String[] allLines = text.split("\n");
                            if (allLines.length > MAX_LOG_LINES) {
                                StringBuilder kept = new StringBuilder();
                                for (int i = 0; i < MAX_LOG_LINES; i++) {
                                    kept.append(allLines[i]).append("\n");
                                }
                                mLogText.setText(kept.toString());
                            }

                            mLogScrollView.post(() ->
                                mLogScrollView.scrollTo(0, 0));
                        }
                    }
                });
            } catch (Exception e) {
                // Silently ignore
            }

            if (mLogPolling) {
                mHandler.postDelayed(mLogPollRunnable, 3000);
            }
        }).start();
    }

    private void appendToLog(String message) {
        appendToLog(message, true);
    }

    private void appendToLog(String message, boolean top) {
        String oldText = mLogText.getText().toString();
        if (oldText.contains("暂无日志内容") || oldText.contains("重新操作")) {
            oldText = "";
        }
        if (top) {
            mLogText.setText(message + oldText);
        } else {
            mLogText.setText(oldText + message);
        }
        mLogScrollView.post(() -> mLogScrollView.scrollTo(0, 0));
    }
}
