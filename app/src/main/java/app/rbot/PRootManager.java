package app.rbot;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Manages PRoot-based execution for Rbot — a rootless alternative to chroot.
 *
 * Uses ptrace syscall interception to provide a Linux environment without
 * requiring root access. Designed as a parallel to ChrootManager (not a subclass).
 *
 * Architecture reference: OpenClaw-termux-zh ProcessManager.kt
 * - Two modes: install (--root-id) and gateway (--change-id=0:0 --sysvipc)
 * - PRoot binary disguised as libproot.so in jniLibs for W^X bypass
 * - Fake /proc entries required on Android
 * - Environment isolation (clear Android JVM env before proot)
 *
 * Rootfs is stored in app internal storage (context.filesDir) because
 * sdcard (vfat/FUSE) does not support symlinks needed by the rootfs.
 */
public final class PRootManager {

    private static final String TAG = "PRootManager";

    // Match proot-distro defaults
    private static final String FAKE_KERNEL_RELEASE = "6.17.0-PRoot-Rbot";
    private static final String FAKE_KERNEL_VERSION =
        "#1 SMP PREEMPT_DYNAMIC PRoot-Rbot";

    /** SSH port for PRoot mode (different from chroot's port 22) */
    public static final int SSH_PORT = 8022;

    private static PRootManager sInstance;
    private final Context mContext;

    // Paths — all inside app internal storage (supports symlinks)
    private final String mFilesDir;         // context.getFilesDir()
    private final String mNativeLibDir;      // context.getApplicationInfo().nativeLibraryDir
    private final String mRootfsDir;         // $filesDir/proot-rootfs
    private final String mConfigDir;         // $filesDir/proot-config
    private final String mTmpDir;           // $filesDir/proot-tmp
    private final String mNativeRuntimeDir;  // $filesDir/proot-native
    private final String mHomeDir;           // $filesDir/proot-home

    /** Marker file indicating PRoot rootfs is extracted and ready */
    private final String mRootfsMarker;

    /** Marker file indicating AstrBot is installed in PRoot rootfs */
    private final String mAstrBotMarker;

    /** AstrBot home inside PRoot rootfs */
    private static final String ASTRBOT_HOME_RELATIVE = "/root/astrbot";

    private PRootManager(Context context) {
        mContext = context.getApplicationContext();
        mFilesDir = mContext.getFilesDir().getAbsolutePath();
        mNativeLibDir = mContext.getApplicationInfo().nativeLibraryDir;
        mRootfsDir = mFilesDir + "/proot-rootfs";
        mConfigDir = mFilesDir + "/proot-config";
        mTmpDir = mFilesDir + "/proot-tmp";
        mNativeRuntimeDir = mFilesDir + "/proot-native";
        mHomeDir = mFilesDir + "/proot-home";
        mRootfsMarker = mFilesDir + "/.proot-rootfs-ready";
        mAstrBotMarker = mFilesDir + "/.proot-astrbot-ready";
    }

