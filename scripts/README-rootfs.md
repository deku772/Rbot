# Rootfs 构建脚本使用说明

## 概述

`build-rootfs-ubuntu2404.sh` 用于在手机上构建 **Ubuntu 24.04 LTS (arm64)** 定制 rootfs，预装 Python 3.12、Node.js 20.x LTS、OpenSSH Server 等工具。

## 前置条件

- 手机已 root
- Rbot APP 已部署 chroot 环境并可正常运行
- SSH 已连接到手机 chroot（`root@手机IP`）
- 网络通畅（需要下载 ~211MB rootfs + 软件包）

## 使用方法

### 一键构建

SSH 连接到手机后，执行：

```bash
curl -o /root/build-rootfs.sh https://gitee.com/deku772/Rbot/raw/root/scripts/build-rootfs-ubuntu2404.sh
bash /root/build-rootfs.sh
```

整个过程约 15-20 分钟（取决于网速），脚本会自动完成：
1. 下载 Ubuntu 24.04 官方 arm64 rootfs
2. 解压到 `/root/buildroot/rootfs/`
3. 配置 DNS（8.8.8.8）
4. chroot 进入并安装软件包
5. 打包为 `/root/ubuntu24_rbot.tar.gz`

### 手动构建（如果脚本失败）

如果脚本中断，可以手动恢复：

```bash
# 1. 配置 DNS（resolv.conf 是 dangling symlink，需要删掉重建）
rm -f /root/buildroot/rootfs/etc/resolv.conf
echo "nameserver 8.8.8.8" > /root/buildroot/rootfs/etc/resolv.conf
echo "nameserver 8.8.4.4" >> /root/buildroot/rootfs/etc/resolv.conf

# 2. 修复 /dev/null 权限 + 挂载文件系统
chmod 666 /root/buildroot/rootfs/dev/null
mount --bind /dev /root/buildroot/rootfs/dev
mount --bind /dev/pts /root/buildroot/rootfs/dev/pts
mount --bind /proc /root/buildroot/rootfs/proc
mount --bind /sys /root/buildroot/rootfs/sys
mount -t tmpfs tmpfs /root/buildroot/rootfs/tmp

# 3. chroot 安装（先装 gpgv 跳过验证，再正常装其他）
chroot /root/buildroot/rootfs /bin/bash -c '
chmod 666 /dev/null
apt-get update --allow-insecure-repositories
apt-get install -y --allow-unauthenticated gpgv
apt-get update
apt-get install -y openssh-server curl wget git vim build-essential ca-certificates software-properties-common locales
apt-get install -y python3 python3-venv python3-pip python3-dev
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs
locale-gen en_US.UTF-8
mkdir -p /run/sshd
sed -i "s/#PermitRootLogin.*/PermitRootLogin yes/" /etc/ssh/sshd_config
sed -i "s/#PasswordAuthentication.*/PasswordAuthentication yes/" /etc/ssh/sshd_config
echo "root:rbot" | chpasswd
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache
echo "Python: $(python3 --version)"
echo "Node:   $(node --version)"
echo "NPM:    $(npm --version)"
'

# 4. 卸载 + 打包
umount /root/buildroot/rootfs/tmp
umount /root/buildroot/rootfs/dev/pts
umount /root/buildroot/rootfs/dev
umount /root/buildroot/rootfs/proc
umount /root/buildroot/rootfs/sys
cd /root/buildroot/rootfs && tar czf /root/ubuntu24_rbot.tar.gz .
```

### 构建完成后

1. 将产物拷贝到 sdcard 或上传到下载服务器：
   ```bash
   cp /root/ubuntu24_rbot.tar.gz /storage/emulated/0/rbot/cache/
   ```

2. 修改 `ChrootManager.java` 中的 `ROOTFS_URL` 指向新镜像文件

3. 重新编译安装 APP

## 预装软件

| 软件 | 版本 | 说明 |
|------|------|------|
| Ubuntu | 24.04 LTS | arm64 |
| Python | 3.12 | 含 venv, pip, dev |
| Node.js | 20.x LTS | 含 npm |
| OpenSSH | 9.6p1 | 允许 root 登录 |
| build-essential | - | gcc, g++, make |
| curl, wget, git, vim | - | 基础工具 |

## 常见问题

### DNS 解析失败
新 rootfs 的 `/etc/resolv.conf` 是 dangling symlink，需要删除后手动写入 DNS。

### gpgv 缺失导致 apt 失败
Ubuntu 24.04 cloud image 最小安装不含 gpgv，需要用 `--allow-insecure-repositories` 先装上。

### /dev/null Permission denied
chroot 后 /dev/null 权限可能不对，需要 `chmod 666 /dev/null`。

### 产物大小
预计 800MB-1.2GB（gzip 压缩后），比 Ubuntu 22.04 rootfs 稍大。
