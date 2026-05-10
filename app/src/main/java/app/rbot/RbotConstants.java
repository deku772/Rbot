package app.rbot;

/**
 * Rbot path constants for the root/chroot architecture.
 * All paths are managed here — no hardcoded paths elsewhere.
 */
public final class RbotConstants {

    private RbotConstants() {} // no instantiation

    /** chroot root directory — the Ubuntu rootfs lives here */
    public static final String CHROOT_DIR = "/data/rbot";

    /** AstrBot home inside chroot */
    public static final String ASTRBOT_HOME = CHROOT_DIR + "/root/astrbot";

    /** AstrBot PID file (host path, written by the wrapper script) */
    public static final String ASTRBOT_PID_FILE = ASTRBOT_HOME + "/astrbot.pid";

    /** AstrBot log file (host path) */
    public static final String ASTRBOT_LOG_FILE = ASTRBOT_HOME + "/astrbot.log";

    /** AstrBot debug log (host path) */
    public static final String ASTRBOT_DEBUG_LOG = ASTRBOT_HOME + "/astrbot-debug.log";

    /** Marker file indicating rootfs is extracted and ready */
    public static final String ROOTFS_MARKER = CHROOT_DIR + "/.rbot-rootfs-ready";

    /** Marker file indicating AstrBot is installed (at chroot root so app process can read it) */
    public static final String ASTRBOT_MARKER = CHROOT_DIR + "/.rbot-astrbot-ready";

    /** sdcard data directory */
    public static final String SDCARD_DIR = "/storage/emulated/0/rbot";

    /** sdcard cache directory — rootfs download cache, preserved across reinstalls */
    public static final String SDCARD_CACHE_DIR = SDCARD_DIR + "/cache";

    /** Local rootfs path — check this first before downloading */
    public static final String SDCARD_ROOTFS_CACHE = SDCARD_CACHE_DIR + "/ubuntu24_rbot.tar.gz";

    /** GitHub release download URL (used when local cache not found) */
    public static final String GITHUB_ROOTFS_URL = "https://github.com/deku772/rbot/releases/download/rootfs-v1/ubuntu24_rbot.tar.gz";

    /** Expected MD5 of the rootfs tarball — used to verify download completeness */
    public static final String ROOTFS_MD5 = "e70b463477f8c5a66568211b84fbc3fe";

    /** App package name */
    public static final String PACKAGE_NAME = "app.rbot";

    /** Rbot tmp directory (on host, for scripts) */
    public static final String RBOT_TMP = "/data/local/tmp/rbot_tmp";

    /** Install script path (on host) */
    public static final String INSTALL_SCRIPT = RBOT_TMP + "/install.sh";

    /** Backup directory on sdcard (unified location for easy restore after reinstall) */
    public static final String BACKUP_DIR = SDCARD_DIR + "/backups";

    /** Default external data backup path (used during rootfs reinstall) */
    public static final String EXTERNAL_DATA_BACKUP = BACKUP_DIR + "/astrbot_data_backup";

    /** AstrBot data directory inside chroot (relative to ASTRBOT_HOME) */
    public static final String ASTRBOT_DATA_DIR = "data";

    /** Operation log file — records all app-initiated actions (install, start, SSH, etc.) */
    public static final String OP_LOG_FILE = CHROOT_DIR + "/.rbot-op-log";

    // ─── SSH Configuration ──────────────────────────────────────────────────────

    /** Default SSH password (set during rootfs installation) */
    public static final String DEFAULT_SSH_PASSWORD = "rbot";

}
