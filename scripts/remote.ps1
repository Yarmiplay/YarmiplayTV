<#
.SYNOPSIS
  Sends TV remote-control keys to the emulator (or any adb device).

.EXAMPLE
  ./scripts/remote.ps1 down down ok
  ./scripts/remote.ps1 playpause
  ./scripts/remote.ps1 -Text "Neptunia"      # types text into the focused field
  ./scripts/remote.ps1 -Delay 800 right right ok back
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Keys,
    [string]$Text,
    [int]$Delay = 300,
    [string]$Serial
)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

$map = @{
    up = "KEYCODE_DPAD_UP"; down = "KEYCODE_DPAD_DOWN"; left = "KEYCODE_DPAD_LEFT"; right = "KEYCODE_DPAD_RIGHT"
    ok = "KEYCODE_DPAD_CENTER"; center = "KEYCODE_DPAD_CENTER"; enter = "KEYCODE_ENTER"
    back = "KEYCODE_BACK"; home = "KEYCODE_HOME"; menu = "KEYCODE_MENU"
    playpause = "KEYCODE_MEDIA_PLAY_PAUSE"; play = "KEYCODE_MEDIA_PLAY"; pause = "KEYCODE_MEDIA_PAUSE"
    stop = "KEYCODE_MEDIA_STOP"; ff = "KEYCODE_MEDIA_FAST_FORWARD"; rew = "KEYCODE_MEDIA_REWIND"
    next = "KEYCODE_MEDIA_NEXT"; prev = "KEYCODE_MEDIA_PREVIOUS"
    volup = "KEYCODE_VOLUME_UP"; voldown = "KEYCODE_VOLUME_DOWN"; mute = "KEYCODE_VOLUME_MUTE"
    info = "KEYCODE_INFO"; guide = "KEYCODE_GUIDE"; captions = "KEYCODE_CAPTIONS"; settings = "KEYCODE_SETTINGS"
    search = "KEYCODE_SEARCH"; del = "KEYCODE_DEL"
}

if ($Text) {
    & $adb @adbArgs shell input text ($Text -replace " ", "%s")
}

foreach ($k in $Keys) {
    $code = if ($map.ContainsKey($k.ToLower())) { $map[$k.ToLower()] } elseif ($k -match "^KEYCODE_") { $k } else { throw "Unknown key '$k'. Known: $($map.Keys -join ', ')" }
    & $adb @adbArgs shell input keyevent $code
    Start-Sleep -Milliseconds $Delay
}
