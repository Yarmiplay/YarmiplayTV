<#
.SYNOPSIS
  Creates the Google TV emulators (GoogleTV_1080p, GoogleTV_4K). Kept for compatibility;
  see create-avds.ps1, which also creates the phone and tablet emulators.
#>
param([switch]$Force)

& "$PSScriptRoot\create-avds.ps1" -Kind tv -Force:$Force
