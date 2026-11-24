<#
.SYNOPSIS
  Two emulators in one room on a real Syncplay server: the leader plays, pauses, seeks and switches
  files; the follower must stay in sync (SyncCheckTest). Without -Port it starts the official Syncplay
  server (as scripts/local-syncplay-server.ps1 does) on a free port for the run and stops it afterwards,
  so another program on 8999 doesn't get in the way. With -Port it uses a server already running there.

.EXAMPLE
  ./scripts/sync-check.ps1 -Leader emulator-5554 -Follower emulator-5558
  ./scripts/sync-check.ps1 -Leader emulator-5554 -Follower emulator-5558 -Port 8999   # a server you started
#>
param(
    [Parameter(Mandatory)][string]$Leader,
    [Parameter(Mandatory)][string]$Follower,
    [string]$ServerHost = "10.0.2.2",
    [int]$Port = 0,
    [string]$Room = "",
    # More followers the leader waits for, besides the emulator one (e2e-desktop.ps1 adds SN-Desktop).
    [string[]]$ExtraFollowers = @(),
    [switch]$NoInstall
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$runner = "com.yarmiplaytv.test/com.yarmiplaytv.YarmiplayTestRunner"
$room = if ($Room) { $Room } else { "safety-net-$(Get-Random -Maximum 99999)" }
$followers = (@("SN-Follower") + $ExtraFollowers) -join ","

# Starts the official Syncplay server from .tools (set up on first use) on a free port; returns the process.
function Start-SyncplayServer([int]$port) {
    $tools = Join-Path $root ".tools"
    $src = Join-Path $tools "syncplay-src"
    $python = Join-Path $tools "syncplay-venv\Scripts\python.exe"
    if (-not (Test-Path "$src\syncplayServer.py")) { git clone -q --depth 1 https://github.com/Syncplay/syncplay $src }
    if (-not (Test-Path $python)) {
        py -3 -m venv (Join-Path $tools "syncplay-venv")
        & $python -m pip install -q "twisted[tls]" certifi pyopenssl service_identity idna pem
    }
    # Not Start-Process: inside a PowerShell job (android-safety-net.ps1 runs this script in one) the server it
    # starts inherits the job's input and never gets as far as listening. Its output is read here instead.
    $psi = New-Object System.Diagnostics.ProcessStartInfo $python
    $psi.Arguments = "-u `"$src\syncplayServer.py`" --port $port"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Close()
    $script:serverOut = $proc.StandardOutput.ReadToEndAsync()
    $script:serverErr = $proc.StandardError.ReadToEndAsync()
    foreach ($i in 1..60) {
        if ($proc.HasExited) { break }
        $client = New-Object System.Net.Sockets.TcpClient
        try { $client.Connect("127.0.0.1", $port); return $proc } catch { Start-Sleep -Milliseconds 500 } finally { $client.Dispose() }
    }
    Stop-SyncplayServer $proc
    Write-Host "   the Syncplay server didn't start; see $serverLog" -ForegroundColor Red
    return $null
}

$serverLog = Join-Path $root "build\sync-check-server.log"
function Stop-SyncplayServer($proc) {
    if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit(5000) | Out-Null }
    New-Item -ItemType Directory -Force (Split-Path $serverLog) | Out-Null
    Set-Content -Path $serverLog -Value @($script:serverOut.Result, $script:serverErr.Result) -Encoding UTF8
}

if (-not $NoInstall) {
    foreach ($s in @($Leader, $Follower)) {
        & $adb -s $s install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
        & $adb -s $s install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
    }
}

$server = $null
if ($Port -eq 0) {
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, 0)
    $probe.Start(); $Port = $probe.LocalEndpoint.Port; $probe.Stop()
    $server = Start-SyncplayServer $Port
    if (-not $server) { exit 1 }
    Write-Host "==> Syncplay server for this check on port $Port" -ForegroundColor Cyan
}

try {
    Write-Host "==> Sync check in room $room ($Leader leads, $Follower follows)" -ForegroundColor Cyan
    $jobs = foreach ($pair in @(@($Follower, "follower"), @($Leader, "leader"))) {
        $serial, $role = $pair
        Start-Job -Name "sync-$role" -ArgumentList $adb, $serial, $role, $room, $ServerHost, $Port, $runner, $followers -ScriptBlock {
            param($adb, $serial, $role, $room, $h, $p, $runner, $followers)
            & $adb -s $serial shell am instrument -w -r -e class com.yarmiplaytv.synccheck.SyncCheckTest `
                -e syncRole $role -e syncRoom $room -e syncHost $h -e syncPort $p -e syncFollowers $followers $runner 2>&1
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
} finally {
    if ($server) { Stop-SyncplayServer $server }
}
if (-not $ok) { exit 1 }
exit 0
