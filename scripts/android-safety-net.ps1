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

  An emulator it has to start boots from its yarmiplaytv_clean snapshot, saved after the first cold boot once
  the device has settled; delete <avd>.avd/snapshots/yarmiplaytv_clean to make the next start rebuild it.

  -Record writes new reference screenshots into app/src/androidTest/screenshots/<device>/ instead of
  comparing. Review them (git diff) before committing.

  -Tests runs only the given instrumented classes (or Class#method), each on the devices it targets:
  com.yarmiplaytv.tv.* on the TV, everything else on the phone and tablet. It skips the sync check.

.EXAMPLE
  ./scripts/android-safety-net.ps1                 # full run
  ./scripts/android-safety-net.ps1 -Record         # re-record the reference screenshots
  ./scripts/android-safety-net.ps1 -SkipJvm -SkipSyncCheck
  ./scripts/android-safety-net.ps1 -SkipJvm -Tests LocalPlaybackTest,MobileScreenshotTest#playerAndSheets
#>
param(
    [switch]$Record,
    [switch]$SkipJvm,
    [switch]$SkipSyncCheck,
    [switch]$NoBuild,
    [string[]]$Tests,
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
$runner = "com.yarmiplaytv.test/com.yarmiplaytv.YarmiplayTestRunner"
$appPackage = ($runner -split "/")[0] -replace "\.test$", ""
$deviceOut = "/sdcard/Android/data/com.yarmiplaytv/files/screenshots"
$results = [ordered]@{}

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

if (-not ("EmulatorQos" -as [type])) {
    Add-Type @"
using System; using System.Runtime.InteropServices;
public static class EmulatorQos {
    [StructLayout(LayoutKind.Sequential)] struct PowerThrottlingState { public uint Version; public uint ControlMask; public uint StateMask; }
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetProcessInformation(IntPtr process, int infoClass, ref PowerThrottlingState info, int size);
    // ProcessPowerThrottling with EXECUTION_SPEED | IGNORE_TIMER_RESOLUTION controlled and off.
    public static bool NoThrottling(IntPtr process) {
        var state = new PowerThrottlingState { Version = 1, ControlMask = 0x1 | 0x4, StateMask = 0 };
        return SetProcessInformation(process, 4, ref state, Marshal.SizeOf(typeof(PowerThrottlingState)));
    }
}
"@
}

function Get-Emulators { & $adb devices | Select-String "^(emulator-\d+)\s+device" | ForEach-Object { $_.Matches[0].Groups[1].Value } }
function Get-AvdName([string]$s) { $o = & $adb -s $s shell getprop ro.boot.qemu.avd_name 2>$null; if ($o) { ($o | Select-Object -First 1).Trim() } }

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE ".android\avd" }
$cleanSnapshot = "yarmiplaytv_clean"
$launched = @{}

function Get-CleanSnapshot([string]$avd) {
    $dir = Join-Path $avdHome "$avd.avd\snapshots\$cleanSnapshot"
    $pb = Get-Item (Join-Path $dir "snapshot.pb") -ErrorAction SilentlyContinue
    $ram = Get-Item (Join-Path $dir "ram.bin") -ErrorAction SilentlyContinue
    # An interrupted save leaves a stub or stale snapshot.pb.
    if ($pb -and $ram -and $pb.Length -gt 100 -and $pb.LastWriteTime -ge $ram.LastWriteTime) { $dir }
}

function Start-Avd([string]$avd) {
    $serial = Get-Emulators | Where-Object { (Get-AvdName $_) -eq $avd } | Select-Object -First 1
    if ($serial) { return $serial }
    $snapshot = Get-CleanSnapshot $avd
    foreach ($attempt in 1, 2) {
        # A settled snapshot of a freshly booted device loads in seconds and -no-snapshot-save keeps it pristine.
        # Otherwise a cold boot: a stale quick-boot snapshot can leave the emulator hanging before adb connects.
        $boot = if ($snapshot) { @("-snapshot", $cleanSnapshot, "-no-snapshot-save") } else { @("-no-snapshot-load") }
        Step "Starting $avd ($(if ($snapshot) { "from the $cleanSnapshot snapshot" } else { 'cold boot' }))"
        $before = @(Get-Emulators)
        $process = Start-Process -FilePath $emulator -ArgumentList (@("-avd", $avd) + $boot + @("-netdelay", "none", "-netspeed", "full")) -WindowStyle Minimized -PassThru
        $launched[$avd] = Get-Date
        $deadline = (Get-Date).AddMinutes($(if ($snapshot) { 1 } else { 3 }))
        while (-not $serial -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $serial = Get-Emulators | Where-Object { $before -notcontains $_ } | Select-Object -First 1
        }
        if ($serial) { return $serial }
        if (-not $snapshot) { throw "$avd did not appear in adb within 3 minutes." }
        Write-Host "   $avd hung loading the snapshot; discarding it" -ForegroundColor Yellow
        & taskkill /T /F /PID $process.Id 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        Remove-Item -Recurse -Force $snapshot -ErrorAction SilentlyContinue
        $snapshot = $null
    }
}

# Runs as a job per emulator this script started. A snapshot boot only needs the clock set (the guest keeps the
# snapshot's time); after a cold boot the device settles, compiles its apps, and is saved as the clean snapshot.
$prepareDevice = {
    param($adb, $serial, $avd, $secondsSinceLaunch, $snapshotName, $appPackage)
    function Get-Busy {
        $a = ((& $adb -s $serial shell head -1 /proc/stat) -split "\s+")[1..8] | ForEach-Object { [long]$_ }
        Start-Sleep -Seconds 3
        $b = ((& $adb -s $serial shell head -1 /proc/stat) -split "\s+")[1..8] | ForEach-Object { [long]$_ }
        $total = 0; $idle = 0
        for ($i = 0; $i -lt 8; $i++) { $total += $b[$i] - $a[$i]; if ($i -in 3, 4) { $idle += $b[$i] - $a[$i] } }
        if ($total -le 0) { 100 } else { 100 * ($total - $idle) / $total }
    }
    function Wait-Idle {
        # Under 25% busy in two consecutive samples, or give up after two minutes.
        $calm = 0
        $deadline = (Get-Date).AddMinutes(2)
        while ($calm -lt 2 -and (Get-Date) -lt $deadline) { if ((Get-Busy) -lt 25) { $calm++ } else { $calm = 0 } }
    }
    $start = Get-Date
    $uptime = [double]((& $adb -s $serial shell cat /proc/uptime) -split " ")[0]
    & $adb -s $serial shell cmd network_time_update_service force_refresh | Out-Null
    if ($uptime -gt $secondsSinceLaunch + (New-TimeSpan $start (Get-Date)).TotalSeconds) {
        Wait-Idle
        $skew = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long](& $adb -s $serial shell date +%s)
        "$avd loaded from the snapshot, ready in {0:N0} s$(if ([math]::Abs($skew) -gt 2) { " (clock still $skew s off)" })" -f ($secondsSinceLaunch + (New-TimeSpan $start (Get-Date)).TotalSeconds)
    } else {
        Wait-Idle
        & $adb -s $serial shell cmd package bg-dexopt-job | Out-Null
        foreach ($p in "$appPackage.test", $appPackage) { & $adb -s $serial uninstall $p 2>&1 | Out-Null }
        Wait-Idle
        $saved = (& $adb -s $serial emu avd snapshot save $snapshotName 2>&1) -match "^OK"
        "$avd cold-booted and settled in {0:N0} s; $(if ($saved) { "saved as $snapshotName" } else { 'saving the snapshot failed' })" -f ($secondsSinceLaunch + (New-TimeSpan $start (Get-Date)).TotalSeconds)
    }
}

