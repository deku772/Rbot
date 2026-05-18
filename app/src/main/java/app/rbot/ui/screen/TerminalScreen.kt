package app.rbot.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rbot.core.AuthManager
import app.rbot.core.PRootManager
import app.rbot.core.ChrootManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 终端 Screen — 使用 AndroidView 包装 Termux TerminalView。
 * 支持 Chroot 和 PRoot 两种模式的终端会话。
 *
 * Chroot 模式: 创建 session 前需要先 setupChrootDevices + chmod /dev/pts/ptmx，
 * 因为 JNI createSubprocess 会在 fork 前调用 open("/dev/ptmx")。
 * Session 结束后需要恢复 ptmx 权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var terminalTitle by remember { mutableStateOf("终端") }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    // 环境准备状态: null=准备中, true=就绪, false=失败
    var envReady by remember { mutableStateOf<Boolean?>(null) }

    val isProot = AuthManager.instance.isProotMode

    // 会话客户端实现
    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView?.onScreenUpdated()
            }

            override fun onTitleChanged(changedSession: TerminalSession) {
                terminalTitle = changedSession.title.ifEmpty { "终端" }
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalTitle = "终端 — 已断开"
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
                session?.write(clip)
            }

            override fun onBell(session: TerminalSession) { }
            override fun onColorsChanged(session: TerminalSession) { }

            override fun onTerminalCursorStateChange(state: Boolean) {
                terminalView?.setTerminalCursorBlinkerState(state, true)
            }

            override fun setTerminalShellPid(session: TerminalSession, pid: Int) { }
            override fun getTerminalCursorStyle(): Int? = null

            override fun logError(tag: String?, msg: String?) { }
            override fun logWarn(tag: String?, msg: String?) { }
            override fun logInfo(tag: String?, msg: String?) { }
            override fun logDebug(tag: String?, msg: String?) { }
            override fun logVerbose(tag: String?, msg: String?) { }
            override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) { }
            override fun logStackTrace(tag: String?, e: Exception?) { }
        }
    }

    // View 客户端实现
    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale

            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(terminalView, 0)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true

            override fun copyModeChanged(copyMode: Boolean) {
                if (copyMode) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(terminalView?.windowToken, 0)
                }
            }

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

            override fun onEmulatorSet() {
                terminalView?.setTerminalCursorBlinkerRate(600)
            }

            override fun logError(tag: String?, msg: String?) { }
            override fun logWarn(tag: String?, msg: String?) { }
            override fun logInfo(tag: String?, msg: String?) { }
            override fun logDebug(tag: String?, msg: String?) { }
            override fun logVerbose(tag: String?, msg: String?) { }
            override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) { }
            override fun logStackTrace(tag: String?, e: Exception?) { }
        }
    }

    // ─── 环境准备 (chroot 模式需要 su 操作，必须异步) ───
    LaunchedEffect(isProot) {
        if (isProot) {
            envReady = true
        } else {
            withContext(Dispatchers.IO) {
                try {
                    if (ChrootManager.isRootfsReady()) {
                        ChrootManager.setupChrootDevices(null)
                        grantPtmxAccess()
                        envReady = true
                    } else {
                        envReady = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TerminalScreen", "setupChrootDevices failed", e)
                    envReady = false
                }
            }
        }
    }

    // ─── 退出时回收 ───
    DisposableEffect(Unit) {
        onDispose {
            session?.finishIfRunning()
            if (!isProot) {
                revokePtmxAccess()
            }
            terminalView = null
            session = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(terminalTitle) })
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (envReady) {
                null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Text(
                        "正在初始化终端环境...",
                        modifier = Modifier.align(Alignment.Center).padding(top = 40.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                false -> {
                    Text(
                        "终端环境未就绪，请先完成安装。",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                true -> {
                    // 使用 key 确保只在 envReady 变为 true 时创建一次
                    key(envReady) {
                        TerminalViewContent(
                            isProot = isProot,
                            context = context,
                            sessionClient = sessionClient,
                            viewClient = viewClient,
                            onTerminalViewCreated = { tv -> terminalView = tv },
                            onSessionCreated = { s -> session = s }
                        )
                    }
                }
            }

            // 快捷键栏
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("Ctrl", "Alt", "Tab", "Esc", "↑", "↓", "Ctrl+C").forEach { key ->
                    FilledTonalButton(
                        onClick = {
                            val s = session ?: return@FilledTonalButton
                            when (key) {
                                "Ctrl" -> { /* TODO: toggle ctrl state */ }
                                "Alt" -> { /* TODO: toggle alt state */ }
                                "Tab" -> s.writeCodePoint(false, 9)
                                "Esc" -> s.writeCodePoint(true, 27)
                                "↑" -> s.write("\u001b[A")
                                "↓" -> s.write("\u001b[B")
                                "Ctrl+C" -> {
                                    // 发送 ETX (Ctrl+C = \u0003)
                                    s.write("\u0003")
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(key, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * 终端视图内容 — 单独抽出以便使用 key() 控制重建。
 *
 * 不在 factory 中创建 session，而是先创建 TerminalView，
 * 等 View layout 完成后再通过 onLayoutChange 创建并 attach session。
 * 这避免了 width/height 为 0 时 attachSession 导致 emulator 无法初始化的问题。
 */
@Composable
private fun TerminalViewContent(
    isProot: Boolean,
    context: Context,
    sessionClient: TerminalSessionClient,
    viewClient: TerminalViewClient,
    onTerminalViewCreated: (TerminalView) -> Unit,
    onSessionCreated: (TerminalSession) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            val tv = TerminalView(ctx, null)
            tv.setTerminalViewClient(viewClient)
            tv.setTextSize(14)
            // 关键：与旧版 XML 布局一致，必须设置 focusable 才能弹出输入法
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            onTerminalViewCreated(tv)

            // 关键：等 View layout 完成后再创建 session
            // 因为 attachSession → updateSize 需要知道 View 的实际尺寸
            var sessionAttached = false
            tv.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val width = right - left
                val height = bottom - top
                if (width > 0 && height > 0 && !sessionAttached) {
                    sessionAttached = true
                    val s = createTerminalSession(ctx, isProot, sessionClient)
                    if (s != null) {
                        onSessionCreated(s)
                        tv.attachSession(s)
                    }
                    // 自动获取焦点并弹出输入法
                    tv.requestFocus()
                    tv.post {
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(tv, 0)
                    }
                }
            }
            tv
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * 创建终端会话，与旧版 rbot ShellActivity 逻辑一致。
 */
private fun createTerminalSession(
    context: Context,
    isProot: Boolean,
    sessionClient: TerminalSessionClient
): TerminalSession? {
    return try {
        if (isProot) {
            val pm = PRootManager.getInstance(context)
            if (!pm.isRootfsReady()) return null
            val shellCmd = pm.buildShellCommand("bash")
            val env = pm.prootEnv().map { (k, v) -> "$k=$v" }.toTypedArray()
            TerminalSession(
                shellCmd[0],
                "/",
                shellCmd.drop(1).toTypedArray(),
                env,
                2000,
                sessionClient
            )
        } else {
            if (!ChrootManager.isRootfsReady()) return null
            val chrootPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val chrootShellCmd = "export HOME=/root; export TERM=xterm-256color; export PATH=$chrootPath; exec chroot /data/rbot /bin/bash -l"
            TerminalSession(
                "/system/bin/su",
                "/",
                arrayOf("/system/bin/su", "-c", chrootShellCmd),
                arrayOf(
                    "TERM=xterm-256color",
                    "HOME=/root",
                    "PATH=/system/bin:/system/xbin:$chrootPath"
                ),
                2000,
                sessionClient
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("TerminalScreen", "Failed to create terminal session", e)
        null
    }
}

// ─── PTY 权限管理 ───

private fun grantPtmxAccess() {
    try {
        val result = ChrootManager.execRoot("chmod 666 /dev/pts/ptmx", 5)
        if (!result.success) {
            android.util.Log.w("TerminalScreen", "chmod ptmx failed: ${result.stderr}")
        }
    } catch (e: Exception) {
        android.util.Log.e("TerminalScreen", "grantPtmxAccess failed", e)
    }
}

private fun revokePtmxAccess() {
    try {
        ChrootManager.execRoot("chmod 000 /dev/pts/ptmx", 5)
    } catch (_: Exception) { }
}
