package app.rbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Manages the authorization mode for Rbot: Root (su) or PRoot.
 * <p>
 * Detection priority:
 * 1. Check if `su` works → ROOT mode
 * 2. Neither available → PRoot mode (always available, no root needed)
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    public enum AuthMode {
        /** su binary works (Magisk/KernelSU/APatch) */
        ROOT,
        /** PRoot mode — no root needed, runs via ptrace syscall interception */
        PROOT,
        /** No root access available (same as PRoot, kept for clarity) */
        UNAVAILABLE
    }

    public interface AuthCallback {
        void onAuthChanged(AuthMode mode);
    }

    private static AuthManager sInstance;

    private AuthMode mCurrentMode = AuthMode.UNAVAILABLE;
    private boolean mForceProot = false; // user explicitly selected PRoot mode
    private AuthCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

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
        detectAndSetMode();
    }

    /**
     * Detect the best available auth mode and set it.
     * Detection priority: ROOT > PROOT
     */
    public void detectAndSetMode() {
        if (ChrootManager.isRootAvailable()) {
            Log.i(TAG, "Root available (su works) → ROOT mode");
            updateMode(mForceProot ? AuthMode.PROOT : AuthMode.ROOT);
        } else {
            Log.i(TAG, "No root access available, using PRoot mode");
            updateMode(AuthMode.PROOT);
        }
    }

    public AuthMode getCurrentMode() {
        return mCurrentMode;
    }

    public void setCallback(AuthCallback callback) {
        mCallback = callback;
    }

    /** Force PRoot mode — call from UI when user selects PRoot */
    public void setForceProot(boolean force) {
        mForceProot = force;
        detectAndSetMode();
    }

    /** Check if currently running in PRoot mode */
    public boolean isProotMode() {
        return mCurrentMode == AuthMode.PROOT;
    }

    /** Check if PRoot mode is available (always true — PRoot doesn't need root) */
    public boolean isProotAvailable() {
        return true;
    }

    // ─── Internal ───

    private void updateMode(AuthMode mode) {
        if (mCurrentMode != mode) {
            Log.i(TAG, "Auth mode changed: " + mCurrentMode + " → " + mode);
            mCurrentMode = mode;

            if (mCallback != null) {
                mHandler.post(() -> mCallback.onAuthChanged(mode));
            }
        }
    }

    /**
     * Cleanup — call from Application.onTerminate() or similar.
     */
    public void destroy() {
        // Nothing to clean up
    }
}
