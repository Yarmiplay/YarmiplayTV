<#
.SYNOPSIS
  Phone, TV, the desktop app and the official Syncplay client in one room on the local server. The phone
  leads SyncCheckTest (play, pause, seek, file switch); the TV and the desktop app (DesktopSyncFollowerTest,
  real libmpv) must follow every step; the official console client watches and must see the same playlist
  and chat. Needs scripts/local-syncplay-server.ps1 running, both emulators booted, and the official client
  from source (.tools/syncplay-src with .tools/syncplay-venv; see OfficialClientInteropTest).

.EXAMPLE
  ./scripts/e2e-desktop.ps1 -Leader emulator-5554 -Follower emulator-5558
#>
param(
    [string]$Leader = "emulator-5554",
    [string]$Follower = "emulator-5558",
    [int]$Port = 8999,
    [string]$OfficialClient = "$PSScriptRoot\..\.tools\syncplay-src\syncplayClient.py",
    [string]$Python = "$PSScriptRoot\..\.tools\syncplay-venv\Scripts\python.exe",
    [string]$Mpv = "C:\Program Files\mpv\mpv.exe",
    [switch]$NoBuild
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk-17" }
$room = "e2e-$(Get-Random -Maximum 99999)"
$log = "$root\build\e2e-desktop"
New-Item -ItemType Directory -Force $log | Out-Null

if (-not $NoBuild) {
    Write-Host "==> Building the app, its tests and the desktop tests" -ForegroundColor Cyan
    & "$root\gradlew.bat" -p $root -q :app:assembleDebug :app:assembleDebugAndroidTest :desktop:testClasses
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Host "==> Room $room on localhost:$Port" -ForegroundColor Cyan

# The official client joins without a file: it only watches, and users without a file don't hold up readiness.
$psi = New-Object System.Diagnostics.ProcessStartInfo $Python
$psi.Arguments = "-u `"$OfficialClient`" --no-gui --no-store -a localhost:$Port -n Official -r $room --player-path `"$Mpv`" -- --vo=null --ao=null"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.EnvironmentVariables["PYTHONUNBUFFERED"] = "1"
$official = [System.Diagnostics.Process]::Start($psi)
$officialOut = $official.StandardOutput.ReadToEndAsync()
$officialErr = $official.StandardError.ReadToEndAsync()
Write-Host "   official client started (pid $($official.Id))"

$desktop = Start-Job -Name e2e-desktop -ArgumentList $root, $room, $Port -ScriptBlock {
    param($root, $room, $port)
    $env:SYNCPLAY_TEST_SERVER = "localhost:$port"
    $env:SYNCPLAY_E2E_ROOM = $room
    & "$root\gradlew.bat" -p $root :desktop:test --tests com.yarmiplaytv.desktop.DesktopSyncFollowerTest --rerun 2>&1
}
Write-Host "   desktop follower starting"

& "$PSScriptRoot\sync-check.ps1" -Leader $Leader -Follower $Follower -Port $Port -Room $room -ExtraFollowers SN-Desktop -NoInstall:$NoBuild
$emulatorsOk = $LASTEXITCODE -eq 0

Write-Host "==> Waiting for the desktop follower" -ForegroundColor Cyan
$desktop | Wait-Job -Timeout 120 | Out-Null
Receive-Job $desktop | Out-File "$log\desktop.log"
Remove-Job $desktop -Force
$result = "$root\desktop\build\test-results\test\TEST-com.yarmiplaytv.desktop.DesktopSyncFollowerTest.xml"
$desktopOk = $false
if (Test-Path $result) {
    $suite = ([xml](Get-Content $result)).testsuite
    $desktopOk = $suite.tests -eq "1" -and $suite.failures -eq "0" -and $suite.errors -eq "0" -and $suite.skipped -eq "0"
    if (-not $desktopOk) { $suite.testcase.failure.message | Select-Object -First 3 | ForEach-Object { Write-Host "   $_" -ForegroundColor Red } }
}

$official.StandardInput.WriteLine("ql")
Start-Sleep -Seconds 3
if (-not $official.HasExited) { $official.Kill() }
$officialLog = $officialOut.Result + $officialErr.Result
$officialLog | Out-File "$log\official.log"
$clips = "SyncplayTV Sync Check A.mp4", "SyncplayTV Sync Check B.mp4"
$sawPlaylist = ($clips | Where-Object { $officialLog -notmatch [regex]::Escape($_) }).Count -eq 0
$sawChat = $officialLog -match "SN-Leader.*SYNC \d+ done" -and $officialLog -match "SN-Desktop.*ACK \d+"
$officialOk = $sawPlaylist -and $sawChat

Write-Host ""
Write-Host "E2E results (logs in build/e2e-desktop)"
foreach ($r in @(@("Phone leads, TV follows", $emulatorsOk), @("Desktop follows", $desktopOk), @("Official client sees playlist and chat", $officialOk))) {
    $text, $ok = $r
    Write-Host ("  {0,-42} {1}" -f $text, $(if ($ok) { "PASS" } else { "FAIL" })) -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
}
if ($emulatorsOk -and $desktopOk -and $officialOk) { exit 0 }
exit 1
