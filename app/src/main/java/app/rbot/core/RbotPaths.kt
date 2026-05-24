package app.rbot.core

/**
 * Rbot 路径常量 — 从 Java RbotConstants 迁移而来。
 * 所有路径集中管理，不在其他位置硬编码。
 */
object RbotPaths {

    // ─── Chroot 模式路径 ───

    /** chroot 根目录 — Ubuntu rootfs 所在地 */
    const val CHROOT_DIR = "/data/rbot"

    /** chroot 内 AstrBot 主目录 */
    const val ASTRBOT_HOME = "$CHROOT_DIR/root/astrbot"

    /** AstrBot PID 文件（宿主路径） */
    const val ASTRBOT_PID_FILE = "$ASTRBOT_HOME/astrbot.pid"

    /** AstrBot 日志文件（宿主路径） */
    const val ASTRBOT_LOG_FILE = "$ASTRBOT_HOME/astrbot.log"

    /** AstrBot 调试日志（宿主路径） */
    const val ASTRBOT_DEBUG_LOG = "$ASTRBOT_HOME/astrbot-debug.log"

    /** rootfs 就绪标记文件 */
    const val ROOTFS_MARKER = "$CHROOT_DIR/.rbot-rootfs-ready"

    /** AstrBot 安装标记文件（chroot 根目录，app 进程可读） */
    const val ASTRBOT_MARKER = "$CHROOT_DIR/.rbot-astrbot-ready"

    // ─── SDCard 路径 ───

    /** sdcard 数据目录 */
    const val SDCARD_DIR = "/storage/emulated/0/rbot"

    /** sdcard 缓存目录 — rootfs 下载缓存 */
    const val SDCARD_CACHE_DIR = "$SDCARD_DIR/cache"

    /** 本地 rootfs 路径 — 优先检查此路径再下载 */
    const val SDCARD_ROOTFS_CACHE = "$SDCARD_CACHE_DIR/ubuntu24_rbot.tar.gz"

    /** GitHub release 下载 URL */
    const val GITHUB_ROOTFS_URL =
        "https://github.com/deku772/rbot/releases/download/rootfs-v1/ubuntu24_rbot.tar.gz"

    /** rootfs MD5 校验 */
    const val ROOTFS_MD5 = "e70b463477f8c5a66568211b84fbc3fe"

    // ─── App 信息 ───

    const val PACKAGE_NAME = "app.rbot"

    // ─── PRoot 模式路径 ───

    /** PRoot SSH 端口 — 必须 >= 1024（Android 阻止非 root 绑定特权端口） */
    const val PROOT_SSH_PORT = 8022

    /** PRoot rootfs 子目录名（相对于 app filesDir） */
    const val PROOT_ROOTFS_DIR_NAME = "proot-rootfs"

    /** PRoot rootfs 就绪标记（相对于 app filesDir） */
    const val PROOT_ROOTFS_MARKER = ".proot-rootfs-ready"

    /** PRoot AstrBot 安装标记（相对于 app filesDir） */
    const val PROOT_ASTRBOT_MARKER = ".proot-astrbot-ready"

    /** PRoot 二进制下载 URL（arm64-v8a） */
    const val PROOT_BINARY_URL =
        "https://github.com/deku772/rbot/releases/download/proot-v1/libproot.so"

    /** PRoot 二进制备用 URL */
    val PROOT_BINARY_URLS = arrayOf(
        PROOT_BINARY_URL,
        "https://github.com/termux/proot/releases/download/v5.1.0/proot-aarch64",
        "https://github.com/JunWan666/openclaw-termux-zh/releases/download/proot-v1/libproot.so"
    )

    /** PRoot loader 二进制下载 URL（arm64-v8a） */
    const val PROOT_LOADER_URL =
        "https://github.com/deku772/rbot/releases/download/proot-v1/libproot-loader.so"

    /** PRoot loader 二进制备用 URL */
    val PROOT_LOADER_URLS = arrayOf(
        PROOT_LOADER_URL,
        "https://github.com/JunWan666/openclaw-termux-zh/releases/download/proot-v1/libproot-loader.so"
    )

    /** Rbot 临时目录（宿主端） */
    const val RBOT_TMP = "/data/local/tmp/rbot_tmp"

    /** 安装脚本路径（宿主端） */
    const val INSTALL_SCRIPT = "$RBOT_TMP/install.sh"

    /** 备份目录 */
    const val BACKUP_DIR = "$SDCARD_DIR/backups"

    /** 外部数据备份路径 */
    const val EXTERNAL_DATA_BACKUP = "$BACKUP_DIR/astrbot_data_backup"

    /** AstrBot 数据目录名（相对于 ASTRBOT_HOME） */
    const val ASTRBOT_DATA_DIR = "data"

    /** 操作日志文件路径 */
    const val OP_LOG_FILE = "$CHROOT_DIR/.rbot-op-log"

    // ─── SSH 配置 ───

    /** 默认 SSH 密码 */
    const val DEFAULT_SSH_PASSWORD = "rbot"
}
