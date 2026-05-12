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
    private CardView mShizukuCard;
    private TextView mShizukuStatus;
    private Button mGrantShizukuButton;
    private CardView mProotCard;
    private TextView mProotStatus;
    private Button mEnableProotButton;
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
        mShizukuCard = findViewById(R.id.shizuku_card);
        mShizukuStatus = findViewById(R.id.shizuku_status);
        mGrantShizukuButton = findViewById(R.id.btn_grant_shizuku);
        mProotCard = findViewById(R.id.proot_card);
        mProotStatus = findViewById(R.id.proot_status);
        mEnableProotButton = findViewById(R.id.btn_enable_proot);
        mNotificationCard = findViewById(R.id.notification_card);
        mNotificationStatus = findViewById(R.id.notification_status);
        mGrantNotificationButton = findViewById(R.id.btn_grant_notification);

        mDisableBatteryOptButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        mGrantStorageButton.setOnClickListener(v -> requestStoragePermission());
        mGrantShizukuButton.setOnClickListener(v -> requestShizukuPermission());
        mEnableProotButton.setOnClickListener(v -> enableProotMode());
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
        refreshShizukuStatus();
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
        if (rootAvailable) {
            mRootStatus.setText("可用 ✅");
        } else {
            mRootStatus.setText("不可用 — 可使用下方 Shizuku 授权");
        }
    }

    private void refreshShizukuStatus() {
        AuthManager am = AuthManager.getInstance();
        boolean shizukuAvailable = am.isShizukuAvailable(); // UID == 0
        boolean shizukuReady = am.isShizukuReady();
        boolean permissionGranted = am.isShizukuPermissionGranted();
        boolean shizukuBinderAlive = am.isShizukuBinderAlive();
        boolean isAdbMode = am.isShizukuAdbMode();

        if (shizukuReady) {
            // Shizuku is connected and running as root
            mShizukuStatus.setText("已授权 ✅（Root 模式）");
            mGrantShizukuButton.setText("已授权");
            mGrantShizukuButton.setEnabled(false);
        } else if (shizukuAvailable && !permissionGranted) {
            // Shizuku running as root but permission not granted yet
            mShizukuStatus.setText("Shizuku 已运行 — 需要授权");
            mGrantShizukuButton.setText("授予 Shizuku 权限");
            mGrantShizukuButton.setEnabled(true);
        } else if (isAdbMode) {
            // Shizuku running in ADB mode (UID != 0) — cannot chroot, but PRoot works
            mShizukuStatus.setText("Shizuku ADB 模式 ⚠️\nADB 权限不足以执行 chroot\n可使用下方 PRoot 免 Root 模式");
            mGrantShizukuButton.setText("切换到 Root 模式");
            mGrantShizukuButton.setEnabled(false);
        } else if (shizukuBinderAlive) {
            // Binder alive but state unclear
            mShizukuStatus.setText("Shizuku 运行中（状态未知）\n如果无法使用 Root 模式，请尝试 PRoot");
            mGrantShizukuButton.setText("重新检测");
            mGrantShizukuButton.setEnabled(true);
        } else {
            // Shizuku not detected — offer download options
            mShizukuStatus.setText("未安装 Shizuku — 点击按钮下载\n或使用下方 PRoot 免 Root 模式");
            mGrantShizukuButton.setText("下载 Shizuku");
            mGrantShizukuButton.setEnabled(true);
        }
    }

    private void requestShizukuPermission() {
        AuthManager am = AuthManager.getInstance();
        if (am.isShizukuBinderAlive()) {
            // Shizuku is running — request permission or bind service
            am.requestShizukuPermission();
        } else {
            // Shizuku not installed — open GitHub Releases for direct download
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://github.com/RikkaApps/Shizuku/releases"));
                startActivity(intent);
            } catch (Exception e) {
                // Fallback to Play Store
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("market://details?id=moe.shizuku.privileged.api"));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void refreshProotStatus() {
        AuthManager am = AuthManager.getInstance();
        PRootManager pm = PRootManager.getInstance(this);

        if (am.isProotMode()) {
            mProotStatus.setText("已启用 ✅（PRoot 免 Root 模式）");
            mEnableProotButton.setText("已启用");
            mEnableProotButton.setEnabled(false);
        } else if (pm.isRootfsReady()) {
            mProotStatus.setText("PRoot 环境已就绪 ✅\n点击启用切换到 PRoot 模式");
            mEnableProotButton.setText("启用 PRoot 模式");
            mEnableProotButton.setEnabled(true);
        } else {
            mProotStatus.setText("免 Root 运行 Linux 环境\n不需要 Root 或 Shizuku，通过 ptrace 系统调用模拟 chroot\n性能略低于 Root 模式，但功能完整");
            mEnableProotButton.setText("启用 PRoot 模式");
            mEnableProotButton.setEnabled(true);
        }
    }

    private void enableProotMode() {
        AuthManager am = AuthManager.getInstance();
        am.setForceProot(true);

        // Show confirmation
        Toast.makeText(this, "已切换到 PRoot 免 Root 模式", Toast.LENGTH_SHORT).show();

        // Refresh status
        refreshProotStatus();
        refreshShizukuStatus();
        refreshRootStatus();

        // Pre-download proot binary in background (no root needed)
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

        // Jump to setup if rootfs not ready
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
