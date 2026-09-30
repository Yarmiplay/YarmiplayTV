<#
.SYNOPSIS
  Captures the emulator/TV screen to a PNG (outside OneDrive) and prints its path.

.EXAMPLE
  ./scripts/screenshot.ps1            # -> %TEMP%\syncplaytv-shots\shot-<time>.png
  ./scripts/screenshot.ps1 -Name home
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Name = "shot",
    [string]$Serial
)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

$dir = Join-Path $env:TEMP "syncplaytv-shots"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$file = Join-Path $dir ("{0}-{1}.png" -f $Name, (Get-Date -Format "HHmmss-fff"))
& $adb @adbArgs shell screencap -p /sdcard/syncplaytv-shot.png
& $adb @adbArgs pull /sdcard/syncplaytv-shot.png $file | Out-Null
$file
