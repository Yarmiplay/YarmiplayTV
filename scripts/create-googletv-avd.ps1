<#
.SYNOPSIS
  Creates the Google TV emulators used for development:
    GoogleTV_1080p  (tv_1080p profile)
    GoogleTV_4K     (tv_4k profile)
  Uses the Google TV system image installed by setup-toolchain.ps1.
#>
param(
    [string]$SdkDir = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$JdkDir = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "$env:LOCALAPPDATA\Programs\jdk-17" }),
    [switch]$Force
)

$ErrorActionPreference = "Continue"
$env:JAVA_HOME = $JdkDir
$avdManager = "$SdkDir\cmdline-tools\latest\bin\avdmanager.bat"
$imageFile = "$SdkDir\.syncplaytv-tv-image"
if (-not (Test-Path $imageFile)) { throw "Run scripts/setup-toolchain.ps1 first." }
$image = (Get-Content $imageFile -Raw).Trim()

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "$env:USERPROFILE\.android\avd" }

$devices = @(
    @{ Name = "GoogleTV_1080p"; Profile = "tv_1080p" },
    @{ Name = "GoogleTV_4K";    Profile = "tv_4k" }
)

foreach ($d in $devices) {
    $avdDir = Join-Path $avdHome "$($d.Name).avd"
    if ((Test-Path $avdDir) -and -not $Force) {
        Write-Host "==> $($d.Name) already exists (use -Force to recreate)" -ForegroundColor Cyan
        continue
    }
    Write-Host "==> Creating $($d.Name) from $image" -ForegroundColor Cyan
    "no" | & $avdManager create avd --force --name $d.Name --package $image --device $d.Profile 2>&1 |
        Where-Object { $_ -notmatch "^\s*\[=*" }

    $config = Join-Path $avdDir "config.ini"
    $overrides = [ordered]@{
        "hw.ramSize"                = "4096"
        "hw.cpu.ncore"              = "4"
        "hw.gpu.enabled"            = "yes"
        "hw.gpu.mode"               = "auto"
        "hw.keyboard"               = "yes"
        "hw.dPad"                   = "yes"
        "disk.dataPartition.size"   = "8G"
        "fastboot.forceColdBoot"    = "no"
        "showDeviceFrame"           = "yes"
    }
    $lines = Get-Content $config | Where-Object { $key = ($_ -split "=", 2)[0].Trim(); -not $overrides.Contains($key) }
    $lines += $overrides.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -Path $config -Value $lines -Encoding ASCII
}

Write-Host "==> AVDs:" -ForegroundColor Cyan
& "$SdkDir\emulator\emulator.exe" -list-avds
