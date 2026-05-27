package app.rbot.core

/**
 * Shell 命令执行结果 — 等价于旧版 ChrootManager.CommandResult (Java record)。
 * 使用 data class + 密封类友好的设计。
 */
data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int = if (success) 0 else 1
) {
    /** 获取 stdout 的非空行 */
    val outputLines: List<String>
        get() = stdout.lines().filter { it.isNotBlank() }

    /** 获取 stderr 的非空行 */
    val errorLines: List<String>
        get() = stderr.lines().filter { it.isNotBlank() }
}
