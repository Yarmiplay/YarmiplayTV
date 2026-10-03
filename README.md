# YarmiplayTV

A [Syncplay](https://syncplay.pl/) client for Android TV / Google TV, phones, tablets and Windows, macOS and Linux
desktops, with [mpv](https://mpv.io/) built in. Media comes from your [Jellyfin](https://jellyfin.org/) and
[Plex](https://www.plex.tv/) servers or from files on the device.

- Joins any Syncplay server/room; play, pause and seek are synchronised with desktop Syncplay users.
- Plays through libmpv (hardware decoding with software fallback, libass subtitles, audio/subtitle track switching).
- Browses Jellyfin libraries (Quick Connect or username/password login) and Plex libraries (link the device at
  plex.tv/link, then pick a server). You can stay signed in to several Jellyfin and several Plex servers at once
  (add or sign out of each under Media servers); their libraries show side by side, home has a "Continue
  watching" row per server, and searches cover all of them. You still join one Syncplay room at a time. Files
  are always played as the original file (direct play), so the name matches what other Syncplay users have.
- Shared playlists: when someone selects an entry, the app finds that filename in your media folders or on your
  servers and loads it automatically. Each file is streamed from one place only: your media folders first, then
  a server with exactly that file name (Settings > Preferred server decides when several have it), and only if
  no server has the exact name, a looser match (punctuation and case ignored, then a title search).
- Reports what you watch back to your servers: progress while playing, and "watched" once 90% is played. When
  several servers have the same file, each is told, also when the file plays from your media folders.
  The app never jumps to a server's saved position, since the room decides where playback is. Turn reporting
  off with Settings > Report progress to your media servers.
- Like Syncplay, it warns when someone's copy of a file differs from yours: the playlist marks the entry when
  their file size differs (with both sizes when they're shared), and the room list says whether the name, size
  or duration differ. It's only a warning; playback and sync carry on as usual.
- One APK: the remote-friendly TV UI on Google TV, a touch UI on phones (bottom navigation) and tablets
  (navigation rail). On phones and tablets you can also open a single video, use "Open with" from a file
  manager, or add media folders that work like Syncplay's media directories.
- A desktop app with the same UI plus a side panel that edits the room's playlist and chats the way the
  desktop Syncplay client does.
- Each room's playlist is remembered per server and put back when you rejoin a room whose playlist is empty
  (Settings > Remember room playlists).

![Tablet: room, shared playlist and chat](docs/screenshots/tablet-room.png)

## Download

The [download page](https://yarmiplay.github.io/YarmiplayTV/) has a card per device type (Google TV, Android
phone and tablet, Windows, macOS, Linux) and highlights the one for the device you open it on. On a TV, enter
`https://yarmiplay.github.io/YarmiplayTV/a` in the Downloader app to get the APK directly.

To host your own Syncplay and Jellyfin servers from a Windows, macOS or Linux computer, use
[YarmiplayServerTV](https://yarmiplay.github.io/YarmiplayServerTV/)
([source and setup guide](https://github.com/Yarmiplay/YarmiplayServerTV#readme)).

The page is built by `scripts/download_site.py` and published by `.github/workflows/pages.yml` after every
green `Build` run on `main` and after every `Release` run (or by hand from the Actions tab). It contains every
artifact of that run whose name starts with `yarmiplaytv-`, except that the Windows installers come from the
latest GitHub release, where they are code signed (see [Code signing policy](#code-signing-policy)). Files are
matched to platforms by extension (`.apk`, `.msi`/`.exe`, `.dmg`/`.pkg`, `.deb`/`.rpm`/`.AppImage`). Platforms
without a package show how to run from source. The page's `version.json` lists each platform's package,
version and SHA-256 for the apps' update check (see [Updates](#updates)). To preview it locally:

```powershell
python scripts/download_site.py --dist app/build/outputs/apk/debug --out build/site
python -m http.server -d build/site 8000
```

### Updates

When it starts, the app from the download page reads `version.json` and shows a notice on the home screen if
its platform's package has a higher version than the one running (so only bumping `appVersion` makes an
update; builds of the same version don't). Phones and tablets link to the download page, TVs show the
Downloader short link, and the installed Windows app downloads the `.msi`, checks its SHA-256 and installs it
(per user, no administrator prompt) with **Install**, or by itself with **Install updates on launch** in
Settings: it downloads at startup and installs when the app closes. "Check for updates" in Settings turns it
all off. Release builds, which go to Google Play, never check: Play doesn't allow apps to update outside it.

The download page's APK is signed with a fixed key from the repository secrets
`YARMIPLAYTV_APK_KEYSTORE_BASE64` and `YARMIPLAYTV_APK_KEYSTORE_PASSWORD` (alias `yarmiplaytv-apk`), so a new
version installs over the old one and keeps its settings. Without the secrets (e.g. pull requests from forks)
CI signs with a throwaway debug key.

## Desktop app

Installers come from the `desktop` CI job (and the download page):

- **Windows:** `.msi` or `.exe`, installed per user, or `winget install Yarmiplay.YarmiplayTV`; libmpv is
  included. Releases are code signed when SignPath signing is set up (see below); uninstall from
  Settings > Apps.
- **macOS:** `.dmg` (Apple Silicon, not notarized: right-click the app and choose Open the first time).
  It uses Homebrew's libmpv, so run `brew install mpv` first.
- **Linux:** `.deb` for Ubuntu 22.04+ and Debian 12+; `sudo apt install ./yarmiplaytv_*.deb` also installs
  libmpv.

Open a video from the home screen, drop files or folders on the window, or use "Open with" on a video.
In a room, the player's playlist, room and chat buttons open a side panel:

- **Playlist:** add videos, a folder or URLs, drop files while the tab is open, drag or Alt+↑/↓ to
  reorder, Ctrl/Shift-click to select several, Delete to remove, Ctrl+Z to undo (anyone's last change),
  double-click to play, shuffle, loop options, and save/load the list as a text file.
- **Chat:** Enter in the player opens it; ↑/↓ recall what you sent.

Player keys: Space or K pauses, ←/→ or J/L seek, ↑/↓ change the volume, F or F11 toggles full screen,
Esc leaves full screen or goes back. The command line follows the official client:
`YarmiplayTV [--host host[:port]] [--name name] [--room room] [--password pw] [file]`.

From source (downloads the pinned libmpv on Windows; use `brew install mpv` or `sudo apt install libmpv2`
elsewhere):

```powershell
./gradlew :desktop:run --args="--host localhost:8999 --room tvtest D:/Videos/episode.mkv"
./gradlew :desktop:packageDistributionForCurrentOS     # installers in desktop/build/compose/binaries
./gradlew :player-mpv-desktop:test :desktop:test       # libmpv playback, the side panel (needs SYNCPLAY_TEST_SERVER)
./scripts/e2e-desktop.ps1                              # phone leads; TV and desktop follow; official client watches
```

## Project layout

| Module | What it is |
| --- | --- |
| `syncplay-protocol` | Pure Kotlin Syncplay protocol client (JSON over TCP, optional TLS) |
| `media-source` | `MediaSource` interface with the Jellyfin and Plex implementations, and a combined view for browsing several servers (pure Kotlin, OkHttp) |
| `player-mpv` | libmpv wrapper exposing a small `Player` interface |
| `shared` | Kotlin Multiplatform (Android and desktop): phone/tablet/desktop UI, `SyncController`, `PlaylistController`, settings, local library |
| `app` | The Android app: TV UI (Compose for TV) and the Android side of `shared` |
| `player-mpv-desktop` | libmpv on the desktop through JNA, rendered with OpenGL (or software) into Compose |
| `desktop` | The desktop app (Compose for Desktop): window, shortcuts, drag and drop, installers |

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

# Screenshot an emulator (saved under %TEMP%\yarmiplaytv-shots, path is printed)
./scripts/screenshot.ps1 -Name home
./scripts/screenshot.ps1 -Serial emulator-5554 -Name phone-home
```

With several emulators running, pass `-Serial` to the scripts. The phone and tablet images include the
Play Store's Gboard, whose stylus tutorial swallows scripted text; `run-app.ps1` turns it off.

### Installing on a real TV

```powershell
./scripts/serve-apk.ps1            # builds the debug APK and serves it on port 8080
```

It serves the same download page as GitHub Pages, with your local build. On the TV, open the printed
`http://<pc-ip>:8080/` in a browser, or install the free **Downloader** app
(by AFTVnews) and enter `http://<pc-ip>:8080/a` for a direct download. Allow that app to install unknown apps
when Android asks, then open the file and choose Install. Re-run the script after changes and download again
to update. Debug builds are signed with this PC's debug key, so an APK from CI (different key) can't update a
locally built install without uninstalling first.

In the TV player, OK, ↓ or Menu shows the controls and ↓ or Menu hides them again; with them hidden, ←/→ seek
and ↑ opens the room panel. Back closes a panel and returns to the controls (on the button that opened it), then
hides the controls. While a video plays they hide by themselves after 3 seconds; Settings > Playback > Hide
player controls after offers 2, 3, 5 or 10 seconds, Never, or a custom time.

### Installing on a phone or tablet

It's the same APK. With the server above running, open `http://<pc-ip>:8080/` in the phone's browser, tap
the download, and allow the browser to install unknown apps when asked. With USB debugging enabled you can
also run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

On a phone or tablet:

- **Local files.** Use "Files on this device" to open a single video, or add a folder such as `Movies`.
  When the room's playlist moves to a file, the app looks for that filename in your folders first, then on
  Jellyfin and Plex, so a friend's desktop Syncplay sees the same file.
- **Player controls.** The player goes fullscreen in landscape. Tap to show the controls. Double-tap the
  left or right side to seek, or the middle to pause. The playlist, room, chat and track panels open as
  bottom sheets.
- **Background playback.** Keep the app in front while watching. Android cuts network access for apps in
  the background, so the room connection drops after a few seconds. It reconnects and resyncs
  automatically when you come back.

Inside the emulator, the host PC is `10.0.2.2`, so a Jellyfin server on this PC is `http://10.0.2.2:8096`
and a Syncplay server on this PC is `10.0.2.2:8999`. The player overlay auto-hides after 3 seconds (by
default), so send key sequences in a single `remote.ps1` call.

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
adb shell am instrument -w -r com.yarmiplaytv.test/com.yarmiplaytv.YarmiplayTestRunner
```

CI (`.github/workflows/build.yml`) does four things:

- runs the unit tests and the protocol test against a real Syncplay server;
- runs the instrumented tests on an API 34 phone emulator;
- tests the desktop app on Windows, macOS and Ubuntu, builds its installers, and installs and plays the
  `.deb` on Ubuntu;
- uploads the debug APK as an artifact, which `pages.yml` then publishes on the download page.

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

## Releasing to Google Play

Bump `appVersion` in `app/build.gradle.kts` (the Android `versionCode` follows from it), run the full
`scripts/android-safety-net.ps1`, then push a matching tag:

```powershell
git tag v1.2; git push origin v1.2
```

`.github/workflows/release.yml` builds the app bundle signed with the upload key from the repository secrets
`YARMIPLAYTV_KEYSTORE_BASE64`, `YARMIPLAYTV_KEYSTORE_PASSWORD`, `YARMIPLAYTV_KEY_ALIAS` and
`YARMIPLAYTV_KEY_PASSWORD`, and attaches `yarmiplaytv-<version>.aab` to the run for uploading in the Play Console.
Locally, `:app:bundleRelease` signs with the key named in a gitignored `keystore.properties` at the repository
root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The store listing, graphics and policy answers
are in [docs/play](docs/play/README.md); the privacy policy is [docs/privacy.md](docs/privacy.md), published at
`/privacy/` on the download page.

The same tag builds the Windows installers, attaches them to the tag's GitHub release and republishes the
download page with them. With the repository variable `SIGNPATH_ORGANIZATION_ID` and the secret
`SIGNPATH_API_TOKEN` (a SignPath CI user with submitter rights) they are first submitted to
[SignPath](https://signpath.io) for signing: an approver accepts the request in SignPath (the job waits up to
6 hours), then the signed `.msi` and `.exe` are verified and released. That also needs a SignPath project with
the slug `YarmiplayTV` linked to the GitHub.com trusted build system, its `release-signing` policy, and
[.github/signpath/artifact-configuration.xml](.github/signpath/artifact-configuration.xml) as its default
artifact configuration. Without the variable the unsigned installers are released; browsers and SmartScreen
warn about those, winget and the Store package don't. SmartScreen may also warn about a newly signed release
until it has been downloaded enough; the reputation then stays with the certificate for later releases.

### winget

The first version is submitted by hand: with [wingetcreate](https://github.com/microsoft/winget-create)
(`winget install Microsoft.WingetCreate`), run, for the latest release's version:

```powershell
wingetcreate new https://github.com/Yarmiplay/YarmiplayTV/releases/download/v1.4.0/YarmiplayTV-1.4.0.msi
```

with the identifier `Yarmiplay.YarmiplayTV`, scope `user` (the `.msi` installs per user) and the `.msi` only,
and let it open the pull request to `microsoft/winget-pkgs`. Once that is merged, set the repository variable
`WINGET_PACKAGE_ID` to `Yarmiplay.YarmiplayTV` and the secret `WINGET_TOKEN` to a classic personal access
token with the `public_repo` scope of an account with a fork of `microsoft/winget-pkgs`; each release tag then
opens the update pull request itself. Installs from winget update themselves like the downloaded `.msi`.

### Microsoft Store

The release run also has a `windows-store-msix-<version>` artifact: an unsigned `.msix` of the same app,
which the Store signs when it is uploaded in [Partner Center](https://partner.microsoft.com/dashboard) (a free
individual developer account). [desktop/msix/AppxManifest.xml](desktop/msix/AppxManifest.xml) needs the
Identity Name, Publisher and PublisherDisplayName that Partner Center shows under Product identity for the
reserved name. The submission asks for the privacy policy (`/privacy/` on the download page), a reason for the
`runFullTrust` capability (a desktop media player built on libmpv), screenshots and the age rating. The Store
build doesn't look for updates; the Store updates it.

`scripts/make-msix.ps1` builds the same package locally (needs the Windows SDK), and
`scripts/make-msix.ps1 -Register` installs it unpacked instead for a check (needs Developer Mode).

## Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io), certificate by
[SignPath Foundation](https://signpath.org).

- Committers and reviewers: [Yarmiplay](https://github.com/Yarmiplay)
- Approvers: [Yarmiplay](https://github.com/Yarmiplay)

Only the Windows installers and the YarmiplayTV launcher in them are signed, built by
`.github/workflows/release.yml` from a version tag of this repository. Bundled third-party files (the Java
runtime, libmpv, Skia) are included as their projects publish them.

Privacy: see the [privacy policy](docs/privacy.md). The program connects to the Syncplay, Jellyfin and Plex
servers you enter or pick (and to plex.tv to sign in to Plex and find its servers) and, when it starts, reads the latest version number from the download page on GitHub Pages; that
request carries no information about the user and can be turned off with "Check for updates" in Settings.

## License

The code in this repository is Apache 2.0, same as Syncplay. The Android app bundles libmpv-android, whose
FFmpeg is built with `--enable-gpl --enable-version3`, and the Windows installer bundles a GPL build of
libmpv, so those packages as a whole are distributed under the GPL 3.0 (Apache 2.0 code may be combined
with it). This repository is their corresponding source. Settings > Open-source licenses lists every
bundled component (`shared/.../ui/shared/Licenses.kt`).
