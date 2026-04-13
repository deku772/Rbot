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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Permissions page — dedicated to battery optimization, storage, root, and notification permissions.
 * BotPocket-style: each permission has its own card with status and action button.
 */
public class PermissionsActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 101;

    private TextView mBatteryStatus;
    private Button mDisableBatteryOptButton;
    private TextView mStorageStatus;
    private Button mGrantStorageButton;
    private TextView mRootStatus;
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
        mNotificationCard = findViewById(R.id.notification_card);
        mNotificationStatus = findViewById(R.id.notification_status);
        mGrantNotificationButton = findViewById(R.id.btn_grant_notification);

        mDisableBatteryOptButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        mGrantStorageButton.setOnClickListener(v -> requestStoragePermission());
        mGrantNotificationButton.setOnClickListener(v -> requestNotificationPermission());

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
            mBatteryStatus.setText("未关闭 — AstrBot 可能被系统自动停止");
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
        // Check if root is available by checking ChrootManager
        ChrootManager.FullStatus status = ChrootManager.getFullStatus();
        if (status.rootAvailable()) {
            mRootStatus.setText("可用 ✅");
        } else {
            mRootStatus.setText("不可用 — 请确保设备已 Root");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
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
