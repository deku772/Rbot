package app.andbott;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import app.andbott.R;
import android.util.Log;


/**
 * Launcher activity with two phases:
 *
 * Phase 1 (Welcome): Guided permission requests — root access, notification,
 * battery optimization, storage access.
 *
 * Phase 2 (Loading): Routes to the appropriate screen:
 * 1. No root access → Show error
 * 2. Rootfs not extracted → SetupActivity (install step)
 * 3. AstrBot not installed → SetupActivity (install step)
 * 4. All ready → MainActivity
 */
public class BotDropLauncherActivity extends Activity {

    private static final String TAG = "BotDropLauncherActivity";
    private static final int REQUEST_CODE_NOTIFICATION_SETTINGS = 1001;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 1002;
    private static final int REQUEST_CODE_ALL_FILES_ACCESS = 1003;
    private static final String PREFS_NAME = "botdrop_launcher";
    private static final String PREF_ONBOARDING_CONTINUE = "onboarding_continue_clicked";

    private View mWelcomeContainer;
    private View mLoadingContainer;
    private TextView mStatusText;
    private Button mNotificationButton;
    private Button mBatteryButton;
    private Button mStorageButton;
    private Button mBackgroundSettingsButton;
    private Button mContinueButton;
    private TextView mNotificationStatus;
    private TextView mBatteryStatus;
    private TextView mStorageStatus;
    private TextView mBackgroundHintText;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mPermissionsPhaseComplete = false;
    private boolean mContinueClickedPersisted = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_botdrop_launcher);

        mWelcomeContainer = findViewById(R.id.welcome_container);
        mLoadingContainer = findViewById(R.id.loading_container);
        mStatusText = findViewById(R.id.launcher_status_text);
        mNotificationButton = findViewById(R.id.btn_notification_permission);
        mBatteryButton = findViewById(R.id.btn_battery_permission);
        mStorageButton = findViewById(R.id.btn_storage_permission);
        mBackgroundSettingsButton = findViewById(R.id.btn_background_settings);
        mContinueButton = findViewById(R.id.btn_continue);
        mNotificationStatus = findViewById(R.id.notification_status);
        mBatteryStatus = findViewById(R.id.battery_status);
        mStorageStatus = findViewById(R.id.storage_status);
        mBackgroundHintText = findViewById(R.id.background_hint_text);

        mContinueClickedPersisted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_ONBOARDING_CONTINUE, false);

        // Trigger update check early
        if (!UpdateChecker.isUpdateManagementDisabled(this)) {
            UpdateChecker.check(this, null);
        }

        mNotificationButton.setOnClickListener(v -> openNotificationSettings());
        mBatteryButton.setOnClickListener(v -> requestBatteryOptimization());
        mStorageButton.setOnClickListener(v -> requestStoragePermission());
        mBackgroundSettingsButton.setOnClickListener(v -> openAdvancedBackgroundSettings());
        mContinueButton.setOnClickListener(v -> {
            mPermissionsPhaseComplete = true;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ONBOARDING_CONTINUE, true)
                .apply();
            mContinueClickedPersisted = true;
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPermissionsPhaseComplete || mContinueClickedPersisted) {
            showLoadingPhase();
            mHandler.postDelayed(this::checkAndRoute, 300);
            return;
        }
        showWelcomePhase();
        updatePermissionStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    // --- Phase management ---

    private void showWelcomePhase() {
        mWelcomeContainer.setVisibility(View.VISIBLE);
        mLoadingContainer.setVisibility(View.GONE);
    }

    private void showLoadingPhase() {
        mWelcomeContainer.setVisibility(View.GONE);
        mLoadingContainer.setVisibility(View.VISIBLE);
    }

    // --- Permission checks ---

    private boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean isBatteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private int getRestrictBackgroundStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
            return cm.getRestrictBackgroundStatus();
        } catch (Exception ignored) {
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        }
    }

    // --- Permission requests ---

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivityForResult(intent, REQUEST_CODE_NOTIFICATION_SETTINGS);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open notification settings: " + e.getMessage());
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request battery optimization: " + e.getMessage());
            }
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_ALL_FILES_ACCESS);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_ALL_FILES_ACCESS);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to open storage settings: " + e2.getMessage());
                }
            }
        }
    }

    private boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void openAdvancedBackgroundSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int status = getRestrictBackgroundStatus();
            if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
                try {
                    startActivity(new Intent("android.settings.DATA_SAVER_SETTINGS"));
                    return;
                } catch (Exception ignored) {}
            }
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    // --- Permission results ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updatePermissionStatus();
    }

    // --- UI updates ---

    private void updatePermissionStatus() {
        boolean notifGranted = areNotificationsEnabled();
        boolean batteryExempt = isBatteryOptimizationExempt();
        boolean storageGranted = isStoragePermissionGranted();

        if (notifGranted) {
            mNotificationStatus.setText("✓");
            mNotificationStatus.setVisibility(View.VISIBLE);
            mNotificationButton.setEnabled(false);
            mNotificationButton.setText(R.string.botdrop_enabled);
        } else {
            mNotificationStatus.setVisibility(View.GONE);
            mNotificationButton.setEnabled(true);
            mNotificationButton.setText(R.string.botdrop_allow);
        }

        if (batteryExempt) {
            mBatteryStatus.setText("✓");
            mBatteryStatus.setVisibility(View.VISIBLE);
            mBatteryButton.setEnabled(false);
            mBatteryButton.setText(R.string.botdrop_granted);
        } else {
            mBatteryStatus.setVisibility(View.GONE);
            mBatteryButton.setEnabled(true);
            mBatteryButton.setText(R.string.botdrop_allow);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mStorageButton.setVisibility(View.VISIBLE);
            mStorageStatus.setVisibility(storageGranted ? View.VISIBLE : View.GONE);
            if (storageGranted) {
                mStorageStatus.setText("✓");
                mStorageButton.setEnabled(false);
                mStorageButton.setText(R.string.botdrop_granted);
            } else {
                mStorageButton.setEnabled(true);
                mStorageButton.setText(R.string.botdrop_allow);
            }
        } else {
            mStorageButton.setVisibility(View.GONE);
            mStorageStatus.setVisibility(View.GONE);
            View storageRow = (View) mStorageButton.getParent();
            if (storageRow != null) storageRow.setVisibility(View.GONE);
            storageGranted = true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int backgroundStatus = getRestrictBackgroundStatus();
            if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_restricted);
            } else if (backgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) {
                mBackgroundHintText.setText(R.string.botdrop_background_data_allowed);
            } else {
                mBackgroundHintText.setText(R.string.botdrop_background_data_hint);
            }
        }

        mContinueButton.setEnabled(notifGranted && batteryExempt && storageGranted);
    }

    // --- Routing ---

    private void checkAndRoute() {
        // Check 1: Root available?
        if (!ChrootManager.isRootAvailable()) {
            Log.w(TAG, "Root not available");
            mStatusText.setText("需要 Root 权限才能运行 BotDrop");
            // Don't route anywhere — user needs to grant root
            return;
        }

        // Check 2: Rootfs extracted?
        if (!ChrootManager.isRootfsReady()) {
            Log.i(TAG, "Rootfs not ready, routing to setup");
            mStatusText.setText(R.string.botdrop_setup_required);
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // Check 3: AstrBot installed?
        if (!ChrootManager.isAstrBotInstalled()) {
            Log.i(TAG, "AstrBot not installed, routing to setup");
            mStatusText.setText(R.string.botdrop_setup_required);
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // All ready — go to Dashboard
        Log.i(TAG, "All ready, routing to dashboard");
        mStatusText.setText(R.string.botdrop_starting_status);
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
