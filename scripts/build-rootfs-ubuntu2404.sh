#!/bin/bash
# ============================================
# Rbot Ubuntu 24.04 LTS rootfs 构建脚本
# 在手机现有 chroot (SSH) 里运行
# ============================================

set -e

WORKDIR="/root/buildroot"
NEWROOT="$WORKDIR/rootfs"
OUTPUT="/root/ubuntu24_rbot.tar.gz"
ROOTFS_URL="https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-arm64-root.tar.xz"

echo "=========================================="
echo " Rbot Rootfs Builder - Ubuntu 24.04 LTS"
echo " Python 3.12 + Node.js 20.x + OpenSSH"
echo "=========================================="

# 1. 准备工作目录
echo "[1/7] 准备工作目录..."
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR" "$NEWROOT"

# 2. 下载 Ubuntu 24.04 arm64 rootfs
echo "[2/7] 下载 Ubuntu 24.04 arm64 rootfs (~211MB)..."
cd "$WORKDIR"
if [ ! -f ubuntu2404-rootfs.tar.xz ]; then
    curl -L -o ubuntu2404-rootfs.tar.xz "$ROOTFS_URL"
fi
echo "  下载完成: $(ls -lh ubuntu2404-rootfs.tar.xz | awk '{print $5}')"

# 3. 解压
echo "[3/7] 解压 rootfs..."
cd "$NEWROOT"
tar xf "$WORKDIR/ubuntu2404-rootfs.tar.xz"
echo "  解压完成"

# 4. 配置 DNS（resolv.conf 可能是 dangling symlink，需要删掉重建）
echo "[4/7] 配置 DNS..."
rm -f "$NEWROOT/etc/resolv.conf"
echo "nameserver 8.8.8.8" > "$NEWROOT/etc/resolv.conf"
echo "nameserver 8.8.4.4" >> "$NEWROOT/etc/resolv.conf"

# 5. 挂载必要文件系统
echo "[5/7] 挂载文件系统..."
mount --bind /dev dev
mount --bind /dev/pts dev/pts
mount --bind /proc proc
mount --bind /sys sys
mount -t tmpfs tmpfs tmp

# 6. chroot 进去装软件
echo "[6/7] 安装软件包（这步最久，约 10-15 分钟）..."
chroot "$NEWROOT" /bin/bash -c '
set -e

# 修复 /dev/null 权限
chmod 666 /dev/null

# 先跳过 GPG 验证装 gpgv，之后才能正常 apt
echo "  -> 安装 gpgv（首次需跳过验证）..."
apt-get update --allow-insecure-repositories
apt-get install -y --allow-unauthenticated gpgv

echo "  -> 更新 apt 源..."
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
'

# 7. 卸载 + 打包
echo "[7/7] 打包 rootfs..."
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
echo "下一步："
echo "  1. 将 ubuntu24_rbot.tar.gz 拷贝到下载服务器"
echo "  2. 修改 ChrootManager.java 的 ROOTFS_URL 指向新文件"
echo "  3. 重新构建 APP 并安装"
