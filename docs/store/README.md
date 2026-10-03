# Microsoft Store listing and Partner Center answers

Everything Partner Center asks for, ready to paste. The package is the unsigned `.msix` from the release run's
`windows-store-msix-<version>` artifact (or `scripts/make-msix.ps1` locally); the Store signs it. Its identity
in [desktop/msix/AppxManifest.xml](../../desktop/msix/AppxManifest.xml) comes from Product management >
Product identity.

**Store ID:** `9PDBVR6W069J`, listing `https://apps.microsoft.com/detail/9PDBVR6W069J`.

## Files

| Partner Center field | File |
|---|---|
| Package | `desktop/build/msix/YarmiplayTV-<version>.msix` |
| Desktop screenshots (1366x768 or larger) | `screenshots/` |
| Privacy policy URL | `https://yarmiplay.github.io/YarmiplayTV/privacy/` (from `docs/privacy.md`) |

## Pricing and availability

**Markets:** all. **Visibility:** public. **Pricing:** free, no trial. **Release:** as soon as it passes
certification.

## Properties

**Category:** Photo & video (no subcategory).
**Privacy policy URL:** the URL above. **Website:** `https://yarmiplay.github.io/YarmiplayTV/`
**Support contact info:** `https://github.com/Yarmiplay/YarmiplayTV/issues`
**Product declarations:** none apply (no accessibility claim, not for Xbox, installable on removable storage
is fine). **System requirements:** Windows 10 version 1809 or later, x64; recommended 4 GB memory.

## Age ratings (IARC)

Category "All other app types". Answer No to violence, sexuality, language, controlled substances and
gambling. Answer **Yes** to "users can interact or exchange content" (room chat with other people), No to
sharing location, No to digital purchases. Expect a rating around PEGI 3 / Everyone with "Users Interact".

## Store listing (English)

**Product name:** `YarmiplayTV` (the reserved name; also `DisplayName` in the manifest).

**Description:**

> YarmiplayTV lets you watch videos together with friends, wherever they are. Everyone in a room plays the
> same video at the same moment: when someone pauses, seeks or starts the next episode, everybody follows.
>
> Works with Syncplay servers, so you can watch with friends who use the Syncplay desktop client, or with
> others on YarmiplayTV on their TV, phone or computer. Join the free public servers or your own.
>
> WATCH TOGETHER
> • Play, pause and seek stay in sync for the whole room
> • Shared playlist: drag to reorder, shuffle, loop, save and load lists
> • Room chat and a "ready" check before you start
> • Small drifts are corrected smoothly, big ones by jumping back in sync
>
> YOUR VIDEOS, YOUR WAY
> • Open files, drop folders on the window, or use "Open with" on a video
> • Browse and search your Jellyfin and Plex libraries
> • When the room plays a file, YarmiplayTV finds it in your libraries or folders by itself
>
> A REAL PLAYER
> • Built on mpv: plays almost any format, with hardware decoding
> • Styled subtitles (ASS/SSA), audio and subtitle track switching
> • Keyboard shortcuts and full screen
>
> No accounts, no ads, no tracking. YarmiplayTV is open source:
> https://github.com/Yarmiplay/YarmiplayTV
>
> YarmiplayTV is an independent app. It is not affiliated with or endorsed by the Syncplay project, Jellyfin
> or Plex, which are named only to describe compatibility. YarmiplayTV doesn't provide any videos: you watch
> your own files and media servers.

**Product features** (one per line, 200 characters max each):

- Watch videos in sync with friends on Syncplay servers
- Shared playlist with drag to reorder, shuffle and loop
- Room chat and ready check
- Works with the Syncplay desktop client and YarmiplayTV on TV and phone
- Browse and search Jellyfin and Plex libraries
- Finds the room's file in your libraries and folders by itself
- Built on mpv: almost any format, hardware decoding, styled subtitles
- No accounts, no ads, no tracking; open source

**Search terms** (7 max): `syncplay`, `watch party`, `watch together`, `video player`, `mpv`, `jellyfin`, `plex`

**Copyright and trademark info:** `© 2026 Yarmiplay`

## Submission options

**Notes for certification** (on the separate Additional testing information page, not in Submission options):

> No account is needed. To try sync, choose Room, keep the server syncplay.pl port 8999, enter any name and a
> room name such as reviewtest, and connect. Open any local video (or drop it on the window) to see the
> shared player; a second copy of the app, or the Syncplay desktop client, in the same room follows it.
> Jellyfin and Plex are optional and need the user's own server.

**Restricted capability runFullTrust** (why it's needed, 500 characters max):

> YarmiplayTV is a desktop media player (Java with the libmpv player library). It needs full trust to open
> the local video files and folders the user picks, play them through libmpv with hardware decoding, and
> connect to Syncplay, Jellyfin and Plex servers the user enters, including ones on the local network.

## Each release

1. Bump `appVersion` in `desktop/build.gradle.kts` (the Store needs a higher package version than the last
   one) and push the version tag.
2. Download the `windows-store-msix-<version>` artifact from the Release run.
3. In Partner Center, open the app, choose **Update** on the published submission (it copies everything),
   replace the package under Packages, add "What's new" to the listing if you like, and submit.

The Store build doesn't check the download page for updates (it runs with `-Dyarmiplaytv.store`); the Store
updates it.
