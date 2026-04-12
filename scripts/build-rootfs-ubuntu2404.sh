#!/bin/bash
# ============================================
# Rbot Ubuntu 24.04 LTS rootfs 构建脚本
# 在手机现有 chroot 里运行
# ============================================

set -e

WORKDIR="/root/buildroot"
NEWROOT="$WORKDIR/rootfs"
OUTPUT="/root/ubuntu24_rbot.tar.gz"
ROOTFS_URL="https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-arm64-root.tar.xz"

echo "=========================================="
echo " Rbot Rootfs Builder - Ubuntu 24.04 LTS"
echo "=========================================="

# 1. 准备工作目录
echo "[1/6] 准备工作目录..."
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR" "$NEWROOT"

# 2. 下载 Ubuntu 24.04 arm64 rootfs
echo "[2/6] 下载 Ubuntu 24.04 arm64 rootfs..."
cd "$WORKDIR"
if [ ! -f ubuntu2404-rootfs.tar.xz ]; then
    curl -L -o ubuntu2404-rootfs.tar.xz "$ROOTFS_URL"
fi
echo "  下载完成: $(ls -lh ubuntu2404-rootfs.tar.xz | awk '{print $5}')"

# 3. 解压
echo "[3/6] 解压 rootfs..."
cd "$NEWROOT"
tar xf "$WORKDIR/ubuntu2404-rootfs.tar.xz"
echo "  解压完成"

# 4. 挂载必要文件系统
echo "[4/6] 挂载文件系统..."
mount --bind /dev dev
mount --bind /dev/pts dev/pts
mount --bind /proc proc
mount --bind /sys sys
mount -t tmpfs tmpfs tmp

# 5. chroot 进去装软件
echo "[5/6] 安装软件包（这步最久，耐心等）..."
chroot "$NEWROOT" /bin/bash -c '
set -e

echo "  -> 配置 apt 源..."
apt-get update

echo "  -> 安装系统基础包..."
apt-get install -y \
    openssh-server \
    curl wget git vim \
    build-essential \
    ca-certificates \
    software-properties-common \
    locales

echo "  -> 安装 Python 3.12..."
apt-get install -y \
    python3 python3-venv python3-pip python3-dev

echo "  -> 安装 Node.js 20.x LTS..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs

echo "  -> 配置 locale..."
locale-gen en_US.UTF-8

echo "  -> 配置 SSH..."
mkdir -p /run/sshd
sed -i "s/#PermitRootLogin.*/PermitRootLogin yes/" /etc/ssh/sshd_config
sed -i "s/#PasswordAuthentication.*/PasswordAuthentication yes/" /etc/ssh/sshd_config

echo "  -> 设置 root 密码..."
echo "root:rbot" | chpasswd

echo "  -> 清理..."
apt-get clean
rm -rf /var/lib/apt/lists/*
rm -rf /tmp/*
rm -rf /root/.cache

echo "  -> 验证安装..."
echo "  Python: $(python3 --version)"
echo "  Node:   $(node --version)"
echo "  NPM:    $(npm --version)"
echo "  SSH:    $(sshd -V 2>&1 | head -1)"
'

# 6. 卸载 + 打包
echo "[6/6] 打包 rootfs..."
umount "$NEWROOT/tmp" 2>/dev/null || true
umount "$NEWROOT/dev/pts" 2>/dev/null || true
umount "$NEWROOT/dev" 2>/dev/null || true
umount "$NEWROOT/proc" 2>/dev/null || true
umount "$NEWROOT/sys" 2>/dev/null || true

cd "$NEWROOT"
tar czf "$OUTPUT" .
echo ""
echo "=========================================="
echo " ✅ 构建完成！"
echo " 产物: $OUTPUT"
echo " 大小: $(ls -lh "$OUTPUT" | awk '{print $5}')"
echo "=========================================="
echo ""
echo "下一步：把 ubuntu24_rbot.tar.gz 上传到你的下载服务器"
echo "然后修改 ChrootManager.java 的 ROOTFS_URL 指向新文件"
