# 飞书 CLI（lark-cli）安装与使用指南

> 本文档记录在 Rbot（Android chroot Ubuntu）环境中安装配置飞书 CLI 的完整流程，以及踩过的坑。

## 一、安装

### 1.1 安装飞书 CLI

```bash
npm install -g @larksuite/cli
```

验证安装：

```bash
which lark-cli
# 输出: /usr/bin/lark-cli
```

### 1.2 安装飞书 CLI Skills（24个技能）

```bash
npx skills add larksuite/cli -g -y
```

技能安装位置：`/root/.agents/skills/`，包含以下 24 个技能：

| 分类 | 技能 |
|------|------|
| 文档 | doc、sheets、slides、markdown、minutes |
| 通讯 | im、mail、contact、vc |
| 协作 | calendar、task、approval、okr、base |
| 存储 | drive、wiki、whiteboard |
| 其他 | event、openapi-explorer、shared、skill-maker、workflow-meeting-summary、workflow-standup-report |

## 二、配置

### 2.1 写入配置文件

```bash
cat > /root/.lark-cli/config.json << 'EOF'
{
  "apps": [
    {
      "appId": "你的appId",
      "appSecret": "你的appSecret",
      "brand": "feishu",
      "lang": "zh"
    }
  ]
}
EOF
```

> ⚠️ **注意**：appSecret 必须完整写入，缺少会导致认证失败。

### 2.2 OAuth 授权登录

飞书 CLI 需要通过浏览器完成 OAuth 设备码授权：

**第一步：获取验证链接（在服务器终端运行）**

```bash
lark-cli auth login --no-wait --domain all
```

输出示例：

```json
{
  "device_code": "ONRWAdsXhXkkV1Ddk0i0ozVItJ0FPIbOGW...",
  "verification_url": "https://accounts.feishu.cn/oauth/v1/device/verify?flow_id=...",
  "user_code": "7JHL-D6MT"
}
```

**第二步：在手机/电脑浏览器中打开 verification_url，完成飞书授权**

**第三步：授权完成后，用 device_code 完成认证**

```bash
lark-cli auth login --device-code <上一步返回的device_code>
```

成功输出：

```
OK: 授权成功! 用户: 你的姓名 (ou_xxx)
```

### 2.3 验证配置

```bash
lark-cli doctor
```

全部通过时输出：

```json
{
  "ok": true,
  "checks": [
    { "name": "cli_version", "status": "pass" },
    { "name": "config_file", "status": "pass" },
    { "name": "app_resolved", "status": "pass" },
    { "name": "token_exists", "status": "pass" },
    { "name": "token_verified", "status": "pass" },
    { "name": "endpoint_open", "status": "pass" }
  ]
}
```

## 三、踩坑记录

### 坑 1：HOME 环境变量异常

**现象**：`lark-cli doctor` 显示 `config_file: fail`，报错 `not configured`。

**原因**：Android 容器环境下，系统启动时 `HOME` 被设为 `/` 而非 `/root`，导致 lark-cli 找不到配置文件。

**排查**：

```bash
echo $HOME
# 如果输出 / 而不是 /root，就是此问题
```

**修复**：

```bash
# 方案A：系统级修复（推荐）
echo 'export HOME=/root' >> /etc/environment

# 方案B：用户级修复
echo 'export HOME=/root' >> /root/.profile

# 方案C：临时修复（每次执行命令前）
HOME=/root lark-cli doctor
```

> ⚠️ 注意：在 Android 容器（Termux/Proot）环境下，`/etc/environment` 和 `~/.profile` 不会自动加载。最可靠的方式是在执行命令时显式指定 `HOME=/root`，或在 AstrBot 启动脚本中设置。

### 坑 2：配置文件缺少 appSecret

**现象**：`app_resolved` 通过但 `token_exists` 失败。

**原因**：手动创建 config.json 时只写了 appId，缺少 appSecret 字段。

**修复**：确保 config.json 包含完整的 appId 和 appSecret。

### 坑 3：OAuth 授权超时

**现象**：`lark-cli auth login --device-code <code>` 超时。

**原因**：device_code 有效期约 10 分钟，超时后需要重新获取。

**修复**：重新执行 `lark-cli auth login --no-wait --domain all` 获取新的 device_code。

### 坑 4：容器环境下 profile 不生效

**现象**：修改了 `/etc/environment` 和 `~/.profile` 但环境变量没有变化。

**原因**：容器环境下不是标准 Linux 登录流程，不会自动加载这些文件。

**修复**：在 AstrBot 启动脚本中加入 `export HOME=/root`，或每次执行命令时显式指定。

## 四、常用命令速查

```bash
# 健康检查
lark-cli doctor

# 创建文档
lark-cli docs +create --title "标题" --markdown "# 内容"

# 发送消息给用户
lark-cli im +messages-send --user-id "ou_xxx" --text "消息内容"

# 查看日历
lark-cli calendar +agenda

# 搜索文档
lark-cli docs +search --query "关键词"

# 查看文件列表
lark-cli drive files

# 上传文件
lark-cli drive upload --file /path/to/file

# 创建多维表格
lark-cli base app create --name "表格名称"

# 创建任务
lark-cli task create --title "任务标题"

# 创建审批
lark-cli approval create --title "审批标题"

# 搜索消息
lark-cli im +messages-search --query "关键词"
```

## 五、在 AstrBot 中使用

飞书 CLI 可以直接在 AstrBot 的插件中调用，例如：

```python
import subprocess

def create_feishu_doc(title, content):
    result = subprocess.run(
        ['lark-cli', 'docs', '+create', '--title', title, '--markdown', content],
        capture_output=True, text=True,
        env={**os.environ, 'HOME': '/root'}
    )
    return result.stdout
```

> ⚠️ 在 AstrBot 插件中调用时，务必在 env 中设置 `HOME=/root`。

