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
| Privacy policy URL | `https://tv.yarmiplay.com/privacy/` (from `docs/privacy.md`) |

## Store listing

**App name** (30 max): `YarmiplayTV: Watch Together`

**Short description** (80 max):

> Watch videos in sync with friends. Works with Syncplay, Jellyfin and Plex.

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
> • Browse and search your Jellyfin and Plex libraries, on several servers at once
> • Sign in to Jellyfin with Quick Connect and to Plex at plex.tv/link, no typing on the TV
> • Play files from your phone or tablet, or add whole folders
> • When the room plays a file, YarmiplayTV finds it in your libraries or folders by itself
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
> YarmiplayTV is an independent app. It is not affiliated with or endorsed by the Syncplay project, Jellyfin
> or Plex, which are named only to describe compatibility. YarmiplayTV doesn't provide any videos: you watch
> your own files and media servers.

**Category:** Video Players & Editors. **Tags:** video player, watch party (pick the closest offered).
**Contact email:** required, shown publicly; use an address you're happy to publish.
**Website:** `https://tv.yarmiplay.com/`

## App content (Policy > App content)

**Privacy policy:** the URL above.

**Ads:** No, the app has no ads.

**App access:** "All functionality is available without special access." For the reviewer notes:
"No account is needed. To try sync, open Room (or Join a Syncplay room on TV), keep the server syncplay.pl
port 8999, enter any name and a room name such as reviewtest, and connect. Play a video from the device to
see the shared player. Jellyfin and Plex are optional and need the user's own server (and, for Plex, a Plex
account)."

**Content rating questionnaire (IARC):** category "All other app types". Answer No to violence, sexuality,
language, controlled substances, gambling. Answer **Yes** to "users can interact or exchange content" (room
chat with other people), No to sharing location, No to digital purchases. Expect a rating around PEGI 3 /
Everyone with the "Users Interact" notice.

**Target audience and content:** 13-15, 16-17 and 18+. Not designed for children, so the Families policy
doesn't apply. Answer No to "could unintentionally appeal to children".

**News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.

**Data safety:**
- "Does your app collect or share any of the required user data types?" **No.** The developer receives
  nothing; the app only connects to servers the user enters or picks (and to plex.tv when the user signs in
  to Plex), like other Syncplay, Jellyfin and Plex clients. Watch progress goes only to the user's own media
  servers. The Play build (release) doesn't run the download page's update check.
- "Is all of the user data collected by your app encrypted in transit?" isn't asked when nothing is
  collected. If you choose to declare the room data instead (see below), answer No: plain Syncplay servers
  and Jellyfin and Plex servers on a home network over HTTP aren't encrypted.
- "Do you provide a way for users to request that their data is deleted?" Not asked when nothing is collected.
- A more cautious alternative, because the default server syncplay.pl and plex.tv are run by third parties:
  declare Personal info > Name and Messages > Other in-app messages (the Syncplay room), and Device or other
  IDs (the random device id plex.tv gets at sign-in), as **shared**, not collected, for **App
  functionality**; Name and Messages not optional, the device id optional (only with Plex); none processed
  by the developer. Either way, keep it consistent with `docs/privacy.md`.

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
2. **Closed testing** (required for personal accounts created after November 2023): the `alpha` track, with
   the Google Group `yarmiplaytv-testers@googlegroups.com` as testers and all countries. It needs at least
   **12 testers** who stay opted in for **14 days in a row**. Google also looks at whether testers actually
   use the app, so ask them to join a room together a few times during those two weeks, on phones and TVs.
   Fix anything in the pre-launch report (crashes, ANRs, accessibility warnings worth fixing) and upload a new
   version if needed; the 14 days don't restart. See [Getting testers](#getting-testers).
3. **Apply for production** (Dashboard > Apply for production) after the 14 days. It asks how testers were
   recruited, what feedback came in and what changed; answer from the test. Review takes up to about a week.
4. **Production:** roll out, staged if you like. The TV opt-in is reviewed separately and can take longer.
   Then set the variable `PLAY_TRACK` to `production`.

## Getting testers

Testers join on their own from the testers page, `https://tv.yarmiplay.com/test/` (from
`docs/testers.md`, linked in the download page's footer). It walks them through joining the Google Group
(`https://groups.google.com/g/yarmiplaytv-testers`: anyone can join, members can't see each other's
addresses), the opt-in link `https://play.google.com/apps/testing/com.yarmiplaytv` and installing from Play,
and asks people with the download page's APK to uninstall it first. The opt-in link only works once the
closed track has passed review; until then the page tells people to come back later.

- **Share the page** wherever friends and Syncplay users are: chats, the GitHub README or release notes,
  forums. A message:

  > I'm getting my watch-together app YarmiplayTV onto Google Play, and Google needs 12 people to test it for
  > two weeks first. If you have an Android phone, tablet or Google TV, joining takes a few minutes:
  > https://tv.yarmiplay.com/test/. Then keep it installed for 14 days and join our room a few
  > times (server syncplay.pl, port 8999, room <room>), so we can watch something together. Tell me anything
  > that breaks or confuses you.

- **Count:** Play Console > Test and release > Testing > Closed testing > the track > **Testers** shows how
  many people have opted in. Group members who never opened the opt-in link don't count.
- **Apply for production** once at least 12 testers have stayed opted in for 14 days in a row: the Dashboard
  then offers **Apply for production** (see Releases, step 3). Keep the closed track running while the
  application is reviewed.

## Automatic uploads

After the first bundle has been uploaded by hand, the Release run's `play` job uploads each version tag's
bundle. A manual Release run (Actions > Release > Run workflow) with **play** checked uploads the chosen
branch's bundle without a tag, as long as its version code isn't on Play yet. Set it up once:

1. In [Google Cloud](https://console.cloud.google.com/), enable the **Google Play Android Developer API** in a
   project, create a service account there and download a JSON key for it.
2. Play Console > Users and permissions > **Invite new users**: invite the service account's email with
   access to YarmiplayTV and the release permissions (release to testing tracks, and to production once the
   app is there).
3. Repository secret `PLAY_SERVICE_ACCOUNT_JSON` (the JSON key) and variable `PLAY_TRACK`: `internal`, `alpha`
   (closed testing), `beta` (open testing), `production`, or the name of a custom closed track.
4. While the app has never passed review, Play only accepts draft releases: set the variable
   `PLAY_RELEASE_STATUS` to `draft` and roll each one out in the Play Console. Remove it later so releases go
   out straight away.

## Before each upload

- Bump `appVersion` (Play rejects a `versionCode` it has seen before) and run the full
  `scripts/android-safety-net.ps1`.
- Smoke-test the signed bundle on the emulators with bundletool:
  `java -jar .tools/bundletool.jar build-apks --bundle app/build/outputs/bundle/release/app-release.aab --output build/app.apks --connected-device --ks <upload.jks> --ks-key-alias upload`
  then `java -jar .tools/bundletool.jar install-apks --apks build/app.apks`.
- People who installed the APK from the download page have to uninstall it before installing from Play:
  the download page's APK is signed with a different key.
