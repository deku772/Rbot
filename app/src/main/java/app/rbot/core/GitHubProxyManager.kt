package app.rbot.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub 代理管理器 — 从 Java GitHubProxyManager 迁移而来。
 * 使用 OkHttp 风格的纯 Java HTTP 测速（无需 root），并添加协程支持。
 *
 * 代理模式：
 * - PREFIX：前缀代理，template + originalUrl（如 https://mirror.ghproxy.com/https://github.com/...）
 * - REPLACE：域名替换，将 github.com 替换为镜像域名（如 https://hub.fastgit.org/user/repo）
 */
object GitHubProxyManager {

    private const val TAG = "GitHubProxyManager"

    enum class ProxyMode { PREFIX, REPLACE }

    data class ProxyEntry(
        val name: String,
        val template: String,  // PREFIX 模式: 前缀 URL; REPLACE 模式: 替换域名
        val mode: ProxyMode = ProxyMode.PREFIX
    )

    private val PROXIES = mutableListOf(
        ProxyEntry("直连 (GitHub)", "", ProxyMode.PREFIX),
        ProxyEntry("EdgeOne", "https://edgeone.gh-proxy.com/", ProxyMode.PREFIX),
        ProxyEntry("HK Proxy", "https://hk.gh-proxy.com/", ProxyMode.PREFIX),
        ProxyEntry("GH Proxy", "https://gh-proxy.com/", ProxyMode.PREFIX),
        ProxyEntry("LLKK", "https://gh.llkk.cc/", ProxyMode.PREFIX),
        ProxyEntry("Mirror GHProxy", "https://mirror.ghproxy.com/", ProxyMode.PREFIX),
        ProxyEntry("GH Proxy Net", "https://gh-proxy.net/", ProxyMode.PREFIX),
        ProxyEntry("GHPS", "https://ghps.cc/", ProxyMode.PREFIX),
        ProxyEntry("FastGit", "https://hub.fastgit.org", ProxyMode.REPLACE),
        ProxyEntry("CNPMJS", "https://github.com.cnpmjs.org", ProxyMode.REPLACE),
        ProxyEntry("521GitHub", "https://521github.com", ProxyMode.REPLACE),
        ProxyEntry("KKGitHub", "https://kkgithub.com", ProxyMode.REPLACE),
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

    /** 测速所有代理 — 用下载小文件头部替代 ping */
    suspend fun testProxies(testUrl: String = "https://github.com/AstrBotDevs/AstrBot/archive/master.zip"): List<ProxyInfo> =
        withContext(Dispatchers.IO) {
            val totalProxies = PROXIES.size + (if (customProxy.isEmpty()) 0 else 1)
            latencies = IntArray(totalProxies)

            // 并行测速 — 用 Range header 只下载前 512KB
            val pairs = coroutineScope {
                val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Pair<Int, Int>>>()
                for (i in PROXIES.indices) {
                    val url = buildUrl(testUrl, i)
                    deferreds.add(async(Dispatchers.IO) {
                        val elapsed = testDownloadSpeed(url)
                        Log.i(TAG, "${PROXIES[i].name} → ${if (elapsed > 0) "${elapsed}ms" else "失败"}")
                        i to elapsed
                    })
                }
                if (customProxy.isNotEmpty()) {
                    val customUrl = customProxy + testUrl
                    val idx = PROXIES.size
                    deferreds.add(async(Dispatchers.IO) {
                        val elapsed = testDownloadSpeed(customUrl)
                        Log.i(TAG, "自定义 → ${if (elapsed > 0) "${elapsed}ms" else "失败"}")
                        idx to elapsed
                    })
                }
                deferreds.awaitAll()
            }

            val results = mutableListOf<ProxyInfo>()
            for ((index, elapsed) in pairs) {
                latencies[index] = elapsed
                val name = if (index >= PROXIES.size) "自定义" else PROXIES[index].name
                val template = if (index >= PROXIES.size) customProxy else PROXIES[index].template
                results.add(ProxyInfo(index, name, elapsed, template))
            }

            bestProxy = results.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }?.index ?: 0
            Log.i(TAG, "Best proxy: ${getProxyName(bestProxy)}")
            results
        }

    fun buildUrl(originalUrl: String, proxyIndex: Int = getBestProxy()): String {
        if (proxyIndex == 0) return originalUrl
        if (proxyIndex >= PROXIES.size) return customProxy + originalUrl
        val entry = PROXIES[proxyIndex]
        return when (entry.mode) {
            ProxyMode.PREFIX -> entry.template + originalUrl
            ProxyMode.REPLACE -> originalUrl.replace("https://github.com", entry.template)
        }
    }

    fun getBestProxy(): Int = if (bestProxy >= 0) bestProxy else 0

    /** 按延迟排序返回可用代理索引（最快在前，不含超时的） */
    fun getAvailableProxies(): List<Int> {
        if (latencies.isEmpty()) return listOf(0)
        val sorted = latencies.indices
            .filter { latencies[it] > 0 }
            .sortedBy { latencies[it] }
        return if (sorted.isEmpty()) listOf(0) else sorted
    }

    fun getProxyName(index: Int): String {
        return if (index >= PROXIES.size) "自定义" else PROXIES[index].name
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

    // ─── 代理列表管理 ───

    /** 获取所有内置代理（不可变快照） */
    fun getProxies(): List<ProxyEntry> = PROXIES.toList()

    /** 更新指定索引的代理 */
    fun updateProxy(index: Int, name: String, url: String, mode: ProxyMode = ProxyMode.PREFIX) {
        if (index in PROXIES.indices) {
            PROXIES[index] = ProxyEntry(name, url, mode)
            bestProxy = -1
        }
    }

    /** 新增代理 */
    fun addProxy(name: String, url: String, mode: ProxyMode = ProxyMode.PREFIX) {
        PROXIES.add(ProxyEntry(name, url, mode))
        bestProxy = -1
    }

    /** 删除指定索引的代理（不允许删除"直连"） */
    fun removeProxy(index: Int) {
        if (index > 0 && index < PROXIES.size) {
            PROXIES.removeAt(index)
            bestProxy = -1
        }
    }

    // ─── 内部实现 ───

    /** 用下载实际文件头部测速（Range: bytes=0-524287，前 512KB） */
    private fun testDownloadSpeed(url: String, redirectCount: Int = 0): Int {
        if (redirectCount > 5) return -1
        var conn: HttpURLConnection? = null
        return try {
            val start = System.currentTimeMillis()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false  // 手动跟 redirect
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Rbot/3.1")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Range", "bytes=0-524287")

            val code = conn.responseCode
            // 跟踪 redirect
            if (code in 301..399) {
                val newLoc = conn.getHeaderField("Location")
                conn.disconnect()
                return if (newLoc != null) testDownloadSpeed(newLoc, redirectCount + 1) else -1
            }
            // 206 = Partial Content, 200 = 服务器忽略 Range（也算通）
            if (code == 206 || code == 200) {
                // 读一点数据确认连接稳定
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var total = 0
                    while (total < 524_288) {
                        val n = input.read(buf)
                        if (n == -1) break
                        total += n
                    }
                }
                (System.currentTimeMillis() - start).toInt()
            } else -1
        } catch (_: Exception) {
            -1
        } finally {
            conn?.disconnect()
        }
    }
}
