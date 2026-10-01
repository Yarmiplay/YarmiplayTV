<#
.SYNOPSIS
  Builds the debug APK and serves it on the local network so a real TV can download and install it.

.DESCRIPTION
  On the TV, open the printed http://<pc-ip>:<port>/ address in a browser or the "Downloader" app
  (or http://<pc-ip>:<port>/a for a direct download), then install the APK.
  The first run may show a Windows Firewall prompt: allow Python on private networks.

.EXAMPLE
  ./scripts/serve-apk.ps1
  ./scripts/serve-apk.ps1 -NoBuild -Port 8090
#>
param(
    [int]$Port = 8080,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk-17" }
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"

if (-not $NoBuild) {
    Write-Host "==> Building debug APK" -ForegroundColor Cyan
    Push-Location $root
    & .\gradlew.bat :app:assembleDebug --console=plain -q
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "Gradle build failed." }
}
if (-not (Test-Path $apk)) { throw "No APK at $apk; run without -NoBuild." }

$version = (Select-String -Path (Join-Path $root "app\build.gradle.kts") -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value

$python = (Get-Command py -ErrorAction SilentlyContinue).Source
$pyArgs = @("-3")
if (-not $python) {
    $python = (Get-Command python -ErrorAction SilentlyContinue).Source
    $pyArgs = @()
}
if (-not $python) { throw "Python 3 not found (install it from python.org or with: winget install Python.Python.3.12)" }

& $python @pyArgs (Join-Path $PSScriptRoot "apk_server.py") --apk $apk --port $Port --version $version
