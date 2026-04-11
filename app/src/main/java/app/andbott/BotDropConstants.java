package app.andbott;

/**
 * BotDrop path constants for the root/chroot architecture.
 * All paths are managed here — no hardcoded paths elsewhere.
 */
public final class BotDropConstants {

    private BotDropConstants() {} // no instantiation

    /** chroot root directory — the Ubuntu rootfs lives here */
    public static final String CHROOT_DIR = "/data/botdrop";

    /** AstrBot home inside chroot */
    public static final String ASTRBOT_HOME = CHROOT_DIR + "/root/astrbot";

    /** AstrBot PID file (host path, written by the wrapper script) */
    public static final String ASTRBOT_PID_FILE = ASTRBOT_HOME + "/astrbot.pid";

    /** AstrBot log file (host path) */
    public static final String ASTRBOT_LOG_FILE = ASTRBOT_HOME + "/astrbot.log";

    /** AstrBot debug log (host path) */
    public static final String ASTRBOT_DEBUG_LOG = ASTRBOT_HOME + "/astrbot-debug.log";

    /** Marker file indicating rootfs is extracted and ready */
    public static final String ROOTFS_MARKER = CHROOT_DIR + "/.botdrop-rootfs-ready";

    /** Marker file indicating AstrBot is installed (at chroot root so app process can read it) */
    public static final String ASTRBOT_MARKER = CHROOT_DIR + "/.botdrop-astrbot-ready";

    /** sdcard data directory */
    public static final String SDCARD_DIR = "/storage/emulated/0/botdrop";

    /** sdcard cache directory — rootfs download cache, preserved across reinstalls */
    public static final String SDCARD_CACHE_DIR = SDCARD_DIR + "/cache";

    /** Local rootfs source (Tier 1) */
    public static final String LOCAL_ROOTFS_SRC = "/storage/emulated/0/环境/ubuntu22_openclaw.tar.gz";

    /** sdcard cached rootfs (Tier 2) */
    public static final String SDCARD_ROOTFS_CACHE = SDCARD_CACHE_DIR + "/ubuntu22_openclaw.tar.gz";

    /** GitHub rootfs download URL (Tier 3) */
    public static final String GITHUB_ROOTFS_URL = "https://github.com/TermuxCHN/rootfs/releases/download/ubuntu2204/rootfs.tar.xz";

    /** App package name */
    public static final String PACKAGE_NAME = "app.andbott";

    /** BotDrop tmp directory (on host, for scripts) */
    public static final String BOTDROP_TMP = "/data/local/tmp/botdrop_tmp";

    /** Install script path (on host) */
    public static final String INSTALL_SCRIPT = BOTDROP_TMP + "/install.sh";
}
