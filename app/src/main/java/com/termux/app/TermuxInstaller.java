package com.termux.app;

import android.app.ActivityManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    private static final String BOTDROP_APT_SOURCE_LINE =
        "deb [trusted=yes] https://zhixianio.github.io/botdrop-packages/ stable main";
    private static final String BOTDROP_GITLAB_APT_SOURCE_LINE =
        "deb [trusted=yes] https://lay2dev.gitlab.io/botdrop-packages/ stable main";
    private static final String BOTDROP_APT_SOURCES_LIST = TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list";
    private static final String BOTDROP_APT_SOURCES_LIST_D = TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list.d";
    private static final String BOTDROP_APT_LIST_FILE = BOTDROP_APT_SOURCES_LIST_D + "/botdrop.list";

    /** Performs bootstrap setup if necessary. */
    public static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;
        String openclawVersion = activity.getSharedPreferences(
            "botdrop_settings", Context.MODE_PRIVATE)
            .getString("openclaw_install_version", "openclaw@latest");

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                // Upgrade path: refresh BotDrop scripts and force BotDrop APT sources.
                createBotDropScripts(activity, openclawVersion);
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                // Acquire a WakeLock to prevent CPU sleep during bootstrap extraction
                PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.botdrop:bootstrap");
                wakeLock.acquire(10 * 60 * 1000L); // 10 min timeout as safety net
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                    // Fix shebang paths in bash scripts that were built for com.termux but
                                    // are now running under BotDrop (app.botdrop).
                                    // Rewrite #!/data/data/com.termux/... to #!/data/data/app.botdrop/...
                                    if (zipEntryName.equals("bin/proot-distro")) {
                                        StringBuilder sb = new StringBuilder();
                                        Error err = FileUtils.readTextFromFile(LOG_TAG, targetFile.getAbsolutePath(), null, sb, false);
                                        if (err == null && sb.length() > 0 && sb.toString().startsWith("#!/data/data/com.termux/")) {
                                            String fixedContent = sb.toString().replaceFirst(
                                                "#!/data/data/com\\.termux/files/usr/bin/bash",
                                                "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash"
                                            );
                                            // Also replace all internal hardcoded com.termux paths
                                            fixedContent = fixedContent.replace(
                                                "/data/data/com.termux/files/usr",
                                                TermuxConstants.TERMUX_PREFIX_DIR_PATH
                                            );
                                            FileUtils.writeTextToFile(LOG_TAG, targetFile.getAbsolutePath(), null, fixedContent, false);
                                            Logger.logInfo(LOG_TAG, "Fixed shebang and paths in bin/proot-distro");
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    // Save a copy of the bootstrap zip so install.sh can re-extract
                    // specific files (like proot) later for path remapping fixes.
                    File bootstrapZipFile = new File(TERMUX_STAGING_PREFIX_DIR, "var/lib/bootstrap-aarch64.zip");
                    File bootstrapZipParent = new File(TERMUX_STAGING_PREFIX_DIR, "var/lib");
                    if (!bootstrapZipParent.exists()) bootstrapZipParent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(bootstrapZipFile)) {
                        fos.write(zipBytes);
                    }
                    Logger.logInfo(LOG_TAG, "Saved bootstrap zip to " + bootstrapZipFile.getAbsolutePath() + " (" + (zipBytes.length / 1024 / 1024) + " MB).");

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // Create BotDrop install script and environment
                    createBotDropScripts(activity, openclawVersion);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    if (wakeLock.isHeld()) wakeLock.release();
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    /**
     * Creates the BotDrop installation script and environment setup.
     *
     * Creates:
     * 1. $PREFIX/share/botdrop/install.sh — standalone installer with structured output
     *    (called by both GUI ProcessBuilder and terminal profile.d)
     * 2. $PREFIX/etc/profile.d/botdrop-env.sh — environment (alias, sshd auto-start)
     *
     * The install.sh outputs structured lines for GUI parsing:
     *   BOTDROP_STEP:N:START:message
     *   BOTDROP_STEP:N:DONE
     *   BOTDROP_COMPLETE
     *   BOTDROP_ERROR:message
     */
    public static void createBotDropScripts(Context context, String ignoredOpenclawVersion) {
        try {
            // --- 1. Create install.sh ---
            File botdropDir = new File(TERMUX_PREFIX_DIR_PATH + "/share/botdrop");
            if (!botdropDir.exists()) {
                botdropDir.mkdirs();
            }

            File installScript = new File(botdropDir, "install.sh");
            String installContent =
                "#!" + com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
                "# BotDrop AstrBot install script — single source of truth\n" +
                "# Called by: GUI (ProcessBuilder) and terminal (profile.d)\n" +
                "# Outputs structured lines for GUI progress parsing.\n\n" +
                "LOGFILE=\"$HOME/botdrop-install.log\"\n" +
                "exec > >(tee -a \"$LOGFILE\") 2>&1\n" +
                "echo \"=== BotDrop AstrBot install started: $(date) ===\"\n\n" +
                "MARKER=\"$HOME/.botdrop_installed\"\n" +
                "ASTRBOT_ROOT=\"$HOME/astrbot\"\n\n" +
                "if [ -f \"$MARKER\" ]; then\n" +
                "    echo \"BOTDROP_ALREADY_INSTALLED\"\n" +
                "    exit 0\n" +
                "fi\n\n" +
                "# Step 0: Setup Termux environment\n" +
                "echo \"BOTDROP_STEP:0:START:Setting up Termux environment\"\n" +
                buildBotDropAptSourceScript() +
                "chmod +x $PREFIX/bin/* 2>/dev/null\n" +
                "# Generate SSH host keys if missing\n" +
                "mkdir -p $PREFIX/var/empty\n" +
                "mkdir -p $HOME/.ssh\n" +
                "touch $HOME/.ssh/authorized_keys\n" +
                "chmod 700 $HOME/.ssh\n" +
                "chmod 600 $HOME/.ssh/authorized_keys\n" +
                "for a in rsa ecdsa ed25519; do\n" +
                "    KEYFILE=\"$PREFIX/etc/ssh/ssh_host_${a}_key\"\n" +
                "    test ! -f \"$KEYFILE\" && ssh-keygen -N '' -t $a -f \"$KEYFILE\" >/dev/null 2>&1\n" +
                "done\n" +
                "# Generate random SSH password\n" +
                "SSH_PASS=$(head -c 12 /dev/urandom | base64 | tr -d '/+=' | head -c 12)\n" +
                "printf '%s\\n%s\\n' \"$SSH_PASS\" \"$SSH_PASS\" | passwd >/dev/null 2>&1\n" +
                "echo \"$SSH_PASS\" > \"$HOME/.ssh_password\"\n" +
                "chmod 600 \"$HOME/.ssh_password\"\n" +
                "# BotDrop APT sources were already written above.\n" +
                "# Start sshd (port 8022)\n" +
                "if ! pgrep -x sshd >/dev/null 2>&1; then\n" +
                "    sshd 2>/dev/null\n" +
                "fi\n" +
                "echo \"BOTDROP_STEP:0:DONE\"\n\n" +
                "# Step 1: Install Termux packages needed for AstrBot\n" +
                "echo \"BOTDROP_STEP:1:START:Installing proot-distro, git, curl\"\n" +
                "# Clean up leftover rootfs from previous failed installs to free space\n" +
                "rm -rf \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" 2>/dev/null\n" +
                "rm -rf \"$PREFIX/var/lib/proot-distro/cache\" 2>/dev/null\n" +
                "# Pre-emptively remove packages with problematic dependencies\n" +
                "# dpkg-perl from Termux repo depends on clang which may conflict with BotDrop packages\n" +
                "dpkg -r --force-remove-reinstreq dpkg-scanpackages 2>/dev/null || true\n" +
                "dpkg -r --force-remove-reinstreq dpkg-perl 2>/dev/null || true\n" +
                "apt update 2>&1 | tee -a \"$LOGFILE\" || true\n" +
                "apt --fix-broken install -y 2>&1 | tee -a \"$LOGFILE\" || true\n" +
                "# Install git & curl from BotDrop source only (path-compatible with app.botdrop)\n" +
                "DEBIAN_FRONTEND=noninteractive apt install -y -o Dir::Etc::sourcelist=\"$PREFIX/etc/apt/sources.list.d/botdrop.list\" -o Dir::Etc::sourceparts=\"-\" git curl 2>&1 | tee -a \"$LOGFILE\" || true\n" +
                "# Create a minimal 'file' replacement since proot-distro needs it to detect\n" +
                "# rootfs archive types. proot-distro only uses 'file -b --mime-type' syntax.\n" +
                "cat > \"$PREFIX/bin/file\" << 'FILEEOF'\n" +
                "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash\n" +
                "# Minimal 'file' replacement for proot-distro\n" +
                "if [ \"$1\" = \"-b\" ] && [ \"$2\" = \"--mime-type\" ]; then\n" +
                "    f=\"$3\"\n" +
                "    case \"$(head -c 6 \"$f\" 2>/dev/null | cat -v)\" in\n" +
                "        */xz\\ *)    echo 'application/x-xz' ;;\n" +
                "        PK\\ \\03\\ *) echo 'application/zip' ;;\n" +
                "        H4sI\\ *)    echo 'application/gzip' ;;\n" +
                "        \\x1f\\x8b\\ *) echo 'application/gzip' ;;\n" +
                "        *)           echo 'application/octet-stream' ;;\n" +
                "    esac\n" +
                "elif [ \"$1\" = \"-b\" ] && [ \"$2\" = \"-i\" ]; then\n" +
                "    f=\"$3\"\n" +
                "    case \"$(head -c 6 \"$f\" 2>/dev/null | cat -v)\" in\n" +
                "        */xz\\ *)    echo 'application/x-xz' ;;\n" +
                "        PK\\ \\03\\ *) echo 'application/zip' ;;\n" +
                "        H4sI\\ *)    echo 'application/gzip' ;;\n" +
                "        \\x1f\\x8b\\ *) echo 'application/gzip' ;;\n" +
                "        *)           echo 'application/octet-stream' ;;\n" +
                "    esac\n" +
                "else\n" +
                "    echo \"$1: directory\" 2>/dev/null || echo \"$1: regular file\"\n" +
                "fi\n" +
                "FILEEOF\n" +
                "chmod +x \"$PREFIX/bin/file\"\n" +
                "echo 'Created minimal file replacement'\n" +
                "# Install proot-distro: official deb uses /data/data/com.termux paths\n" +
                "# which conflict with app.botdrop, so we extract and remap manually\n" +
                "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
                "    echo 'Installing proot-distro (manual deb remap)...'\n" +
                "    PROOT_DEB=\"$TMPDIR/proot-distro.deb\"\n" +
                "    curl -sL 'https://packages.termux.dev/apt/termux-main/pool/main/p/proot-distro/proot-distro_4.38.0_all.deb' -o \"$PROOT_DEB\"\n" +
                "    if [ -f \"$PROOT_DEB\" ] && [ $(wc -c < \"$PROOT_DEB\") -gt 1000 ]; then\n" +
                "        EXTRACT_DIR=\"$TMPDIR/proot-extract\"\n" +
                "        rm -rf \"$EXTRACT_DIR\"\n" +
                "        mkdir -p \"$EXTRACT_DIR\"\n" +
                "        # dpkg-deb extracts to com.termux paths; we remap to app.botdrop\n" +
                "        dpkg-deb -x \"$PROOT_DEB\" \"$EXTRACT_DIR\" 2>&1 | tee -a \"$LOGFILE\"\n" +
                "        if [ -d \"$EXTRACT_DIR/data/data/com.termux/files/usr\" ]; then\n" +
                "            cp -r \"$EXTRACT_DIR/data/data/com.termux/files/usr/\"* \"$PREFIX/\" 2>&1 | tee -a \"$LOGFILE\"\n" +
                "            echo 'proot-distro files installed to $PREFIX'\n" +
                "        else\n" +
                "            echo 'WARNING: unexpected deb layout, trying apt fallback'\n" +
                "            DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends proot-distro 2>&1 | tee -a \"$LOGFILE\" || true\n" +
                "        fi\n" +
                "        rm -rf \"$EXTRACT_DIR\"\n" +
                "    else\n" +
                "        echo 'WARNING: proot-distro deb download failed, trying apt fallback'\n" +
                "        DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends proot-distro 2>&1 | tee -a \"$LOGFILE\" || true\n" +
                "    fi\n" +
                "    rm -f \"$PROOT_DEB\"\n" +
                "fi\n" +
                "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
                "    echo \"BOTDROP_ERROR:proot-distro installation failed\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "# Fix ALL hardcoded /data/data/com.termux paths in proot-distro files.\n" +
                "# The official deb is built for com.termux but we run as app.botdrop.\n" +
                "# We must fix shebangs AND internal path references in every script.\n" +
                "PROOT_DISTRO_FILES=$(find \"$PREFIX/bin\" \"$PREFIX/libexec\" \"$PREFIX/etc/proot-distro\" \"$PREFIX/share/proot-distro\" -type f 2>/dev/null || true)\n" +
                "for f in $PROOT_DISTRO_FILES; do\n" +
                "    if head -1 \"$f\" 2>/dev/null | grep -q '^#!/data/data/com.termux/'; then\n" +
                "        sed -i '1s|#!/data/data/com\\.termux/files/usr|#!'\"$PREFIX\"'|' \"$f\"\n" +
                "        echo \"Fixed shebang in $(basename $f)\"\n" +
                "    fi\n" +
                "    # Replace all internal hardcoded com.termux paths with app.botdrop paths\n" +
                "    if grep -q '/data/data/com\\.termux/' \"$f\" 2>/dev/null; then\n" +
                "        sed -i 's|/data/data/com\\.termux/files/usr|'\"$PREFIX\"'|g' \"$f\"\n" +
                "        echo \"Fixed paths in $(basename $f)\"\n" +
                "    fi\n" +
                "done\n" +
                "# Fix cpu_arch unbound variable in proot-distro script (set -u at top, then $cpu_arch used without default)\n" +
                "# Add default cpu_arch=${cpu_arch:-$(uname -m)} right after any 'set -u' line\n" +
                "if [ -f \"$PREFIX/bin/proot-distro\" ]; then\n" +
                "    if ! grep -q \"cpu_arch=\\${cpu_arch:-\" \"$PREFIX/bin/proot-distro\" 2>/dev/null; then\n" +
                "        sed -i '/^set -u$/a cpu_arch=${cpu_arch:-$(uname -m)}' \"$PREFIX/bin/proot-distro\"\n" +
                "        echo \"Fixed cpu_arch default in proot-distro\"\n" +
                "    fi\n" +
                "fi\n" +
                "BS_ZIP=\"$PREFIX/var/lib/bootstrap-aarch64.zip\"\n" +
                "if [ -f \"$BS_ZIP\" ]; then\n" +
                "    echo 'Restoring proot binaries from bootstrap zip...'\n" +
                "    mkdir -p \"$TMPDIR/_bs\"\n" +
                "    unzip -oq \"$BS_ZIP\" 'bin/proot' 'bin/proot-distro' 'libexec/proot/loader' 'libexec/proot/loader32' -d \"$TMPDIR/_bs\" 2>/dev/null || true\n" +
                "    [ -f \"$TMPDIR/_bs/bin/proot\" ] && cp \"$TMPDIR/_bs/bin/proot\" \"$PREFIX/bin/proot\" && chmod 700 \"$PREFIX/bin/proot\" && echo 'Restored proot binary'\n" +
                "    [ -f \"$TMPDIR/_bs/libexec/proot/loader\" ] && mkdir -p \"$PREFIX/libexec/proot\" && cp \"$TMPDIR/_bs/libexec/proot/loader\" \"$PREFIX/libexec/proot/\" && chmod 700 \"$PREFIX/libexec/proot/loader\" && echo 'Restored libexec/proot/loader'\n" +
                "    [ -f \"$TMPDIR/_bs/libexec/proot/loader32\" ] && cp \"$TMPDIR/_bs/libexec/proot/loader32\" \"$PREFIX/libexec/proot/\" && chmod 700 \"$PREFIX/libexec/proot/loader32\" && echo 'Restored libexec/proot/loader32'\n" +
                "    rm -rf \"$TMPDIR/_bs\"\n" +
                "else\n" +
                "    echo 'WARNING: bootstrap zip not found, cannot restore proot binaries'\n" +
                "fi\n" +
                "# Create symlink bridge for any remaining /data/data/com.termux hardcoded refs.\n" +
                "# This is a safety net: even after sed fixes, some proot-distro plugins or\n" +
                "# sub-scripts may still reference /data/data/com.termux paths internally.\n" +
                "# On non-rooted devices this may fail silently (SELinux / app sandbox).\n" +
                "# That's OK because the sed path-rewrite above is the primary fix.\n" +
                "COMTERMUX_BASE='/data/data/com.termux/files/usr'\n" +
                "if mkdir -p \"$COMTERMUX_BASE/bin\" \"$COMTERMUX_BASE/libexec\" \"$COMTERMUX_BASE/etc\" 2>/dev/null; then\n" +
                "    ln -sfn \"$PREFIX/bin/bash\"         \"$COMTERMUX_BASE/bin/bash\"         2>/dev/null || true\n" +
                "    ln -sfn \"$PREFIX/bin/proot\"         \"$COMTERMUX_BASE/bin/proot\"         2>/dev/null || true\n" +
                "    ln -sfn \"$PREFIX/bin/proot-distro\"  \"$COMTERMUX_BASE/bin/proot-distro\"  2>/dev/null || true\n" +
                "    ln -sfn \"$PREFIX/libexec/proot\"     \"$COMTERMUX_BASE/libexec/proot\"     2>/dev/null || true\n" +
                "    if [ -d \"$PREFIX/etc/proot-distro\" ]; then\n" +
                "        ln -sfn \"$PREFIX/etc/proot-distro\" \"$COMTERMUX_BASE/etc/proot-distro\" 2>/dev/null || true\n" +
                "    fi\n" +
                "    echo 'Created /data/data/com.termux symlink bridge'\n" +
                "else\n" +
                "    echo 'WARNING: Cannot create /data/data/com.termux symlink bridge (no permission)'\n" +
                "    echo 'Relying on sed path-rewrite fix instead'\n" +
                "fi\n" +
                "echo \"BOTDROP_STEP:1:DONE\"\n\n" +
                "# Step 2: Extract Ubuntu rootfs using proot-distro's cached download.\n" +
                "# proot-distro install ubuntu fails during post-install on Android,\n" +
                "# so we use the cached rootfs tarball and extract it manually.\n" +
                "# Rootfs must go in internal storage (external storage doesn't support symlinks).\n" +
                "echo \"BOTDROP_STEP:2:START:Installing Ubuntu rootfs\"\n" +
                "ROOTFS_PARENT=\"$PREFIX/var/lib/proot-distro/installed-rootfs\"\n" +
                "ROOTFS_DIR=\"$ROOTFS_PARENT/ubuntu\"\n" +
                "# The tarball extracts to a subdirectory 'ubuntu-fs/' inside ROOTFS_DIR.\n" +
                "# proot -r expects the rootfs at ROOTFS_DIR directly, so we track the actual path.\n" +
                "ROOTFS_ACTUAL=\"$ROOTFS_DIR/ubuntu-fs\"\n" +
                "ROOTFS_MARKER=\"$ROOTFS_DIR/.botdrop-rootfs-ready\"\n" +
                "# Check if rootfs needs restructure: tar extracts to ubuntu-fs/ subdirectory,\n" +
                "# but proot-distro expects rootfs directly at ROOTFS_DIR (ubuntu/).\n" +
                "# If ubuntu-fs/ still exists, restructure is needed even with marker present.\n" +
                "NEEDS_RESTRC=0\n" +
                "if [ -f \"$ROOTFS_MARKER\" ] && [ ! -d \"$ROOTFS_ACTUAL\" ]; then\n" +
                "    echo \"BOTDROP_INFO:Ubuntu rootfs already installed, skipping...\"\n" +
                "elif [ -d \"$ROOTFS_ACTUAL\" ]; then\n" +
                "    # Rootfs at wrong nesting level (ubuntu-fs/ exists) - restructure needed\n" +
                "    echo \"BOTDROP_INFO:Restructuring existing rootfs (ubuntu-fs/ -> ubuntu/)...\"\n" +
                "    cp -r \"$ROOTFS_ACTUAL/.\" \"$ROOTFS_DIR/\"\n" +
                "    rm -rf \"$ROOTFS_ACTUAL\"\n" +
                "    echo \"BOTDROP_INFO:Rootfs restructured successfully.\"\n" +
                "    touch \"$ROOTFS_MARKER\"\n" +
                "else\n" +
                "    # === Download Ubuntu rootfs (3-tier fallback) ===\n" +
                "    # Clean up old rootfs cache in internal storage to free space\n" +
                "    rm -rf \"$PREFIX/var/lib/proot-distro/cache/ubuntu22_openclaw.tar.gz\" 2>/dev/null\n" +
                "    rm -rf \"$PREFIX/var/lib/proot-distro/cache/ubuntu-rootfs.tar.xz\" 2>/dev/null\n" +
                "    rm -rf \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" 2>/dev/null\n" +
                "    # Ensure sdcard cache directory exists\n" +
                "    mkdir -p \"" + TermuxConstants.BOTDROP_SDCARD_CACHE_DIR_PATH + "\"\n" +
                "    CACHE_TAR=\"\"\n" +
                "    # Tier 1: Use tar.gz directly from device storage (no copy needed)\n" +
                "    LOCAL_SRC=\"/storage/emulated/0/claw-apk/ubuntu22_openclaw.tar.gz\"\n" +
                "    if [ -f \"$LOCAL_SRC\" ]; then\n" +
                "        CACHE_TAR=\"$LOCAL_SRC\"\n" +
                "        echo \"BOTDROP_INFO:Using local rootfs: $CACHE_TAR\"\n" +
                "    fi\n" +
                "    # Tier 2: Check previously cached rootfs on sdcard (survives reinstalls)\n" +
                "    SDCARD_CACHE=\"" + TermuxConstants.BOTDROP_SDCARD_CACHE_DIR_PATH + "/ubuntu22_openclaw.tar.gz\"\n" +
                "    if [ -z \"$CACHE_TAR\" ] && [ -f \"$SDCARD_CACHE\" ]; then\n" +
                "        CACHE_TAR=\"$SDCARD_CACHE\"\n" +
                "        echo \"BOTDROP_INFO:Using sdcard cached rootfs: $CACHE_TAR\"\n" +
                "    fi\n" +
                "    # Tier 3: Download from GitHub, save to sdcard cache for future use\n" +
                "    if [ -z \"$CACHE_TAR\" ]; then\n" +
                "        echo \"BOTDROP_INFO:Downloading Ubuntu rootfs from GitHub...\"\n" +
                "        GITHUB_TAR=\"$SDCARD_CACHE\"\n" +
                "        curl -L --progress-bar \\\n" +
                "            'https://github.com/TermuxCHN/rootfs/releases/download/ubuntu2204/rootfs.tar.xz' \\\n" +
                "            -o \"$GITHUB_TAR\" 2>&1 | tee -a \"$LOGFILE\"\n" +
                "        if [ -f \"$GITHUB_TAR\" ] && [ $(wc -c < \"$GITHUB_TAR\") -gt 10485760 ]; then\n" +
                "            CACHE_TAR=\"$GITHUB_TAR\"\n" +
                "        else\n" +
                "            echo \"BOTDROP_ERROR:Ubuntu rootfs download failed\"\n" +
                "            rm -f \"$GITHUB_TAR\" 2>/dev/null\n" +
                "            exit 1\n" +
                "        fi\n" +
                "    fi\n" +
                "    # Also cache Tier 1 tarball to sdcard for future reinstalls\n" +
                "    if [ -f \"$LOCAL_SRC\" ] && [ ! -f \"$SDCARD_CACHE\" ]; then\n" +
                "        echo \"BOTDROP_INFO:Caching rootfs to sdcard for future use...\"\n" +
                "        cp \"$LOCAL_SRC\" \"$SDCARD_CACHE\" 2>/dev/null || true\n" +
                "    fi\n" +
                "    # === Extract rootfs to external storage ===\n" +
                "    mkdir -p \"$ROOTFS_DIR\"\n" +
                "    rm -rf \"$ROOTFS_ACTUAL\"\n" +
                "    echo \"BOTDROP_INFO:Extracting Ubuntu rootfs (this may take a while)...\"\n" +
                "    # ubuntu22_openclaw.tar.gz extracts as ./ (root), github tar.xz extracts as ubuntu-fs/\n" +
                "    if echo \"$CACHE_TAR\" | grep -q 'openclaw'; then\n" +
                "        # Complete rootfs: extracts directly into ROOTFS_DIR\n" +
                "        tar -xzf \"$CACHE_TAR\" -C \"$ROOTFS_DIR\" --checkpoint=1000 --checkpoint-action=echo=\"Extracting...\" 2>&1 | tee -a \"$LOGFILE\"\n" +
                "        # Verify key binary exists\n" +
                "        if [ -f \"$ROOTFS_DIR/usr/bin/env\" ]; then\n" +
                "            echo \"BOTDROP_INFO:Complete rootfs extracted (with /usr/bin/env).\"\n" +
                "        else\n" +
                "            echo \"BOTDROP_WARN:Rootfs missing /usr/bin/env, may be incomplete.\"\n" +
                "        fi\n" +
                "    else\n" +
                "        # GitHub tar.xz: extracts into ubuntu-fs/ subdirectory\n" +
                "        tar -xf \"$CACHE_TAR\" -C \"$ROOTFS_DIR\" --checkpoint=1000 --checkpoint-action=echo=\"Extracting...\" 2>&1 | tee -a \"$LOGFILE\"\n" +
                "        if [ -d \"$ROOTFS_ACTUAL\" ]; then\n" +
                "            echo \"BOTDROP_INFO:Restructuring rootfs (ubuntu-fs/ -> ubuntu/)...\"\n" +
                "            cp -r \"$ROOTFS_ACTUAL/.\" \"$ROOTFS_DIR/\"\n" +
                "            rm -rf \"$ROOTFS_ACTUAL\"\n" +
                "        else\n" +
                "            echo \"BOTDROP_ERROR:Rootfs extraction failed - ubuntu-fs/ not found\"\n" +
                "            exit 1\n" +
                "        fi\n" +
                "    fi\n" +
                "    touch \"$ROOTFS_MARKER\"\n" +
                "    echo \"BOTDROP_INFO:Ubuntu rootfs extracted successfully.\"\n" +
                "fi\n" +
                "echo \"BOTDROP_STEP:2:DONE\"\n\n" +
                "# Step 3: Install AstrBot inside Ubuntu\n" +
                "echo \"BOTDROP_STEP:3:START:Installing AstrBot in Ubuntu\"\n" +
                "# Write AstrBot install script to be executed inside Ubuntu\n" +
                "AstrBotScript=\"$PREFIX/tmp/astrbot_install.sh\"\n" +
                "cat > \"$AstrBotScript\" << 'ASTRBOT_EOF'\n" +
                "set -e\n" +
                "export DEBIAN_FRONTEND=noninteractive\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "# Fix apt/dpkg metadata if missing (happens when rootfs is from openclaw tarball)\n" +
                "mkdir -p /var/lib/apt/lists/partial /var/lib/dpkg\n" +
                "if [ ! -f /var/lib/dpkg/status ]; then\n" +
                "    touch /var/lib/dpkg/status\n" +
                "fi\n" +
                "apt update && apt install -y python3 python3-pip git curl || true\n" +
                "pip install uv 2>&1 | tail -3\n" +
                "if [ ! -d \"/root/astrbot\" ]; then\n" +
                "    git clone https://github.com/AstrBotDevs/AstrBot.git /root/astrbot 2>&1 | tail -5\n" +
                "fi\n" +
                "cd /root/astrbot\n" +
                "uv run main.py --version 2>&1 | head -3 || true\n" +
                "ASTRBOT_EOF\n" +
                "# Copy script into Ubuntu rootfs /tmp\n" +
                "UbuntuRootfs=\"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"\n" +
                "mkdir -p \"$UbuntuRootfs/tmp\"\n" +
                "cp \"$AstrBotScript\" \"$UbuntuRootfs/tmp/astrbot_install.sh\"\n" +
                "chmod 755 \"$UbuntuRootfs/tmp/astrbot_install.sh\"\n" +
                "rm -f \"$AstrBotScript\"\n" +
                "echo \"BOTDROP_INFO:Entering Ubuntu via proot-distro login...\"\n" +
                "proot-distro login ubuntu -- /bin/bash /tmp/astrbot_install.sh 2>&1 | tee -a \"$LOGFILE\"\n" +
                "# Verify AstrBot was installed\n" +
                "if ! test -d \"$UbuntuRootfs/root/astrbot\" 2>/dev/null; then\n" +
                "    echo \"BOTDROP_ERROR:AstrBot was not installed successfully\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "echo \"BOTDROP_STEP:3:DONE\"\n\n" +
                "touch \"$MARKER\"\n" +
                "echo \"BOTDROP_COMPLETE\"\n";

            try (FileOutputStream fos = new FileOutputStream(installScript)) {
                fos.write(installContent.getBytes());
            }
            //noinspection OctalInteger
            Os.chmod(installScript.getAbsolutePath(), 0755);

            // --- 2. Create profile.d env script ---
            File profileDir = new File(TERMUX_PREFIX_DIR_PATH + "/etc/profile.d");
            if (!profileDir.exists()) {
                profileDir.mkdirs();
            }

            File envScript = new File(profileDir, "botdrop-env.sh");
            String envContent =
                "# BotDrop environment setup\n" +
                "export TMPDIR=$PREFIX/tmp\n" +
                "mkdir -p $TMPDIR 2>/dev/null\n" +
                buildBotDropAptSourceScript() +
                "# Auto-start sshd if not running\n" +
                "if ! pgrep -x sshd >/dev/null 2>&1; then\n" +
                "    sshd 2>/dev/null\n" +
                "fi\n\n" +
                "# Run install if not done yet\n" +
                "if [ ! -f \"$HOME/.botdrop_installed\" ]; then\n" +
                "    echo \"\\U0001F4A7 Setting up BotDrop AstrBot...\"\n" +
                "    bash $PREFIX/share/botdrop/install.sh\n" +
                "fi\n";

            try (FileOutputStream fos = new FileOutputStream(envScript)) {
                fos.write(envContent.getBytes());
            }
            //noinspection OctalInteger
            Os.chmod(envScript.getAbsolutePath(), 0755);

            Logger.logInfo(LOG_TAG, "Created BotDrop scripts in " + botdropDir.getAbsolutePath());

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create BotDrop scripts", e);
        }
    }

    private static String buildBotDropAptSourceScript() {
        return
            "mkdir -p " + BOTDROP_APT_SOURCES_LIST_D + "\n" +
            "# Write BotDrop custom APT sources to sources.list.d/botdrop.list\n" +
            "printf '%s\\n' \"" + BOTDROP_APT_SOURCE_LINE + "\" > " + BOTDROP_APT_LIST_FILE + "\n" +
            "printf '%s\\n' \"" + BOTDROP_GITLAB_APT_SOURCE_LINE + "\" >> " + BOTDROP_APT_LIST_FILE + "\n" +
            "# Keep official Termux repo for proot-distro (only available there)\n" +
            "# but BotDrop source will be prioritized for common packages\n" +
            "if ! grep -qF 'packages.termux.dev' " + BOTDROP_APT_SOURCES_LIST + " 2>/dev/null; then\n" +
            "    printf '%s\\n' 'deb https://packages.termux.dev/apt/termux-main/ stable main' > " + BOTDROP_APT_SOURCES_LIST + "\n" +
            "fi\n" +
            "# Remove any stale .list files from sources.list.d (except botdrop.list)\n" +
            "for f in " + BOTDROP_APT_SOURCES_LIST_D + "/*.list; do\n" +
            "    if [ -f \"$f\" ] && [ \"$f\" != \"" + BOTDROP_APT_LIST_FILE + "\" ]; then\n" +
            "        rm -f \"$f\"\n" +
            "    fi\n" +
            "done\n";
    }
}



