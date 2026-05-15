package app.rbot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 更新管理器 - 检查 GitHub 上的最新版本并提供更新功能
 */
public class UpdateManager {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/deku772/Rbot/releases/latest";
    private static final String APK_DOWNLOAD_URL = "https://github.com/deku772/Rbot/releases/download/%s/rbot-%s.apk";

    private Context mContext;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mChecking = false;

    public UpdateManager(Context context) {
        mContext = context;
    }

    /**
     * 检查更新
     */
    public void checkForUpdates(boolean showToastIfNoUpdate) {
        if (mChecking) return;
        mChecking = true;

        Toast.makeText(mContext, "正在检查更新...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.addRequestProperty("Accept", "application/json");

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    parseUpdateInfo(response.toString(), showToastIfNoUpdate);
                } else {
                    mHandler.post(() -> {
                        mChecking = false;
                        Toast.makeText(mContext, "检查更新失败: HTTP " + responseCode, Toast.LENGTH_SHORT).show();
                    });
                }
                connection.disconnect();
            } catch (Exception e) {
                mHandler.post(() -> {
                    mChecking = false;
                    Toast.makeText(mContext, "检查更新失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 解析更新信息
     */
    private void parseUpdateInfo(String jsonResponse, boolean showToastIfNoUpdate) {
        try {
            JSONObject release = new JSONObject(jsonResponse);
            
            // 获取最新版本号
            String latestVersion = release.getString("tag_name");
            
            // 获取当前版本号
            String currentVersion = mContext.getPackageManager()
                    .getPackageInfo(mContext.getPackageName(), 0).versionName;

            // 比较版本号
            if (isVersionGreater(latestVersion, currentVersion)) {
                // 有新版本
                String releaseNotes = release.getString("body");
                String downloadUrl = getApkDownloadUrl(latestVersion);
                
                mHandler.post(() -> {
                    mChecking = false;
                    showUpdateDialog(latestVersion, currentVersion, releaseNotes, downloadUrl);
                });
            } else {
                // 当前已是最新版本
                if (showToastIfNoUpdate) {
                    mHandler.post(() -> {
                        mChecking = false;
                        Toast.makeText(mContext, "当前已是最新版本", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mChecking = false;
                }
            }
        } catch (Exception e) {
            mHandler.post(() -> {
                mChecking = false;
                Toast.makeText(mContext, "解析更新信息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * 显示更新对话框
     */
    private void showUpdateDialog(String latestVersion, String currentVersion, 
                                   String releaseNotes, String downloadUrl) {
        String message = String.format("检测到新版本：%s\n\n当前版本：%s\n\n更新内容：\n%s",
                latestVersion, currentVersion, releaseNotes);

        new AlertDialog.Builder(mContext)
                .setTitle("📦 发现更新")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    dialog.dismiss();
                    openDownloadUrl(downloadUrl);
                })
                .setNegativeButton("稍后更新", (dialog, which) -> {
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 打开下载链接
     */
    private void openDownloadUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            Toast.makeText(mContext, "正在打开下载页面...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mContext, "无法打开下载链接", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取 APK 下载链接
     */
    private String getApkDownloadUrl(String version) {
        return String.format(APK_DOWNLOAD_URL, version, version);
    }

    /**
     * 比较版本号
     * @return true 如果 version1 > version2
     */
    private boolean isVersionGreater(String version1, String version2) {
        try {
            // 移除 v 前缀
            String v1 = version1.replaceFirst("^v", "");
            String v2 = version2.replaceFirst("^v", "");

            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");

            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
                if (num1 > num2) return true;
                if (num1 < num2) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