    public static synchronized PRootManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PRootManager(context);
        }
        return sInstance;
    }

    // ─── Path accessors ───

    public String getRootfsDir() { return mRootfsDir; }
    public String getConfigDir() { return mConfigDir; }
    public String getAstrBotHome() { return mRootfsDir + ASTRBOT_HOME_RELATIVE; }
    public String getAstrBotPidFile() { return getAstrBotHome() + "/astrbot.pid"; }
    public String getAstrBotLogFile() { return getAstrBotHome() + "/astrbot.log"; }

    // ─── Status checks ───

    /** Check if PRoot rootfs is extracted and ready */
    public boolean isRootfsReady() {
        return new File(mRootfsMarker).exists()
            && new File(mRootfsDir + "/bin/bash").exists();
    }

    /** Check if AstrBot is installed in PRoot rootfs */
    public boolean isAstrBotInstalled() {
        return new File(mAstrBotMarker).exists();
    }

    /** Check if AstrBot is running in PRoot */
    public boolean isAstrBotRunning() {
        File pidFile = new File(getAstrBotPidFile());
        if (!pidFile.exists()) return false;
        try {
            String pid = new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim();
            if (pid.isEmpty()) return false;
            // Check if process is alive — we're the parent, so we can check
            Process p = new ProcessBuilder("kill", "-0", pid)
                .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── PRoot binary resolution ───

    /**
     * Check if proot binary is available (either bundled in APK or downloaded at runtime).
     * Does NOT trigger a download — safe to call from UI thread for status checks.
     */
    public boolean isProotBinaryAvailable() {
        File direct = new File(mNativeLibDir, "libproot.so");
        if (direct.exists() && direct.length() > 0) return true;
        File runtime = new File(mNativeRuntimeDir, "libproot.so");
        if (runtime.exists() && runtime.length() > 0) return true;
        return false;
    }

    /** Resolve the path to the proot binary (libproot.so), auto-downloading if missing */
    public String resolveProotPath() {
        // Try nativeLibDir first (APK-installed .so)
        File direct = new File(mNativeLibDir, "libproot.so");
        if (direct.exists() && direct.length() > 0) {
            return direct.getAbsolutePath();
        }
        // Try runtime dir (downloaded binary)
        File runtime = new File(mNativeRuntimeDir, "libproot.so");
        if (runtime.exists() && runtime.length() > 0) {
            return runtime.getAbsolutePath();
        }

        // Binary not found — attempt runtime download
        Log.i(TAG, "PRoot binary not found, downloading at runtime...");
        if (ensureProotBinary()) {
            return runtime.getAbsolutePath();
        }

        throw new IllegalStateException(
            "PRoot binary not found and download failed (checked "
            + direct.getAbsolutePath() + " and " + runtime.getAbsolutePath() + ")");
    }

    /**
     * Download proot binary at runtime if not available.
     * Downloads from GitHub (via proxy if available) to proot-native/libproot.so.
     * Returns true if the binary is available after this call.
     */
    public boolean ensureProotBinary() {
        if (isProotBinaryAvailable()) return true;

        File runtimeDir = new File(mNativeRuntimeDir);
        runtimeDir.mkdirs();
        File target = new File(mNativeRuntimeDir, "libproot.so");

        // Build download URL — use GitHub proxy for Chinese users
        String downloadUrl = RbotConstants.PROOT_BINARY_URL;
        int bestProxy = GitHubProxyManager.testProxies();
        downloadUrl = GitHubProxyManager.buildUrl(downloadUrl, bestProxy);

        Log.i(TAG, "Downloading proot binary from: " + downloadUrl);
        try {
            downloadFile(downloadUrl, target.getAbsolutePath(), null);
        } catch (IOException e) {
            Log.e(TAG, "Proot binary download failed: " + e.getMessage());
            // Fallback: try direct URL
            if (!downloadUrl.equals(RbotConstants.PROOT_BINARY_URL)) {
                Log.i(TAG, "Retrying with direct URL...");
                try {
                    downloadFile(RbotConstants.PROOT_BINARY_URL, target.getAbsolutePath(), null);
                } catch (IOException e2) {
                    Log.e(TAG, "Direct download also failed: " + e2.getMessage());
                    return false;
                }
            } else {
                return false;
            }
        }

        // Make executable
        if (target.exists() && target.length() > 0) {
            target.setExecutable(true, false);
            target.setReadable(true, false);
            Log.i(TAG, "Proot binary downloaded: " + target.getAbsolutePath()
                + " (" + target.length() + " bytes)");
            return true;
        }

        return false;
    }

    /** Resolve a native library by name */
    private String resolveNativePath(String fileName) {
        File direct = new File(mNativeLibDir, fileName);
        if (direct.exists() && direct.length() > 0) return direct.getAbsolutePath();
        File runtime = new File(mNativeRuntimeDir, fileName);
        if (runtime.exists() && runtime.length() > 0) return runtime.getAbsolutePath();
        throw new IllegalStateException("Native binary missing: " + fileName);
    }

    // ─── Environment setup ───

    /** Host-side environment for proot binary itself */
    private java.util.Map<String, String> prootEnv() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("PROOT_TMP_DIR", mTmpDir);
        try {
            env.put("PROOT_LOADER", resolveNativePath("libprootloader.so"));
        } catch (Exception e) {
            Log.w(TAG, "PROOT_LOADER not found, proot may fail for 32-bit exec: " + e.getMessage());
        }
        try {
            env.put("PROOT_LOADER_32", resolveNativePath("libprootloader32.so"));
        } catch (Exception e) {
            // 32-bit loader is optional
        }
        // LD_LIBRARY_PATH for proot itself (needs libtalloc.so.2)
        env.put("LD_LIBRARY_PATH", joinPaths(mConfigDir, mNativeLibDir, mNativeRuntimeDir));
        // NOTE: Do NOT set PROOT_NO_SECCOMP — seccomp BPF provides efficient syscall
        // interception AND proper fork/clone child process tracking
        return env;
    }

    // ─── Fake /proc and DNS setup ───

    /** Ensure fake /proc files exist (Android restricts real /proc access) */
    private void ensureProcFakes() {
        File procFakesDir = new File(mConfigDir, "proc_fakes");
        procFakesDir.mkdirs();

        // Write fake proc files with plausible content
        writeFile(new File(procFakesDir, "loadavg"),
            "0.50 0.40 0.30 1/234 5678\n");
        writeFile(new File(procFakesDir, "stat"),
            "cpu  12345 678 901 23456 7890 123 456 0 0 0\n" +
            "cpu0 12345 678 901 23456 7890 123 456 0 0 0\n");
        writeFile(new File(procFakesDir, "uptime"),
            "123456.78 234567.89\n");
        writeFile(new File(procFakesDir, "version"),
            "Linux version 6.17.0-PRoot-Rbot (gcc version 13.2.0) #1 SMP PREEMPT_DYNAMIC\n");
        writeFile(new File(procFakesDir, "vmstat"),
            "nr_free_pages 123456\n");
        writeFile(new File(procFakesDir, "cap_last_cap"), "40\n");
        writeFile(new File(procFakesDir, "max_user_watches"), "524288\n");
        writeFile(new File(procFakesDir, "fips_enabled"), "0\n");

        // Ensure sys fakes dir exists (empty dir for SELinux override)
        File sysFakesDir = new File(mConfigDir, "sys_fakes/empty");
        sysFakesDir.mkdirs();
    }

    /** Ensure DNS resolv.conf exists — write both bind-mount source and direct rootfs copy */
    private void ensureResolvConf() {
        String dnsConfig = "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\n";
        // Bind-mount source (proot --bind uses this file)
        File resolvFile = new File(mConfigDir, "resolv.conf");
        writeFile(resolvFile, dnsConfig);
        // Direct rootfs copy (fallback if bind-mount doesn't work)
        File rootfsResolv = new File(mRootfsDir, "etc/resolv.conf");
        rootfsResolv.getParentFile().mkdirs();
        writeFile(rootfsResolv, dnsConfig);
    }

    // ─── PRoot command builders ───

    /**
     * Common proot flags shared by install and gateway modes.
     * Matches proot-distro's bind mounts and OpenClaw's implementation.
     */
    private java.util.List<String> commonProotFlags() {
        ensureProcFakes();
        ensureResolvConf();

        String prootPath = resolveProotPath();
        String procFakes = mConfigDir + "/proc_fakes";
        String sysFakes = mConfigDir + "/sys_fakes";

        java.util.List<String> flags = new java.util.ArrayList<>();
        flags.add(prootPath);
        flags.add("--link2symlink");
        flags.add("-L");
        flags.add("--kill-on-exit");
        flags.add("--rootfs=" + mRootfsDir);
        flags.add("--cwd=/root");

        // Core device binds (matching proot-distro)
        flags.add("--bind=/dev");
        flags.add("--bind=/dev/urandom:/dev/random");
        flags.add("--bind=/proc");
        flags.add("--bind=/proc/self/fd:/dev/fd");
        flags.add("--bind=/sys");

        // Fake /proc entries — Android restricts most /proc access
        flags.add("--bind=" + procFakes + "/loadavg:/proc/loadavg");
        flags.add("--bind=" + procFakes + "/stat:/proc/stat");
        flags.add("--bind=" + procFakes + "/uptime:/proc/uptime");
        flags.add("--bind=" + procFakes + "/version:/proc/version");
        flags.add("--bind=" + procFakes + "/vmstat:/proc/vmstat");
        flags.add("--bind=" + procFakes + "/cap_last_cap:/proc/sys/kernel/cap_last_cap");
        flags.add("--bind=" + procFakes + "/max_user_watches:/proc/sys/fs/inotify/max_user_watches");
        // libgcrypt reads this; missing causes apt SIGABRT
        flags.add("--bind=" + procFakes + "/fips_enabled:/proc/sys/crypto/fips_enabled");

        // Shared memory — proot-distro binds rootfs/tmp to /dev/shm
        flags.add("--bind=" + mRootfsDir + "/tmp:/dev/shm");
        // SELinux override
        flags.add("--bind=" + sysFakes + "/empty:/sys/fs/selinux");
        // Home overlay
        flags.add("--bind=" + mHomeDir + ":/root/home");

        // DNS
        File resolvFile = new File(mConfigDir, "resolv.conf");
        if (resolvFile.exists()) {
            flags.add("--bind=" + resolvFile.getAbsolutePath() + ":/etc/resolv.conf");
        }

        // Storage access
        if (hasStorageAccess()) {
            File storageDir = new File(mRootfsDir, "storage");
            storageDir.mkdirs();
            flags.add("--bind=/storage:/storage");
            flags.add("--bind=/storage/emulated/0:/sdcard");
        }

        return flags;
    }

    /**
     * Build install-mode proot command (matches proot-distro's run_proot_cmd).
     * Used for: apt-get, dpkg, npm install, chmod, etc.
     * Simpler: --root-id, simple kernel-release, minimal guest env.
     */
    public String[] buildInstallCommand(String command) {
        java.util.List<String> flags = new java.util.ArrayList<>(commonProotFlags());

        // --root-id: fake root identity (proot-distro run_proot_cmd)
        flags.add(1, "--root-id");
        // Simple kernel-release
        flags.add(2, "--kernel-release=" + FAKE_KERNEL_RELEASE);
        // NOTE: --sysvipc is NOT used during install — causes SIGABRT when dpkg forks

        // Guest environment via env -i
        flags.addAll(java.util.Arrays.asList(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "APT::Sandbox::User=root",
            "/bin/bash", "-c",
            command
        ));

        return flags.toArray(new String[0]);
    }

    /**
     * Build gateway-mode proot command (matches proot-distro's command_login).
     * Used for: running AstrBot (long-lived process).
     * Full featured: --change-id=0:0, --sysvipc, full uname struct.
     */
    public String[] buildGatewayCommand(String command) {
        java.util.List<String> flags = new java.util.ArrayList<>(commonProotFlags());

        String machine = getUnameMachine();

        // --change-id=0:0 (proot-distro command_login uses this for root)
        flags.add(1, "--change-id=0:0");
        // --sysvipc: enable SysV IPC (proot-distro enables for login sessions)
        flags.add(2, "--sysvipc");
        // Full uname struct
        String kernelRelease = "\\Linux\\localhost\\" + FAKE_KERNEL_RELEASE
            + "\\" + FAKE_KERNEL_VERSION + "\\" + machine + "\\localdomain\\-1\\";
        flags.add(3, "--kernel-release=" + kernelRelease);

        // Guest environment via env -i
        flags.addAll(java.util.Arrays.asList(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "-c",
            command
        ));

        return flags.toArray(new String[0]);
    }

    // ─── PRoot command execution ───

    /**
     * Execute a command in proot (install mode) synchronously.
     * Used during bootstrap for apt, pip, chmod, etc.
     */
    public ChrootManager.CommandResult runInProot(String command, int timeoutSec) {
        String[] cmd = buildInstallCommand(command);
        java.util.Map<String, String> env = prootEnv();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // CRITICAL: Clear inherited Android JVM environment
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Log.d(TAG, "Executing proot: " + String.join(" ", cmd));
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("proot warning") || line.contains("can't sanitize")) {
                        continue; // suppress proot noise
                    }
                    output.append(line).append("\n");
                }
            }

            boolean exited = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new ChrootManager.CommandResult(false, output.toString(),
                    "Proot command timed out after " + timeoutSec + "s", -1);
            }

            int exitCode = process.exitValue();
            return new ChrootManager.CommandResult(exitCode == 0, output.toString(),
                "", exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Proot execution failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "",
                "Proot 执行失败: " + e.getMessage(), -1);
        }
    }

    /**
     * Execute a command in proot with progress callback.
     */
    public ChrootManager.CommandResult runInProotWithProgress(
            String command, int timeoutSec, ChrootManager.ProgressCallback callback) {
        String[] cmd = buildInstallCommand(command);
        java.util.Map<String, String> env = prootEnv();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(false);

            Log.d(TAG, "Executing proot (progress): " + command);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            final long[] lastActivity = {System.currentTimeMillis()};

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                        if (callback != null && isProgressLine(line)) {
                            String cleaned = line.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").trim();
                            if (!cleaned.isEmpty()) {
                                callback.onProgress(cleaned);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "proot stdout read error: " + e.getMessage());
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        lastActivity[0] = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "proot stderr read error: " + e.getMessage());
                }
            });

            stdoutThread.start();
            stderrThread.start();

            // Wait with adaptive timeout
            long startTime = System.currentTimeMillis();
            long maxWallTime = timeoutSec * 3 * 1000L;
            long idleTimeout = timeoutSec * 1000L;

            while (process.isAlive()) {
                long now = System.currentTimeMillis();
                if (now - startTime > maxWallTime) {
                    process.destroyForcibly();
                    return new ChrootManager.CommandResult(false, stdout.toString(),
                        "Proot 命令超时 (wall " + (maxWallTime/1000) + "s)", -1);
                }
                if (now - lastActivity[0] > idleTimeout) {
                    process.destroyForcibly();
                    return new ChrootManager.CommandResult(false, stdout.toString(),
                        "Proot 命令超时 (" + (idleTimeout/1000) + "s 无输出)", -1);
                }
                Thread.sleep(500);
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            return new ChrootManager.CommandResult(exitCode == 0, stdout.toString(),
                stderr.toString(), exitCode);

        } catch (Exception e) {
            Log.e(TAG, "Proot execution failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "",
                "Proot 执行失败: " + e.getMessage(), -1);
        }
    }

    // ─── AstrBot lifecycle (PRoot mode) ───

    /** Start AstrBot inside PRoot in background */
    public ChrootManager.CommandResult startAstrBot() {
        // Ensure home and tmp dirs
        new File(mHomeDir).mkdirs();
        new File(mTmpDir).mkdirs();

        // Kill any existing process
        stopAstrBot();

        // Use venv python if available
        String pythonBin = "python3";
        ChrootManager.CommandResult venvCheck = runInProot(
            "test -f /root/astrbot/venv/bin/python3 && echo venv_ok", 5);
        if (venvCheck.success() && venvCheck.stdout().trim().equals("venv_ok")) {
            pythonBin = "/root/astrbot/venv/bin/python3";
        }

        String startCmd =
            "cd /root/astrbot && " +
            "rm -f /root/astrbot/astrbot.pid && " +
            "nohup " + pythonBin + " main.py >> /root/astrbot/astrbot.log 2>&1 & " +
            "disown && " +
            "echo $! > /root/astrbot/astrbot.pid && " +
            "/bin/sleep 5 && " +
            "PID=$(cat /root/astrbot/astrbot.pid 2>/dev/null) && " +
            "if [ -n \"$PID\" ] && kill -0 \"$PID\" 2>/dev/null; then " +
            "  echo started_$PID; " +
            "else " +
            "  echo 'FAIL_PID='$PID': ' $(tail -5 /root/astrbot/astrbot.log 2>/dev/null); exit 1; " +
            "fi";

        String[] cmd = buildGatewayCommand(startCmd);
        java.util.Map<String, String> env = prootEnv();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("proot warning") && !line.contains("can't sanitize")) {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            String stdout = output.toString();
            boolean started = exited && stdout.contains("started_");

            Log.d(TAG, "[startAstrBot] stdout=" + stdout.trim());
            return new ChrootManager.CommandResult(started, stdout, "", started ? 0 : 1);

        } catch (Exception e) {
            Log.e(TAG, "startAstrBot failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "", e.getMessage(), -1);
        }
    }

    /** Stop AstrBot in PRoot mode */
    public ChrootManager.CommandResult stopAstrBot() {
        try {
            File pidFile = new File(getAstrBotPidFile());
            if (pidFile.exists()) {
                String pid = new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim();
                if (!pid.isEmpty()) {
                    // Try to kill the process group
                    new ProcessBuilder("kill", "-9", pid).start().waitFor();
                }
                pidFile.delete();
            }
            // Also try pkill as fallback
            new ProcessBuilder("pkill", "-9", "-f", "python.*main.py")
                .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            Log.w(TAG, "stopAstrBot error: " + e.getMessage());
        }
        return new ChrootManager.CommandResult(true, "stopped", "", 0);
    }

    // ─── SSH Service (PRoot mode) ───

    /** Start SSH service in PRoot mode on port 8022 */
    public ChrootManager.CommandResult startSshService() {
        // Ensure dropbear is installed
        runInProot("which dropbear >/dev/null 2>&1 || apt-get install -y dropbear-bin >/dev/null 2>&1", 60);

        String setupCmd =
            "mkdir -p /etc/dropbear && " +
            "[ -f /etc/dropbear/dropbear_rsa_host_key ] || dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ecdsa_host_key ] || dropbearkey -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ed25519_host_key ] || dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null; " +
            "pkill -x dropbear 2>/dev/null; sleep 1; " +
            "dropbear -r /etc/dropbear/dropbear_rsa_host_key " +
            "-r /etc/dropbear/dropbear_ecdsa_host_key " +
            "-r /etc/dropbear/dropbear_ed25519_host_key " +
            "-p " + SSH_PORT + " -R -B && echo dropbear_started";

        return runInProot(setupCmd, 20);
    }

    /** Stop SSH service in PRoot mode */
    public ChrootManager.CommandResult stopSshService() {
        return runInProot("pkill -x dropbear 2>/dev/null; echo stopped", 10);
    }

    /** Check if SSH is running in PRoot mode */
    public boolean isSshRunning() {
        ChrootManager.CommandResult result = runInProot(
            "pgrep -x dropbear >/dev/null 2>&1 && echo running || echo stopped", 5);
        return result.success() && result.stdout().trim().equals("running");
    }

    /** Get SSH info string for PRoot mode */
    public String getSshInfo() {
        String ip = "127.0.0.1";
        try {
            java.net.NetworkInterface iface = java.net.NetworkInterface.getByName("wlan0");
            if (iface == null) {
                java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface ni = interfaces.nextElement();
                    if (ni.isUp() && !ni.isLoopback()) { iface = ni; break; }
                }
            }
            if (iface != null) {
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        ip = addr.getHostAddress(); break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "ssh root@" + ip + " -p " + SSH_PORT;
    }

    // ─── Rootfs setup for proot ───

    /** Prepare directories needed by proot before first use */
    public void ensureDirectories() {
        new File(mRootfsDir).mkdirs();
        new File(mConfigDir).mkdirs();
        new File(mTmpDir).mkdirs();
        new File(mNativeRuntimeDir).mkdirs();
        new File(mHomeDir).mkdirs();
        // Pre-create directories that proot can't mkdir (ENOSYS)
        new File(mRootfsDir + "/tmp").mkdirs();
        new File(mRootfsDir + "/tmp/npm-cache").mkdirs();
        new File(mRootfsDir + "/var/cache/apt/archives/partial").mkdirs();
        new File(mRootfsDir + "/var/lib/apt/lists/partial").mkdirs();
        new File(mRootfsDir + "/var/lib/dpkg/updates").mkdirs();
        new File(mRootfsDir + "/var/lib/dpkg").mkdirs();
        new File(mRootfsDir + "/root").mkdirs();
    }

    /** Write proot-specific apt/dpkg config into rootfs */
    public void configureProotRootfs() {
        // APT config — proot can't do setresuid, must run as root
        writeFile(new File(mRootfsDir, "/etc/apt/apt.conf.d/01-rbot-proot"),
            "APT::Sandbox::User \"root\";\n" +
            "Acquire::Languages \"none\";\n" +
            "Acquire::Retries \"3\";\n" +
            "Acquire::http::Timeout \"20\";\n" +
            "Acquire::https::Timeout \"20\";\n" +
            "Dpkg::Use-Pty \"0\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; };\n");

        // Dpkg config
        writeFile(new File(mRootfsDir, "/etc/dpkg/dpkg.cfg.d/01-rbot-proot"),
            "force-unsafe-io\n" +
            "no-debsig\n" +
            "force-overwrite\n" +
            "force-depends\n");

        // Block service startups during apt
        writeFile(new File(mRootfsDir, "/usr/sbin/policy-rc.d"),
            "#!/bin/sh\nexit 101\n");
        new File(mRootfsDir, "/usr/sbin/policy-rc.d").setExecutable(true);

        // DNS
        ensureResolvConf();

        // Timezone
        writeFile(new File(mRootfsDir, "/etc/timezone"), "Asia/Shanghai\n");

        // Hosts file (prevent DNS lookups for localhost)
        writeFile(new File(mRootfsDir, "/etc/hosts"),
            "127.0.0.1\tlocalhost\n"
            + "::1\t\tlocalhost ip6-localhost ip6-loopback\n");

        // Replace Ubuntu default mirrors with Tsinghua mirror (faster in China)
        // This overrides whatever sources.list the rootfs shipped with
        writeFile(new File(mRootfsDir, "/etc/apt/sources.list"),
            "# Ubuntu 24.04 Noble - Tsinghua mirror (auto-configured by rbot)\n"
            + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble main restricted universe multiverse\n"
            + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble-updates main restricted universe multiverse\n"
            + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble-security main restricted universe multiverse\n"
            + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ noble-backports main restricted universe multiverse\n");
    }

    /** Mark rootfs as ready */
    public void markRootfsReady() {
        writeFile(new File(mRootfsMarker),
            "format=rbot-proot-rootfs\ncreated_at=" +
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .format(new java.util.Date()) + "\n");
    }

    /** Mark AstrBot as installed */
    public void markAstrBotInstalled() {
        writeFile(new File(mAstrBotMarker), "installed\n");
    }

    // ─── Utility methods ───

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        File sdcard = android.os.Environment.getExternalStorageDirectory();
        return sdcard.exists() && sdcard.canRead();
    }

    private String getUnameMachine() {
        String arch = Build.SUPPORTED_ABIS[0];
        switch (arch) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "armv7l";
            case "x86_64": return "x86_64";
            case "x86": return "i686";
            default: return arch;
        }
    }

    private String joinPaths(String... paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (sb.length() > 0) sb.append(":");
            sb.append(p);
        }
        return sb.toString();
    }

    private void writeFile(File file, String content) {
        try {
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes("UTF-8"));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to write " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    // ─── Download utilities (non-root, Java HTTP) ───

    /**
     * Download a file using Java HttpURLConnection — no root required.
     * Supports progress callback and follows redirects.
     * @param urlStr Download URL
     * @param destPath Destination file path
     * @param callback Optional progress callback (message format: "已下载 XX MB")
     * @throws IOException on network or I/O errors
     */
    public void downloadFile(String urlStr, String destPath,
                             ChrootManager.ProgressCallback callback) throws IOException {
        File destFile = new File(destPath);
        destFile.getParentFile().mkdirs();

        // Delete partial download if exists
        if (destFile.exists()) {
            destFile.delete();
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            // GitHub releases return 302 to CDN — handle manually for reliability
            conn.setRequestProperty("Accept", "application/octet-stream");

            int responseCode = conn.getResponseCode();
            // Follow up to 5 redirects manually (some CDNs chain redirects)
            int redirects = 0;
            while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || responseCode == 307 || responseCode == 308)
                    && redirects < 5) {
                String location = conn.getHeaderField("Location");
                if (location == null) break;
                conn.disconnect();
                url = new URL(url, location);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Accept", "application/octet-stream");
                responseCode = conn.getResponseCode();
                redirects++;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " for " + urlStr);
            }

            int contentLength = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(destFile);

            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int bytesRead;
            long lastProgressTime = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                // Report progress at most once per second
                long now = System.currentTimeMillis();
                if (callback != null && now - lastProgressTime > 1000) {
                    long mb = totalRead / (1024 * 1024);
                    if (contentLength > 0) {
                        int percent = (int) (totalRead * 100 / contentLength);
                        callback.onProgress("已下载 " + mb + " MB (" + percent + "%)");
                    } else {
                        callback.onProgress("已下载 " + mb + " MB");
                    }
                    lastProgressTime = now;
                }
            }

            out.flush();
            Log.i(TAG, "Download complete: " + destPath + " (" + totalRead + " bytes)");

        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Compute MD5 of a file using Java — no root required.
     * @param filePath Path to the file
     * @return Lowercase hex MD5 string, or null on error
     */
    public static String computeMd5(String filePath) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read last N lines of a text file — no root required.
     * Used for reading bot logs in PRoot mode (files are in app storage).
     * @param filePath Path to the file
     * @param numLines Number of lines to read from the end
     * @return The last N lines, or empty string on error
     */
    public static String readTail(String filePath, int numLines) {
        try {
            java.util.LinkedList<String> lines = new java.util.LinkedList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(filePath), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.addLast(line);
                    if (lines.size() > numLines) {
                        lines.removeFirst();
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isProgressLine(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        String l = line.trim();
        if (l.length() < 3) return false;
        if (l.startsWith("debconf:")) return false;
        if (l.startsWith("Requirement already satisfied:")) return false;
        return true;
    }
}
