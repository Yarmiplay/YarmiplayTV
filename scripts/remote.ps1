<#
.SYNOPSIS
  Sends TV remote-control keys, taps, swipes and rotation to the emulator (or any adb device).

.EXAMPLE
  ./scripts/remote.ps1 down down ok
  ./scripts/remote.ps1 playpause
  ./scripts/remote.ps1 -Text "Neptunia"      # types text into the focused field
  ./scripts/remote.ps1 -Delay 800 right right ok back
  ./scripts/remote.ps1 -Serial emulator-5556 tap 540 1200 swipe 540 1800 540 600
  ./scripts/remote.ps1 -Serial emulator-5556 rotate landscape   # portrait | landscape | auto#>
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

function Take([int]$count) {
    if ($script:i + $count -ge $Keys.Count) { throw "'$($Keys[$script:i])' needs $count argument(s)" }
    $vals = $Keys[($script:i + 1)..($script:i + $count)]
    $script:i += $count
    $vals
}

$script:i = 0
while ($script:i -lt $Keys.Count) {
    $k = $Keys[$script:i].ToLower()
    switch ($k) {
        "tap" {
            $x, $y = Take 2
            & $adb @adbArgs shell input tap $x $y
        }
        "swipe" {
            $x1, $y1, $x2, $y2 = Take 4
            & $adb @adbArgs shell input swipe $x1 $y1 $x2 $y2 300
        }
        "rotate" {
            $mode = (Take 1).ToLower()
            if ($mode -eq "auto") {
                & $adb @adbArgs shell settings put system accelerometer_rotation 1
            } else {
                $rotation = @{ portrait = 0; landscape = 1; "reverse-portrait" = 2; "reverse-landscape" = 3 }[$mode]
                if ($null -eq $rotation) { throw "rotate takes portrait, landscape, reverse-portrait, reverse-landscape or auto" }
                & $adb @adbArgs shell settings put system accelerometer_rotation 0
                & $adb @adbArgs shell settings put system user_rotation $rotation
            }
        }
        default {
            $code = if ($map.ContainsKey($k)) { $map[$k] } elseif ($Keys[$script:i] -match "^KEYCODE_") { $Keys[$script:i] } else {
                throw "Unknown key '$k'. Known: tap x y, swipe x1 y1 x2 y2, rotate <mode>, $($map.Keys -join ', ')"
            }
            & $adb @adbArgs shell input keyevent $code
        }
    }
    $script:i++
    Start-Sleep -Milliseconds $Delay
}
