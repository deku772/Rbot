# build.ps1 — 一键构建 + 安装 rbot 到手机
# 用法: .\build.ps1  (或右键 → 用 PowerShell 运行)

param(
    [switch]$Clean,    # -Clean: 先 clean 再构建
    [switch]$Install   # -Install: 构建后自动 adb install (默认开启)
)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Adb = "C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "🔨 rbot Build Script" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

Set-Location $ProjectDir

if ($Clean) {
    Write-Host "🧹 Cleaning..." -ForegroundColor Yellow
    & ./gradlew clean 2>&1 | ForEach-Object { Write-Host $_ }
}

Write-Host "📦 Building debug APK..." -ForegroundColor Yellow
& ./gradlew assembleDebug 2>&1 | ForEach-Object { Write-Host $_ }

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Build FAILED!" -ForegroundColor Red
    exit 1
}

$ApkPath = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $ApkPath)) {
    Write-Host "❌ APK not found: $ApkPath" -ForegroundColor Red
    exit 1
}

$ApkSize = [math]::Round((Get-Item $ApkPath).Length / 1MB, 1)
Write-Host "`n✅ Build OK: app-debug.apk ($ApkSize MB)" -ForegroundColor Green

# 检查设备连接
$DevicesOutput = & $Adb devices 2>$null
$Connected = ($DevicesOutput | Select-String "\tdevice$" | Measure-Object).Count

if ($Connected -eq 0) {
    Write-Host "⚠️ No device connected. APK ready at:`n   $ApkPath" -ForegroundColor Yellow
    exit 0
}

Write-Host "📱 Installing to device..." -ForegroundColor Yellow
$InstallResult = & $Adb install -r $ApkPath 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Install OK!" -ForegroundColor Green
} else {
    Write-Host "❌ Install failed:" -ForegroundColor Red
    $InstallResult | ForEach-Object { Write-Host $_ }
}