function Get-SpinningProcess([string]$serial) {
    # Right after boot Play services and app optimisation are legitimately busy.
    $uptime = [double]((& $adb -s $serial shell cat /proc/uptime 2>$null) -split " ")[0]
    if ($uptime -lt 300) { return }
    $hogs = foreach ($i in 1..2) {
        & $adb -s $serial shell top -b -n 1 -m 5 -s 1 -o %CPU,NAME 2>$null | ForEach-Object {
            if ($_ -match "^\s*(\d+(\.\d+)?)\s+(\S*(allocator|systemui|launcher|surfaceflinger)\S*)" -and [double]$matches[1] -ge 40) { $matches[3] }
        }
        Start-Sleep -Seconds 1
    }
    # Busy in both samples, not just a momentary spike.
    $hogs | Group-Object | Where-Object Count -ge 2 | Select-Object -First 1 -ExpandProperty Name
}

function Get-SlowTests([string]$serial, [double]$since) {
    # The suite is the first instrumentation run on the device since $since; the sync check may run there later.
    $runPid = $null
    $started = @{}
    $times = foreach ($line in & $adb -s $serial logcat -d -v epoch -s TestRunner:I 2>$null) {
        if ($line -notmatch '^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+\w\s+TestRunner\s*:\s*(.*)$') { continue }
        $t, $p, $msg = [double]$matches[1], $matches[2], $matches[3]
        if ($t -lt $since) { continue }
        if (-not $runPid) { if ($msg -like "run started:*") { $runPid = $p }; continue }
        if ($p -ne $runPid) { continue }
        if ($msg -match '^started: (\w+)\((?:.*\.)?(\w+)\)') { $started["$($matches[2])#$($matches[1])"] = $t }
        elseif ($msg -match '^finished: (\w+)\((?:.*\.)?(\w+)\)' -and $started.ContainsKey("$($matches[2])#$($matches[1])")) {
            $name = "$($matches[2])#$($matches[1])"
            [pscustomobject]@{ Test = $name; Seconds = $t - $started[$name] }
        }
    }
    $times | Sort-Object Seconds -Descending | Select-Object -First 5
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
    $testsByAvd = @{}
    if ($Tests) {
        $SkipSyncCheck = $true
        $resolved = foreach ($t in ($Tests -split ",") | Where-Object { $_ }) {
            $class, $method = $t.Trim() -split "#", 2
            if ($class -notmatch "\.") {
                $src = Get-ChildItem "app\src\androidTest\java" -Recurse -Filter "$class.kt" | Select-Object -First 1
                if (-not $src) { throw "No instrumented test class named $class." }
                $pkg = (Select-String -Path $src.FullName -Pattern "^package\s+(\S+)" | Select-Object -First 1).Matches[0].Groups[1].Value
                $class = "$pkg.$class"
            }
            if ($method) { "$class#$method" } else { $class }
        }
        foreach ($avd in $Avds) {
            $tv = $avd -like "*TV*"
            $mine = @($resolved | Where-Object { ($_ -like "com.yarmiplaytv.tv.*") -eq $tv })
            if ($mine) { $testsByAvd[$avd] = $mine -join "," }
        }
        $Avds = @($Avds | Where-Object { $testsByAvd.ContainsKey($_) })
        if (-not $Avds) { throw "None of the selected devices runs $($Tests -join ', ')." }
    }

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
    # Debug variants only (release runs the same tests after a second full compile); the desktop player's
    # libmpv tests aren't part of the Android safety net.
    $jvmTasks = @("testDebugUnitTest", ":syncplay-protocol:test", ":media-source:test", ":player-api:test", ":shared:desktopTest")
    $runJvm = -not $SkipJvm -and -not $Record
    # Two runs on the same emulators (e.g. from two working copies) break each other's tests.
    $emulatorLock = New-Object System.Threading.Mutex($false, "Global\YarmiplayTV-safety-net")
    try { $locked = $emulatorLock.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) {
        Step "Waiting for another safety-net run to finish with the emulators"
        try { [void]$emulatorLock.WaitOne() } catch [System.Threading.AbandonedMutexException] { }
        $locked = $true
    }

    # A worn emulator has system processes spinning (graphics allocator, System UI) and stays slow and flaky
    # until it's restarted. Even without that, suites ran 2.5-4x slower on emulators that had been up for
    # half a day than on fresh ones, and a restart from the clean snapshot takes well under a minute.
    $sick = foreach ($serial in Get-Emulators) {
        $avd = Get-AvdName $serial
        if ($Avds -notcontains $avd) { continue }
        $hog = Get-SpinningProcess $serial
        $hours = [double]((& $adb -s $serial shell cat /proc/uptime 2>$null) -split " ")[0] / 3600
        $reason = if ($hog) { "$hog is spinning" } elseif ($hours -gt 2) { "up for {0:N1} hours" -f $hours }
        if ($reason) {
            Step "Restarting $avd ($reason)"
            & $adb -s $serial emu kill 2>&1 | Out-Null
            $serial
        }
    }
    $deadline = (Get-Date).AddSeconds(60)
    while ($sick -and (Get-Emulators | Where-Object { $sick -contains $_ }) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }

    # When the host runs short of memory the emulators get paged out and stall (I/O-bound slowness, ANRs), so
    # the Gradle and Kotlin daemons give their memory back unless there's plenty to spare.
    $running = @(Get-Emulators | ForEach-Object { Get-AvdName $_ })
    $freeGb = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
    if (($Avds | Where-Object { $running -notcontains $_ }) -or $freeGb -lt 6) {
        & .\gradlew.bat --stop -q | Out-Null
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "KotlinCompileDaemon" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }

    $devices = [ordered]@{}
    foreach ($avd in $Avds) { $devices[$avd] = Start-Avd $avd }
    foreach ($avd in $Avds) { Wait-Boot $devices[$avd] }
    # Emulator windows are in the background; on hybrid CPUs Windows would otherwise throttle them onto efficiency cores.
    Get-Process qemu-system* -ErrorAction SilentlyContinue | ForEach-Object { [void][EmulatorQos]::NoThrottling($_.Handle) }
    $prep = @(foreach ($avd in $Avds) {
        if (-not $launched.ContainsKey($avd)) { continue }
        Start-Job -ScriptBlock $prepareDevice -ArgumentList $adb, $devices[$avd], $avd, ((Get-Date) - $launched[$avd]).TotalSeconds, $cleanSnapshot, $appPackage
    })
    if ($prep) {
        $prep | Wait-Job | Receive-Job | ForEach-Object { Write-Host "   $_" }
        $prep | Remove-Job
    }

    $apk = "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $out | Out-Null

    $jobs = foreach ($avd in $Avds) {
        $serial = $devices[$avd]
        $tv = $avd -like "*TV*"
        $filter = @("-e", "screenshots", "true") + $(if ($Record) {
            @("-e", "class", $(if ($tv) { "com.yarmiplaytv.tv.TvScreenshotTest" } else { "com.yarmiplaytv.screenshots.MobileScreenshotTest" }), "-e", "recordScreenshots", "true")
        } elseif ($Tests) {
            @("-e", "class", $testsByAvd[$avd])
        } elseif ($tv) {
            @("-e", "package", "com.yarmiplaytv.tv")
        } else {
            @("-e", "notPackage", "com.yarmiplaytv.tv,com.yarmiplaytv.synccheck")
        })
        Start-Job -Name $avd -ArgumentList $adb, $serial, $apk, $testApk, $runner, $deviceOut, $filter, $root, $appPackage -ScriptBlock {
            param($adb, $serial, $apk, $testApk, $runner, $deviceOut, $filter, $root, $appPackage)
            Set-Location $root
            # Room for a whole suite's log (mpv is chatty); the per-test timings are read from it.
            & $adb -s $serial logcat -G 16M | Out-Null
            foreach ($install in @(@($apk, $appPackage), @($testApk, "$appPackage.test"))) {
                $output = & $adb -s $serial install -r -t $install[0] 2>&1 | Out-String
                # A build from another working copy (other signing key or a higher version) blocks the update.
                if ($output -match "INSTALL_FAILED_(UPDATE_INCOMPATIBLE|VERSION_DOWNGRADE)") {
                    & $adb -s $serial uninstall $install[1] | Out-Null
                    & $adb -s $serial install -r -t $install[0] | Out-Null
                }
            }
            & $adb -s $serial shell rm -rf $deviceOut | Out-Null
            $args = @("-s", $serial, "shell", "am", "instrument", "-w", "-r") + $filter + @($runner)
            & $adb @args 2>&1
        }
    }
    $suitesStarted = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000
    Step "Running the instrumented suites on $($Avds -join ', ')"

    # With memory to spare once the emulators are up, the JVM tests run on the host alongside the suites;
    # otherwise after them, so Gradle doesn't page the emulators out.
    $jvmJob = $null
    if ($runJvm -and (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB -ge 6) {
        $runJvm = $false
        Step "JVM tests (on the host, while the suites run)"
        $jvmJob = Start-Job -ArgumentList $root, $env:JAVA_HOME, $jvmTasks -ScriptBlock {
            param($root, $javaHome, $tasks)
            Set-Location $root
            $env:JAVA_HOME = $javaHome
            & .\gradlew.bat @tasks --console=plain -q 2>&1 | Out-String -Stream
            "JVM-EXIT $LASTEXITCODE"
        }
    }

    # The sync check needs a mobile device and the TV; it runs on whichever of phone and tablet finishes its
    # suite first, while the other one is still busy.
    $syncJob = $null
    if (-not $SkipSyncCheck -and -not $Record) {
        $mobiles = @($Avds | Where-Object { $_ -notlike "*TV*" })
        $tvAvd = $Avds | Where-Object { $_ -like "*TV*" } | Select-Object -First 1
        if ($mobiles -and $tvAvd) {
            $mobile = (Wait-Job -Name $mobiles -Any).Name
            Wait-Job -Name $tvAvd | Out-Null
            Step "Sync check on $mobile + $tvAvd"
            $syncLabel = "Sync check ($(($mobile -replace '_.*', '').ToLower()) + TV)"
            $syncJob = Start-Job -ArgumentList "$PSScriptRoot\sync-check.ps1", $devices[$mobile], $devices[$tvAvd] -ScriptBlock {
                param($script, $leader, $follower)
                & $script -Leader $leader -Follower $follower -NoInstall *>&1 | Out-String -Stream
                "SYNC-EXIT $LASTEXITCODE"
            }
        }
    }
    $jobs | Wait-Job | Out-Null

    $slowTests = [ordered]@{}
    foreach ($avd in $Avds) {
        $serial = $devices[$avd]
        $job = Get-Job -Name $avd
        $log = Receive-Job $job
        $log | Out-File (Join-Path $out "$avd.txt")
        $ok = ($log | Select-String "^OK \(\d+ tests?\)").Count -gt 0
        $summary = ($log | Select-String "^(OK \(|Tests run:)" | Select-Object -Last 1).Line
        $results[$avd] = "$(if ($ok) { 'PASS' } else { 'FAIL' })  $summary in {0:N0} s" -f ($job.PSEndTime - $job.PSBeginTime).TotalSeconds
        $slowTests[$avd] = Get-SlowTests $serial ($suitesStarted - 5)
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

    if ($syncJob) {
        $syncLog = @($syncJob | Wait-Job | Receive-Job)
        $syncLog | Where-Object { $_ -notmatch "^SYNC-EXIT" } | ForEach-Object { Write-Host "   $_" }
        $results[$syncLabel] = if ($syncLog -contains "SYNC-EXIT 0") { "PASS" } else { "FAIL" }
        Remove-Job $syncJob -Force
    }
    if ($jvmJob) {
        $jvmLog = @($jvmJob | Wait-Job | Receive-Job)
        $jvmOk = $jvmLog -contains "JVM-EXIT 0"
        if (-not $jvmOk) { $jvmLog | Where-Object { $_ -notmatch "^JVM-EXIT" } | Select-Object -Last 40 | ForEach-Object { Write-Host "   [JVM] $_" } }
        $results["JVM tests"] = "$(if ($jvmOk) { 'PASS' } else { 'FAIL' })  in {0:N0} s" -f ($jvmJob.PSEndTime - $jvmJob.PSBeginTime).TotalSeconds
        Remove-Job $jvmJob -Force
    }
    if ($runJvm) {
        Step "JVM tests"
        $jvmStart = Get-Date
        & .\gradlew.bat @jvmTasks --console=plain -q
        $results["JVM tests"] = "$(if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' })  in {0:N0} s" -f ((Get-Date) - $jvmStart).TotalSeconds
    }
} finally {
    if ($locked) { $emulatorLock.ReleaseMutex() }
    Pop-Location
}

if ($slowTests) {
    Write-Host ""
    Write-Host "Slowest tests" -ForegroundColor Cyan
    foreach ($avd in $slowTests.Keys) {
        $line = ($slowTests[$avd] | ForEach-Object { "{0} {1:N0}s" -f $_.Test, $_.Seconds }) -join ", "
        if ($line) { Write-Host ("  {0,-16} {1}" -f $avd, $line) }
    }
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
exit 0
