package app.rbot;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings page — contains all environment management, backup/restore, bot switching, and danger zone actions.
 * BotPocket-style: all settings are organized in cards/sections with clear descriptions.
 */
public class SettingsActivity extends AppCompatActivity {

    private Button mReinstallDepsButton;
    private Button mReinstallBotButton;
    private Button mReinstallRootfsButton;
    private Button mRepairEnvButton;
    private Button mResetPasswordButton;
    private Button mBackupButton;
    private Button mRestoreButton;
    private Button mCleanInstallButton;
    private Button mSwitchBotButton;
    private TextView mCurrentBotName;
    private TextView mCurrentBotStatus;

    private BotManager mBotManager;
    private boolean mBackupInProgress = false;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mBotManager = BotManager.getInstance(this);

        mReinstallDepsButton = findViewById(R.id.btn_reinstall_deps);
        mReinstallBotButton = findViewById(R.id.btn_reinstall_bot);
        mReinstallRootfsButton = findViewById(R.id.btn_reinstall_rootfs);
        mRepairEnvButton = findViewById(R.id.btn_repair_env);
        mResetPasswordButton = findViewById(R.id.btn_reset_password);
        mBackupButton = findViewById(R.id.btn_backup);
        mRestoreButton = findViewById(R.id.btn_restore);
        mCleanInstallButton = findViewById(R.id.btn_clean_install);
        mSwitchBotButton = findViewById(R.id.btn_switch_bot);
        mCurrentBotName = findViewById(R.id.current_bot_name);
        mCurrentBotStatus = findViewById(R.id.current_bot_status);

        // Hide backup/restore until active bot is installed
        if (!mBotManager.isInstalled()) {
            mBackupButton.setEnabled(false);
            mRestoreButton.setEnabled(false);
        }

        mReinstallDepsButton.setOnClickListener(v -> showReinstallDepsDialog());
        mReinstallBotButton.setOnClickListener(v -> showReinstallBotDialog());
        mReinstallRootfsButton.setOnClickListener(v -> showReinstallRootfsDialog());
        mRepairEnvButton.setOnClickListener(v -> showRepairEnvDialog());
        mResetPasswordButton.setOnClickListener(v -> showResetPasswordDialog());
        mBackupButton.setOnClickListener(v -> showBackupDialog());
        mRestoreButton.setOnClickListener(v -> showRestoreDialog());
        mCleanInstallButton.setOnClickListener(v -> showCleanInstallDialog());
        mSwitchBotButton.setOnClickListener(v -> showSwitchBotDialog());

        refreshBotInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBotInfo();
    }

    private void refreshBotInfo() {
        BotAdapter bot = mBotManager.getActiveBot();
        mCurrentBotName.setText(bot.getName());

        StringBuilder statusText = new StringBuilder();
        if (bot.isInstalled()) {
            statusText.append("已安装");
            if (bot.isRunning()) {
                statusText.append(" · 运行中");
            } else {
                statusText.append(" · 已停止");
            }
        } else {
            statusText.append("未安装");
        }
        mCurrentBotStatus.setText(statusText.toString());

        // Update platform text
        TextView platforms = findViewById(R.id.current_bot_platforms);
        if (platforms != null) {
            platforms.setText("支持: " + bot.getSupportedPlatforms());
        }

        // Update reinstall button label
        mReinstallBotButton.setText("🤖 重装 " + bot.getName());
    }

    private void showReinstallDepsDialog() {
        BotAdapter bot = mBotManager.getActiveBot();
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重装系统依赖")
            .setMessage("将重新运行 apt update 和安装 Python 3、SSH 等依赖。\n\n如果 " + bot.getName() + " 正在运行，将会先停止。")
            .setPositiveButton("开始重装", (dialog, which) -> {
                dialog.dismiss();
                appendToLog("\n🔄 开始重装系统依赖...\n");
                new Thread(() -> {
                    if (mBotManager.isRunning()) {
                        mHandler.post(() -> appendToLog("  ⏹ 正在停止 " + bot.getName() + "..."));
                        mBotManager.stop();
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
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showReinstallBotDialog() {
        BotAdapter bot = mBotManager.getActiveBot();
        String botName = bot.getName();

        if (bot.getId().equals(BotAdapter.ID_ASTRBOT)) {
            // AstrBot — version selector + proxy (existing logic)
            java.util.List<String> versionDisplay = new java.util.ArrayList<>();
            java.util.List<String> versionArgs = new java.util.ArrayList<>();
            versionDisplay.add("最新版 (main)");
            versionArgs.add("");

            appendToLog("  📡 正在获取 " + botName + " 版本列表...");
            new Thread(() -> {
                java.util.List<String> releases = GitHubProxyManager.fetchAstrBotReleases(5);
                if (releases != null) {
                    for (String tag : releases) {
                        versionDisplay.add(tag);
                        versionArgs.add(tag);
                    }
                } else {
                    versionDisplay.add("v4.22.3");
                    versionArgs.add("v4.22.3");
                }
                mHandler.post(() -> {
                    android.widget.Spinner spinner = new android.widget.Spinner(this);
                    android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, versionDisplay.toArray(new String[0]));
                    spinner.setAdapter(adapter);
                    spinner.setPadding(48, 24, 48, 24);

                    android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                    layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                    layout.setPadding(48, 24, 48, 0);

                    android.widget.TextView label = new android.widget.TextView(this);
                    label.setText("选择版本：");
                    label.setTextSize(16);
                    label.setPadding(0, 0, 0, 16);
                    layout.addView(label);
                    layout.addView(spinner);

                    android.widget.TextView proxyLabel = new android.widget.TextView(this);
                    proxyLabel.setText("下载线路：");
                    proxyLabel.setTextSize(16);
                    proxyLabel.setPadding(0, 24, 0, 16);
                    layout.addView(proxyLabel);

                    android.widget.Spinner proxySpinner = new android.widget.Spinner(this);
                    String[] proxyDisplayNames = GitHubProxyManager.getAllProxyNamesWithLatency();
                    android.widget.ArrayAdapter<String> proxyAdapter = new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, proxyDisplayNames);
                    proxySpinner.setAdapter(proxyAdapter);
                    proxySpinner.setSelection(GitHubProxyManager.getBestProxy());
                    proxySpinner.setPadding(48, 24, 48, 24);
                    layout.addView(proxySpinner);

                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("重装 " + botName)
                        .setMessage("将删除 /root/astrbot 目录并重新克隆安装。\n\n⚠️ data 目录（配置、数据库）会被保留，但自定义修改会丢失。")
                        .setView(layout)
                        .setPositiveButton("开始重装", (dialog, which) -> {
                            String version = versionArgs.get(spinner.getSelectedItemPosition());
                            int selectedProxyIndex = proxySpinner.getSelectedItemPosition();
                            appendToLog("\n🤖 开始重装 " + botName + "...\n");
                            new Thread(() -> {
                                appendToLog("  🌐 使用线路: " + GitHubProxyManager.getProxyName(selectedProxyIndex));
                                ChrootManager.stopAstrBot();
                                ChrootManager.execInChroot(
                                    "if [ -d /root/astrbot/data ]; then " +
                                    "  cp -a /root/astrbot/data /root/astrbot_data_backup; " +
                                    "fi && rm -rf /root/astrbot", 60);
                                ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);
                                ChrootManager.ProgressCallback cb = new ChrootManager.ProgressCallback() {
                                    @Override public void onProgress(String msg) {
                                        mHandler.post(() -> appendToLog("  " + msg));
                                    }
                                    @Override public void onError(String err) {
                                        mHandler.post(() -> appendToLog("  ❌ " + err));
                                    }
                                };
                                if (ChrootManager.cloneAstrBotWithProxy(cb, version, selectedProxyIndex)) {
                                    mHandler.post(() -> appendToLog("❌ " + botName + " 克隆失败，可尝试切换线路后重试"));
                                } else if (ChrootManager.pipInstallDeps(cb)) {
                                    mHandler.post(() -> appendToLog("❌ Python 依赖安装失败"));
                                } else {
                                    ChrootManager.execInChroot(
                                        "if [ -d /root/astrbot_data_backup ]; then " +
                                        "  cp -a /root/astrbot_data_backup/. /root/astrbot/data/ 2>/dev/null; " +
                                        "  rm -rf /root/astrbot_data_backup; " +
                                        "fi", 60);
                                    mHandler.post(() -> {
                                        appendToLog("✅ " + botName + " 重装完成");
                                        appendToLog("🔄 正在启动 " + botName + "...");
                                        ChrootManager.startAstrBot();
                                        refreshBotInfo();
                                    });
                                }
                            }).start();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
            }).start();
        } else {
            // Hermes — simple confirm reinstall
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("重装 " + botName)
                .setMessage("将删除 " + botName + " 并重新安装。\n\n当前数据将被清除。")
                .setPositiveButton("开始重装", (dialog, which) -> {
                    appendToLog("\n🤖 开始重装 " + botName + "...\n");
                    new Thread(() -> {
                        bot.stop();
                        ChrootManager.ProgressCallback cb = new ChrootManager.ProgressCallback() {
                            @Override public void onProgress(String msg) {
                                mHandler.post(() -> appendToLog("  " + msg));
                            }
                            @Override public void onError(String err) {
                                mHandler.post(() -> appendToLog("  ❌ " + err));
                            }
                        };
                        boolean failed = bot.reinstall(cb, null, 0);
                        mHandler.post(() -> {
                            if (!failed) {
                                appendToLog("✅ " + botName + " 重装完成");
                            } else {
                                appendToLog("❌ " + botName + " 重装失败");
                            }
                            refreshBotInfo();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
        }
    }

    private void showSwitchBotDialog() {
        BotManager.BotInfo[] bots = mBotManager.getAllBotInfo();

        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog);
        dialog.setContentView(R.layout.dialog_bot_select);

        ListView listView = dialog.findViewById(R.id.bot_list);
        TextView titleView = dialog.findViewById(R.id.dialog_title);

        java.util.List<BotManager.BotInfo> botList = java.util.Arrays.asList(bots);
        android.widget.ArrayAdapter<BotManager.BotInfo> adapter = new android.widget.ArrayAdapter<BotManager.BotInfo>(this, R.layout.item_bot_select, botList) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.item_bot_select, parent, false);
                }
                BotManager.BotInfo bot = getItem(position);
                TextView nameView = convertView.findViewById(R.id.bot_name);
                TextView platformsView = convertView.findViewById(R.id.bot_platforms);
                TextView statusView = convertView.findViewById(R.id.bot_status);

                nameView.setText(bot.name);
                platformsView.setText("支持平台: " + bot.platforms);

                String statusText;
                int statusColor;
                if (bot.running) {
                    statusText = "🟢 运行中";
                    statusColor = 0xFF4CAF50;
                } else if (bot.installed) {
                    statusText = "🟡 已安装";
                    statusColor = 0xFFFFC107;
                } else {
                    statusText = "⚪ 未安装";
                    statusColor = 0xFF9E9E9E;
                }
                statusView.setText(statusText);
                statusView.setTextColor(statusColor);

                return convertView;
            }
        };

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            BotManager.BotInfo selected = bots[position];

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.TermuxAlertDialogStyle)
                .setTitle("确认切换到 " + selected.name + "？")
                .setMessage("• 当前 Bot 会停止\n• 切换后需在新引擎中重新配置消息平台\n\n" +
                    "⚠️ 如未安装，将跳转到安装流程。")
                .setPositiveButton("确认切换", (d2, w2) -> {
                    appendToLog("\n🔄 正在切换到 " + selected.name + "...");
                    new Thread(() -> {
                        mBotManager.stop();
                        mBotManager.switchTo(selected.id);
                        mHandler.post(() -> {
                            appendToLog("✅ 已切换到 " + selected.name);
                            refreshBotInfo();
                            Toast.makeText(this, "已切换到 " + selected.name, Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        dialog.show();
    }

    private void showReinstallRootfsDialog() {
        boolean hasLocalCache = new java.io.File(RbotConstants.SDCARD_ROOTFS_CACHE).exists();
        String message = "将重新解压系统镜像到 /data/rbot，替换整个 Ubuntu 环境。\n\n" +
            "• 如果存在 Bot 数据，会自动备份到 sdcard\n" +
            "• rootfs 解压完成后会自动恢复 Bot 数据\n" +
            "• 需要重新安装系统依赖（自动执行）\n\n" +
            "⚠️ 注意：手动安装的额外包（非默认依赖）将丢失。";
        if (!hasLocalCache) {
            message += "\n\n❌ 未找到本地镜像缓存，请先将 ubuntu24_rbot.tar.gz 放到:\n" +
                RbotConstants.SDCARD_ROOTFS_CACHE +
                "\n\n或先通过安装流程自动下载。";
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💿 重装 rootfs")
            .setMessage(message)
            .setPositiveButton(hasLocalCache ? "开始重装" : "确定", (dialog, which) -> {
                if (!hasLocalCache) return;
                appendToLog("\n💿 开始重装 rootfs...\n");
                new Thread(() -> {
                    mBotManager.stop();
                    ChrootManager.stopSshService();
                    ChrootManager.execRoot("rm -f " + RbotConstants.ROOTFS_MARKER + " " + RbotConstants.ASTRBOT_MARKER);
                    ChrootManager.execRoot("rm -f /data/rbot/.rbot-hermes-ready");
                    ChrootManager.ProgressCallback cb = new ChrootManager.ProgressCallback() {
                        @Override public void onProgress(String msg) {
                            mHandler.post(() -> appendToLog("  " + msg));
                        }
                        @Override public void onError(String err) {
                            mHandler.post(() -> appendToLog("  ❌ " + err));
                        }
                    };
                    if (ChrootManager.extractRootfs(RbotConstants.SDCARD_ROOTFS_CACHE, cb)) {
                        mHandler.post(() -> appendToLog("❌ rootfs 解压失败"));
                        return;
                    }
                    ChrootManager.setupChrootEnvironment(cb);
                    appendToLog("  📦 正在重装系统依赖...");
                    if (ChrootManager.aptUpdate(cb)) {
                        mHandler.post(() -> appendToLog("❌ 软件源更新失败"));
                        return;
                    }
                    if (ChrootManager.aptInstallDeps(cb)) {
                        mHandler.post(() -> appendToLog("❌ 依赖安装失败"));
                        return;
                    }
                    java.io.File backupData = new java.io.File(RbotConstants.EXTERNAL_DATA_BACKUP);
                    if (backupData.exists()) {
                        appendToLog("  🔄 正在恢复 Bot 数据...");
                        ChrootManager.restoreAstrBotData(cb);
                    }
                    mHandler.post(() -> appendToLog("✅ rootfs 重装完成"));
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
                appendToLog("\n🛠️ 开始修复环境...\n");
                new Thread(() -> {
                    ChrootManager.setupChrootDevices(null);
                    mHandler.post(() -> appendToLog("  ✅ chroot 设备已重新挂载"));
                    ChrootManager.execInChroot(
                        "dpkg --configure -a 2>/dev/null; apt --fix-broken install -y 2>/dev/null; echo done", 60);
                    mHandler.post(() -> appendToLog("  ✅ apt/dpkg 状态已修复"));
                    if (ChrootManager.isSshRunning()) {
                        ChrootManager.stopSshService();
                        ChrootManager.startSshService();
                        mHandler.post(() -> appendToLog("  ✅ SSH 服务已重启"));
                    }
                    mHandler.post(() -> appendToLog("✅ 环境修复完成"));
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
                            Toast.makeText(this, "密码已重置", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        mHandler.post(() -> appendToLog("\n❌ root 密码重置失败"));
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showBackupDialog() {
        BotAdapter bot = mBotManager.getActiveBot();
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("备份 " + bot.getName() + " 数据")
            .setMessage("将备份配置、数据库、插件数据到存储卡。\n\n确认继续？")
            .setPositiveButton("开始备份", (dialog, which) -> startBackup())
            .setNegativeButton("取消", null)
            .show();
    }

    private void startBackup() {
        if (mBackupInProgress) return;
        mBackupInProgress = true;
        mBackupButton.setEnabled(false);
        appendToLog("\n📦 开始备份 " + mBotManager.getActiveBot().getName() + " 数据...\n");
        new Thread(() -> {
            ChrootManager.ProgressCallback callback = new ChrootManager.ProgressCallback() {
                @Override public void onProgress(String message) {
                    mHandler.post(() -> appendToLog("  " + message));
                }
                @Override public void onError(String error) {
                    mHandler.post(() -> appendToLog("  ❌ " + error));
                }
            };
            String backupPath = ChrootManager.backupAstrBotData(callback);
            mHandler.post(() -> {
                mBackupInProgress = false;
                mBackupButton.setEnabled(true);
                if (backupPath != null) {
                    appendToLog("✅ 备份完成: " + backupPath.replace(RbotConstants.BACKUP_DIR + "/", ""));
                    new androidx.appcompat.app.AlertDialog.Builder(SettingsActivity.this)
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
        BotAdapter bot = mBotManager.getActiveBot();
        String[] backups = ChrootManager.listBackups();
        if (backups.length == 0) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("恢复备份")
                .setMessage("没有找到备份文件。\n\n备份文件应位于:\n" + RbotConstants.BACKUP_DIR)
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        String[] displayNames = new String[backups.length];
        for (int i = 0; i < backups.length; i++) {
            displayNames[i] = backups[i].substring(backups[i].lastIndexOf('/') + 1);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要恢复的备份")
            .setItems(displayNames, (dialog, which) -> {
                confirmRestore(backups[which], displayNames[which]);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmRestore(String backupPath, String displayName) {
        BotAdapter bot = mBotManager.getActiveBot();
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认恢复")
            .setMessage("将从以下备份恢复:\n" + displayName + "\n\n⚠️ 当前数据将被覆盖！\n恢复后 " + bot.getName() + " 将自动重启。")
            .setPositiveButton("确认恢复", (dialog, which) -> startRestore(backupPath))
            .setNegativeButton("取消", null)
            .show();
    }

    private void startRestore(String backupPath) {
        BotAdapter bot = mBotManager.getActiveBot();
        if (mBackupInProgress) return;
        mBackupInProgress = true;
        mRestoreButton.setEnabled(false);
        appendToLog("\n📂 开始恢复 " + bot.getName() + " 数据...\n");
        new Thread(() -> {
            ChrootManager.ProgressCallback callback = new ChrootManager.ProgressCallback() {
                @Override public void onProgress(String message) {
                    mHandler.post(() -> appendToLog("  " + message));
                }
                @Override public void onError(String error) {
                    mHandler.post(() -> appendToLog("  ❌ " + error));
                }
            };
            boolean failed = ChrootManager.restoreAstrBotData(backupPath, callback);
            mHandler.post(() -> {
                mBackupInProgress = false;
                mRestoreButton.setEnabled(true);
                if (!failed) {
                    appendToLog("✅ 恢复完成");
                    if (mBotManager.isRunning()) {
                        appendToLog("🔄 正在重启 " + bot.getName() + "...");
                        mBotManager.start();
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("恢复成功")
                        .setMessage("数据已恢复，" + bot.getName() + " 已重启。")
                        .setPositiveButton("确定", null)
                        .show();
                } else {
                    appendToLog("❌ 恢复失败");
                }
            });
        }).start();
    }

    private void showCleanInstallDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ 完全重装")
            .setMessage("这将删除所有数据，包括：\n• 系统镜像 (/data/rbot)\n• 所有 Bot 代码和配置\n• 所有备份文件\n\n此操作不可恢复！")
            .setPositiveButton("我确定要删除所有数据", (dialog, which) -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("最后确认")
                    .setMessage("你真的确定吗？所有数据将永久丢失。")
                    .setPositiveButton("是的，删除所有数据", (d2, w2) -> {
                        appendToLog("\n⚠️ 开始完全重装...\n");
                        new Thread(() -> {
                            mBotManager.stop();
                            ChrootManager.stopSshService();
                            ChrootManager.cleanupChrootDevices();
                            ChrootManager.execRoot("rm -rf " + RbotConstants.CHROOT_DIR);
                            ChrootManager.execRoot("rm -f " + RbotConstants.ROOTFS_MARKER);
                            ChrootManager.execRoot("rm -f " + RbotConstants.ASTRBOT_MARKER);
                            // Also clear hermes marker
                            ChrootManager.execRoot("rm -f /data/rbot/.rbot-hermes-ready");
                            mHandler.post(() -> {
                                appendToLog("✅ 所有数据已清除");
                                appendToLog("➡️ 请前往首页重新安装...");
                                Toast.makeText(this, "所有数据已清除", Toast.LENGTH_LONG).show();
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

    private void appendToLog(String message) {
        // This Activity doesn't have a log view — just show toast for feedback
        Toast.makeText(this, message.replace("\n", " ").trim(), Toast.LENGTH_SHORT).show();
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
}
