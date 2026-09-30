<#
.SYNOPSIS
  Installs everything needed to build SyncplayTV and run the Google TV emulator, without admin rights:
  a portable JDK 17, the Android command-line tools, platform-tools, emulator, SDK platform,
  build-tools and the newest Google TV x86_64 system image.

.NOTES
  Paths default to the same places Android Studio uses, so Android Studio will pick them up too:
    JDK:  %LOCALAPPDATA%\Programs\jdk-17
    SDK:  %LOCALAPPDATA%\Android\Sdk
  JAVA_HOME, ANDROID_HOME and PATH are set for the current user.
#>
param(
    [string]$JdkDir = "$env:LOCALAPPDATA\Programs\jdk-17",
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Platform = "android-36",
    [string]$BuildTools = "36.0.0"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

function Expand-SingleRootZip($zip, $dest) {
    $tmp = Join-Path $env:TEMP ("syncplaytv-" + [guid]::NewGuid())
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $root = Get-ChildItem $tmp | Select-Object -First 1
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Move-Item $root.FullName $dest
    Remove-Item $tmp -Recurse -Force
}

function Add-UserPath($dir) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if (-not $userPath) { $userPath = "" }
    if (($userPath -split ";") -notcontains $dir) {
        [Environment]::SetEnvironmentVariable("Path", ($userPath.TrimEnd(";") + ";" + $dir).TrimStart(";"), "User")
    }
    if (($env:Path -split ";") -notcontains $dir) { $env:Path = "$dir;$env:Path" }
}

# --- JDK 17 ------------------------------------------------------------------
if (-not (Test-Path "$JdkDir\bin\java.exe")) {
    Write-Step "Downloading Temurin JDK 17"
    $zip = Join-Path $env:TEMP "jdk17.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $zip
    Expand-SingleRootZip $zip $JdkDir
    Remove-Item $zip
} else {
    Write-Step "JDK 17 already present at $JdkDir"
}
[Environment]::SetEnvironmentVariable("JAVA_HOME", $JdkDir, "User")
$env:JAVA_HOME = $JdkDir
Add-UserPath "$JdkDir\bin"

# --- Android command-line tools ---------------------------------------------
$sdkManager = "$SdkDir\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    Write-Step "Looking up the latest Android command-line tools"
    $repoUrl = "https://dl.google.com/android/repository/repository2-3.xml"
    [xml]$repo = (Invoke-WebRequest -UseBasicParsing -Uri $repoUrl).Content
    $pkg = $repo.SelectNodes("//remotePackage") | Where-Object { $_.path -eq "cmdline-tools;latest" } | Select-Object -First 1
    $archive = $pkg.archives.archive | Where-Object { $_.'host-os' -eq "windows" } | Select-Object -First 1
    $url = "https://dl.google.com/android/repository/" + $archive.complete.url
    Write-Step "Downloading $url"
    $zip = Join-Path $env:TEMP "cmdline-tools.zip"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    Expand-SingleRootZip $zip "$SdkDir\cmdline-tools\latest"
    Remove-Item $zip
} else {
    Write-Step "Android command-line tools already present"
}
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkDir, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $SdkDir, "User")
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
Add-UserPath "$SdkDir\cmdline-tools\latest\bin"
Add-UserPath "$SdkDir\platform-tools"
Add-UserPath "$SdkDir\emulator"

# --- SDK packages -----------------------------------------------------------
# Recent command-line tools replace sdkmanager with the "android" CLI, whose package ids use "/"
# instead of ";". Native tools write progress to stderr, so don't let that abort the script.
$ErrorActionPreference = "Continue"
$androidCli = "$SdkDir\cmdline-tools\latest\bin\android.exe"
$useAndroidCli = Test-Path $androidCli
$yes = ("y`n" * 50)

function Invoke-Sdk([string[]]$sdkArgs) {
    if ($useAndroidCli) {
        $yes | & $androidCli --no-metrics "--sdk=$SdkDir" sdk @sdkArgs 2>&1
    } else {
        $yes | & $sdkManager "--sdk_root=$SdkDir" @sdkArgs 2>&1
    }
}

function Convert-PackageId([string]$id) {
    if ($useAndroidCli) { return $id.Replace(";", "/") } else { return $id.Replace("/", ";") }
}

if (-not $useAndroidCli) { Invoke-Sdk @("--licenses") | Out-Null }

Write-Step "Finding the newest Google TV system image"
if ($useAndroidCli) { $list = Invoke-Sdk @("list", "--all", "system-images*") } else { $list = Invoke-Sdk @("--list") }
$tvImages = $list | ForEach-Object { (($_ -split "\|")[0].Trim() -split "\s+")[0] } |
    Where-Object { $_ -match "^system-images[;/]android-(\d+)[;/]google-tv[;/]x86(_64)?$" } |
    Sort-Object -Unique |
    Sort-Object @{ Expression = { [int]([regex]::Match($_, "android-(\d+)").Groups[1].Value) }; Descending = $true },
                @{ Expression = { $_ -match "x86_64$" }; Descending = $true }
if (-not $tvImages) { throw "No Google TV system image found in the SDK package list." }
$tvImage = $tvImages | Select-Object -First 1
Write-Step "Using $tvImage"

$packages = @("platform-tools", "emulator", "platforms;$Platform", "build-tools;$BuildTools", $tvImage) |
    ForEach-Object { Convert-PackageId $_ }
foreach ($p in $packages) {
    Write-Step "Installing $p"
    Invoke-Sdk @("install", $p) | Where-Object { $_ -notmatch "^\s*\[?=*>?\s*\]?\s*\d*%?\s*$" } | Select-Object -Last 3
    if ($LASTEXITCODE -ne 0) { throw "Installing $p failed with exit code $LASTEXITCODE" }
}

Set-Content -Path "$SdkDir\.syncplaytv-tv-image" -Value ($tvImage.Replace("/", ";")) -Encoding ASCII

# --- Hardware acceleration --------------------------------------------------
Write-Step "Checking emulator hardware acceleration"
& "$SdkDir\emulator\emulator.exe" -accel-check 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning ("The emulator cannot use hardware acceleration. With Hyper-V active, enable 'Windows Hypervisor Platform' " +
        "(admin PowerShell: Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform) and reboot.")
}

Write-Step "Done. Open a new terminal so the updated PATH, JAVA_HOME and ANDROID_HOME take effect."
