# 在 GitHub 创建仓库并推送代码

## 方法 1：通过 GitHub 网页创建（推荐）

### 步骤 1：创建 GitHub 仓库
1. 访问：https://github.com/new
2. 填写信息：
   - **Repository name**: `rbot`
   - **Description**: `ANDBOTT - Android Bot Runtime: Run AstrBot in chroot Ubuntu environment on Android devices`
   - **Visibility**: Public
   - **不要**勾选 "Initialize this repository with a README"
   - **不要**添加 .gitignore 或 license（我们已经有了）
3. 点击 "Create repository"

### 步骤 2：添加 GitHub 远程并推送
在本地仓库执行以下命令：

```bash
# 添加 GitHub 远程
git remote add github https://github.com/deku772/rbot.git

# 推送所有分支和标签
git push github root
git push github --tags

# 或者推送所有分支
git push github --all
git push github --tags
```

### 步骤 3：在 GitHub 创建 Release
1. 访问：https://github.com/deku772/rbot/releases/new
2. 填写信息：
   - **Choose a tag**: 选择 `v1.0.224` 或创建新标签
   - **Release title**: `v1.0.224 - 移除 Hermes Agent 支持`
   - **Description**: 复制 `RELEASE_NOTES_v1.0.224.md` 的内容
3. 上传 APK：
   - 拖拽 `app/build/outputs/apk/debug/app-debug.apk` 到附件区域
   - 或点击 "Attach binaries" 上传
4. 点击 "Publish release"

---

## 方法 2：使用 GitHub CLI（需要正确的 token）

如果你有正确权限的 token，可以执行：

```bash
# 创建仓库
gh repo create rbot --public --description "ANDBOTT - Android Bot Runtime" --source=. --remote=github

# 推送代码
git push github root --tags

# 创建 Release
gh release create v1.0.224 \
  --title "v1.0.224 - 移除 Hermes Agent 支持" \
  --notes-file RELEASE_NOTES_v1.0.224.md \
  app/build/outputs/apk/debug/app-debug.apk#rbot-v1.0.224.apk
```

---

## 方法 3：自动化脚本

我已经为你准备了一个脚本，执行以下命令：

```bash
# 添加 GitHub 远程
git remote add github https://github.com/deku772/rbot.git

# 推送代码和标签
git push github root
git push github v1.0.224
```

然后手动在 GitHub 网页创建 Release 并上传 APK。

---

## 同时维护 Gitee 和 GitHub

如果你想同时推送到两个平台：

```bash
# 查看当前远程
git remote -v

# 推送到 Gitee
git push origin root --tags

# 推送到 GitHub
git push github root --tags

# 或者一次推送到所有远程
git remote | xargs -L1 git push --all
git remote | xargs -L1 git push --tags
```

---

## 推荐的工作流程

1. **开发**: 在本地开发和测试
2. **提交**: `git commit -m "feat: ..."`
3. **推送到 Gitee**: `git push origin root`
4. **推送到 GitHub**: `git push github root`
5. **创建 Release**: 在 GitHub 网页创建（更方便上传 APK）
