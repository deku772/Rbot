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
 * BotPocket-style: clean layout with clear button, monospace font.
 */
public class LogActivity extends AppCompatActivity {

    private TextView mLogText;
    private ScrollView mLogScrollView;
    private Button mClearLogButton;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mLogPolling = false;
    private String mLastLogTail = "";
    private final Object mLogLock = new Object();
    private static final int MAX_LOG_LINES = 500;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        mLogText = findViewById(R.id.log_text);
        mLogScrollView = findViewById(R.id.log_scroll);
        mClearLogButton = findViewById(R.id.btn_clear_log);

        mClearLogButton.setOnClickListener(v -> {
            mLogText.setText("日志已清理。\n\n重新启动 AstrBot 后，日志将实时显示在这里。");
            mLastLogTail = "";
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLogPolling();
        // Add header when opened
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
                ChrootManager.CommandResult result = ChrootManager.execRoot(
                    "tail -n 100 '" + RbotConstants.ASTRBOT_LOG_FILE + "' 2>/dev/null", 5);

                if (result.success() && !result.stdout().trim().isEmpty()) {
                    String newTail = result.stdout();
                    mHandler.post(() -> {
                        synchronized (mLogLock) {
                            if (newTail.equals(mLastLogTail)) return;

                            String[] newLines = newTail.split("\n");
                            StringBuilder delta = new StringBuilder();

                            int startIdx = 0;
                            String current = mLogText.getText().toString();
                            if (!current.isEmpty() && !mLastLogTail.isEmpty()) {
                                outer:
                                for (int i = Math.max(0, newLines.length - 150);
                                     i < newLines.length; i++) {
                                    for (int j = Math.max(0, newLines.length - i - 1);
                                         j < newLines.length; j++) {
                                        if (newLines[i].equals(newLines[j])) {
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
                                String oldText = mLogText.getText().toString();
                                // Remove placeholder
                                if (oldText.contains("暂无日志内容") ||
                                    oldText.contains("重新启动 AstrBot")) {
                                    oldText = "";
                                }
                                mLogText.setText(delta.toString() + oldText);
                                mLastLogTail = newTail;

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
                }
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
        if (oldText.contains("暂无日志内容") || oldText.contains("重新启动 AstrBot")) {
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
