package app.rbot;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages GitHub proxy selection with automatic speed testing.
 * Uses root curl for testing (bypasses Android VPN/network restrictions).
 * Proxies are used to bypass GitHub access issues in China.
 */
public class GitHubProxyManager {

    private static final String TAG = "GitHubProxyManager";

    /** Available GitHub proxy prefixes — {0} will be replaced with the original URL */
    private static final String[] PROXY_TEMPLATES = {
        "",  // Direct (no proxy)
        "https://edgeone.gh-proxy.com/",
        "https://hk.gh-proxy.com/",
        "https://gh-proxy.com/",
        "https://gh.llkk.cc/",
    };

    private static final String[] PROXY_NAMES = {
        "直连 (GitHub)",
        "EdgeOne",
        "HK Proxy",
        "GH Proxy",
        "LLKK",
    };

    /** Cached best proxy index (0 = direct, 1+ = proxy) */
    private static int sBestProxy = -1;

    /** Custom proxy URL set by user */
    private static String sCustomProxy = "";

    /** Cached latency results from last test (index → ms, -1 = failed) */
    private static int[] sLatencies = new int[0];

    /**
     * Test all proxies using root curl and return the index of the fastest one.
     * Root curl bypasses Android VPN restrictions and uses the system's network stack.
     * @return index into PROXY_TEMPLATES, or 0 (direct) if all fail
     */
    public static int testProxies() {
        String testUrl = "https://raw.githubusercontent.com/AstrBotDevs/AstrBot/main/README.md";
        return testProxies(testUrl);
    }

    /**
     * Test all proxies against a specific URL using root curl.
     * Stores latency results in sLatencies for UI display.
     * @return index of fastest proxy
     */
    public static int testProxies(String testUrl) {
        Log.i(TAG, "Starting proxy speed test via root curl...");

        int totalProxies = PROXY_TEMPLATES.length + (sCustomProxy.isEmpty() ? 0 : 1);
        sLatencies = new int[totalProxies];

        int bestIndex = 0;
        int bestTime = Integer.MAX_VALUE;

        for (int i = 0; i < PROXY_TEMPLATES.length; i++) {
            String url = buildUrl(testUrl, i);
            int elapsed = testWithCurl(url);
            sLatencies[i] = elapsed;

            if (elapsed > 0 && elapsed < bestTime) {
                bestTime = elapsed;
                bestIndex = i;
                Log.i(TAG, PROXY_NAMES[i] + " → " + elapsed + "ms ✓");
            } else {
                Log.i(TAG, PROXY_NAMES[i] + " → " + (elapsed > 0 ? elapsed + "ms" : "失败"));
            }
        }

        // If custom proxy is set, test it too
        if (!sCustomProxy.isEmpty()) {
            String customUrl = sCustomProxy + testUrl;
            int elapsed = testWithCurl(customUrl);
            sLatencies[PROXY_TEMPLATES.length] = elapsed;
            if (elapsed > 0 && elapsed < bestTime) {
                bestTime = elapsed;
                bestIndex = PROXY_TEMPLATES.length;
                Log.i(TAG, "自定义 → " + elapsed + "ms ✓");
            } else {
                Log.i(TAG, "自定义 → " + (elapsed > 0 ? elapsed + "ms" : "失败"));
            }
        }

        sBestProxy = bestIndex;
        Log.i(TAG, "Best proxy: " + getProxyName(bestIndex) + " (" + bestTime + "ms)");
        return bestIndex;
    }

