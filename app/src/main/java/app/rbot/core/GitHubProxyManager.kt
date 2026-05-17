package app.rbot.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub 代理管理器 — 从 Java GitHubProxyManager 迁移而来。
 * 使用 OkHttp 风格的纯 Java HTTP 测速（无需 root），并添加协程支持。
 */
object GitHubProxyManager {

    private const val TAG = "GitHubProxyManager"

    private val PROXY_TEMPLATES = arrayOf(
        "",  // 直连
        "https://edgeone.gh-proxy.com/",
        "https://hk.gh-proxy.com/",
        "https://gh-proxy.com/",
        "https://gh.llkk.cc/",
    )

    private val PROXY_NAMES = arrayOf(
        "直连 (GitHub)",
        "EdgeOne",
        "HK Proxy",
        "GH Proxy",
        "LLKK",
    )

    private var bestProxy = -1
    private var customProxy = ""
    private var latencies = IntArray(0)

    data class ProxyInfo(
        val index: Int,
        val name: String,
        val latencyMs: Int,  // -1 = 失败
        val template: String
    )

    /** 测速所有代理（挂起函数，协程友好） */
    suspend fun testProxies(testUrl: String = "https://api.github.com/zen"): List<ProxyInfo> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyInfo>()
            val totalProxies = PROXY_TEMPLATES.size + (if (customProxy.isEmpty()) 0 else 1)
            latencies = IntArray(totalProxies)

            for (i in PROXY_TEMPLATES.indices) {
                val url = buildUrl(testUrl, i)
                val elapsed = testWithHttp(url)
                latencies[i] = elapsed
                results.add(ProxyInfo(i, PROXY_NAMES[i], elapsed, PROXY_TEMPLATES[i]))
                Log.i(TAG, "${PROXY_NAMES[i]} → ${if (elapsed > 0) "${elapsed}ms" else "失败"}")
            }

            if (customProxy.isNotEmpty()) {
                val customUrl = customProxy + testUrl
                val elapsed = testWithHttp(customUrl)
                latencies[PROXY_TEMPLATES.size] = elapsed
                results.add(ProxyInfo(PROXY_TEMPLATES.size, "自定义", elapsed, customProxy))
            }

            bestProxy = results.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }?.index ?: 0
            Log.i(TAG, "Best proxy: ${getProxyName(bestProxy)}")
            results
        }

    fun buildUrl(originalUrl: String, proxyIndex: Int = getBestProxy()): String {
        if (proxyIndex == 0) return originalUrl
        if (proxyIndex >= PROXY_TEMPLATES.size) return customProxy + originalUrl
        return PROXY_TEMPLATES[proxyIndex] + originalUrl
    }

    fun getBestProxy(): Int = if (bestProxy >= 0) bestProxy else 0

    fun getProxyName(index: Int): String {
        return if (index >= PROXY_TEMPLATES.size) "自定义" else PROXY_NAMES[index]
    }

    fun getLatencyString(index: Int): String {
        if (latencies.isEmpty() || index < 0 || index >= latencies.size) return "未测试"
        val ms = latencies[index]
        return when {
            ms < 0 -> "超时"
            ms < 500 -> "快 ${ms}ms"
            ms < 2000 -> "中 ${ms}ms"
            else -> "慢 ${ms}ms"
        }
    }

    fun setCustomProxy(url: String?) {
        customProxy = url ?: ""
        bestProxy = -1
    }

    fun getCustomProxy(): String = customProxy

    // ─── 内部实现 ───

    private fun testWithHttp(url: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            val start = System.currentTimeMillis()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Rbot/4.0")
            conn.setRequestProperty("Accept", "*/*")

            val code = conn.responseCode
            if (code in 200..399) {
                (System.currentTimeMillis() - start).toInt()
            } else -1
        } catch (_: Exception) {
            -1
        } finally {
            conn?.disconnect()
        }
    }
}
