<#
.SYNOPSIS
  Boots a Google TV emulator (if it isn't running), builds and installs the debug app, and launches it.
  Thin wrapper around run-app.ps1, which also handles the phone and tablet emulators.

.EXAMPLE
  ./scripts/run-tv.ps1
  ./scripts/run-tv.ps1 -Avd GoogleTV_4K
  ./scripts/run-tv.ps1 -NoBuild          # just boot and launch the already-installed app
  ./scripts/run-tv.ps1 -Headless         # no emulator window (useful for automated checks)
#>
param(
    [string]$Avd = "GoogleTV_1080p",
    [switch]$NoBuild,
    [switch]$Headless,
    [switch]$Cold
)

& "$PSScriptRoot\run-app.ps1" -Avd $Avd -NoBuild:$NoBuild -Headless:$Headless -Cold:$Cold