    /**
     * Test a URL using root curl. Returns latency in ms, or -1 on failure.
     * Uses curl's -w flag to extract timing info, -o /dev/null to discard body,
     * and -sS for silent mode (show errors only).
     */
    private static int testWithCurl(String url) {
        try {
            // Use curl with timing output: %{time_total} in seconds (e.g. 0.123)
            String cmd = "curl -sS -o /dev/null -w '%{time_total}' -L --connect-timeout 8 --max-time 15 '" + url + "'";
            ChrootManager.CommandResult result = ChrootManager.execRoot(cmd, 20);

            if (result.success() && result.stdout() != null && !result.stdout().trim().isEmpty()) {
                String timeStr = result.stdout().trim().replace("'", "");
                float seconds = Float.parseFloat(timeStr);
                int ms = (int) (seconds * 1000);
                Log.i(TAG, "curl test " + url + " → " + ms + "ms");
                return ms;
            } else {
                Log.w(TAG, "curl test failed: " + result.stderr());
                return -1;
            }
        } catch (Exception e) {
            Log.w(TAG, "curl test exception: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Get the latency string for a proxy index (e.g., "123ms" or "超时").
     */
    public static String getLatencyString(int index) {
        if (sLatencies == null || index < 0 || index >= sLatencies.length) {
            return "未测试";
        }
        int ms = sLatencies[index];
        if (ms < 0) return "❌ 超时";
        if (ms < 500) return "🟢 " + ms + "ms";
        if (ms < 2000) return "🟡 " + ms + "ms";
        return "🔴 " + ms + "ms";
    }

    /**
     * Get all proxy names with latency info for UI display.
     */
    public static String[] getAllProxyNamesWithLatency() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < PROXY_NAMES.length; i++) {
            String latency = getLatencyString(i);
            names.add(PROXY_NAMES[i] + "  " + latency);
        }
        if (!sCustomProxy.isEmpty()) {
            String latency = getLatencyString(PROXY_TEMPLATES.length);
            names.add("自定义 (" + sCustomProxy + ")  " + latency);
        }
        return names.toArray(new String[0]);
    }

    /**
     * Get the cached best proxy. If not tested yet, returns 0 (direct).
     */
    public static int getBestProxy() {
        return sBestProxy >= 0 ? sBestProxy : 0;
    }

    /**
     * Set custom proxy URL.
     */
    public static void setCustomProxy(String url) {
        sCustomProxy = url != null ? url : "";
        sBestProxy = -1; // reset cache
    }

    public static String getCustomProxy() {
        return sCustomProxy;
    }

    /**
     * Build a proxied URL from an original GitHub URL and proxy index.
     */
    public static String buildUrl(String originalUrl, int proxyIndex) {
        if (proxyIndex == 0) return originalUrl; // direct
        if (proxyIndex >= PROXY_TEMPLATES.length) {
            // Custom proxy
            return sCustomProxy + originalUrl;
        }
        return PROXY_TEMPLATES[proxyIndex] + originalUrl;
    }

    /**
     * Build URL using the cached best proxy.
     */
    public static String buildUrl(String originalUrl) {
        return buildUrl(originalUrl, getBestProxy());
    }

    /**
     * Get proxy display name.
     */
    public static String getProxyName(int index) {
        if (index >= PROXY_TEMPLATES.length) return "自定义";
        return PROXY_NAMES[index];
    }

    /**
     * Get all proxy names for UI display (without latency).
     */
    public static String[] getAllProxyNames() {
        List<String> names = new ArrayList<>();
        for (String name : PROXY_NAMES) {
            names.add(name);
        }
        if (!sCustomProxy.isEmpty()) {
            names.add("自定义 (" + sCustomProxy + ")");
        }
        return names.toArray(new String[0]);
    }

    public static int getProxyCount() {
        return PROXY_TEMPLATES.length + (sCustomProxy.isEmpty() ? 0 : 1);
    }

    // ─── AstrBot Release API ───

    /**
     * Fetch the latest N release tags from AstrBot GitHub Releases.
     * Uses root curl for network access (bypasses Android VPN issues).
     * Returns null on failure.
     */
    public static List<String> fetchAstrBotReleases(int count) {
        List<String> tags = new ArrayList<>();
        try {
            String apiUrl = "https://api.github.com/repos/AstrBotDevs/AstrBot/releases?per_page=" + count;

            // Try with best proxy first, then direct
            String[] urlsToTry;
            if (sBestProxy > 0) {
                urlsToTry = new String[]{buildUrl(apiUrl), apiUrl};
            } else {
                urlsToTry = new String[]{apiUrl};
            }

            String json = null;
            for (String url : urlsToTry) {
                String cmd = "curl -sS -L --connect-timeout 10 --max-time 20 " +
                    "-H 'User-Agent: Rbot/1.0' -H 'Accept: application/vnd.github.v3+json' " +
                    "'" + url + "'";
                ChrootManager.CommandResult result = ChrootManager.execRoot(cmd, 25);
                if (result.success() && result.stdout() != null && !result.stdout().trim().isEmpty()
                    && !result.stdout().trim().startsWith("<")) {
                    json = result.stdout().trim();
                    break;
                }
            }

            if (json == null) {
                Log.e(TAG, "Failed to fetch AstrBot releases from all URLs");
                return null;
            }

            // Parse JSON - extract "tag_name" fields (simple regex, no Gson dependency)
            Pattern pattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);
            while (matcher.find() && tags.size() < count) {
                String tag = matcher.group(1);
                // Skip pre-releases (beta, alpha, rc)
                if (tag != null && !tag.contains("beta") && !tag.contains("alpha") && !tag.contains("rc")) {
                    tags.add(tag);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch AstrBot releases: " + e.getMessage());
        }
        return tags.isEmpty() ? null : tags;
    }
}
