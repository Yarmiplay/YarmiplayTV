<#
.SYNOPSIS
  Boots a Google TV emulator (if none is running), builds and installs the debug app, and launches it.

.EXAMPLE
  ./scripts/run-tv.ps1
  ./scripts/run-tv.ps1 -Avd GoogleTV_4K
  ./scripts/run-tv.ps1 -NoBuild          # just boot and launch the already-installed app
  ./scripts/run-tv.ps1 -Headless         # no emulator window (useful for automated checks)
#>
param(
    [string]$Avd = "GoogleTV_1080p",
    [switch]$NoBuild,
    [switch]$Headless,
    [switch]$Cold
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk-17" }
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$appId = "com.syncplaytv"

& $adb start-server 2>&1 | Out-Null
$running = & $adb devices | Select-String "^emulator-\d+\s+device"
if (-not $running) {
    Write-Host "==> Starting $Avd" -ForegroundColor Cyan
    $emuArgs = @("-avd", $Avd, "-netdelay", "none", "-netspeed", "full")
    if ($Headless) { $emuArgs += "-no-window" }
    if ($Cold) { $emuArgs += "-no-snapshot-load" }
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
    & $adb wait-for-device
    Write-Host "==> Waiting for boot to complete" -ForegroundColor Cyan
    $deadline = (Get-Date).AddMinutes(5)
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch "1") {
        if ((Get-Date) -gt $deadline) { throw "Emulator did not finish booting within 5 minutes." }
        Start-Sleep -Seconds 2
    }
}

if (-not $NoBuild) {
    Write-Host "==> Building and installing debug APK" -ForegroundColor Cyan
    Push-Location $root
    & .\gradlew.bat :app:installDebug --console=plain
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "Gradle build failed." }
}

Write-Host "==> Launching $appId" -ForegroundColor Cyan
& $adb shell am start -n "$appId/.MainActivity" | Out-Null
