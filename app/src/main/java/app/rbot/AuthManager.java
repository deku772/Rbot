package app.rbot;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import rikka.shizuku.Shizuku;
import rikka.shizuku.Shizuku.OnBinderReceivedListener;
import rikka.shizuku.Shizuku.OnBinderDeadListener;

/**
 * Manages the authorization mode for Rbot: Root (su) or Shizuku (Sui/Shizuku app).
 * <p>
 * Detection priority:
 * 1. Check if `su` works → ROOT mode
 * 2. Check if Shizuku binder is alive and UID=0 → SHIZUKU mode
 * 3. Neither available → UNAVAILABLE
 * <p>
 * When Shizuku mode is active, binds a UserService (ShellService) that runs
 * shell commands in a root process. ChrootManager.execRoot() then routes
 * commands through this service instead of `su -c`.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    public enum AuthMode {
        /** su binary works (Magisk/KernelSU/APatch) */
        ROOT,
        /** Shizuku or Sui with root (UID 0) */
        SHIZUKU,
        /** No root access available */
        UNAVAILABLE
    }

    public interface AuthCallback {
        void onAuthChanged(AuthMode mode);
    }

    private static AuthManager sInstance;

    private AuthMode mCurrentMode = AuthMode.UNAVAILABLE;
    private IShellService mShellService;
    private Shizuku.UserServiceArgs mServiceArgs;
    private ServiceConnection mServiceConnection;
    private AuthCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // ─── Shizuku permission request ───

    private static final int SHIZUKU_REQUEST_CODE = 101;

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "Shizuku permission " + (granted ? "granted" : "denied"));
                if (granted) {
                    bindShellService();
                } else {
                    updateMode(AuthMode.UNAVAILABLE);
                }
            }
        };

    // ─── Shizuku binder listeners ───

    private final OnBinderReceivedListener mBinderReceivedListener = () -> {
        Log.i(TAG, "Shizuku binder received, UID=" + Shizuku.getUid());
        detectAndSetMode();
    };

    private final OnBinderDeadListener mBinderDeadListener = () -> {
        Log.w(TAG, "Shizuku binder dead");
        if (mCurrentMode == AuthMode.SHIZUKU) {
            updateMode(AuthMode.UNAVAILABLE);
        }
    };

    private AuthManager() {}

    public static synchronized AuthManager getInstance() {
        if (sInstance == null) {
            sInstance = new AuthManager();
        }
        return sInstance;
    }

    /**
     * Initialize AuthManager. Call from RbotApplication.onCreate().
     */
    public void init(@NonNull Context context) {
        // Add Shizuku listeners
        Shizuku.addBinderReceivedListener(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

        // Prepare UserService binding args
        mServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), ShellService.class.getName()))
            .tag("rbot_shell")
            .version(1);

        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "ShellService connected");
                mShellService = IShellService.Stub.asInterface(service);
                // Verify it's running as root
                try {
                    String ping = mShellService.ping();
                    JSONObject json = new JSONObject(ping);
                    String stdout = json.optString("stdout", "");
                    Log.i(TAG, "ShellService ping: " + stdout);
                    if (stdout.contains("uid=0")) {
                        updateMode(AuthMode.SHIZUKU);
                    } else {
                        Log.e(TAG, "ShellService NOT running as root: " + stdout);
                        updateMode(AuthMode.UNAVAILABLE);
                    }
                } catch (RemoteException | JSONException e) {
                    Log.e(TAG, "ShellService ping failed: " + e.getMessage());
                    updateMode(AuthMode.UNAVAILABLE);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "ShellService disconnected");
                mShellService = null;
                if (mCurrentMode == AuthMode.SHIZUKU) {
                    updateMode(AuthMode.UNAVAILABLE);
                }
            }
        };

        // Do initial detection
        detectAndSetMode();
    }

    /**
     * Detect the best available auth mode and set it.
     */
    public void detectAndSetMode() {
        // Priority 1: Check root
        if (ChrootManager.isRootAvailable()) {
            Log.i(TAG, "Root available (su works) → ROOT mode");
            // Unbind Shizuku service if previously bound
            unbindShellService();
            updateMode(AuthMode.ROOT);
            return;
        }

        // Priority 2: Check Shizuku
        if (Shizuku.getUid() == 0) {
            // Shizuku is running with root — check permission
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Shizuku running as root, permission granted → SHIZUKU mode");
                bindShellService();
            } else {
                Log.i(TAG, "Shizuku running as root, requesting permission");
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            }
            return;
        }

        // Priority 3: Shizuku running but not root (ADB mode)
        if (Shizuku.getUid() > 0) {
            Log.w(TAG, "Shizuku running as UID " + Shizuku.getUid()
                + " (ADB mode) — insufficient for chroot");
            updateMode(AuthMode.UNAVAILABLE);
            return;
        }

        // No auth available
        Log.w(TAG, "No root access available");
        updateMode(AuthMode.UNAVAILABLE);
    }

    /**
     * Execute a command via the current auth mode.
     * Called by ChrootManager.execRoot() when Shizuku mode is active.
     *
     * @return CommandResult or null if service not ready
     */
    public ChrootManager.CommandResult execViaShizuku(String command, int timeoutSec) {
        if (mShellService == null) {
            Log.e(TAG, "ShellService not bound, cannot execute");
            return new ChrootManager.CommandResult(false, "",
                "Shizuku 服务未连接", -1);
        }

        try {
            String json = mShellService.exec(command, timeoutSec);
            return parseCommandResult(json);
        } catch (RemoteException e) {
            Log.e(TAG, "ShellService exec failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "",
                "Shizuku 通信失败: " + e.getMessage(), -1);
        }
    }

    /** Check if Shizuku mode is active and service is connected */
    public boolean isShizukuReady() {
        return mCurrentMode == AuthMode.SHIZUKU && mShellService != null;
    }

    public AuthMode getCurrentMode() {
        return mCurrentMode;
    }

    public void setCallback(AuthCallback callback) {
        mCallback = callback;
    }

    /**
     * Request Shizuku permission from an Activity context.
     * Call this from PermissionsActivity when user taps the Shizuku card.
     */
    public void requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } else {
            // Already granted, try to bind
            bindShellService();
        }
    }

    /** Check if Shizuku is installed and the binder is alive */
    public boolean isShizukuAvailable() {
        try {
            return Shizuku.getUid() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Check if Shizuku permission is granted */
    public boolean isShizukuPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Internal ───

    private void bindShellService() {
        try {
            Log.i(TAG, "Binding ShellService via Shizuku");
            Shizuku.bindUserService(mServiceArgs, mServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind ShellService: " + e.getMessage());
            updateMode(AuthMode.UNAVAILABLE);
        }
    }

    private void unbindShellService() {
        if (mServiceConnection != null && mServiceArgs != null) {
            try {
                Shizuku.unbindUserService(mServiceArgs, mServiceConnection, false);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unbind ShellService: " + e.getMessage());
            }
        }
        mShellService = null;
    }

    private void updateMode(AuthMode mode) {
        if (mCurrentMode != mode) {
            Log.i(TAG, "Auth mode changed: " + mCurrentMode + " → " + mode);
            mCurrentMode = mode;

            // Notify ChrootManager about the mode change
            ChrootManager.setAuthMode(mode == AuthMode.SHIZUKU);

            if (mCallback != null) {
                mHandler.post(() -> mCallback.onAuthChanged(mode));
            }
        }
    }

    private ChrootManager.CommandResult parseCommandResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String stdout = obj.optString("stdout", "");
            String stderr = obj.optString("stderr", "");
            int exitCode = obj.optInt("exitCode", -1);
            boolean success = obj.optBoolean("success", false);
            return new ChrootManager.CommandResult(success, stdout, stderr, exitCode);
        } catch (JSONException e) {
            return new ChrootManager.CommandResult(false, "",
                "JSON parse error: " + e.getMessage(), -1);
        }
    }

    /**
     * Cleanup — call from Application.onTerminate() or similar.
     */
    public void destroy() {
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        unbindShellService();
    }
}
