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
    private Button mBtnChrootStart;
    private Button mBtnChrootStop;
    private Button mBtnSshConnect;

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
        mBtnChrootStart = findViewById(R.id.btn_chroot_start);
        mBtnChrootStop = findViewById(R.id.btn_chroot_stop);
        mBtnSshConnect = findViewById(R.id.btn_ssh_connect);

        mBtnOpenShell.setOnClickListener(v -> {
            // Check if chroot is mounted first
            if (!ChrootManager.isChrootMounted()) {
                new AlertDialog.Builder(this)
                    .setTitle("虚拟机未启动")
                    .setMessage("需要先启动虚拟机才能使用终端。点击确认启动虚拟机。")
                    .setPositiveButton("启动", (dialog, which) -> startChroot())
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
            Intent shellIntent = new Intent(this, ShellActivity.class);
            startActivity(shellIntent);
        });

        mBtnSshConnect.setOnClickListener(v -> {
            if (!ChrootManager.isChrootMounted()) {
                new AlertDialog.Builder(this)
                    .setTitle("虚拟机未启动")
                    .setMessage("需要先启动虚拟机才能使用 SSH。点击确认启动虚拟机。")
                    .setPositiveButton("启动", (dialog, which) -> startChroot())
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
            // Show SSH info
            String sshInfo = ChrootManager.getSshInfo();
            String password = ChrootManager.getRootPassword();
            new AlertDialog.Builder(this)
                .setTitle("SSH 连接信息")
                .setMessage("连接地址: " + sshInfo + "\n端口: 22\n用户名: root\n密码: " + (password.isEmpty() ? "(未设置)" : password) +
                    "\n\n⚠️ 如遇 'host key changed' 错误，在电脑上执行:\nssh-keygen -R " + sshInfo.replace("root@", "") +
                    "\n\n点击复制地址")
                .setPositiveButton("复制地址", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("SSH", sshInfo);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制: " + sshInfo, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
        });

        mBtnChrootStart.setOnClickListener(v -> startChroot());

        mBtnChrootStop.setOnClickListener(v -> {
            mBtnChrootStop.setEnabled(false);
            mExecutor.execute(() -> {
                ChrootManager.cleanupChrootDevices();
                mMainHandler.post(() -> {
                    mBtnChrootStop.setEnabled(true);
                    Toast.makeText(this, "虚拟机已停止", Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            });
        });

        mBtnStart.setOnClickListener(v -> {
            mBtnStart.setEnabled(false);
            mExecutor.execute(() -> {
                OpLog.log("启动 Hermes...");
                ChrootManager.CommandResult result = mHermes.start();
                mMainHandler.post(() -> {
                    mBtnStart.setEnabled(true);
                    if (result.success()) {
                        OpLog.log("Hermes 启动成功");
                        Toast.makeText(this, "Hermes 启动成功", Toast.LENGTH_SHORT).show();
                    } else {
                        OpLog.error("Hermes 启动失败: " + result.stderr());
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
            boolean chrootMounted = ChrootManager.isChrootMounted();
            boolean installed = mHermes.isInstalled();
            boolean running = mHermes.isRunning();
            mMainHandler.post(() -> updateStatusUI(chrootMounted, installed, running));
        });
    }

    private void updateStatusUI(boolean chrootMounted, boolean installed, boolean running) {
        // Update chroot buttons
        if (mBtnChrootStart != null) mBtnChrootStart.setEnabled(!chrootMounted);
        if (mBtnChrootStop != null) mBtnChrootStop.setEnabled(chrootMounted);

        if (!installed) {
            mTvStatus.setText("未安装 Hermes");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_not_ready);
        } else if (!chrootMounted) {
            mTvStatus.setText("虚拟机未启动");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_warning);
        } else if (running) {
            mTvStatus.setText("Hermes 运行中 (CLI)");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mTvStatus.setText("已安装 · Hermes 未运行");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_warning);
        }
    }

    private String readFile(String path) {
        // Must use root to read files under /data/rbot (root:root 700)
        String fullPath = RbotConstants.CHROOT_DIR + path;
        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "tail -n 200 '" + fullPath + "' 2>/dev/null", 5);
        if (!result.success() || result.stdout().trim().isEmpty()) {
            return "无法读取日志 (可能文件不存在或无内容)";
        }
        return result.stdout();
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

    private void startChroot() {
        mBtnChrootStart.setEnabled(false);
        mExecutor.execute(() -> {
            OpLog.log("启动虚拟机...");
            ChrootManager.setupChrootEnvironment(new ChrootManager.ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    OpLog.progress(message);
                    mMainHandler.post(() -> {
                        mTvStatus.setText(message);
                    });
                }

                @Override
                public void onError(String error) {
                    OpLog.error(error);
                    mMainHandler.post(() -> {
                        Toast.makeText(HermesManagementActivity.this, "错误: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
            
            // 检查 SSH 状态并报告
            boolean sshRunning = ChrootManager.isSshRunning();
            String sshMsg = sshRunning ? "✅ SSH 服务已启动" : "⚠️ SSH 未运行 (可能未安装 openssh-server)";
            final String finalSshMsg = sshMsg;
            mMainHandler.post(() -> {
                mBtnChrootStart.setEnabled(true);
                mTvStatus.setText("虚拟机已启动 - " + finalSshMsg);
                Toast.makeText(this, "虚拟机已启动", Toast.LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }
}
