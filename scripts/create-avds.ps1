<#
.SYNOPSIS
  Creates the emulators used for development and testing:
    GoogleTV_1080p  (tv_1080p, Google TV image)
    GoogleTV_4K     (tv_4k, Google TV image)
    Phone_Pixel8    (pixel_8, Android 16 Google Play image)
    Tablet_Pixel    (pixel_tablet, Android 16 Google Play image)
  System images are installed by setup-toolchain.ps1.

.EXAMPLE
  ./scripts/create-avds.ps1                 # all of them
  ./scripts/create-avds.ps1 -Kind phone,tablet
  ./scripts/create-avds.ps1 -Kind tv -Force # recreate the TV emulators
#>
param(
    [string[]]$Kind = @("all"),
    [string]$SdkDir = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$JdkDir = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "$env:LOCALAPPDATA\Programs\jdk-17" }),
    [switch]$Force
)

$ErrorActionPreference = "Continue"
$env:JAVA_HOME = $JdkDir
$avdManager = "$SdkDir\cmdline-tools\latest\bin\avdmanager.bat"
$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "$env:USERPROFILE\.android\avd" }

$tvImageFile = "$SdkDir\.syncplaytv-tv-image"
$tvImage = if (Test-Path $tvImageFile) { (Get-Content $tvImageFile -Raw).Trim() } else { $null }
$mobileImage = "system-images;android-36;google_apis_playstore;x86_64"

$common = [ordered]@{
    "hw.ramSize"              = "4096"
    "hw.cpu.ncore"            = "4"
    "hw.gpu.enabled"          = "yes"
    "hw.gpu.mode"             = "auto"
    "hw.keyboard"             = "yes"
    "disk.dataPartition.size" = "8G"
    "fastboot.forceColdBoot"  = "no"
    "showDeviceFrame"         = "yes"
}

$devices = @(
    @{ Name = "GoogleTV_1080p"; Profile = "tv_1080p";     Kind = "tv";     Image = $tvImage;     Extra = @{ "hw.dPad" = "yes" } },
    @{ Name = "GoogleTV_4K";    Profile = "tv_4k";        Kind = "tv";     Image = $tvImage;     Extra = @{ "hw.dPad" = "yes" } },
    @{ Name = "Phone_Pixel8";   Profile = "pixel_8";      Kind = "phone";  Image = $mobileImage; Extra = @{ "hw.sensors.orientation" = "yes" } },
    @{ Name = "Tablet_Pixel";   Profile = "pixel_tablet"; Kind = "tablet"; Image = $mobileImage; Extra = @{ "hw.sensors.orientation" = "yes" } }
)

$Kind = @($Kind | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
$unknown = $Kind | Where-Object { $_ -notin @("tv", "phone", "tablet", "all") }
if ($unknown) { throw "Unknown -Kind '$($unknown -join ", ")'. Use tv, phone, tablet or all." }
$wanted = if ($Kind -contains "all") { @("tv", "phone", "tablet") } else { $Kind }

foreach ($d in $devices | Where-Object { $wanted -contains $_.Kind }) {
    if (-not $d.Image) { Write-Warning "No image for $($d.Name); run scripts/setup-toolchain.ps1 first."; continue }
    $imageDir = Join-Path $SdkDir ($d.Image -replace ";", "\")
    if (-not (Test-Path $imageDir)) { Write-Warning "$($d.Image) is not installed; run scripts/setup-toolchain.ps1 first."; continue }

    $avdDir = Join-Path $avdHome "$($d.Name).avd"
    if ((Test-Path $avdDir) -and -not $Force) {
        Write-Host "==> $($d.Name) already exists (use -Force to recreate)" -ForegroundColor Cyan
        continue
    }
    Write-Host "==> Creating $($d.Name) ($($d.Profile)) from $($d.Image)" -ForegroundColor Cyan
    "no" | & $avdManager create avd --force --name $d.Name --package $d.Image --device $d.Profile 2>&1 |
        Where-Object { $_ -notmatch "^\s*\[=*" }

    $config = Join-Path $avdDir "config.ini"
    if (-not (Test-Path $config)) { Write-Warning "avdmanager did not create $config"; continue }
    $overrides = [ordered]@{}
    foreach ($e in $common.GetEnumerator()) { $overrides[$e.Key] = $e.Value }
    foreach ($e in $d.Extra.GetEnumerator()) { $overrides[$e.Key] = $e.Value }
    $lines = Get-Content $config | Where-Object { $key = ($_ -split "=", 2)[0].Trim(); -not $overrides.Contains($key) }
    $lines += $overrides.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -Path $config -Value $lines -Encoding ASCII
}

Write-Host "==> AVDs:" -ForegroundColor Cyan
& "$SdkDir\emulator\emulator.exe" -list-avds
