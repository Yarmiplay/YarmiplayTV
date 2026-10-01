<#
.SYNOPSIS
  Touch automation by what's on screen: finds an element by its visible text, content description or
  Compose test tag (via `uiautomator dump`) and taps it. Works in other apps too (e.g. the system
  file picker), which makes it handy for driving phone/tablet emulators from scripts.

.EXAMPLE
  ./scripts/ui.ps1 -List                               # print the visible elements and their bounds
  ./scripts/ui.ps1 "Settings"                          # tap the first element whose text/desc contains "Settings"
  ./scripts/ui.ps1 -Exact "Room"                       # exact (case-insensitive) match only
  ./scripts/ui.ps1 -Id tab_Room                        # Compose testTag (exposed as resource-id)
  ./scripts/ui.ps1 "Use this folder" -Wait 10          # retry for up to 10 s until it shows up
  ./scripts/ui.ps1 "Movies" -Serial emulator-5556 -LongPress
  ./scripts/ui.ps1 -Exists "Couldn't find"             # exit code 0 if present, 1 if not (no tap)
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)][string]$Text,
    [string]$Id,
    [switch]$Exact,
    [switch]$List,
    [switch]$Exists,
    [switch]$LongPress,
    [int]$Index = 0,
    [double]$Wait = 0,
    [string]$Serial
)

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

function Get-Nodes {
    & $adb @adbArgs shell uiautomator dump /sdcard/syncplaytv-ui.xml 2>&1 | Out-Null
    $raw = (& $adb @adbArgs exec-out cat /sdcard/syncplaytv-ui.xml) -join "`n"
    if (-not $raw -or $raw -notmatch "<hierarchy") { return @() }
    $xml = [xml]$raw
    $xml.SelectNodes("//node") | ForEach-Object {
        if ($_.bounds -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            [pscustomobject]@{
                Text   = $_.text
                Desc   = $_.'content-desc'
                Id     = ($_.'resource-id' -replace "^.*:id/", "")
                X      = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                Y      = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
                Bounds = $_.bounds
                Click  = $_.clickable -eq "true"
            }
        }
    }
}

function Test-Match($n) {
    if ($Id) { return $n.Id -eq $Id }
    foreach ($v in @($n.Text, $n.Desc)) {
        if (-not $v) { continue }
        if ($Exact) { if ($v -ieq $Text) { return $true } }
        elseif ($v.IndexOf($Text, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

if ($List) {
    Get-Nodes | Where-Object { $_.Text -or $_.Desc -or $_.Id } |
        Format-Table @{ n = "text"; e = { $_.Text } }, @{ n = "desc"; e = { $_.Desc } }, @{ n = "id"; e = { $_.Id } }, X, Y, Click -AutoSize |
        Out-String -Width 220
    exit 0
}

if (-not $Text -and -not $Id) { throw "Give the text to tap, -Id <testTag>, or -List." }

$deadline = (Get-Date).AddSeconds($Wait)
do {
    $found = @(Get-Nodes | Where-Object { Test-Match $_ })
    if ($found.Count -gt $Index) { break }
    if ((Get-Date) -ge $deadline) { break }
    Start-Sleep -Milliseconds 700
} while ($true)

$label = if ($Id) { "id '$Id'" } else { "'$Text'" }
if ($found.Count -le $Index) {
    if ($Exists) { exit 1 }
    Write-Error "No element matching $label on screen."
    exit 1
}
if ($Exists) { exit 0 }

$n = $found[$Index]
if ($LongPress) {
    & $adb @adbArgs shell input swipe $n.X $n.Y $n.X $n.Y 800
} else {
    & $adb @adbArgs shell input tap $n.X $n.Y
}
Write-Host "Tapped $label at $($n.X),$($n.Y)"
