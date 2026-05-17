package app.rbot.core

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService 实现 — 从 Java ShellService 迁移。
 * 运行在 root 进程中（UID 0），通过 sh -c 执行命令。
 *
 * 手动实现 AIDL 生成的 IShellService 接口协议，
 * 避免编译期依赖 AIDL 生成代码（AGP/KSP 有时不会把 AIDL 输出放入 Kotlin classpath）。
 */
class ShellService : Binder() {

    companion object {
        private const val TAG = "ShellService"
        private const val DESCRIPTOR = "app.rbot.IShellService"
        private const val TRANSACTION_exec = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_ping = IBinder.FIRST_CALL_TRANSACTION + 1
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            TRANSACTION_exec -> {
                data.enforceInterface(DESCRIPTOR)
                val command = data.readString() ?: ""
                val timeoutSec = data.readInt()
                val result = exec(command, timeoutSec)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
            TRANSACTION_ping -> {
                data.enforceInterface(DESCRIPTOR)
                val result = ping()
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
            // Shizuku destroy signal
            16777115 -> {
                Log.i(TAG, "ShellService destroy signal received, exiting process")
                System.exit(0)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    fun exec(command: String, timeoutSec: Int): String {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        try {
            Log.d(TAG, "Executing (sh -c): $command")

            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val lastActivity = longArrayOf(System.currentTimeMillis())
            val startTime = System.currentTimeMillis()
            val maxWallTime = timeoutSec * 3 * 1000L
            val idleTimeout = timeoutSec * 1000L

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdout.appendLine(line)
                            lastActivity[0] = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) { }
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderr.appendLine(line)
                            lastActivity[0] = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) { }
            }

            stdoutThread.start()
            stderrThread.start()

            while (process.isAlive) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime
                val idle = now - lastActivity[0]

                if (elapsed > maxWallTime) {
                    process.destroyForcibly()
                    return jsonResult(stdout.toString(), "命令超过最大运行时间 (${maxWallTime / 1000}s)", -1)
                }

                if (idle > idleTimeout) {
                    process.destroyForcibly()
                    return jsonResult(stdout.toString(), "命令超时 (${idle / 1000}s 无输出)", -1)
                }

                Thread.sleep(500)
            }

            stdoutThread.join(2000)
            stderrThread.join(2000)

            return jsonResult(stdout.toString(), stderr.toString(), process.exitValue())
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: ${e.message}")
            return jsonResult(stdout.toString(), e.message ?: "Unknown error", -1)
        }
    }

    fun ping(): String {
        val uid = android.os.Process.myUid()
        return jsonResult("pong uid=$uid", "", 0)
    }

    private fun jsonResult(stdout: String, stderr: String, exitCode: Int): String {
        return buildString {
            append("{\"stdout\":"); append(jsonString(stdout))
            append(",\"stderr\":"); append(jsonString(stderr))
            append(",\"exitCode\":"); append(exitCode)
            append(",\"success\":"); append(exitCode == 0)
            append("}")
        }
    }

    private fun jsonString(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
