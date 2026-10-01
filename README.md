# SyncplayTV

A [Syncplay](https://syncplay.pl/) client for Android TV / Google TV, phones and tablets with [mpv](https://mpv.io/) built in.
Media comes from your [Jellyfin](https://jellyfin.org/) server on the local network or, on phones and tablets,
from files on the device.

- Joins any Syncplay server/room; play, pause and seek are synchronised with desktop Syncplay users.
- Plays through libmpv (hardware decoding with software fallback, libass subtitles, audio/subtitle track switching).
- Browses Jellyfin libraries (Quick Connect or username/password login).
- Shared playlists: when someone selects an entry, the app finds that filename in your media folders or Jellyfin
  and loads it automatically.
- One APK: the remote-friendly TV UI on Google TV, a touch UI on phones (bottom navigation) and tablets
  (navigation rail). On phones and tablets you can also open a single video, use "Open with" from a file
  manager, or add media folders that work like Syncplay's media directories.

![Tablet: room, shared playlist and chat](docs/screenshots/tablet-room.png)

## Project layout

| Module | What it is |
| --- | --- |
| `syncplay-protocol` | Pure Kotlin Syncplay protocol client (JSON over TCP, optional TLS) |
| `media-source` | `MediaSource` interface and the Jellyfin implementation (pure Kotlin, OkHttp) |
| `player-mpv` | libmpv wrapper exposing a small `Player` interface |
| `app` | TV UI (Compose for TV) and phone/tablet UI (Material 3), `SyncController`, `PlaylistController`, `LocalLibrary` |

## Development on Windows

```powershell
# One-time: JDK 17, Android SDK, emulator, Google TV and phone system images
./scripts/setup-toolchain.ps1

# One-time: create the emulators (GoogleTV_1080p, GoogleTV_4K, Phone_Pixel8, Tablet_Pixel)
./scripts/create-avds.ps1                 # or -Kind tv / -Kind phone,tablet

# Build, boot the emulator, install and launch (prints the serial, e.g. emulator-5556)
./scripts/run-tv.ps1                      # 1080p Google TV
./scripts/run-app.ps1 -Avd Phone_Pixel8   # phone; boots next to a running TV
./scripts/run-app.ps1 -Avd Tablet_Pixel -NoBuild
./scripts/run-app.ps1 -Avd Phone_Pixel8 -Ui tv   # debug: force the TV UI

# Send remote-control keys to the emulator
./scripts/remote.ps1 down down ok back
./scripts/remote.ps1 -Text "Neptunia"

# Touch input: tap/swipe by coordinates, rotate the screen
./scripts/remote.ps1 -Serial emulator-5554 tap 540 1200 swipe 540 1800 540 600 rotate landscape

# Tap by visible text, content description or Compose test tag (works in the system file picker too)
./scripts/ui.ps1 -Serial emulator-5554 -List
./scripts/ui.ps1 -Serial emulator-5554 -Id tab_Room
./scripts/ui.ps1 -Serial emulator-5554 "Use this folder" -Wait 10

# Screenshot an emulator (saved under %TEMP%\syncplaytv-shots, path is printed)
./scripts/screenshot.ps1 -Name home
./scripts/screenshot.ps1 -Serial emulator-5554 -Name phone-home
```

With several emulators running, pass `-Serial` to the scripts. The phone and tablet images include the
Play Store's Gboard, whose stylus tutorial swallows scripted text; `run-app.ps1` turns it off.

### Installing on a real TV

```powershell
./scripts/serve-apk.ps1            # builds the debug APK and serves it on port 8080
```

On the TV, open the printed `http://<pc-ip>:8080/` in a browser, or install the free **Downloader** app
(by AFTVnews) and enter `http://<pc-ip>:8080/a` for a direct download. Allow that app to install unknown apps
when Android asks, then open the file and choose Install. Re-run the script after changes and download again
to update. Debug builds are signed with this PC's debug key, so an APK from CI (different key) can't update a
locally built install without uninstalling first.

### Installing on a phone or tablet

It's the same APK. With the server above running, open `http://<pc-ip>:8080/` in the phone's browser, tap
the download, and allow the browser to install unknown apps when asked. With USB debugging enabled you can
also run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

On a phone or tablet:

- **Local files.** Use "Files on this device" to open a single video, or add a folder such as `Movies`.
  When the room's playlist moves to a file, the app looks for that filename in your folders first, then in
  Jellyfin, so a friend's desktop Syncplay sees the same file.
- **Player controls.** The player goes fullscreen in landscape. Tap to show the controls. Double-tap the
  left or right side to seek, or the middle to pause. The playlist, room, chat and track panels open as
  bottom sheets.
- **Background playback.** Keep the app in front while watching. Android cuts network access for apps in
  the background, so the room connection drops after a few seconds. It reconnects and resyncs
  automatically when you come back.

Inside the emulator, the host PC is `10.0.2.2`, so a Jellyfin server on this PC is `http://10.0.2.2:8096`
and a Syncplay server on this PC is `10.0.2.2:8999`. The player overlay auto-hides after 6 seconds, so
send key sequences in a single `remote.ps1` call.

### Unit and integration tests

```powershell
./gradlew :syncplay-protocol:test :media-source:test

# Protocol test against a real syncplay-server (see below)
$env:SYNCPLAY_TEST_SERVER = "127.0.0.1:8999"; ./gradlew :syncplay-protocol:test

# Live Jellyfin test (optional)
# (API key from Jellyfin Dashboard > API Keys, user id from the user's profile URL; keep these out of the repo)
$env:JELLYFIN_URL = "http://localhost:8096"; $env:JELLYFIN_TOKEN = "..."; $env:JELLYFIN_USER_ID = "..."
$env:JELLYFIN_RESOLVE = "Some Show - S01E01 - Title.mkv"
./gradlew :media-source:test --tests "*LiveJellyfinTest*"
```

### Instrumented tests (phone emulator)

Compose UI and playback tests in `app/src/androidTest` cover:

- device detection;
- navigation;
- the connect and settings forms;
- player gestures and sheets;
- playing a generated test clip through a `content://` URI.

Run them on a booted `Phone_Pixel8`:

```powershell
./gradlew :app:connectedDebugAndroidTest
```

If Gradle can't clean its result folders (OneDrive sometimes locks them), install both APKs and run the
tests through adb instead:

```powershell
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.syncplaytv.test/androidx.test.runner.AndroidJUnitRunner
```

CI (`.github/workflows/build.yml`) does three things:

- runs the unit tests and the protocol test against a real Syncplay server;
- runs the instrumented tests on an API 34 phone emulator;
- uploads the debug APK as an artifact.

### Testing sync locally

```powershell
./scripts/local-syncplay-server.ps1    # clones Syncplay, runs its server on port 8999
```

Connect the TV app to `10.0.2.2:8999` and join a room (e.g. `tvtest`). A phone emulator running next to it
can join the same room. Push a video with `adb -s emulator-5554 push episode.mkv /sdcard/Movies/` and add
the `Movies` folder in the app; it then resolves that filename locally while the TV uses Jellyfin. Then either:

**A desktop Syncplay client with mpv** (run from the same source checkout, no install needed besides mpv):

```powershell
winget install shinchiro.mpv
.tools\syncplay-venv\Scripts\python.exe .tools\syncplay-src\syncplayClient.py --no-gui --no-store `
    -a 127.0.0.1:8999 -n Desktop -r tvtest --player-path "C:\Program Files\mpv\mpv.exe" "D:\path\to\episode.mkv"

# Drive that desktop mpv from a script (play / pause / seek / get <property>)
python scripts/mpv_ipc.py seek 600
python scripts/mpv_ipc.py get time-pos
```

**Or the scripted peer** `scripts/fake_peer.py` (stdlib only), which speaks the protocol with a virtual clock and
fails with exit code 1 when an expectation isn't met:

```powershell
python scripts/fake_peer.py --room tvtest --name Peer --script (
  "wait 2; playlist Some Show - S01E01 - Title.mkv; select 0; wait 10; expect-user TV ready=true; " +
  "ready; play; expect-room paused=false; seek 300; expect-room position~300; wait 3; drift -7; wait 4; status; quit")
```

The TV should resolve the playlist entry through Jellyfin, load it, report the same file and mark itself ready,
then follow the peer's unpause and seek, slow down for small drifts and rewind for large ones.

![Player following a desktop Syncplay client](docs/screenshots/player-desktop-sync.png)

## License

Apache 2.0, same as Syncplay.
