package app.rbot;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
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

    /** SSH port — must be >= 1024 because Android's ip_unprivileged_port_start=1024
     *  prevents non-root apps from binding to privileged ports, even inside proot. */
    public static final int SSH_PORT = 8022;

    private static PRootManager sInstance;
    private static String sFilesDir; // Static cache of filesDir for use without Context
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
    
    /** Static marker file name for AstrBot installed check */
    private static final String ASTRBOT_MARKER_NAME = ".proot-astrbot-ready";

    /** AstrBot home inside PRoot rootfs */
    private static final String ASTRBOT_HOME_RELATIVE = "/root/astrbot";

    /** PID file for the proot parent process (host-side) */
    private final String mProotPidFile;

    /** The currently running proot process for AstrBot gateway, or null */
    private Process mProotGatewayProcess;

    /** PID file for the proot SSH daemon process (host-side) */
    private final String mProotSshPidFile;

    /** The currently running proot process for SSH, or null */
    private Process mProotSshProcess;

    private PRootManager(Context context) {
        mContext = context.getApplicationContext();
        mFilesDir = mContext.getFilesDir().getAbsolutePath();
        sFilesDir = mFilesDir; // Cache for static methods
        mNativeLibDir = mContext.getApplicationInfo().nativeLibraryDir;
        mRootfsDir = mFilesDir + "/proot-rootfs";
        mConfigDir = mFilesDir + "/proot-config";
        mTmpDir = mFilesDir + "/proot-tmp";
        mNativeRuntimeDir = mFilesDir + "/proot-native";
        mHomeDir = mFilesDir + "/proot-home";
        mRootfsMarker = mFilesDir + "/.proot-rootfs-ready";
        mAstrBotMarker = mFilesDir + "/" + ASTRBOT_MARKER_NAME;
        mProotPidFile = mFilesDir + "/proot-gateway.pid";
        mProotGatewayProcess = null;
        mProotSshPidFile = mFilesDir + "/proot-ssh.pid";
        mProotSshProcess = null;
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
    public String getProotPidFile() { return mProotPidFile; }

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
    
    /** 
     * Static version of isAstrBotInstalled that doesn't require Context.
     * Uses cached sFilesDir which is set on first getInstance() call.
     * @return true if AstrBot marker exists, false otherwise (or if sFilesDir is not set)
     */
    public static boolean isAstrBotInstalledStatic() {
        if (sFilesDir == null) {
            return false; // Instance never created, can't check
        }
        return new File(sFilesDir + "/" + ASTRBOT_MARKER_NAME).exists();
    }

    /** Check if AstrBot is running in PRoot.
     * Checks from the host side only — avoids starting a second proot instance
     * which could conflict with the gateway proot's ptrace.
     */
    public boolean isAstrBotRunning() {
        // Check: is the proot parent process alive?
        // If proot is dead, AstrBot is definitely dead too.
        boolean prootAlive = false;

        // Check Java Process object first
        if (mProotGatewayProcess != null && mProotGatewayProcess.isAlive()) {
            prootAlive = true;
        }

        // Also check via PID file (survives app restart)
        if (!prootAlive) {
            File prootPidFile = new File(mProotPidFile);
            if (prootPidFile.exists()) {
                try {
                    int prootPid = Integer.parseInt(
                        new String(java.nio.file.Files.readAllBytes(prootPidFile.toPath())).trim());
                    prootAlive = isPidAlive(prootPid);
                } catch (Exception e) {
                    Log.w(TAG, "isAstrBotRunning: proot PID check failed: " + e.getMessage());
                }
            }
        }

        if (!prootAlive) {
            return false;
        }

        // Proot is alive — AstrBot should be running inside it.
        // Quick check on the AstrBot PID from host side.
        // proot doesn't virtualize PIDs, so the PID in astrbot.pid is the host PID.
        File pidFile = new File(getAstrBotPidFile());
        if (!pidFile.exists()) return false;
        try {
            int pid = Integer.parseInt(
                new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim());
            return isPidAlive(pid);
        } catch (Exception e) {
            Log.e(TAG, "isAstrBotRunning failed: " + e.getMessage());
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
            ensureLibTalloc(); // Must be done before proot starts (linker needs it)
            return direct.getAbsolutePath();
        }
        // Try runtime dir (downloaded binary)
        File runtime = new File(mNativeRuntimeDir, "libproot.so");
        if (runtime.exists() && runtime.length() > 0) {
            return runtime.getAbsolutePath();
        }

        // Try system proot as fallback
        File systemProot = new File("/system/bin/proot");
        if (systemProot.exists() && systemProot.canExecute()) {
            Log.i(TAG, "Using system proot at: " + systemProot.getAbsolutePath());
            return systemProot.getAbsolutePath();
        }
        
        // Try termux proot
        File termuxProot = new File("/data/data/com.termux/files/usr/bin/proot");
        if (termuxProot.exists() && termuxProot.canExecute()) {
            Log.i(TAG, "Using Termux proot at: " + termuxProot.getAbsolutePath());
            return termuxProot.getAbsolutePath();
        }

        // Binary not found — attempt runtime download
        Log.i(TAG, "PRoot binary not found at " + direct.getAbsolutePath() + " or " + runtime.getAbsolutePath());
        Log.i(TAG, "System proot not found, attempting to download...");
        
        // Try downloading up to 2 times
        boolean downloaded = false;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Log.i(TAG, "Download attempt " + attempt + "/2");
            if (ensureProotBinary()) {
                downloaded = true;
                break;
            }
            Log.w(TAG, "Download attempt " + attempt + " failed, retrying...");
            try {
                Thread.sleep(2000); // Wait 2 seconds before retrying
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (downloaded) {
            ensureLibTalloc();
            Log.i(TAG, "PRoot binary downloaded successfully to " + runtime.getAbsolutePath());
            return runtime.getAbsolutePath();
        }

        Log.e(TAG, "Failed to download PRoot binary after 2 attempts");
        Log.e(TAG, "Native lib dir: " + mNativeLibDir);
        Log.e(TAG, "Runtime dir: " + mNativeRuntimeDir);
        Log.e(TAG, "Files dir: " + mFilesDir);
        
        throw new IllegalStateException(
            "PRoot binary not found and download failed after 2 attempts. " +
            "Checked: " + direct.getAbsolutePath() + ", " + runtime.getAbsolutePath() + ", " +
            systemProot.getAbsolutePath() + ", " + termuxProot.getAbsolutePath() + ". " +
            "Please check your network connection and try again, or install Termux for proot support.");
    }

    /** Ensure libtalloc.so.2 exists in a writable directory.
     *  proot is dynamically linked against libtalloc.so.2 but Android's
     *  nativeLibraryDir is read-only. We bundle the .so in assets and
     *  copy it to our writable filesDir at runtime. */
    private File ensureLibTalloc() {
        File libDir = new File(mFilesDir, "lib");
        libDir.mkdirs();
        File target = new File(libDir, "libtalloc.so.2");
        if (target.exists() && target.length() > 30000) return target;

        // Copy from bundled assets
        try (java.io.InputStream is = mContext.getAssets().open("libtalloc.so");
             java.io.OutputStream os = new java.io.FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            target.setExecutable(true);
            target.setReadable(true, false);
            Log.i(TAG, "libtalloc.so.2 extracted to " + target.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract libtalloc: " + e.getMessage());
        }
        return target;
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
        
        // Try multiple download URLs
        boolean downloaded = false;
        for (String baseUrl : RbotConstants.PROOT_BINARY_URLS) {
            downloadUrl = GitHubProxyManager.buildUrl(baseUrl, bestProxy);

            Log.i(TAG, "Downloading proot binary from: " + downloadUrl);
            try {
                downloadFile(downloadUrl, target.getAbsolutePath(), null);
                if (target.exists() && target.length() > 1000) {
                    downloaded = true;
                    break;
                }
            } catch (IOException e) {
                Log.e(TAG, "Proot binary download failed from " + downloadUrl + ": " + e.getMessage());
            }
            
            // Also try direct URL without proxy
            if (!downloadUrl.equals(baseUrl)) {
                try {
                    downloadFile(baseUrl, target.getAbsolutePath(), null);
                    if (target.exists() && target.length() > 1000) {
                        downloaded = true;
                        break;
                    }
                } catch (IOException e2) {
                    Log.e(TAG, "Direct download from " + baseUrl + " also failed: " + e2.getMessage());
                }
            }
        }
        
        if (!downloaded) {
            Log.e(TAG, "Failed to download proot binary from all available sources");
            return false;
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
    java.util.Map<String, String> prootEnv() {
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
        // Ensure libtalloc.so.2 exists and add its directory to LD_LIBRARY_PATH
        File talloc = ensureLibTalloc();
        env.put("LD_LIBRARY_PATH", joinPaths(talloc.getParent(), mConfigDir, mNativeLibDir, mNativeRuntimeDir));
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
        File talloc = ensureLibTalloc();
        String ldLibraryPath = joinPaths(talloc.getParent(), mConfigDir, mNativeLibDir, mNativeRuntimeDir);
        flags.addAll(java.util.Arrays.asList(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "APT::Sandbox::User=root",
            "LD_LIBRARY_PATH=" + ldLibraryPath,
            "/bin/bash", "-c",
            command
        ));

        return flags.toArray(new String[0]);
    }

    /**
     * Build gateway-mode proot command (matches proot-distro's command_login).
     * Used for: running AstrBot (long-lived process).
     * Full featured: --change-id=0:0, --sysvipc, full uname struct.
     *
     * CRITICAL: No --kill-on-exit here! The proot process must stay alive
     * as AstrBot's process namespace container. The command ends with
     * 'sleep infinity' so proot never exits on its own.
     * To stop: kill the proot process via stopAstrBot().
     */
    public String[] buildGatewayCommand(String command) {
        java.util.List<String> flags = new java.util.ArrayList<>();

        // NOTE: Do NOT add --kill-on-exit for gateway mode!
        // AstrBot needs proot to stay alive as its container.
        // commonProotFlags() adds --kill-on-exit; we must not use it directly.
        // Instead, build from scratch without --kill-on-exit.

        ensureProcFakes();
        ensureResolvConf();

        String prootPath = resolveProotPath();
        String procFakes = mConfigDir + "/proc_fakes";
        String sysFakes = mConfigDir + "/sys_fakes";

        flags.add(prootPath);
        flags.add("--link2symlink");
        flags.add("-L");
        // NO --kill-on-exit — AstrBot needs proot alive
        flags.add("--rootfs=" + mRootfsDir);
        flags.add("--cwd=/root");

        // --change-id=0:0 (proot-distro command_login uses this for root)
        flags.add("--change-id=0:0");
        // --sysvipc: enable SysV IPC (proot-distro enables for login sessions)
        flags.add("--sysvipc");
        // Full uname struct
        String machine = getUnameMachine();
        String kernelRelease = "\\Linux\\localhost\\" + FAKE_KERNEL_RELEASE
            + "\\" + FAKE_KERNEL_VERSION + "\\" + machine + "\\localdomain\\-1\\";
        flags.add("--kernel-release=" + kernelRelease);

        // Core device binds (same as commonProotFlags)
        flags.add("--bind=/dev");
        flags.add("--bind=/dev/urandom:/dev/random");
        flags.add("--bind=/proc");
        flags.add("--bind=/proc/self/fd:/dev/fd");
        flags.add("--bind=/sys");

        // Fake /proc entries
        flags.add("--bind=" + procFakes + "/loadavg:/proc/loadavg");
        flags.add("--bind=" + procFakes + "/stat:/proc/stat");
        flags.add("--bind=" + procFakes + "/uptime:/proc/uptime");
        flags.add("--bind=" + procFakes + "/version:/proc/version");
        flags.add("--bind=" + procFakes + "/vmstat:/proc/vmstat");
        flags.add("--bind=" + procFakes + "/cap_last_cap:/proc/sys/kernel/cap_last_cap");
        flags.add("--bind=" + procFakes + "/max_user_watches:/proc/sys/fs/inotify/max_user_watches");
        flags.add("--bind=" + procFakes + "/fips_enabled:/proc/sys/crypto/fips_enabled");

        // Shared memory
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

        // Guest environment via env -i
        File talloc = ensureLibTalloc();
        String ldLibraryPath = joinPaths(talloc.getParent(), mConfigDir, mNativeLibDir, mNativeRuntimeDir);
        flags.addAll(java.util.Arrays.asList(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "LD_LIBRARY_PATH=" + ldLibraryPath,
            "/bin/bash", "-c",
            command
        ));

        return flags.toArray(new String[0]);
    }

    /**
     * Build interactive shell proot command (for ShellActivity terminal).
     * Same as buildGatewayCommand but WITH --kill-on-exit, because when the user
     * exits the shell (types 'exit'), proot should also terminate.
     * The proot process is NOT a long-lived daemon in this case.
     */
    public String[] buildShellCommand(String command) {
        java.util.List<String> flags = new java.util.ArrayList<>();

        ensureProcFakes();
        ensureResolvConf();

        String prootPath = resolveProotPath();
        String procFakes = mConfigDir + "/proc_fakes";
        String sysFakes = mConfigDir + "/sys_fakes";

        flags.add(prootPath);
        flags.add("--link2symlink");
        flags.add("-L");
        flags.add("--kill-on-exit"); // Shell is interactive, proot should exit when shell exits
        flags.add("--rootfs=" + mRootfsDir);
        flags.add("--cwd=/root");
        flags.add("--change-id=0:0");
        flags.add("--sysvipc");
        String machine = getUnameMachine();
        String kernelRelease = "\\Linux\\localhost\\" + FAKE_KERNEL_RELEASE
            + "\\" + FAKE_KERNEL_VERSION + "\\" + machine + "\\localdomain\\-1\\";
        flags.add("--kernel-release=" + kernelRelease);

        // Core device binds
        flags.add("--bind=/dev");
        flags.add("--bind=/dev/urandom:/dev/random");
        flags.add("--bind=/proc");
        flags.add("--bind=/proc/self/fd:/dev/fd");
        flags.add("--bind=/sys");

        // Fake /proc entries
        flags.add("--bind=" + procFakes + "/loadavg:/proc/loadavg");
        flags.add("--bind=" + procFakes + "/stat:/proc/stat");
        flags.add("--bind=" + procFakes + "/uptime:/proc/uptime");
        flags.add("--bind=" + procFakes + "/version:/proc/version");
        flags.add("--bind=" + procFakes + "/vmstat:/proc/vmstat");
        flags.add("--bind=" + procFakes + "/cap_last_cap:/proc/sys/kernel/cap_last_cap");
        flags.add("--bind=" + procFakes + "/max_user_watches:/proc/sys/fs/inotify/max_user_watches");
        flags.add("--bind=" + procFakes + "/fips_enabled:/proc/sys/crypto/fips_enabled");

        // Shared memory
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

        // Guest environment via env -i
        File talloc = ensureLibTalloc();
        String ldLibraryPath = joinPaths(talloc.getParent(), mConfigDir, mNativeLibDir, mNativeRuntimeDir);
        flags.addAll(java.util.Arrays.asList(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "LD_LIBRARY_PATH=" + ldLibraryPath,
            "/bin/bash", "-c",
            command
        ));

        return flags.toArray(new String[0]);
    }

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

            // Wait with adaptive timeout - only check max wall time, not idle timeout
            // This allows long-running commands like pip install to continue even without output
            long startTime = System.currentTimeMillis();
            long maxWallTime = timeoutSec * 3 * 1000L;

            while (process.isAlive()) {
                long now = System.currentTimeMillis();
                if (now - startTime > maxWallTime) {
                    process.destroyForcibly();
                    return new ChrootManager.CommandResult(false, stdout.toString(),
                        "Proot 命令超时 (wall " + (maxWallTime/1000) + "s)", -1);
                }
                // Don't kill for idle timeout - pip install can take minutes without output
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

    /** Start AstrBot inside PRoot in background.
     *
     * CRITICAL ARCHITECTURE: Unlike chroot mode where `su -c` exits and the child
     * process survives, proot is a ptrace-based container — when the proot parent
     * process exits, ALL traced child processes die (or with --kill-on-exit, are
     * explicitly killed). Therefore, we must keep the proot process alive as a
     * daemon for as long as AstrBot needs to run.
     *
     * Strategy:
     * 1. Start proot in background with a long-running `sleep infinity` guard.
     *    The bash command launches AstrBot in background, then sleeps forever.
     *    This keeps proot alive without consuming CPU.
     * 2. Save the proot host PID to proot-gateway.pid for later cleanup.
     * 3. Verify AstrBot actually started by checking its PID from the host side.
     */
    public ChrootManager.CommandResult startAstrBot() {
        // Ensure home and tmp dirs
        new File(mHomeDir).mkdirs();
        new File(mTmpDir).mkdirs();

        // Kill any existing process first
        stopAstrBot();

        // Use venv python if available
        String pythonBin = "python3";
        ChrootManager.CommandResult venvCheck = runInProot(
            "test -f /root/astrbot/venv/bin/python3 && echo venv_ok", 5);
        if (venvCheck.success() && venvCheck.stdout().trim().equals("venv_ok")) {
            pythonBin = "/root/astrbot/venv/bin/python3";
        }

        // Delete old PID file so we can detect a fresh write
        new File(getAstrBotPidFile()).delete();

        // The command inside proot: start AstrBot in background, then sleep forever.
        // Simple and reliable — no startup detection inside proot (proot's ptrace
        // can interfere with kill -0, file writes may be delayed by filesystem cache).
        // We detect startup from the Java side by checking the PID file.
        final String startCmd =
            "cd /root/astrbot && " +
            "nohup " + pythonBin + " main.py >> /root/astrbot/astrbot.log 2>&1 & " +
            "echo $! > /root/astrbot/astrbot.pid && " +
            "exec /bin/sleep infinity"; // Keep proot alive forever

        String[] cmd = buildGatewayCommand(startCmd);
        java.util.Map<String, String> env = prootEnv();

        Log.i(TAG, "Starting AstrBot with gateway proot command");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            // Start proot in background — do NOT waitFor()!
            Process process = pb.start();
            mProotGatewayProcess = process;

            // Save the host-side proot PID for later cleanup
            long prootPid = getProcessPid(process);
            if (prootPid > 0) {
                writeFile(new File(mProotPidFile), String.valueOf(prootPid));
                Log.i(TAG, "Proot gateway process PID: " + prootPid);
            }

            // Drain proot output in background to prevent pipe blocking
            Thread drainThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    while (reader.readLine() != null) {}
                } catch (Exception ignored) {}
            });
            drainThread.setDaemon(true);
            drainThread.start();

            // Wait for AstrBot PID file to appear and process to be alive.
            // Detection is done entirely from the Java/host side using Os.kill(pid, 0)
            // which is immune to proot's ptrace interference.
            boolean started = false;
            String detail = "";
            File pidFile = new File(getAstrBotPidFile());

            for (int i = 0; i < 30; i++) { // 30 × 1s = 30s max wait
                try { Thread.sleep(1000); } catch (Exception ignored) {}

                if (pidFile.exists() && pidFile.length() > 0) {
                    try {
                        int pid = Integer.parseInt(
                            new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim());
                        if (pid > 0 && isPidAlive(pid)) {
                            started = true;
                            Log.i(TAG, "AstrBot started successfully, PID=" + pid);
                            break;
                        } else {
                            detail = "PID " + pid + " not alive";
                        }
                    } catch (NumberFormatException e) {
                        detail = "Invalid PID in file";
                    }
                }
            }

            if (!started) {
                Log.e(TAG, "AstrBot startup verification failed: " + detail);
                // Read log for details
                try {
                    File logFile = new File(mRootfsDir, "root/astrbot/astrbot.log");
                    if (logFile.exists()) {
                        detail = readTail(logFile.getAbsolutePath(), 20);
                    }
                } catch (Exception e) {
                    detail = e.getMessage();
                }
            }

            return new ChrootManager.CommandResult(started, started ? "started" : "", detail, started ? 0 : 1);

        } catch (Exception e) {
            Log.e(TAG, "startAstrBot failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "", e.getMessage(), -1);
        }
    }

    /**
     * Get the PID of a Java Process object on Android/Linux.
     * Uses reflection to access the internal pid field.
     */
    private long getProcessPid(Process process) {
        try {
            // Android's ProcessImpl stores pid in a field called "pid"
            java.lang.reflect.Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(process);
        } catch (NoSuchFieldException e) {
            // Some Android versions use a different field name
            try {
                java.lang.reflect.Field pidField = process.getClass().getDeclaredField("mPid");
                pidField.setAccessible(true);
                return pidField.getInt(process);
            } catch (Exception e2) {
                Log.w(TAG, "Could not get process PID: " + e2.getMessage());
                return -1;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get process PID via reflection: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Check if a process with the given PID is alive.
     * Uses android.system.Os.kill(pid, 0) — signal 0 doesn't kill,
     * just checks if the process exists.
     */
    private boolean isPidAlive(int pid) {
        if (pid <= 0) return false;
        try {
            Os.kill(pid, 0); // Signal 0 = existence check
            return true;
        } catch (ErrnoException e) {
            // ESRCH = no such process
            if (e.errno == OsConstants.ESRCH) return false;
            // EPERM = process exists but we can't signal it (still alive)
            if (e.errno == OsConstants.EPERM) return true;
            return false;
        }
    }

    /**
     * Kill a process by PID. Tries SIGTERM first, then SIGKILL.
     */
    private void killPid(int pid) {
        if (pid <= 0) return;
        try {
            Os.kill(pid, 9); // SIGKILL
            Log.i(TAG, "Killed process " + pid);
        } catch (ErrnoException e) {
            Log.w(TAG, "Failed to kill PID " + pid + ": " + e.getMessage());
        }
    }

    /** Stop AstrBot in PRoot mode.
     * Kills both the AstrBot process inside proot AND the proot parent process.
     * Without killing proot, it would stay alive as a zombie consuming the
     * sleep infinity guard.
     */
    public ChrootManager.CommandResult stopAstrBot() {
        // Step 1: Kill the proot parent process — this also kills all proot-traced children
        // (AstrBot). This is the most reliable way to stop everything.
        try {
            // Kill the Java Process object if we have it
            if (mProotGatewayProcess != null) {
                mProotGatewayProcess.destroyForcibly();
                try { mProotGatewayProcess.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                mProotGatewayProcess = null;
            }

            // Also kill by PID file (survives app restart)
            File prootPidFile = new File(mProotPidFile);
            if (prootPidFile.exists()) {
                try {
                    int prootPid = Integer.parseInt(
                        new String(java.nio.file.Files.readAllBytes(prootPidFile.toPath())).trim());
                    if (prootPid > 0 && isPidAlive(prootPid)) {
                        killPid(prootPid);
                        Log.i(TAG, "Killed proot process " + prootPid);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to kill proot by PID: " + e.getMessage());
                }
                prootPidFile.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error killing proot process: " + e.getMessage());
        }

        // Step 2: Kill any AstrBot/python processes that might still be running
        // (fallback in case proot didn't clean them up)
        try {
            File pidFile = new File(getAstrBotPidFile());
            if (pidFile.exists()) {
                try {
                    int pid = Integer.parseInt(
                        new String(java.nio.file.Files.readAllBytes(pidFile.toPath())).trim());
                    if (pid > 0 && isPidAlive(pid)) {
                        killPid(pid);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to kill AstrBot by PID: " + e.getMessage());
                }
                pidFile.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "stopAstrBot cleanup error: " + e.getMessage());
        }

        return new ChrootManager.CommandResult(true, "stopped", "", 0);
    }

    // ─── SSH Service (PRoot mode) ───

    /** Start SSH service in PRoot mode on port 22.
     *
     * Same architecture as startAstrBot(): proot must stay alive as a daemon.
     * Without it, --kill-on-exit in runInProot() would kill dropbear when proot exits.
     */
    public ChrootManager.CommandResult startSshService() {
        // Kill any existing SSH proot first
        stopSshService();

        // Ensure dropbear is installed
        runInProot("which dropbear >/dev/null 2>&1 || apt-get install -y dropbear-bin >/dev/null 2>&1", 60);

        // Ensure root password is set (dropbear needs it for password auth)
        // Always set the password to ensure it's correct
        runInProot(
            "echo 'root:" + RbotConstants.DEFAULT_SSH_PASSWORD + "' | chpasswd 2>/dev/null; " +
            "chmod 600 /etc/shadow 2>/dev/null", 10);

        String setupCmd =
            "mkdir -p /etc/dropbear && " +
            "[ -f /etc/dropbear/dropbear_rsa_host_key ] || dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ecdsa_host_key ] || dropbearkey -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key 2>/dev/null; " +
            "[ -f /etc/dropbear/dropbear_ed25519_host_key ] || dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key 2>/dev/null; " +
            "pkill -x dropbear 2>/dev/null; sleep 1; " +
            "dropbear -r /etc/dropbear/dropbear_rsa_host_key " +
            "-r /etc/dropbear/dropbear_ecdsa_host_key " +
            "-r /etc/dropbear/dropbear_ed25519_host_key " +
            "-p " + SSH_PORT + " -R && " +
            "echo dropbear_started > /root/.rbot-ssh-result && " +
            "exec /bin/sleep infinity"; // Keep proot alive

        // Use gateway command (no --kill-on-exit)
        String[] cmd = buildGatewayCommand(setupCmd);
        java.util.Map<String, String> env = prootEnv();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            mProotSshProcess = process;

            // Save SSH proot PID
            long sshPid = getProcessPid(process);
            if (sshPid > 0) {
                writeFile(new File(mProotSshPidFile), String.valueOf(sshPid));
            }

            // Drain output
            Thread drainThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    while (reader.readLine() != null) {}
                } catch (Exception ignored) {}
            });
            drainThread.setDaemon(true);
            drainThread.start();

            // Wait for startup result via marker file
            File sshMarker = new File(mRootfsDir, "/root/.rbot-ssh-result");
            boolean started = false;
            for (int i = 0; i < 20; i++) { // 10 seconds max
                try { Thread.sleep(500); } catch (Exception ignored) {}
                if (sshMarker.exists() && sshMarker.length() > 0) {
                    String result = new String(java.nio.file.Files.readAllBytes(sshMarker.toPath())).trim();
                    started = result.contains("dropbear_started");
                    break;
                }
            }
            sshMarker.delete();

            return new ChrootManager.CommandResult(started, started ? "dropbear_started" : "", "", started ? 0 : 1);
        } catch (Exception e) {
            Log.e(TAG, "startSshService failed: " + e.getMessage());
            return new ChrootManager.CommandResult(false, "", e.getMessage(), -1);
        }
    }

    /** Stop SSH service in PRoot mode.
     * Kills the proot parent process which also kills dropbear inside.
     */
    public ChrootManager.CommandResult stopSshService() {
        // Kill proot SSH process
        if (mProotSshProcess != null) {
            mProotSshProcess.destroyForcibly();
            try { mProotSshProcess.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            mProotSshProcess = null;
        }

        // Kill by PID file
        File sshPidFile = new File(mProotSshPidFile);
        if (sshPidFile.exists()) {
            try {
                int sshPid = Integer.parseInt(
                    new String(java.nio.file.Files.readAllBytes(sshPidFile.toPath())).trim());
                if (sshPid > 0 && isPidAlive(sshPid)) {
                    killPid(sshPid);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to kill SSH proot by PID: " + e.getMessage());
            }
            sshPidFile.delete();
        }

        return new ChrootManager.CommandResult(true, "stopped", "", 0);
    }

    /** Check if SSH is running in PRoot mode.
     * Checks if the proot SSH process is alive.
     */
    public boolean isSshRunning() {
        // Check Java Process object
        if (mProotSshProcess != null && mProotSshProcess.isAlive()) {
            return true;
        }

        // Check via PID file
        File sshPidFile = new File(mProotSshPidFile);
        if (sshPidFile.exists()) {
            try {
                int sshPid = Integer.parseInt(
                    new String(java.nio.file.Files.readAllBytes(sshPidFile.toPath())).trim());
                return isPidAlive(sshPid);
            } catch (Exception e) {
                Log.w(TAG, "isSshRunning: PID check failed: " + e.getMessage());
            }
        }
        return false;
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
            "Acquire::http::Timeout \"30\";\n" +
            "Acquire::https::Timeout \"30\";\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "APT::Get::Update::AllowUnauthenticated \"true\";\n" +
            "Dpkg::Use-Pty \"0\";\n" +
            "Dpkg::Options { \"--force-confnew\"; \"--force-overwrite\"; \"--force-depends\"; };\n");

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

        // Clear any old mirror configs from sources.list.d (e.g. Ubuntu official mirrors)
        File sourcesListD = new File(mRootfsDir, "/etc/apt/sources.list.d");
        if (sourcesListD.exists()) {
            java.io.File[] oldFiles = sourcesListD.listFiles();
            if (oldFiles != null) {
                for (java.io.File f : oldFiles) {
                    f.delete();
                    Log.i(TAG, "Deleted old source: " + f.getName());
                }
            }
        }

        // Replace Ubuntu default mirrors with ARM64 ports repository
        // ARM64 requires ubuntu-ports, not regular ubuntu!
        writeFile(new File(mRootfsDir, "/etc/apt/sources.list"),
            "# Ubuntu 24.04 Noble - ARM64 ports (auto-configured by rbot)\n"
            + "# NOTE: ARM64 requires ports.ubuntu.com, not archive.ubuntu.com\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-backports main restricted universe multiverse\n");
    }

    /** Update sources.list to use Tsinghua mirror — call this before apt update
     *  even if rootfs already existed, to replace any stale official mirrors */
    public void updateSourcesList() {
        // Clear old mirror configs
        File sourcesListD = new File(mRootfsDir, "/etc/apt/sources.list.d");
        if (sourcesListD.exists()) {
            java.io.File[] oldFiles = sourcesListD.listFiles();
            if (oldFiles != null) {
                for (java.io.File f : oldFiles) {
                    f.delete();
                }
            }
        }
        // ARM64 must use ubuntu-ports repository, not regular ubuntu!
        // https://ports.ubuntu.com/ is the official ARM64 repo
        // Use Aliyun mirror for faster download in China
        writeFile(new File(mRootfsDir, "/etc/apt/sources.list"),
            "# Ubuntu 24.04 Noble - ARM64 ports (auto-configured by rbot)\n"
            + "# NOTE: ARM64 requires ports.ubuntu.com, not archive.ubuntu.com\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n"
            + "deb https://mirrors.aliyun.com/ubuntu-ports/ noble-backports main restricted universe multiverse\n");
        Log.i(TAG, "sources.list updated to Aliyun ubuntu-ports mirror for ARM64");
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

    // ─── Backup & Restore (PRoot mode) ───

    /**
     * Create a backup of AstrBot data directory in PRoot mode.
     * Uses tar to preserve symlinks and permissions.
     * Backup: /sdcard/rbot/backups/astrbot_data_{timestamp}.tar.gz
     * @param callback Progress callback for UI updates
     * @return Backup file path on success, null on failure
     */
    public String backupAstrBotData(ChrootManager.ProgressCallback callback) {
        if (callback != null) callback.onProgress("准备备份...");

        // Ensure backup directory exists on host side (need root for sdcard)
        CommandResult mkdirResult = ChrootManager.execRoot("mkdir -p " + RbotConstants.BACKUP_DIR);
        if (!mkdirResult.success()) {
            if (callback != null) callback.onError("无法创建备份目录");
            return null;
        }

        // Generate timestamped backup file
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String backupFile = RbotConstants.BACKUP_DIR + "/astrbot_data_" + timestamp + ".tar.gz";

        if (callback != null) callback.onProgress("正在打包数据（排除虚拟环境和缓存）...");

        // Use proot to create tar archive inside PRoot, then copy to sdcard
        // First, create tar.gz inside proot rootfs
        CommandResult tarResult = runInProot(
            "cd /root/astrbot && tar czf /root/astrbot_backup.tar.gz " +
            "--exclude='./venv' " +
            "--exclude='./__pycache__' " +
            "--exclude='./.git' " +
            "--exclude='./astrbot.log' " +
            "--exclude='./astrbot-debug.log' " +
            "--exclude='./astrbot.pid' " +
            ". 2>&1", 300);

        if (!tarResult.success()) {
            if (callback != null) callback.onError("打包失败: " + tarResult.stderr());
            return null;
        }

        // Copy the backup file to sdcard (need root for sdcard access)
        CommandResult copyResult = ChrootManager.execRoot(
            "cp " + mRootfsDir + "/root/astrbot/astrbot_backup.tar.gz '" + backupFile + "' && " +
            "rm -f " + mRootfsDir + "/root/astrbot/astrbot_backup.tar.gz", 60);

        if (!copyResult.success()) {
            if (callback != null) callback.onError("复制备份文件失败: " + copyResult.stderr());
            return null;
        }

        // Verify backup file was created and has content
        CommandResult checkResult = ChrootManager.execRoot(
            "test -f '" + backupFile + "' && stat -c '%s' '" + backupFile + "' || echo missing");
        if (!checkResult.success() || checkResult.stdout().trim().equals("missing") || checkResult.stdout().trim().equals("0")) {
            if (callback != null) callback.onError("备份文件无效或为空");
            return null;
        }

        String sizeInfo = checkResult.stdout().trim();
        if (callback != null) callback.onProgress("备份完成: " + backupFile + " (" + sizeInfo + " bytes)");
        return backupFile;
    }

    /**
     * Restore AstrBot data from a backup tar.gz file in PRoot mode.
     * @param backupFile Full path to the backup .tar.gz file
     * @param callback Progress callback for UI updates
     * @return true on failure, false on success
     */
    public boolean restoreAstrBotData(String backupFile, ChrootManager.ProgressCallback callback) {
        if (callback != null) callback.onProgress("检查备份...");

        // Verify backup file exists
        CommandResult checkResult = ChrootManager.execRoot("test -f '" + backupFile + "' && echo exists");
        if (!checkResult.success() || !checkResult.stdout().trim().equals("exists")) {
            if (callback != null) callback.onError("备份文件不存在");
            return true;
        }

        if (callback != null) callback.onProgress("停止 AstrBot...");
        stopAstrBot();

        if (callback != null) callback.onProgress("正在恢复数据...");

        // Copy backup file to proot rootfs
        CommandResult copyResult = ChrootManager.execRoot(
            "cp '" + backupFile + "' " + mRootfsDir + "/root/astrbot/astrbot_restore.tar.gz", 60);
        if (!copyResult.success()) {
            if (callback != null) callback.onError("复制备份文件失败: " + copyResult.stderr());
            return true;
        }

        // Extract tar.gz into astrbot directory inside proot
        CommandResult tarResult = runInProot(
            "cd /root/astrbot && rm -rf ./data ./config ./plugins 2>/dev/null; " +
            "tar xzf /root/astrbot_restore.tar.gz && " +
            "rm -f /root/astrbot_restore.tar.gz", 300);

        if (!tarResult.success()) {
            if (callback != null) callback.onError("恢复失败: " + tarResult.stderr());
            return true;
        }

        // Clean up restore file
        ChrootManager.execRoot("rm -f " + mRootfsDir + "/root/astrbot/astrbot_restore.tar.gz");

        if (callback != null) callback.onProgress("恢复完成");
        return false;
    }

    /**
     * List all available backup files in PRoot mode.
     * @return Array of backup file paths, sorted by modification time (newest first)
     */
    public static String[] listBackups() {
        CommandResult result = ChrootManager.execRoot(
            "ls -1t " + RbotConstants.BACKUP_DIR + "/astrbot_data_*.tar.gz 2>/dev/null || echo none");

        if (!result.success() || result.stdout().trim().equals("none")) {
            return new String[0];
        }

        return result.stdout().trim().split("\n");
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
        File file = new File(filePath);
        if (!file.exists()) {
            Log.w(TAG, "computeMd5: file not found: " + filePath);
            return null;
        }
        if (!file.canRead()) {
            Log.w(TAG, "computeMd5: file not readable: " + filePath);
            return null;
        }
        
        Log.i(TAG, "Computing MD5 for: " + filePath + " (size: " + file.length() + " bytes)");
        long startTime = System.currentTimeMillis();
        
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                Log.i(TAG, "MD5 computed " + totalBytes + " bytes in " + 
                    (System.currentTimeMillis() - startTime) + "ms");
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String result = sb.toString();
            Log.i(TAG, "MD5 result: " + result);
            return result;
        } catch (java.io.FileNotFoundException e) {
            Log.e(TAG, "computeMd5: file not found during read: " + filePath);
            return null;
        } catch (java.io.IOException e) {
            Log.e(TAG, "computeMd5: IO error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "computeMd5: unexpected error: " + e.getClass().getName() + ": " + e.getMessage());
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
