package app.botdrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight version checker that queries the BotDrop API for the latest release.
 * Throttled to once per 6 hours. Fails silently — never blocks app usage.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String CHECK_URL = "https://api.botdrop.app/version";
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final String PREFS_NAME = "botdrop_update";
    private static final String KEY_LAST_CHECK = "last_check_time";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";
    private static final String KEY_LATEST_VERSION = "latest_version";
    private static final String KEY_DOWNLOAD_URL = "download_url";
    private static final String KEY_RELEASE_NOTES = "release_notes";
    private static final String KEY_CHECKED_VERSION = "checked_version";

    public interface UpdateCallback {
        void onUpdateAvailable(String latestVersion, String downloadUrl, String notes);
        default void onNoUpdate() {}
    }

    public interface ForceCheckCallback {
        void onComplete(boolean updateAvailable, String latestVersion, String downloadUrl, String notes, String message);
    }

    static boolean isUpdateManagementDisabled(@SuppressWarnings("unused") Context ctx) {
        return false;
    }

    static void check(Context ctx, UpdateCallback cb) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String currentVersion;
        int currentVersionCode;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            currentVersion = pi.versionName;
            currentVersionCode = pi.versionCode;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get package info: " + e.getMessage());
            return;
        }

        String lastCheckedVersion = prefs.getString(KEY_CHECKED_VERSION, null);
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        long elapsed = System.currentTimeMillis() - lastCheck;
        if (!TextUtils.equals(currentVersion, lastCheckedVersion) || lastCheckedVersion == null) {
            Log.i(TAG, "App version changed, bypassing check throttle");
        } else if (elapsed < CHECK_INTERVAL_MS) {
            if (cb != null) notifyFromStored(ctx, prefs, cb);
            return;
        }

        Log.i(TAG, "Starting update check, current=" + currentVersion);

        new Thread(() -> {
            try {
                String urlStr = CHECK_URL + "?v=" + currentVersion + "&vc=" + currentVersionCode;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    conn.disconnect();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .putString(KEY_CHECKED_VERSION, currentVersion)
                    .apply();

                JSONObject json = new JSONObject(sb.toString());
                String latestVersion = json.optString("latest_version", "");
                String downloadUrl = json.optString("download_url", "");
                String notes = json.optString("release_notes", "");

                if (latestVersion.isEmpty() || latestVersion.equals(currentVersion)) {
                    clearStored(prefs);
                    if (cb != null) notifyNoUpdate(cb);
                    return;
                }

                String dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null);
                if (latestVersion.equals(dismissedVersion)) {
                    if (cb != null) notifyNoUpdate(cb);
                    return;
                }

                if (isNewer(latestVersion, currentVersion)) {
                    Log.i(TAG, "Update available: " + latestVersion);
                    prefs.edit()
                        .putString(KEY_LATEST_VERSION, latestVersion)
                        .putString(KEY_DOWNLOAD_URL, downloadUrl)
                        .putString(KEY_RELEASE_NOTES, notes)
                        .apply();
                    if (cb != null) {
                        new Handler(Looper.getMainLooper()).post(() -> cb.onUpdateAvailable(latestVersion, downloadUrl, notes));
                    }
                } else {
                    clearStored(prefs);
                    if (cb != null) notifyNoUpdate(cb);
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed: " + e.getMessage());
            }
        }).start();
    }

    public static void forceCheckWithFeedback(Context ctx, ForceCheckCallback cb) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_LAST_CHECK, 0).apply();

        String currentVersion;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            currentVersion = pi.versionName;
        } catch (Exception e) {
            notifyForceResult(cb, false, null, null, null, "Failed to read current app version");
            return;
        }

        new Thread(() -> {
            try {
                String urlStr = CHECK_URL + "?v=" + currentVersion;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    conn.disconnect();
                    notifyForceResult(cb, false, null, null, null, "HTTP " + responseCode);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String latestVersion = json.optString("latest_version", "");
                String downloadUrl = json.optString("download_url", "");
                String notes = json.optString("release_notes", "");

                if (latestVersion.isEmpty() || !isNewer(latestVersion, currentVersion)) {
                    clearStored(prefs);
                    notifyForceResult(cb, false, null, null, null, "No updates available");
                    return;
                }

                prefs.edit()
                    .putString(KEY_LATEST_VERSION, latestVersion)
                    .putString(KEY_DOWNLOAD_URL, downloadUrl)
                    .putString(KEY_RELEASE_NOTES, notes)
                    .apply();

                notifyForceResult(cb, true, latestVersion, downloadUrl, notes, "Update available: v" + latestVersion);
            } catch (Exception e) {
                Log.e(TAG, "Forced update check failed: " + e.getMessage());
                notifyForceResult(cb, false, null, null, null, "Update check failed: " + e.getMessage());
            }
        }).start();
    }

    static String[] getAvailableUpdate(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String latestVersion = prefs.getString(KEY_LATEST_VERSION, null);
        if (latestVersion == null) return null;
        String dismissed = prefs.getString(KEY_DISMISSED_VERSION, null);
        if (latestVersion.equals(dismissed)) return null;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (!isNewer(latestVersion, pi.versionName)) { clearStored(prefs); return null; }
        } catch (Exception e) { return null; }
        return new String[]{ latestVersion, prefs.getString(KEY_DOWNLOAD_URL, ""), prefs.getString(KEY_RELEASE_NOTES, "") };
    }

    private static void notifyForceResult(ForceCheckCallback cb, boolean updateAvailable,
                                          String latestVersion, String downloadUrl, String notes, String message) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.onComplete(updateAvailable, latestVersion, downloadUrl, notes, message));
    }

    private static void notifyFromStored(Context ctx, SharedPreferences prefs, UpdateCallback cb) {
        String[] update = getAvailableUpdate(ctx);
        if (update != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onUpdateAvailable(update[0], update[1], update[2]));
        } else {
            notifyNoUpdate(cb);
        }
    }

    private static void notifyNoUpdate(UpdateCallback cb) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(cb::onNoUpdate);
    }

    private static void clearStored(SharedPreferences prefs) {
        prefs.edit().remove(KEY_LATEST_VERSION).remove(KEY_DOWNLOAD_URL).remove(KEY_RELEASE_NOTES).apply();
    }

    private static boolean isNewer(String latest, String current) {
        try {
            int[] l = parseSemver(latest);
            int[] c = parseSemver(current);
            for (int i = 0; i < 3; i++) { if (l[i] > c[i]) return true; if (l[i] < c[i]) return false; }
        } catch (Exception ignored) {}
        return false;
    }

    private static int[] parseSemver(String v) {
        String t = v.trim();
        if (t.startsWith("v")) t = t.substring(1);
        int si = t.indexOf('-'), bi = t.indexOf('+'), ti = si;
        if (bi >= 0 && (ti < 0 || bi < ti)) ti = bi;
        if (ti >= 0) t = t.substring(0, ti);
        String[] p = t.split("\\.");
        return new int[]{ p.length > 0 ? Integer.parseInt(p[0]) : 0, p.length > 1 ? Integer.parseInt(p[1]) : 0, p.length > 2 ? Integer.parseInt(p[2]) : 0 };
    }
}
