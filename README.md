# SyncplayTV

A [Syncplay](https://syncplay.pl/) client for Android TV / Google TV with [mpv](https://mpv.io/) built in.
Media comes from your [Jellyfin](https://jellyfin.org/) server on the local network.

- Joins any Syncplay server/room; play, pause and seek are synchronised with desktop Syncplay users.
- Plays through libmpv (hardware decoding with software fallback, libass subtitles, audio/subtitle track switching).
- Browses Jellyfin libraries (Quick Connect or username/password login).
- Shared playlists: when someone selects an entry, the TV finds that filename in Jellyfin and loads it automatically.

## Project layout

| Module | What it is |
| --- | --- |
| `syncplay-protocol` | Pure Kotlin Syncplay protocol client (JSON over TCP, optional TLS) |
| `media-source` | `MediaSource` interface and the Jellyfin implementation (pure Kotlin, OkHttp) |
| `player-mpv` | libmpv wrapper exposing a small `Player` interface |
| `app` | Compose for TV UI, `SyncController`, `PlaylistController` |

## Development on Windows

```powershell
# One-time: JDK 17, Android SDK, emulator and the Google TV system image
./scripts/setup-toolchain.ps1

# One-time: create the Google TV emulators
./scripts/create-googletv-avd.ps1

# Build, boot the emulator, install and launch
./scripts/run-tv.ps1            # 1080p Google TV
./scripts/run-tv.ps1 -Avd GoogleTV_4K

# Send remote-control keys to the emulator
./scripts/remote.ps1 down down ok back
```

Inside the emulator, the host PC is `10.0.2.2`, so a Jellyfin server on this PC is `http://10.0.2.2:8096`.

### Testing sync locally

```powershell
pip install syncplay                    # provides syncplay-server
./scripts/local-syncplay-server.ps1    # runs a server on port 8999
```

Point the TV app and a desktop Syncplay client at your PC (`10.0.2.2:8999` from the emulator) and join the same room.
`scripts/fake_peer.py` is a scripted Syncplay peer for automated checks.

## License

Apache 2.0, same as Syncplay.
