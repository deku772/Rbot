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

/**
 * 终端 Screen — 使用 AndroidView 包装 Termux TerminalView。
 * 支持 Chroot 和 PRoot 两种模式的终端会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var terminalTitle by remember { mutableStateOf("终端") }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

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
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).also { tv ->
                        terminalView = tv
                        tv.setTerminalViewClient(viewClient)
                        tv.setTextSize(14)

                        val isProot = AuthManager.instance.isProotMode
                        if (isProot) {
                            val pm = PRootManager.getInstance(ctx)
                            if (pm.isRootfsReady()) {
                                val shellCmd = pm.buildShellCommand("bash")
                                val env = pm.prootEnv().map { (k, v) -> "$k=$v" }.toTypedArray()
                                val s = TerminalSession(
                                    shellCmd[0],
                                    "/",
                                    shellCmd.drop(1).toTypedArray(),
                                    env,
                                    2000,
                                    sessionClient
                                )
                                session = s
                                tv.attachSession(s)
                            }
                        } else {
                            if (ChrootManager.isRootfsReady()) {
                                val chrootBash = "chroot /data/rbot /bin/bash -c " +
                                    "'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                                    "export HOME=/root && unset ANDROID_ROOT && bash'"
                                val s = TerminalSession(
                                    "/system/bin/sh",
                                    "/",
                                    arrayOf("-c", chrootBash),
                                    arrayOf("TERM=xterm-256color", "HOME=/root"),
                                    2000,
                                    sessionClient
                                )
                                session = s
                                tv.attachSession(s)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 快捷键栏
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("Ctrl", "Alt", "Tab", "Esc", "↑", "↓").forEach { key ->
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

    DisposableEffect(Unit) {
        onDispose {
            session?.finishIfRunning()
            terminalView = null
            session = null
        }
    }
}
