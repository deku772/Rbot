package app.rbot;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * In-app interactive shell running inside the Rbot chroot.
 * Uses terminal-emulator module's JNI to fork a process with a PTY,
 * then exec a shell script that chroots into the Ubuntu environment.
 *
 * Prerequisites:
 * 1. setupChrootDevices() must have been called (mounts /dev/pts into chroot)
 * 2. /data/local/rbot_shell.sh must exist (deployed on first run)
 */
public class ShellActivity extends AppCompatActivity
    implements TerminalViewClient, TerminalSessionClient {

    private static final String TAG = "ShellActivity";
    private static final String SHELL_SCRIPT = "/data/local/rbot_shell.sh";
    private static final String CHROOT_DIR = "/data/local/rbot";

    private TerminalView mTerminalView;
    private TerminalSession mSession;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        // Prerequisite: ensure chroot dev/pts is mounted
        ChrootManager.setupChrootDevices(null);

        // Deploy the shell entrypoint script
        deployShellScript();

        // Set up the terminal view from XML layout
        setContentView(R.layout.activity_shell);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(this);

        // Start the chroot shell session
        mSession = startChrootShell();
        mTerminalView.attachSession(mSession);
    }

    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private void deployShellScript() {
        // This script runs as: exec /system/bin/sh -c 'exec /data/local/rbot_shell.sh'
        // The script itself does: exec chroot /data/local/rbot /bin/bash -l
        // exec replaces the shell process, keeping the PTY stdin/stdout/stderr
        String script =
            "#!/system/bin/sh\n" +
            "export HOME=/root\n" +
            "export TERM=xterm-256color\n" +
            "exec chroot " + CHROOT_DIR + " /bin/bash -l\n";

        File f = new File(SHELL_SCRIPT);
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(script);
            w.close();
            f.setExecutable(true, false);
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (java.io.IOException e) {
            android.util.Log.e(TAG, "Failed to deploy shell script: " + e.getMessage());
        }
    }

    private TerminalSession startChrootShell() {
        // JNI flow:
        // 1. fork() - create child process
        // 2. open /dev/ptmx -> PTY master (parent keeps this)
        // 3. setsid() - new session
        // 4. open pts -> PTY slave -> dup2 as stdin/stdout/stderr
        // 5. chdir("/root")
        // 6. execvp("/system/bin/sh", ["/system/bin/sh", "-c", "exec /data/local/rbot_shell.sh"])
        // 7. Shell script: exec chroot /data/local/rbot /bin/bash -l
        //    -> replaces shell with chroot+bash, PTY remains connected
        return new TerminalSession(
            "/system/bin/sh",
            "/root",
            new String[]{
                "/system/bin/sh",
                "-c",
                "exec " + SHELL_SCRIPT
            },
            new String[]{
                "TERM=xterm-256color",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            },
            2000,  // transcript rows (scrollback buffer)
            this   // TerminalSessionClient
        );
    }

    // ─── TerminalSessionClient ───────────────────────────────────────────────

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) { }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) { }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        runOnUiThread(() -> {
            mTerminalView.postDelayed(this::finish, 1500);
        });
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
        if (cb != null) {
            android.content.ClipData clip = android.content.ClipData.newPlainText("rbot-shell", text);
            cb.setPrimaryClip(clip);
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
        if (cb != null && cb.hasPrimaryClip()) {
            android.content.ClipData clip = cb.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String text = clip.getItemAt(0).getText().toString();
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                mSession.write(bytes, 0, bytes.length);
            }
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) { }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) { }

    @Override
    public void onTerminalCursorStateChange(boolean state) { }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }

    @Override
    public Integer getTerminalCursorStyle() {
        // Return null to use default cursor style
        return null;
    }

    // ─── TerminalViewClient ───────────────────────────────────────────────────

    @Override public float onScale(float scale) { return scale; }
    @Override public void onSingleTapUp(MotionEvent e) { }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return true; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
    @Override public void onEmulatorSet() { }

    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.e(tag, message, e);
    }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "", e); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.finishIfRunning();
    }
}
