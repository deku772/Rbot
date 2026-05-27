package app.rbot.core

/**
 * Rbot 路径常量 — 从 Java RbotConstants 迁移而来。
 * 所有路径集中管理，不在其他位置硬编码。
 */
object RbotPaths {

    // ─── Chroot 模式路径（不变）──────────────────────────

    /** chroot 根目录 — Ubuntu rootfs 所在地 */
    const val CHROOT_DIR = "/data/rbot"

    /** chroot 内 AStrBot 主目录 */
    const val ASTRBOT_HOME = "$CHROOT_DIR/root/astrbot"

    /** AStrBot PID 文件（宿主路径） */
    const val ASTRBOT_PID_FILE = "$ASTRBOT_HOME/astrbot.pid"

    /** AStrBot 日志文件（宿主路径） */
    const val ASTRBOT_LOG_FILE = "$ASTRBOT_HOME/astrbot.log"

    /** AStrBot 调试日志（宿主路径） */
    const val ASTRBOT_DEBUG_LOG = "$ASTRBOT_HOME/astrbot-debug.log"

    /** rootfs 就绪标记文件 */
    const val ROOTFS_MARKER = "$CHROOT_DIR/.rbot-rootfs-ready"

    /** AStrBot 安装标记文件（chroot 根目录，app 进程可读） */
    const val ASTRBOT_MARKER = "$CHROOT_DIR/.rbot-astrbot-ready"

    // ─── SDCard 路径 ─────────────────────────────────────

    /** sdcard 数据目录 */
    const val SDCARD_DIR = "/storage/emulated/0/rbot"

    /** sdcard 缓存目录 — rootfs 下载缓存 */
    const val SDCARD_CACHE_DIR = "$SDCARD_DIR/cache"

    /** 本地 rootfs 路径 — 优先检查此路径再下载（Chroot 模式用） */
    const val SDCARD_ROOTFS_CACHE = "$SDCARD_CACHE_DIR/ubuntu24_rbot.tar.gz"

    /** GitHub release 下载 URL（Chroot 模式 Ubuntu rootfs） */
    const val GITHUB_ROOTFS_URL =
        "https://github.com/deku772/rbot/releases/download/rootfs-v1/ubuntu24_rbot.tar.gz"

    /** rootfs MD5 校验 */
    const val ROOTFS_MD5 = "e70b463477f8c5a66568211b84fbc3fe"

    // ─── App 信息 ────────────────────────────────────────

    const val PACKAGE_NAME = "app.rbot"

    // ─── 运行时路径 ──────────────────────────────────────

    /** Rbot 临时目录（宿主端） */
    const val RBOT_TMP = "/data/local/tmp/rbot_tmp"

    /** 安装脚本路径（宿主端） */
    const val INSTALL_SCRIPT = "$RBOT_TMP/install.sh"

    /** 备份目录 */
    const val BACKUP_DIR = "$SDCARD_DIR/backups"

    /** 外部数据备份路径 */
    const val EXTERNAL_DATA_BACKUP = "$BACKUP_DIR/astrbot_data_backup"

    /** AStrBot 数据目录名（相对于 ASTRBOT_HOME） */
    const val ASTRBOT_DATA_DIR = "data"

    /** 操作日志文件路径 */
    const val OP_LOG_FILE = "$CHROOT_DIR/.rbot-op-log"

    // ─── SSH 配置 ────────────────────────────────────────

    /** 默认 SSH 密码 */
    const val DEFAULT_SSH_PASSWORD = "rbot"
}
