<#
.SYNOPSIS
  The Android safety net: JVM tests, the instrumented suites and the reference-screenshot comparison on
  the phone, tablet and TV emulators, plus the two-device sync check. Every code-move step must leave
  this all green and pixel-identical.

.DESCRIPTION
  Boots Phone_Pixel8, Tablet_Pixel and GoogleTV_1080p if they aren't running, builds the debug and
  test APKs, and runs on all three in parallel:
    - phone/tablet: every instrumented test except the TV ones (MobileUiTest, LocalPlaybackTest,
      DeviceUiTest, MobileScreenshotTest)
    - TV: TvNavigationTest and TvScreenshotTest
  Screenshot mismatches are pulled into build/safety-net/failures/<device>/ (actual + diff images).

  -Record writes new reference screenshots into app/src/androidTest/screenshots/<device>/ instead of
  comparing. Review them (git diff) before committing.

.EXAMPLE
  ./scripts/android-safety-net.ps1                 # full run
  ./scripts/android-safety-net.ps1 -Record         # re-record the reference screenshots
  ./scripts/android-safety-net.ps1 -SkipJvm -SkipSyncCheck
#>
param(
    [switch]$Record,
    [switch]$SkipJvm,
    [switch]$SkipSyncCheck,
    [switch]$NoBuild,
    [string[]]$Avds = @("Phone_Pixel8", "Tablet_Pixel", "GoogleTV_1080p")
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\jdk-17" }
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$out = Join-Path $root "build\safety-net"
$runner = "com.syncplaytv.test/androidx.test.runner.AndroidJUnitRunner"
$deviceOut = "/sdcard/Android/data/com.syncplaytv/files/screenshots"
$results = [ordered]@{}

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

function Get-Emulators { & $adb devices | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches[0].Groups[1].Value } }
function Get-AvdName([string]$s) { $o = & $adb -s $s shell getprop ro.boot.qemu.avd_name 2>$null; if ($o) { ($o | Select-Object -First 1).Trim() } }

function Start-Avd([string]$avd) {
    $serial = Get-Emulators | Where-Object { (Get-AvdName $_) -eq $avd } | Select-Object -First 1
    if ($serial) { return $serial }
    Step "Starting $avd"
    $before = @(Get-Emulators)
    # Cold boot: a stale quick-boot snapshot can leave the emulator hanging before adb connects.
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $avd, "-no-snapshot-load", "-netdelay", "none", "-netspeed", "full") -WindowStyle Minimized | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    while (-not $serial) {
        if ((Get-Date) -gt $deadline) { throw "$avd did not appear in adb within 3 minutes." }
        Start-Sleep -Seconds 2
        $serial = Get-Emulators | Where-Object { $before -notcontains $_ } | Select-Object -First 1
    }
    return $serial
}

function Wait-Boot([string]$serial) {
    & $adb -s $serial wait-for-device
    $deadline = (Get-Date).AddMinutes(5)
    while ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -notmatch "1") {
        if ((Get-Date) -gt $deadline) { throw "$serial did not finish booting within 5 minutes." }
        Start-Sleep -Seconds 2
    }
    & $adb -s $serial shell settings put secure stylus_handwriting_enabled 0 2>$null
}

