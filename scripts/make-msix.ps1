<#
.SYNOPSIS
  Builds the Microsoft Store package of the desktop app: wraps the jpackage app image
  (:desktop:createDistributable) with desktop/msix/AppxManifest.xml and the generated logos into an
  unsigned desktop/build/msix/YarmiplayTV-<version>.msix. The Store signs it when it is uploaded in
  Partner Center. The Store build doesn't look for updates (-Dyarmiplaytv.store).

  -Register installs the unpacked package for a local check instead (needs Developer Mode, Settings >
  System > For developers); remove it again from Settings > Apps.

.EXAMPLE
  ./scripts/make-msix.ps1
  ./scripts/make-msix.ps1 -NoBuild -Register
#>
param(
    [switch]$NoBuild,
    [switch]$Register
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$desktop = Join-Path $root "desktop"
$version = (Select-String -Path "$desktop\build.gradle.kts" -Pattern '^val appVersion = "(.*)"').Matches[0].Groups[1].Value
$appImage = "$desktop\build\compose\binaries\main\app\YarmiplayTV"
$out = "$desktop\build\msix"
$layout = "$out\layout"

if (-not $NoBuild) {
    Write-Host "==> Building the app image" -ForegroundColor Cyan
    Push-Location $root
    & .\gradlew.bat :desktop:createDistributable --console=plain -q
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "Gradle build failed." }
}
if (-not (Test-Path "$appImage\YarmiplayTV.exe")) { throw "No app image at $appImage; run without -NoBuild first." }

Write-Host "==> Laying out YarmiplayTV $version" -ForegroundColor Cyan
if (Test-Path $layout) { Remove-Item $layout -Recurse -Force }
Copy-Item $appImage $layout -Recurse
Copy-Item "$desktop\build\icons\msix" "$layout\Assets" -Recurse
$utf8 = New-Object System.Text.UTF8Encoding($false)
$manifest = (Get-Content "$desktop\msix\AppxManifest.xml" -Raw).Replace("{VERSION}", $version)
[System.IO.File]::WriteAllText("$layout\AppxManifest.xml", $manifest, $utf8)

# The launcher passes the [JavaOptions] lines of its .cfg to the JVM; it doesn't expect a byte order mark.
$cfg = "$layout\app\YarmiplayTV.cfg"
$lines = [System.Collections.Generic.List[string]](Get-Content $cfg)
$at = $lines.IndexOf("[JavaOptions]")
if ($at -lt 0) { throw "No [JavaOptions] section in $cfg" }
$lines.Insert($at + 1, "java-options=-Dyarmiplaytv.store=msstore")
[System.IO.File]::WriteAllLines($cfg, $lines, $utf8)

function Find-SdkTool($name) {
    $tool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\$name" -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Parent.Name -match '^\d+(\.\d+)+$' } | Sort-Object { [version]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
    if (-not $tool) { throw "$name not found; install the Windows SDK." }
    $tool.FullName
}

# Windows only finds the targetsize/altform-unplated logos through resources.pri. It is built from a copy
# of just the Assets, so the index doesn't list every file of the app image.
Write-Host "==> Indexing the logos" -ForegroundColor Cyan
$makepri = Find-SdkTool "makepri.exe"
$priRoot = "$out\pri"
if (Test-Path $priRoot) { Remove-Item $priRoot -Recurse -Force }
New-Item -ItemType Directory $priRoot | Out-Null
Copy-Item "$layout\Assets" "$priRoot\Assets" -Recurse
& $makepri createconfig /cf "$out\priconfig.xml" /dq en-US /pv 10.0.0 /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri createconfig failed." }
& $makepri new /pr $priRoot /cf "$out\priconfig.xml" /mn "$layout\AppxManifest.xml" /of "$layout\resources.pri" /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri new failed." }

if ($Register) {
    Write-Host "==> Registering the package" -ForegroundColor Cyan
    Add-AppxPackage -Register "$layout\AppxManifest.xml" -ForceApplicationShutdown
    Write-Host "Installed; start YarmiplayTV from the Start menu."
    return
}

$makeappx = Find-SdkTool "makeappx.exe"

$msix = "$out\YarmiplayTV-$version.msix"
Write-Host "==> Packing $msix" -ForegroundColor Cyan
& $makeappx pack /d $layout /p $msix /o
if ($LASTEXITCODE -ne 0) { throw "makeappx failed." }
$msix
