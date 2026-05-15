package app.rbot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Permissions page — battery, storage, root, Shizuku, and notification permissions.
 * BotPocket-style: each permission has its own card with status and action button.
 */
public class PermissionsActivity extends AppCompatActivity {

    private static final String TAG = "PermissionsActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 101;

    private TextView mBatteryStatus;
    private Button mDisableBatteryOptButton;
    private TextView mStorageStatus;
    private Button mGrantStorageButton;
    private TextView mRootStatus;
    private CardView mProotCard;
    private TextView mModeStatus;
    private Button mInstallChrootButton;
    private Button mInstallProotButton;
    private CardView mNotificationCard;
    private TextView mNotificationStatus;
    private Button mGrantNotificationButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        mBatteryStatus = findViewById(R.id.battery_status);
        mDisableBatteryOptButton = findViewById(R.id.btn_disable_battery_opt);
        mStorageStatus = findViewById(R.id.storage_status);
        mGrantStorageButton = findViewById(R.id.btn_grant_storage);
        mRootStatus = findViewById(R.id.root_status);
        mProotCard = findViewById(R.id.proot_card);
        mModeStatus = findViewById(R.id.mode_status);
        mInstallChrootButton = findViewById(R.id.btn_install_chroot);
        mInstallProotButton = findViewById(R.id.btn_install_proot);
        mNotificationCard = findViewById(R.id.notification_card);
        mNotificationStatus = findViewById(R.id.notification_status);
        mGrantNotificationButton = findViewById(R.id.btn_grant_notification);

        mDisableBatteryOptButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        mGrantStorageButton.setOnClickListener(v -> requestStoragePermission());
        mInstallChrootButton.setOnClickListener(v -> installChrootMode());
        mInstallProotButton.setOnClickListener(v -> showProotConfirmDialog());
        mGrantNotificationButton.setOnClickListener(v -> requestNotificationPermission());

