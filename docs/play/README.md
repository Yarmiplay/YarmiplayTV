# Google Play listing and console answers

Everything the Play Console asks for, ready to paste. The graphics are drawn by `java scripts/BrandArt.java`,
which also fits the reference screenshots onto the 16:9 / 9:16 canvases Play requires; rerun it after
re-recording screenshots.

## Files

| Console field | File |
|---|---|
| App icon (512x512) | `icon-512.png` |
| Feature graphic (1024x500) | `feature-graphic-1024x500.png` |
| Android TV banner (1280x720) | `tv-banner-1280x720.png` |
| Phone screenshots | `screenshots/phone/` |
| 7-inch and 10-inch tablet screenshots | `screenshots/tablet/` (the same set works for both) |
| Android TV screenshots | `screenshots/tv/` |
| Privacy policy URL | `https://yarmiplay.github.io/YarmiplayTV/privacy/` (from `docs/privacy.md`) |

## Store listing

**App name** (30 max): `YarmiplayTV: Watch Together`

**Short description** (80 max):

> Watch videos in sync with friends. Works with Syncplay servers and Jellyfin.

**Full description:**

> YarmiplayTV lets you watch videos together with friends, wherever they are. Everyone in a room plays the
> same video at the same moment: when someone pauses, seeks or starts the next episode, everybody follows.
>
> Works with Syncplay servers, so you can watch with friends who use the Syncplay desktop client, or with
> others on YarmiplayTV. Join the free public servers or your own.
>
> WATCH TOGETHER
> • Play, pause and seek stay in sync for the whole room
> • Shared playlist: pick the next episode and it loads for everyone
> • Room chat and a "ready" check before you start
> • Small drifts are corrected smoothly, big ones by jumping back in sync
>
> YOUR VIDEOS, YOUR WAY
> • Browse and search your Jellyfin library, sign in with Quick Connect
> • Play files from your phone or tablet, or add whole folders
> • When the room plays a file, YarmiplayTV finds it in your Jellyfin library or folders by itself
>
> A REAL PLAYER
> • Built on mpv: plays almost any format, with hardware decoding
> • Styled subtitles (ASS/SSA), audio and subtitle track switching
>
> MADE FOR EVERY SCREEN
> • A remote-friendly interface on Google TV and Android TV
> • A touch interface on phones and tablets
> • Desktop apps for Windows, macOS and Linux on the website
>
> No accounts, no ads, no tracking. YarmiplayTV is open source:
> https://github.com/Yarmiplay/YarmiplayTV
>
> YarmiplayTV is an independent app. It is not affiliated with or endorsed by the Syncplay project or
> Jellyfin. Syncplay and Jellyfin are named only to describe compatibility. YarmiplayTV doesn't provide
> any videos: you watch your own files and media servers.

**Category:** Video Players & Editors. **Tags:** video player, watch party (pick the closest offered).
**Contact email:** required, shown publicly; use an address you're happy to publish.
**Website:** `https://yarmiplay.github.io/YarmiplayTV/`

## App content (Policy > App content)

**Privacy policy:** the URL above.

**Ads:** No, the app has no ads.

**App access:** "All functionality is available without special access." For the reviewer notes:
"No account is needed. To try sync, open Room (or Join a Syncplay room on TV), keep the server syncplay.pl
port 8999, enter any name and a room name such as reviewtest, and connect. Play a video from the device to
see the shared player. Jellyfin is optional and needs the user's own server."

**Content rating questionnaire (IARC):** category "All other app types". Answer No to violence, sexuality,
language, controlled substances, gambling. Answer **Yes** to "users can interact or exchange content" (room
chat with other people), No to sharing location, No to digital purchases. Expect a rating around PEGI 3 /
Everyone with the "Users Interact" notice.

**Target audience and content:** 13-15, 16-17 and 18+. Not designed for children, so the Families policy
doesn't apply. Answer No to "could unintentionally appeal to children".

**News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.

**Data safety:**
- "Does your app collect or share any of the required user data types?" **No.** The developer receives
  nothing; the app only connects to servers the user enters, like other Jellyfin and Syncplay clients.
- "Is all of the user data collected by your app encrypted in transit?" isn't asked when nothing is
  collected. If you choose to declare the room data instead (see below), answer No: plain Syncplay and
  local Jellyfin servers aren't encrypted.
- "Do you provide a way for users to request that their data is deleted?" Not asked when nothing is collected.
- A more cautious alternative, because the default server syncplay.pl is run by a third party: declare
  Personal info > Name and Messages > Other in-app messages as **shared**, not collected, for **App
  functionality**, not optional, and not processed by the developer. Either way, keep it consistent with
  `docs/privacy.md`.

**Permissions declarations:** none needed (no SMS, call log, location, all-files access or background
location).

## Advanced settings

- **Form factors:** add **Android TV** and accept the TV terms. TV review checks the leanback launcher entry,
  the banner, D-pad navigation and that no touchscreen is required (the manifest already declares
  `android.hardware.touchscreen` as not required).
- **App signing:** keep Play App Signing (the default). Upload with the upload key; Google signs what users get.
- **Countries:** all, or start with a few for the closed test.

## Releases

1. **Internal testing:** upload the bundle from the `Release bundle` workflow (or a local
   `:app:bundleRelease`) and add yourself as a tester. Install from the opt-in link on a phone and on a Google
   TV to check that the Play build works.
2. **Closed testing** (required for personal accounts created after November 2023): create a closed track with
   a Google Group or an email list of at least **12 testers**, who must stay opted in for **14 days in a row**.
   Google also looks at whether testers actually use the app, so ask them to join a room together a few times
   during those two weeks, on phones and TVs. Fix anything in the pre-launch report (crashes, ANRs,
   accessibility warnings worth fixing) and upload a new version if needed; the 14 days don't restart.
3. **Apply for production** (Dashboard > Apply for production) after the 14 days. It asks how testers were
   recruited, what feedback came in and what changed; answer from the test. Review takes up to about a week.
4. **Production:** roll out, staged if you like. The TV opt-in is reviewed separately and can take longer.

## Before each upload

- Bump `appVersion` (Play rejects a `versionCode` it has seen before) and run the full
  `scripts/android-safety-net.ps1`.
- Smoke-test the signed bundle on the emulators with bundletool:
  `java -jar .tools/bundletool.jar build-apks --bundle app/build/outputs/bundle/release/app-release.aab --output build/app.apks --connected-device --ks <upload.jks> --ks-key-alias upload`
  then `java -jar .tools/bundletool.jar install-apks --apks build/app.apks`.
- People who installed the APK from the download page have to uninstall it before installing from Play:
  the download page's APK is signed with a different key.
