package app.rbot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Management panel for Hermes Agent.
 * Provides WebUI access info, interactive shell instructions, and start/stop controls.
 */
public class HermesManagementActivity extends AppCompatActivity {

    private static final String TAG = "HermesManagement";

    private TextView mTvStatus;
    private View mDotStatus;
    private Button mBtnOpenShell;
    private Button mBtnStart;
    private Button mBtnStop;
    private Button mBtnRestart;
    private Button mBtnViewLog;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private BotAdapter mHermes;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_management);

        mHermes = BotManager.getInstance(this).getBot(BotAdapter.ID_HERMES);

        mTvStatus = findViewById(R.id.tv_hermes_status);
        mDotStatus = findViewById(R.id.dot_hermes_status);
        mBtnOpenShell = findViewById(R.id.btn_open_shell);
        mBtnStart = findViewById(R.id.btn_hermes_start);
        mBtnStop = findViewById(R.id.btn_hermes_stop);
        mBtnRestart = findViewById(R.id.btn_hermes_restart);
        mBtnViewLog = findViewById(R.id.btn_view_log);

        mBtnOpenShell.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("进入交互式终端")
                .setMessage(
                    "点击确认后，将打开 Rbot 内置终端。\n\n" +
                    "在终端中执行以下命令进行初始配置：\n\n" +
                    "$ source /root/hermes/venv/bin/activate\n" +
                    "$ cd /root/hermes-agent-src\n" +
                    "$ hermes setup\n\n" +
                    "首次运行需要配置 API Key 和平台渠道。"
                )
                .setPositiveButton("打开终端", (dialog, which) -> {
                    Intent shellIntent = new Intent(this, ShellActivity.class);
                    startActivity(shellIntent);
                })
                .setNegativeButton("取消", null)
                .show();
        });

        mBtnStart.setOnClickListener(v -> {
            mBtnStart.setEnabled(false);
            mExecutor.execute(() -> {
                ChrootManager.CommandResult result = mHermes.start();
                mMainHandler.post(() -> {
                    mBtnStart.setEnabled(true);
                    if (result.success()) {
                        Toast.makeText(this, "Hermes 启动成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "启动失败: " + result.stderr(), Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });
            });
        });

        mBtnStop.setOnClickListener(v -> {
            mBtnStop.setEnabled(false);
            mExecutor.execute(() -> {
                ChrootManager.CommandResult result = mHermes.stop();
                mMainHandler.post(() -> {
                    mBtnStop.setEnabled(true);
                    if (result.success()) {
                        Toast.makeText(this, "Hermes 已停止", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "停止失败: " + result.stderr(), Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });
            });
        });

        mBtnRestart.setOnClickListener(v -> {
            mBtnRestart.setEnabled(false);
            mExecutor.execute(() -> {
                mHermes.stop();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                ChrootManager.CommandResult result = mHermes.start();
                mMainHandler.post(() -> {
                    mBtnRestart.setEnabled(true);
                    if (result.success()) {
                        Toast.makeText(this, "Hermes 重启成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "重启失败: " + result.stderr(), Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });
            });
        });

        mBtnViewLog.setOnClickListener(v -> {
            String logPath = mHermes.getLogFile();
            mExecutor.execute(() -> {
                String content = readFile(logPath);
                mMainHandler.post(() -> showLogDialog(content, logPath));
            });
        });

        // Initial status check
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        mExecutor.execute(() -> {
            boolean installed = mHermes.isInstalled();
            boolean running = mHermes.isRunning();
            mMainHandler.post(() -> updateStatusUI(installed, running));
        });
    }

    private void updateStatusUI(boolean installed, boolean running) {
        if (!installed) {
            mTvStatus.setText("未安装");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_not_ready);
        } else if (running) {
            mTvStatus.setText("运行中 (CLI)");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mTvStatus.setText("已安装 · 未运行");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_warning);
        }
    }

    private String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        // Try chroot path first
        String fullPath = RbotConstants.CHROOT_DIR + path;
        try (BufferedReader reader = new BufferedReader(new FileReader(fullPath))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 200) {
                sb.append(line).append('\n');
                lines++;
            }
            if (lines == 200) sb.append("\n... (超过 200 行截断)");
        } catch (IOException e) {
            return "无法读取日志: " + e.getMessage();
        }
        return sb.length() == 0 ? "日志为空" : sb.toString();
    }

    private void showLogDialog(String content, String logPath) {
        new AlertDialog.Builder(this)
            .setTitle("Hermes Gateway 日志")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .setNeutralButton("刷新", (dialog, which) -> {
                mExecutor.execute(() -> {
                    String fresh = readFile(logPath);
                    mMainHandler.post(() -> showLogDialog(fresh, logPath));
                });
            })
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }
}