        // Start install button
        android.widget.Button btnStartInstall = findViewById(R.id.btn_start_install);
        btnStartInstall.setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
        });

        // Show notification card only on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mNotificationCard.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllPermissionStatus();
    }

    private void refreshAllPermissionStatus() {
        refreshBatteryStatus();
        refreshStorageStatus();
        refreshRootStatus();
        refreshProotStatus();
        refreshNotificationStatus();
    }

    private void refreshBatteryStatus() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            mBatteryStatus.setText("无法检测");
            return;
        }
        boolean ignoring = pm.isIgnoringBatteryOptimizations(getPackageName());
        if (ignoring) {
            mBatteryStatus.setText("已优化（推荐）✅");
            mDisableBatteryOptButton.setText("已优化");
            mDisableBatteryOptButton.setEnabled(false);
        } else {
            mBatteryStatus.setText("未关闭 — 服务可能被系统自动停止");
            mDisableBatteryOptButton.setText("优化电池设置");
            mDisableBatteryOptButton.setEnabled(true);
        }
    }

    private void refreshStorageStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — check MANAGE_EXTERNAL_STORAGE
            boolean hasManage = Environment.isExternalStorageManager();
            if (hasManage) {
                mStorageStatus.setText("已授予 ✅");
                mGrantStorageButton.setText("已授予");
                mGrantStorageButton.setEnabled(false);
            } else {
                mStorageStatus.setText("未授予 — rootfs 和备份需要此权限");
                mGrantStorageButton.setText("授予存储权限");
                mGrantStorageButton.setEnabled(true);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                mStorageStatus.setText("已授予 ✅");
                mGrantStorageButton.setText("已授予");
                mGrantStorageButton.setEnabled(false);
            } else {
                mStorageStatus.setText("未授予 — rootfs 和备份需要此权限");
                mGrantStorageButton.setText("授予存储权限");
                mGrantStorageButton.setEnabled(true);
            }
        } else {
            mStorageStatus.setText("已授予 ✅");
            mGrantStorageButton.setText("已授予");
            mGrantStorageButton.setEnabled(false);
        }
    }

    private void refreshRootStatus() {
        boolean rootAvailable = ChrootManager.isRootAvailable();
        AuthManager am = AuthManager.getInstance();
        if (am.isProotMode()) {
            mRootStatus.setText("无需 — PRoot 模式不需要 Root 权限");
        } else if (rootAvailable) {
            mRootStatus.setText("可用 ✅");
        } else {
            mRootStatus.setText("不可用 — 可使用下方 PRoot 免 Root 模式");
        }
    }

    private void refreshProotStatus() {
        AuthManager am = AuthManager.getInstance();
        PRootManager pm = PRootManager.getInstance(this);

        if (am.isProotMode()) {
            mModeStatus.setText("已选择 PRoot 模式 ✅");
            mInstallChrootButton.setEnabled(true);
            mInstallProotButton.setEnabled(false);
            mInstallProotButton.setText("已选择");
        } else {
            mModeStatus.setText("请选择安装模式");
            mInstallChrootButton.setEnabled(true);
            mInstallProotButton.setEnabled(true);
            mInstallProotButton.setText("PRoot 模式");
        }
    }

    private void installChrootMode() {
        boolean rootAvailable = ChrootManager.isRootAvailable();
        
        if (!rootAvailable) {
            Toast.makeText(this, "❌ 未检测到 Root 权限\nChroot 模式需要 Root 权限才能运行\n请使用 PRoot 模式", Toast.LENGTH_LONG).show();
            return;
        }

        AuthManager am = AuthManager.getInstance();
        am.setForceProot(false);
        
        Toast.makeText(this, "✅ 已选择 Chroot 模式", Toast.LENGTH_SHORT).show();
        refreshProotStatus();
        refreshRootStatus();

        PRootManager pm = PRootManager.getInstance(this);
        if (!pm.isRootfsReady()) {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
        }
    }

    private void showProotConfirmDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("确认使用 PRoot 模式？")
            .setMessage("PRoot 模式特点：\n\n✓ 无需 Root 权限\n✓ 通过 ptrace 模拟 chroot\n✓ 功能完整，可运行 AstrBot\n\n⚠ 性能略低于 Chroot 模式\n⚠ 首次使用需要下载 PRoot 二进制文件\n\n是否继续？")
            .setPositiveButton("确认", (dialog, which) -> enableProotMode())
            .setNegativeButton("取消", null)
            .show();
    }

    private void enableProotMode() {
        AuthManager am = AuthManager.getInstance();
        am.setForceProot(true);

        Toast.makeText(this, "✅ 已选择 PRoot 模式", Toast.LENGTH_SHORT).show();

        refreshProotStatus();
        refreshRootStatus();

        PRootManager pm = PRootManager.getInstance(this);
        if (!pm.isProotBinaryAvailable()) {
            Toast.makeText(this, "正在下载 PRoot 二进制...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean ok = pm.ensureProotBinary();
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(this, "PRoot 二进制下载完成 ✅", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "PRoot 二进制下载失败，稍后会自动重试", Toast.LENGTH_LONG).show();
                    }
                    refreshProotStatus();
                });
            }).start();
        }

        if (!pm.isRootfsReady()) {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
        }
    }

    private void refreshNotificationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                mNotificationStatus.setText("已授予 ✅");
                mGrantNotificationButton.setText("已授予");
                mGrantNotificationButton.setEnabled(false);
            } else {
                mNotificationStatus.setText("未授予 — 无法显示服务通知");
                mGrantNotificationButton.setText("授予通知权限");
                mGrantNotificationButton.setEnabled(true);
            }
        } else {
            mNotificationStatus.setText("不需要 ✅");
            mGrantNotificationButton.setText("不需要");
            mGrantNotificationButton.setEnabled(false);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent("android.intent.action.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    private void requestStoragePermission() {
        Log.i(TAG, "requestStoragePermission called, SDK=" + Build.VERSION.SDK_INT);
        Toast.makeText(this, "正在打开存储权限设置...", Toast.LENGTH_SHORT).show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 先尝试标准的 MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.i(TAG, "Starting storage permission activity: " + intent);
                startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Primary storage permission intent failed: " + e.getMessage());
            }
            // Fallback: 通用存储管理页面
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.i(TAG, "Starting fallback storage permission activity");
                startActivity(intent);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Fallback storage permission intent failed: " + e.getMessage());
            }
            // Last resort: 应用详情页面
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.i(TAG, "Starting app details as last resort");
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "All storage permission intents failed: " + e.getMessage());
                Toast.makeText(this, "请手动到系统设置中授予存储权限", Toast.LENGTH_LONG).show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                REQUEST_STORAGE_PERMISSION);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            refreshStorageStatus();
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            refreshNotificationStatus();
        }
    }
}
