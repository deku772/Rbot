package app.rbot;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.nio.charset.StandardCharsets;

/**
 * In-app interactive shell running inside the Rbot chroot.
 * Features an extra keys toolbar (ZeroTermux-style 2x7 grid) with
 * sticky modifier keys (CTRL/ALT), back navigation, and keyboard toggle.
 */
public class ShellActivity extends AppCompatActivity
    implements TerminalViewClient, TerminalSessionClient {

    private static final String TAG = "ShellActivity";
    private static final String PTMX_DEVICE = "/dev/pts/ptmx";

    // ── Key definitions: { label, keyType }
    //   keyType: "esc", "tab", "ctrl", "alt", "arrow", "special", "char"
    private static final Object[][] ROW1_KEYS = {
        {"ESC",   "esc"},
        {"/",     "char"},
        {"-",     "char"},
        {"HOME",  "special"},
        {"\u2191", "arrow"},
        {"END",   "special"},
        {"PGUP",  "special"},
    };
    private static final Object[][] ROW2_KEYS = {
        {"TAB",   "tab"},
        {"CTRL",  "ctrl"},
        {"ALT",   "alt"},
        {"\u2190", "arrow"},
        {"\u2193", "arrow"},
        {"\u2192", "arrow"},
        {"PGDN",  "special"},
    };

    // Escape sequences for special keys
    private static final String KEY_HOME = "\u001b[H";
    private static final String KEY_END  = "\u001b[F";
    private static final String KEY_PGUP = "\u001b[5~";
    private static final String KEY_PGDN = "\u001b[6~";

    private TerminalView mTerminalView;
    private TerminalSession mSession;
    private TextView mTitleView;

    // Sticky modifier state: true = locked (long press), int > 0 = sticky (single tap)
    private boolean mCtrlLocked = false;
    private boolean mAltLocked = false;
    private int mCtrlStickyCount = 0;
    private int mAltStickyCount = 0;
    private final Handler mStickyHandler = new Handler(Looper.getMainLooper());
    private static final long STICKY_TIMEOUT_MS = 3000;

    // View references for modifier buttons
    private Button mCtrlButton;
    private Button mAltButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_shell);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(this);
        mTerminalView.setTextSize(18);
        mTitleView = findViewById(R.id.tv_title);

        // Build extra keys toolbar (ZeroTermux-style 2x7 grid)
        LinearLayout row1 = findViewById(R.id.extra_keys_row1);
        LinearLayout row2 = findViewById(R.id.extra_keys_row2);
        buildRow(row1, ROW1_KEYS);
        buildRow(row2, ROW2_KEYS);

        // Back button
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        // Paste button
        ImageButton btnPaste = findViewById(R.id.btn_paste);
        btnPaste.setOnClickListener(v -> pasteFromClipboard());

        // Keyboard toggle
        ImageButton btnKb = findViewById(R.id.btn_keyboard_toggle);
        btnKb.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });

        // Start shell session in background
        boolean useProot = AuthManager.getInstance().isProotMode();
        new Thread(() -> {
            if (useProot) {
                // Proot mode: no su needed, no ptmx chmod needed
                runOnUiThread(() -> {
                    mSession = startProotShell();
                    mTerminalView.attachSession(mSession);
                });
            } else {
                // Chroot mode: need su + chroot setup
                ChrootManager.setupChrootDevices(null);
                grantPtmxAccess();
                runOnUiThread(() -> {
                    mSession = startChrootShell();
                    mTerminalView.attachSession(mSession);
                });
            }
        }).start();
    }

    // ─── Extra Keys Toolbar ───────────────────────────────────────────────────

    private void buildRow(LinearLayout row, Object[][] keys) {
        for (Object[] key : keys) {
            String label = (String) key[0];
            String type = (String) key[1];
            Button btn = makeKeyButton(label);

            switch (type) {
                case "esc":
                    btn.setOnClickListener(v -> writeBytes("\u001b"));
                    break;
                case "tab":
                    btn.setOnClickListener(v -> writeBytes("\t"));
                    break;
                case "ctrl":
                    btn.setTag("ctrl");
                    mCtrlButton = btn;
                    btn.setOnTouchListener(this::onModifierTouch);
                    break;
                case "alt":
                    btn.setTag("alt");
                    mAltButton = btn;
                    btn.setOnTouchListener(this::onModifierTouch);
                    break;
                case "arrow":
                    String seq = getArrowSequence(label);
                    btn.setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            writeBytes(seq);
                        }
                        // Allow repeat on long press for arrows
                        if (event.getAction() == MotionEvent.ACTION_UP
                            || event.getAction() == MotionEvent.ACTION_CANCEL) {
                            v.setPressed(false);
                        }
                        return false;
                    });
                    break;
                case "special":
                    String specialSeq = getSpecialSequence(label);
                    btn.setOnClickListener(v -> writeBytes(specialSeq));
                    break;
                case "char":
                    String ch = label;
                    btn.setOnClickListener(v -> writeBytes(ch));
                    break;
            }
            row.addView(btn);
        }
    }

    private Button makeKeyButton(String label) {
        Button btn = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#CCCCCC"));
        btn.setTextSize(12);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setPadding(0, 0, 0, 0);
        btn.setIncludeFontPadding(false);
        btn.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
        );
        lp.setMargins(0, 0, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private String getArrowSequence(String symbol) {
        switch (symbol) {
            case "\u2191": return "\u001b[A"; // UP
            case "\u2193": return "\u001b[B"; // DOWN
            case "\u2192": return "\u001b[C"; // RIGHT
            case "\u2190": return "\u001b[D"; // LEFT
            default: return symbol;
        }
    }

    private String getSpecialSequence(String label) {
        switch (label) {
            case "HOME": return KEY_HOME;
            case "END":  return KEY_END;
            case "PGUP": return KEY_PGUP;
            case "PGDN": return KEY_PGDN;
            default: return label;
        }
    }

    // ─── Sticky Modifier Keys (ZeroTermux-style) ─────────────────────────────

    /**
     * Single tap = sticky (auto-release after 3s or next key).
     * Long press = locked (stays on until next long press).
     */
    private boolean onModifierTouch(View v, MotionEvent event) {
        String tag = (String) v.getTag();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                v.postDelayed(() -> {
                    if (v.isPressed()) {
                        // Long press → toggle lock
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        if ("ctrl".equals(tag)) {
                            mCtrlLocked = !mCtrlLocked;
                            mCtrlStickyCount = 0;
                        } else {
                            mAltLocked = !mAltLocked;
                            mAltStickyCount = 0;
                        }
                        updateModifierButtons();
                    }
                }, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_UP:
                v.removeCallbacks(null);
                v.setPressed(false);
                if ("ctrl".equals(tag)) {
                    if (!mCtrlLocked) {
                        mCtrlStickyCount++;
                        scheduleStickyReset("ctrl");
                    }
                } else {
                    if (!mAltLocked) {
                        mAltStickyCount++;
                        scheduleStickyReset("alt");
                    }
                }
                updateModifierButtons();
                return true;

            case MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(null);
                v.setPressed(false);
                return true;
        }
        return false;
    }

    private void scheduleStickyReset(String modifier) {
        mStickyHandler.removeCallbacksAndMessages(null);
        mStickyHandler.postDelayed(() -> {
            if ("ctrl".equals(modifier)) {
                mCtrlStickyCount = 0;
            } else {
                mAltStickyCount = 0;
            }
            updateModifierButtons();
        }, STICKY_TIMEOUT_MS);
    }

    private boolean isCtrlActive() {
        return mCtrlLocked || mCtrlStickyCount > 0;
    }

    private boolean isAltActive() {
        return mAltLocked || mAltStickyCount > 0;
    }

    private void consumeSticky() {
        // After a normal key is pressed, consume one sticky count
        mStickyHandler.removeCallbacksAndMessages(null);
        if (mCtrlStickyCount > 0) mCtrlStickyCount = 0;
        if (mAltStickyCount > 0) mAltStickyCount = 0;
        updateModifierButtons();
    }

    private void updateModifierButtons() {
        if (mCtrlButton != null) {
            boolean active = isCtrlActive();
            mCtrlButton.setTextColor(active
                ? Color.parseColor("#4CAF50")
                : Color.parseColor("#CCCCCC"));
            if (mCtrlLocked) {
                mCtrlButton.setBackgroundColor(Color.parseColor("#2E4A2E"));
            } else {
                mCtrlButton.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        if (mAltButton != null) {
            boolean active = isAltActive();
            mAltButton.setTextColor(active
                ? Color.parseColor("#4CAF50")
                : Color.parseColor("#CCCCCC"));
            if (mAltLocked) {
                mAltButton.setBackgroundColor(Color.parseColor("#2E4A2E"));
            } else {
                mAltButton.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    // ─── Write to Terminal ───────────────────────────────────────────────────

    private void writeBytes(String str) {
        if (mSession == null) return;
        byte[] bytes;
        boolean ctrl = isCtrlActive();
        boolean alt = isAltActive();

        if (ctrl && str.length() == 1) {
            char c = str.charAt(0);
            if (c >= '@' && c <= '_') {
                bytes = new byte[]{(byte) (c - '@')};
            } else if (c >= 'a' && c <= 'z') {
                bytes = new byte[]{(byte) (c - 'a' + 1)};
            } else {
                bytes = str.getBytes(StandardCharsets.UTF_8);
            }
        } else {
            bytes = str.getBytes(StandardCharsets.UTF_8);
        }

        // If alt is active, prepend ESC for Alt+key
        if (alt && bytes.length == 1) {
            byte[] altBytes = new byte[bytes.length + 1];
            altBytes[0] = 0x1b;
            System.arraycopy(bytes, 0, altBytes, 1, bytes.length);
            bytes = altBytes;
        }

        // Consume sticky modifiers
        if (ctrl || alt) consumeSticky();

        mSession.write(bytes, 0, bytes.length);
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
        if (cb != null && cb.hasPrimaryClip()) {
            android.content.ClipData clip = cb.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    mSession.write(text.toString().getBytes(StandardCharsets.UTF_8),
                        0, text.length());
                }
            }
        }
    }

    // ─── PTY Access ───────────────────────────────────────────────────────────

    private void grantPtmxAccess() {
        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "chmod 666 " + PTMX_DEVICE, 5);
        if (!result.success()) {
            android.util.Log.e(TAG, "Failed to chmod ptmx: " + result.stderr());
        }
    }

    private void revokePtmxAccess() {
        // Only needed for chroot mode
        if (!AuthManager.getInstance().isProotMode()) {
            ChrootManager.execRoot("chmod 000 " + PTMX_DEVICE, 5);
        }
    }

    // ─── Shell Session ────────────────────────────────────────────────────────

    private static final String CHROOT_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Start a shell inside the proot environment (no root needed) */
    private TerminalSession startProotShell() {
        PRootManager pm = PRootManager.getInstance(this);
        // Add Android supplementary group IDs to /etc/group to suppress
        // "groups: cannot find name for group ID" warnings in bash login shell
        String groupSetup =
            "getent group 3003 >/dev/null || echo 'inet:3003:' >> /etc/group; " +
            "getent group 9997 >/dev/null || echo 'inet:9997:' >> /etc/group; " +
            "getent group 20339 >/dev/null || echo 'aid_read_20339:20339:' >> /etc/group; " +
            "getent group 50339 >/dev/null || echo 'aid_read_50339:50339:' >> /etc/group; " +
            "getent group 99909997 >/dev/null || echo 'aid_read_99909997:99909997:' >> /etc/group; ";
        String[] cmd = pm.buildShellCommand(groupSetup + "/bin/bash -l");
        java.util.Map<String, String> env = pm.prootEnv();

        // Convert env map to array
        String[] envArray = new String[env.size()];
        int i = 0;
        for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
            envArray[i++] = entry.getKey() + "=" + entry.getValue();
        }

        return new TerminalSession(
            cmd[0],
            "/",
            cmd,
            envArray,
            2000,
            this
        );
    }

    /** Start a shell inside the chroot environment (requires root) */
    private TerminalSession startChrootShell() {
        return new TerminalSession(
            "/system/bin/su",
            "/",
            new String[]{
                "/system/bin/su",
                "-c",
                "export HOME=/root; export TERM=xterm-256color; export PATH=" + CHROOT_PATH + "; exec chroot " + RbotConstants.CHROOT_DIR + " /bin/bash -l"
            },
            new String[]{
                "TERM=xterm-256color",
                "HOME=/root",
                "PATH=/system/bin:/system/xbin:" + CHROOT_PATH
            },
            2000,
            this
        );
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        finish();
    }

    // ─── TerminalSessionClient ────────────────────────────────────────────────

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (mTerminalView != null) {
            mTerminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        String title = changedSession.getTitle();
        if (title != null && !title.isEmpty()) {
            runOnUiThread(() -> mTitleView.setText(title));
        }
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        revokePtmxAccess();
        runOnUiThread(() -> {
            mTerminalView.postDelayed(this::finish, 1500);
        });
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
        if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("rbot-shell", text));
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        pasteFromClipboard();
    }

    @Override public void onBell(@NonNull TerminalSession session) { }
    @Override public void onColorsChanged(@NonNull TerminalSession session) { }
    @Override public void onTerminalCursorStateChange(boolean state) { }
    @Override public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }
    @Override public Integer getTerminalCursorStyle() { return null; }

    // ─── TerminalViewClient ───────────────────────────────────────────────────

    @Override public float onScale(float scale) { return scale; }
    @Override public void onSingleTapUp(MotionEvent e) { }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    /** True when user is selecting text (copy mode active) */
    private boolean mIsSelectingText = false;

    @Override public void copyModeChanged(boolean copyMode) {
        mIsSelectingText = copyMode;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (copyMode) {
            // Hide soft keyboard during text selection
            if (imm != null) imm.hideSoftInputFromWindow(mTerminalView.getWindowToken(), 0);
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return mIsSelectingText; // Block key input while selecting text
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return mIsSelectingText; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }

    /** Physical keyboard CTRL state */
    @Override
    public boolean readControlKey() { return isCtrlActive(); }

    /** Physical keyboard ALT state */
    @Override
    public boolean readAltKey() { return isAltActive(); }

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
        mStickyHandler.removeCallbacksAndMessages(null);
        if (mSession != null) mSession.finishIfRunning();
        revokePtmxAccess();
    }
}
