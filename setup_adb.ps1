# Setup ADB (Android Platform Tools) for Windows
# This script downloads and configures ADB if not already installed

$platformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
$downloadPath = "$env:TEMP\platform-tools.zip"
$installDir = "$env:LOCALAPPDATA\Android"
$platformToolsDir = "$installDir\platform-tools"

Write-Host "=== Android Platform Tools Setup ===" -ForegroundColor Cyan

# Check if already installed
if (Test-Path "$platformToolsDir\adb.exe") {
    Write-Host "✓ ADB already installed at: $platformToolsDir" -ForegroundColor Green
    & "$platformToolsDir\adb.exe" version
    
    # Check if in PATH
    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$platformToolsDir*") {
        Write-Host "Adding to PATH..." -ForegroundColor Yellow
        [Environment]::SetEnvironmentVariable("Path", "$currentPath;$platformToolsDir", "User")
        $env:Path += ";$platformToolsDir"
        Write-Host "✓ Added to PATH (restart terminal to take effect)" -ForegroundColor Green
    } else {
        Write-Host "✓ Already in PATH" -ForegroundColor Green
    }
    
    Write-Host "`nTesting ADB connection..." -ForegroundColor Cyan
    & "$platformToolsDir\adb.exe" devices
    exit 0
}

Write-Host "Downloading Android Platform Tools..." -ForegroundColor Yellow
Write-Host "URL: $platformToolsUrl"

try {
    # Download
    Invoke-WebRequest -Uri $platformToolsUrl -OutFile $downloadPath -UseBasicParsing
    Write-Host "✓ Downloaded to: $downloadPath" -ForegroundColor Green
    
    # Create install directory
    if (-not (Test-Path $installDir)) {
        New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    }
    
    # Extract
    Write-Host "Extracting..." -ForegroundColor Yellow
    Expand-Archive -Path $downloadPath -DestinationPath $installDir -Force
    Write-Host "✓ Extracted to: $platformToolsDir" -ForegroundColor Green
    
    # Clean up
    Remove-Item $downloadPath -Force
    
    # Add to PATH
    Write-Host "Adding to PATH..." -ForegroundColor Yellow
    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$platformToolsDir*") {
        [Environment]::SetEnvironmentVariable("Path", "$currentPath;$platformToolsDir", "User")
        $env:Path += ";$platformToolsDir"
        Write-Host "✓ Added to PATH" -ForegroundColor Green
    }
    
    # Verify installation
    Write-Host "`n=== Installation Complete ===" -ForegroundColor Green
    & "$platformToolsDir\adb.exe" version
    
    Write-Host "`nTesting device connection..." -ForegroundColor Cyan
    & "$platformToolsDir\adb.exe" devices
    
    Write-Host "`n✓ ADB is ready to use!" -ForegroundColor Green
    Write-Host "Note: You may need to restart your terminal for PATH changes to take effect." -ForegroundColor Yellow
    
} catch {
    Write-Host "✗ Error: $_" -ForegroundColor Red
    exit 1
}
