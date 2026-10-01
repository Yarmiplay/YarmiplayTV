<#
.SYNOPSIS
  Two emulators in one room on a real Syncplay server: the leader plays, pauses, seeks and switches
  files; the follower must stay in sync (SyncCheckTest). Needs a Syncplay server reachable from the
  emulators (default 10.0.2.2:8999, i.e. port 8999 on this PC).

.EXAMPLE
  ./scripts/sync-check.ps1 -Leader emulator-5554 -Follower emulator-5558
#>
param(
    [Parameter(Mandatory)][string]$Leader,
    [Parameter(Mandatory)][string]$Follower,
    [string]$ServerHost = "10.0.2.2",
    [int]$Port = 8999,
    [switch]$NoInstall
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$runner = "com.syncplaytv.test/androidx.test.runner.AndroidJUnitRunner"
$room = "safety-net-$(Get-Random -Maximum 99999)"

if (-not $NoInstall) {
    foreach ($s in @($Leader, $Follower)) {
        & $adb -s $s install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
        & $adb -s $s install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
    }
}

Write-Host "==> Sync check in room $room ($Leader leads, $Follower follows)" -ForegroundColor Cyan
$jobs = foreach ($pair in @(@($Follower, "follower"), @($Leader, "leader"))) {
    $serial, $role = $pair
    Start-Job -Name "sync-$role" -ArgumentList $adb, $serial, $role, $room, $ServerHost, $Port, $runner -ScriptBlock {
        param($adb, $serial, $role, $room, $h, $p, $runner)
        & $adb -s $serial shell am instrument -w -r -e class com.syncplaytv.synccheck.SyncCheckTest `
            -e syncRole $role -e syncRoom $room -e syncHost $h -e syncPort $p $runner 2>&1
    }
    Start-Sleep -Seconds 2
}
$jobs | Wait-Job | Out-Null
$ok = $true
foreach ($role in @("leader", "follower")) {
    $log = Receive-Job -Name "sync-$role"
    $pass = ($log | Select-String "^OK \(1 test\)").Count -gt 0
    if (-not $pass) {
        $ok = $false
        Write-Host "   $role failed:" -ForegroundColor Red
        $log | Select-String "(AssertionError|Timed out|FAIL|Exception)" | Select-Object -First 8 | ForEach-Object { Write-Host "     $($_.Line)" }
    } else {
        Write-Host "   $role passed" -ForegroundColor Green
    }
}
Remove-Job -Name "sync-leader", "sync-follower" -Force
if (-not $ok) { exit 1 }
exit 0