Push-Location $root
try {
    # References are packaged into the test APK, so newly recorded ones need a rebuild.
    $testApkFile = Get-Item "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" -ErrorAction SilentlyContinue
    $newestShot = Get-ChildItem "app\src\androidTest\screenshots" -Recurse -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($NoBuild -and -not $Record -and ((-not $testApkFile) -or ($newestShot -and $newestShot.LastWriteTime -gt $testApkFile.LastWriteTime))) {
        Write-Host "   reference screenshots changed since the last build; rebuilding" -ForegroundColor Yellow
        $NoBuild = $false
    }
    if (-not $NoBuild) {
        Step "Building the app and test APKs"
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    }
    if (-not $SkipJvm -and -not $Record) {
        Step "JVM tests"
        & .\gradlew.bat test --console=plain -q
        $results["JVM tests"] = if ($LASTEXITCODE -eq 0) { "PASS" } else { "FAIL" }
    }
    # Three emulators need the memory the Gradle and Kotlin daemons hold (a starved emulator stops responding).
    & .\gradlew.bat --stop -q | Out-Null
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "KotlinCompileDaemon" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

    $devices = [ordered]@{}
    foreach ($avd in $Avds) { $devices[$avd] = Start-Avd $avd }
    foreach ($avd in $Avds) { Wait-Boot $devices[$avd] }

    $apk = "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $out | Out-Null

    $jobs = foreach ($avd in $Avds) {
        $serial = $devices[$avd]
        $tv = $avd -like "*TV*"
        $filter = if ($Record) {
            @("-e", "class", $(if ($tv) { "com.syncplaytv.tv.TvScreenshotTest" } else { "com.syncplaytv.screenshots.MobileScreenshotTest" }), "-e", "recordScreenshots", "true")
        } elseif ($tv) {
            @("-e", "package", "com.syncplaytv.tv")
        } else {
            @("-e", "notPackage", "com.syncplaytv.tv,com.syncplaytv.synccheck")
        }
        Start-Job -Name $avd -ArgumentList $adb, $serial, $apk, $testApk, $runner, $deviceOut, $filter, $root -ScriptBlock {
            param($adb, $serial, $apk, $testApk, $runner, $deviceOut, $filter, $root)
            Set-Location $root
            & $adb -s $serial install -r -t $apk | Out-Null
            & $adb -s $serial install -r -t $testApk | Out-Null
            & $adb -s $serial shell rm -rf $deviceOut | Out-Null
            $args = @("-s", $serial, "shell", "am", "instrument", "-w", "-r") + $filter + @($runner)
            & $adb @args 2>&1
        }
    }
    Step "Running the instrumented suites on $($Avds -join ', ')"
    $jobs | Wait-Job | Out-Null

    foreach ($avd in $Avds) {
        $serial = $devices[$avd]
        $log = Receive-Job -Name $avd
        $log | Out-File (Join-Path $out "$avd.txt")
        $ok = ($log | Select-String "^OK \(\d+ tests?\)").Count -gt 0
        $summary = ($log | Select-String "^(OK \(|Tests run:)" | Select-Object -Last 1).Line
        $results[$avd] = "$(if ($ok) { 'PASS' } else { 'FAIL' })  $summary"
        if (-not $ok) {
            $log | Select-String "^(INSTRUMENTATION_STATUS: (test|stack)=|There w|\d+\) )" | Select-Object -First 40 | ForEach-Object { Write-Host "   [$avd] $($_.Line)" }
        }
        $kind = if ($Record) { "record" } else { "failures" }
        $listing = & $adb -s $serial shell ls "$deviceOut/$kind" 2>$null
        if ($LASTEXITCODE -eq 0 -and $listing) {
            if ($Record) {
                $dest = Join-Path $root "app\src\androidTest\screenshots"
                New-Item -ItemType Directory -Force $dest | Out-Null
                foreach ($dir in $listing) {
                    $dir = $dir.Trim()
                    if (-not $dir) { continue }
                    Remove-Item -Recurse -Force (Join-Path $dest $dir) -ErrorAction SilentlyContinue
                    & $adb -s $serial pull "$deviceOut/record/$dir" $dest 2>&1 | Out-Null
                    Write-Host "   recorded $((Get-ChildItem (Join-Path $dest $dir) -Filter *.png).Count) screenshots into app/src/androidTest/screenshots/$dir"
                }
            } else {
                & $adb -s $serial pull "$deviceOut/failures" $out 2>&1 | Out-Null
                Write-Host "   [$avd] screenshot diffs saved under build/safety-net/failures/" -ForegroundColor Yellow
            }
        }
    }
    Remove-Job -Name $Avds -Force -ErrorAction SilentlyContinue

    if (-not $SkipSyncCheck -and -not $Record) {
        $phone = $Avds | Where-Object { $_ -like "Phone*" } | Select-Object -First 1
        $tvAvd = $Avds | Where-Object { $_ -like "*TV*" } | Select-Object -First 1
        if ($phone -and $tvAvd) {
            & "$PSScriptRoot\sync-check.ps1" -Leader $devices[$phone] -Follower $devices[$tvAvd] -NoInstall
            $results["Sync check (phone + TV)"] = if ($LASTEXITCODE -eq 0) { "PASS" } else { "FAIL" }
        }
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Safety net results" -ForegroundColor Cyan
$failed = $false
foreach ($k in $results.Keys) {
    $v = $results[$k]
    if ($v -notlike "PASS*") { $failed = $true }
    Write-Host ("  {0,-26} {1}" -f $k, $v) -ForegroundColor $(if ($v -like "PASS*") { "Green" } else { "Red" })
}
if ($failed) { exit 1 }
