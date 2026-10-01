<#
.SYNOPSIS
  Boots an emulator (unless it is already running), builds and installs the debug app on it, and
  launches it. Several emulators can run side by side (e.g. a TV and a phone in the same room).
  Prints the device serial (emulator-55xx) at the end for use with -Serial in the other scripts.

.EXAMPLE
  ./scripts/run-app.ps1 -Avd Phone_Pixel8
  ./scripts/run-app.ps1 -Avd Tablet_Pixel -NoBuild
  ./scripts/run-app.ps1 -Serial emulator-5556          # an already-running device
  ./scripts/run-app.ps1 -Avd Phone_Pixel8 -Ui tv       # force the TV UI (debug)
  ./scripts/run-app.ps1 -Avd GoogleTV_1080p -Headless
#>
param(
    [string]$Avd = "GoogleTV_1080p",
    [string]$Serial,
    [ValidateSet("", "tv", "mobile")][string]$Ui = "",
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

function Get-Emulators {
    & $adb devices | Select-String "^(emulator-\d+)\s+(device|offline)" | ForEach-Object { $_.Matches[0].Groups[1].Value }
}

function Get-AvdName([string]$s) {
    $out = & $adb -s $s emu avd name 2>$null
    if ($out) { ($out | Select-Object -First 1).Trim() } else { $null }
}

& $adb start-server 2>&1 | Out-Null

if (-not $Serial) {
    $Serial = Get-Emulators | Where-Object { (Get-AvdName $_) -eq $Avd } | Select-Object -First 1
}
if (-not $Serial) {
    Write-Host "==> Starting $Avd" -ForegroundColor Cyan
    $before = @(Get-Emulators)
    $emuArgs = @("-avd", $Avd, "-netdelay", "none", "-netspeed", "full")
    if ($Headless) { $emuArgs += "-no-window" }
    if ($Cold) { $emuArgs += "-no-snapshot-load" }
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    while (-not $Serial) {
        if ((Get-Date) -gt $deadline) { throw "$Avd did not appear in adb within 3 minutes." }
        Start-Sleep -Seconds 2
        $Serial = Get-Emulators | Where-Object { $before -notcontains $_ } | Select-Object -First 1
    }
}

Write-Host "==> Waiting for $Serial to finish booting" -ForegroundColor Cyan
& $adb -s $Serial wait-for-device
$deadline = (Get-Date).AddMinutes(5)
while ((& $adb -s $Serial shell getprop sys.boot_completed 2>$null) -notmatch "1") {
    if ((Get-Date) -gt $deadline) { throw "$Serial did not finish booting within 5 minutes." }
    Start-Sleep -Seconds 2
}
# Gboard's stylus tutorial pops up on scripted taps and swallows typed text.
& $adb -s $Serial shell settings put secure stylus_handwriting_enabled 0 2>$null

if (-not $NoBuild) {
    Write-Host "==> Building debug APK" -ForegroundColor Cyan
    Push-Location $root
    & .\gradlew.bat :app:assembleDebug --console=plain -q
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "Gradle build failed." }
}
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "No APK at $apk; run without -NoBuild first." }
Write-Host "==> Installing on $Serial" -ForegroundColor Cyan
& $adb -s $Serial install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

Write-Host "==> Launching $appId on $Serial" -ForegroundColor Cyan
$startArgs = @("shell", "am", "start", "-S", "-n", "$appId/.MainActivity")
if ($Ui) { $startArgs += @("--es", "ui", $Ui) }
& $adb -s $Serial @startArgs | Out-Null
$Serial
