package app.rbot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Management panel for Hermes Agent.
 * Start/stop controls, log viewer, SSH connection info (copy to clipboard).
 */
public class HermesManagementActivity extends AppCompatActivity {

    private static final String TAG = "HermesManagement";
    private static final String PREFS_NAME = "rbot_prefs";
    private static final String KEY_SSH_PASSWORD = "hermes_ssh_password";

    private TextView mTvStatus;
    private View mDotStatus;
    private TextView mTvPlatforms;
    private TextView mTvSshLocal;
    private TextView mTvSshLan;
    private android.widget.ImageButton mBtnBack;
    private Button mBtnStart;
    private Button mBtnStop;
    private Button mBtnRestart;
    private Button mBtnViewLog;
    private Button mBtnSsh;
    private Button mBtnCopySshLocal;
    private Button mBtnCopySshLan;
    private Button mBtnResetSshPassword;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private BotAdapter mHermes;
    private String mLanIp = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_management);

        mHermes = BotManager.getInstance(this).getBot(BotAdapter.ID_HERMES);

        mTvStatus = findViewById(R.id.tv_hermes_status);
        mDotStatus = findViewById(R.id.dot_hermes_status);
        mTvPlatforms = findViewById(R.id.tv_hermes_platforms);
        mBtnBack = findViewById(R.id.btn_hermes_back);
        mBtnBack.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        mBtnStart = findViewById(R.id.btn_hermes_start);
        mBtnStop = findViewById(R.id.btn_hermes_stop);
        mBtnRestart = findViewById(R.id.btn_hermes_restart);
        mBtnViewLog = findViewById(R.id.btn_view_log);
        mBtnSsh = findViewById(R.id.btn_ssh);
        mBtnCopySshLocal = findViewById(R.id.btn_copy_ssh_local);
        mBtnCopySshLan = findViewById(R.id.btn_copy_ssh_lan);
        mBtnResetSshPassword = findViewById(R.id.btn_reset_ssh_password);
        mTvSshLocal = findViewById(R.id.tv_ssh_local_cmd);
        mTvSshLan = findViewById(R.id.tv_ssh_lan_cmd);

        // Set platform info
        mTvPlatforms.setText(mHermes.getSupportedPlatforms());

        // Get LAN IP for SSH info
        mLanIp = getLanIpAddress();
        mTvSshLocal.setText("ssh root@127.0.0.1 -p 8022");
        mTvSshLan.setText(mLanIp.isEmpty() ? "未获取到局域网IP" : "ssh root@" + mLanIp + " -p 8022");

        // Click handlers for new views
        mBtnCopySshLocal.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("ssh_local", mTvSshLocal.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        mBtnCopySshLan.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("ssh_lan", mTvSshLan.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        mBtnResetSshPassword.setOnClickListener(v -> showSetPasswordDialog());

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
                ChrootManager.CommandResult r = ChrootManager.execInChroot(
                    "if [ -f " + logPath + " ]; then cat " + logPath + "; else echo HERMES_LOG_NOT_FOUND; fi", 10);
                String content = r.success() ? r.stdout() : "读取失败: " + r.stderr();
                mMainHandler.post(() -> showLogDialog(content, logPath));
            });
        });

        mBtnSsh.setOnClickListener(v -> openSshSetup());

        // Initial status check
        refreshStatus();
    }

    /** Add SSH info section dynamically below the button row */
    private String getLanIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<java.net.InetAddress> addrEnum = intf.getInetAddresses(); addrEnum.hasMoreElements();) {
                    java.net.InetAddress addr = addrEnum.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void openSshSetup() {
        String sshPassword = getSharedPreferences("rbot_prefs", Context.MODE_PRIVATE)
                .getString("hermes_ssh_password", null);

        if (sshPassword == null || sshPassword.isEmpty()) {
            showSetPasswordDialog();
            return;
        }
        applyPasswordAndStartSshd(sshPassword);
    }

    private void showSetPasswordDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedPw = prefs.getString(KEY_SSH_PASSWORD, null);

        View view = getLayoutInflater().inflate(R.layout.dialog_set_ssh_password, null);
        android.widget.EditText etPwd = view.findViewById(R.id.et_ssh_password);
        android.widget.EditText etConfirm = view.findViewById(R.id.et_ssh_password_confirm);

        // Pre-fill if already saved
        if (savedPw != null && !savedPw.isEmpty()) {
            etPwd.setText(savedPw);
            etConfirm.setText(savedPw);
        }

        String title = (savedPw != null && !savedPw.isEmpty()) ? "SSH 密码（已保存）" : "设置 SSH 密码";
        String msg = (savedPw != null && !savedPw.isEmpty())
                ? "当前密码：" + savedPw + "\n\n修改后请使用新密码连接"
                : "首次连接需设置 root 密码（仅用于 SSH 登录）\n密码至少 6 位";

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setView(view)
                .setPositiveButton("保存并连接", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pwd = etPwd.getText().toString();
                String confirm = etConfirm.getText().toString();
                if (pwd.length() < 6) {
                    etPwd.setError("密码至少 6 位");
                    return;
                }
                if (!pwd.equals(confirm)) {
                    etConfirm.setError("两次密码不一致");
                    return;
                }
                prefs.edit().putString(KEY_SSH_PASSWORD, pwd).apply();
                dialog.dismiss();
                applyPasswordAndStartSshd(pwd);
            });
        });
        dialog.show();
    }

    private void applyPasswordAndStartSshd(String password) {
        mBtnSsh.setEnabled(false);
        // Show persistent loading dialog so user can read logs while it runs
        AlertDialog loading = new AlertDialog.Builder(this)
            .setMessage("正在设置密码...\n\n(等待完成)")
            .setCancelable(false)
            .create();
        loading.show();

        mExecutor.execute(() -> {
            // Set password in chroot
            ChrootManager.CommandResult pwdResult = ChrootManager.setSshPassword(password);
            // Start/restart sshd with correct config
            ChrootManager.CommandResult sshResult = ChrootManager.startSshService();
            mMainHandler.post(() -> {
                loading.dismiss();
                mBtnSsh.setEnabled(true);
                if (!pwdResult.success()) {
                    String err = pwdResult.stdout().trim();
                    if (err.isEmpty()) err = "未知错误";
                    new AlertDialog.Builder(this)
                        .setTitle("密码设置失败")
                        .setMessage("脚本输出:\n" + err + "\n\n→ 首页点「查看日志」看完整 chroot 输出")
                        .setPositiveButton("确定", null)
                        .show();
                } else if (!sshResult.success()) {
                    new AlertDialog.Builder(this)
                        .setTitle("SSH 启动失败")
                        .setMessage(sshResult.stdout().trim() + "\n" + sshResult.stderr().trim())
                        .setPositiveButton("确定", null)
                        .show();
                } else {
                    // Show password prominently so user can see and copy it
                    String lanIp = getLanIpAddress();
                    String lanCmd = "ssh root@" + (lanIp.isEmpty() ? "你的IP" : lanIp) + " -p 8022";
                    new AlertDialog.Builder(this)
                        .setTitle("SSH 密码设置成功")
                        .setMessage("密码：「" + password + "」\n\n连接命令：\n" + lanCmd + "\n\n端口：8022\n用户名：root\n\n点击「复制密码」复制密码，或「打开 SSH」打开 SSH 客户端。")
                        .setPositiveButton("复制密码", (dialog, which) -> {
                            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("ssh_password", password));
                            Toast.makeText(this, "密码已复制", Toast.LENGTH_SHORT).show();
                        })
                        .setNeutralButton("打开 SSH", (dialog, which) -> {
                            String sshUri = "ssh://root@127.0.0.1:8022";
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sshUri));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                startActivity(Intent.createChooser(intent, "打开 SSH 客户端"));
                            } catch (Exception e) {
                                Toast.makeText(this, "未找到 SSH 客户端应用", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
                }
            });
        });
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
            // Auto-start sshd when Hermes is installed (for SSH access)
            // Also apply saved password so SSH auth works
            if (installed) {
                String savedPwd = getSharedPreferences("rbot_prefs", Context.MODE_PRIVATE)
                        .getString("hermes_ssh_password", null);
                if (savedPwd != null && !savedPwd.isEmpty()) {
                    ChrootManager.setSshPassword(savedPwd);
                }
                ChrootManager.startSshService();
            }
            mMainHandler.post(() -> updateStatusUI(installed, running));
        });
    }

    private void updateStatusUI(boolean installed, boolean running) {
        if (!installed) {
            mTvStatus.setText("未安装");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_not_ready);
        } else if (running) {
            mTvStatus.setText("运行中 · Gateway @ 8080");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_ready);
        } else {
            mTvStatus.setText("已安装 · 未运行");
            mDotStatus.setBackgroundResource(R.drawable.ic_status_warning);
        }
    }

    private void showLogDialog(String content, String logPath) {
        new AlertDialog.Builder(this)
            .setTitle("Hermes Gateway 日志")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .setNeutralButton("刷新", (dialog, which) -> {
                mExecutor.execute(() -> {
                    ChrootManager.CommandResult r = ChrootManager.execInChroot(
                        "if [ -f " + logPath + " ]; then cat " + logPath + "; else echo HERMES_LOG_NOT_FOUND; fi", 10);
                    String fresh = r.success() ? r.stdout() : "读取失败: " + r.stderr();
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
