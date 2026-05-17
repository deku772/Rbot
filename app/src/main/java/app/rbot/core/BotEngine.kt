package app.rbot.core

/**
 * Bot 引擎枚举 — 替代旧版 BotAdapter 的 ID 常量 + BotManager 的字符串映射。
 * 用密封类更安全、可扩展。
 */
enum class BotEngine(
    val id: String,
    val displayName: String,
    val statusLabel: String,
    val supportedPlatforms: String,
    val webUIUrl: String
) {
    ASTRBOT(
        id = "astrbot",
        displayName = "AstrBot",
        statusLabel = "ASTRBOT",
        supportedPlatforms = "QQ（原生）",
        webUIUrl = "http://127.0.0.1:6185"
    );

    companion object {
        /** 根据 ID 查找，默认返回 ASTRBOT */
        fun fromId(id: String): BotEngine =
            entries.find { it.id == id } ?: ASTRBOT
    }
}
