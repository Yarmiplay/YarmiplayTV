<#
.SYNOPSIS
  Runs the official Syncplay server locally (from source) for testing.
  The emulator reaches it at 10.0.2.2:<port>; desktop clients at localhost:<port>.

.EXAMPLE
  ./scripts/local-syncplay-server.ps1
  ./scripts/local-syncplay-server.ps1 -Port 8995 -Password secret
#>
param(
    [int]$Port = 8999,
    [string]$Password,
    [string]$Motd = "YarmiplayTV local test server"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$tools = Join-Path $root ".tools"
$src = Join-Path $tools "syncplay-src"
$venv = Join-Path $tools "syncplay-venv"
$python = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $src)) {
    git clone --depth 1 https://github.com/Syncplay/syncplay $src
}
if (-not (Test-Path $python)) {
    py -3 -m venv $venv
    & $python -m pip install -q "twisted[tls]" certifi pyopenssl service_identity idna pem
}

$motdFile = Join-Path $tools "motd.txt"
Set-Content -Path $motdFile -Value $Motd -Encoding UTF8
$serverArgs = @("$src\syncplayServer.py", "--port", "$Port", "--motd-file", $motdFile)
if ($Password) { $serverArgs += @("--password", $Password) }
Write-Host "==> Syncplay server on port $Port (Ctrl+C to stop)" -ForegroundColor Cyan
& $python @serverArgs
