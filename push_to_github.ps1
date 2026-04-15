# 推送代码到 GitHub 并创建 Release
# 使用前请先在 GitHub 网页创建空仓库: https://github.com/new

Write-Host "=== 推送 rbot 到 GitHub ===" -ForegroundColor Green

# 检查 GitHub 远程是否存在
$remotes = git remote
if ($remotes -notcontains "github") {
    Write-Host "添加 GitHub 远程..." -ForegroundColor Yellow
    git remote add github https://github.com/deku772/rbot.git
}

# 推送主分支
Write-Host "`n推送 root 分支到 GitHub..." -ForegroundColor Yellow
git push github root

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n❌ 推送失败！" -ForegroundColor Red
    Write-Host "请先在 GitHub 创建仓库: https://github.com/new" -ForegroundColor Yellow
    Write-Host "Repository name: rbot" -ForegroundColor Yellow
    Write-Host "Visibility: Public" -ForegroundColor Yellow
    Write-Host "不要勾选任何初始化选项" -ForegroundColor Yellow
    exit 1
}

# 推送标签
Write-Host "`n推送标签到 GitHub..." -ForegroundColor Yellow
git push github --tags

Write-Host "`n✅ 代码推送成功！" -ForegroundColor Green

# 检查 APK 是否存在
$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkPath) {
    $apkSize = (Get-Item $apkPath).Length / 1MB
    Write-Host "`n📦 APK 文件信息:" -ForegroundColor Cyan
    Write-Host "   路径: $apkPath" -ForegroundColor White
    Write-Host "   大小: $([math]::Round($apkSize, 2)) MB" -ForegroundColor White
} else {
    Write-Host "`n⚠️  APK 文件不存在，请先编译:" -ForegroundColor Yellow
    Write-Host "   ./gradlew assembleDebug" -ForegroundColor White
}

# 提示创建 Release
Write-Host "`n📝 下一步: 创建 GitHub Release" -ForegroundColor Cyan
Write-Host "   1. 访问: https://github.com/deku772/rbot/releases/new" -ForegroundColor White
Write-Host "   2. 选择标签: v1.0.224" -ForegroundColor White
Write-Host "   3. 标题: v1.0.224 - 移除 Hermes Agent 支持" -ForegroundColor White
Write-Host "   4. 说明: 复制 RELEASE_NOTES_v1.0.224.md 的内容" -ForegroundColor White
Write-Host "   5. 上传 APK: $apkPath" -ForegroundColor White
Write-Host "   6. 点击 'Publish release'" -ForegroundColor White

Write-Host "`n或者使用 GitHub CLI (如果有权限):" -ForegroundColor Cyan
Write-Host "   gh release create v1.0.224 --title 'v1.0.224 - 移除 Hermes Agent 支持' --notes-file RELEASE_NOTES_v1.0.224.md $apkPath" -ForegroundColor White

Write-Host "`n✨ 完成！" -ForegroundColor Green
