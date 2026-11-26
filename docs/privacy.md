# YarmiplayTV privacy policy

Effective 26 November 2026. This policy covers the YarmiplayTV app for Android TV, Android phones and tablets,
desktop (Windows, Linux and macOS), and the browser player.

## Summary

YarmiplayTV doesn't collect any data. It has no accounts, no analytics, no advertising and no crash reporting,
and the developer runs no servers that the app talks to. The app connects to the Syncplay, Jellyfin and Plex
servers you enter or pick, to plex.tv when you sign in to Plex, to MyAnimeList or AniList when you sign in to
them, to OpenSubtitles.com and Subdl only if you turn on online subtitles search in Settings, and, unless you
turn it off, to GitHub to check for a new version.

## What the app sends, and to whom

- **Syncplay servers you connect to:** the name you enter, the room name, the room password if you set one
  (hashed with MD5, as the Syncplay desktop client does), chat messages you write, your ready state, playback
  position and pause state, and the name, duration and size of the file you are playing. This is what the
  Syncplay protocol needs to keep everyone in the room in sync. The server and the other people in the room
  can see it. The server is run by whoever hosts it, not by the developer of YarmiplayTV.
- **YarmiplayServerTV servers:** a Syncplay server hosted with the YarmiplayServerTV app tells the app so
  when it connects, and only then does the app also send the following. A stock Syncplay server gets nothing
  more than the list above.
  - *Device access:* the app creates a key for each such server. It sends the server the key's public half, a
    signature that proves this device holds the key, the device name (set in Settings, by default the device's
    own name) and your Syncplay name, so the host can recognise and approve the device. The private half never
    leaves the device: on Android it is kept in the system keystore and can't be exported, and on desktop it is
    a file in the app's data folder (see below), readable only by your user account on macOS and Linux. **Forget this device's key** on the connect screen deletes it.
  - *File relay, when the host turned it on:* for files in the room's playlist that are in a folder you added
    to the app, the app tells the server each file's name, size, duration and a fingerprint of its contents
    (a SHA-256 hash of its first and last megabyte). When another viewer in the room plays one of them, the
    app uploads the parts the server asks for, and the server passes them on to that viewer. Files that
    aren't in the room's playlist are never offered or uploaded. Turn this off with **Share my files with the
    room** in Settings; you can still watch files other viewers share.
  - *Shared media servers:* when the host shares their Jellyfin server, the app adds it to your servers and
    signs in with a Quick Connect code that the Syncplay server approves for you. Turn this off with
    **Add media servers shared by Syncplay hosts** in Settings.
  - *MyAnimeList in the browser player, only if you choose it:* MyAnimeList doesn't accept requests from web pages,
    so to sign in to MyAnimeList in the browser player you choose, on its MyAnimeList screen, one YarmiplayServerTV
    to pass your requests on, and confirm that it may. That server then handles your MyAnimeList sign-in (the code
    MyAnimeList's page gives back and the sign-in tokens), and sees what the app asks MyAnimeList for you: your
    list, the shows you look up and the changes you make. Nothing with your account goes through any other server,
    including the rooms you join; you can change the server, or choose none, on the MyAnimeList screen, and **Sign
    out** deletes the account from this browser. The apps for Android and computers talk to MyAnimeList directly.
  - *Online subtitles search in the browser player, only if you turn it on:* OpenSubtitles.com and Subdl do not
    accept direct requests from web pages. When you enable **Online subtitles search** in Settings > Playback in
    the browser player and run a search, the browser sends the subtitle search and download through the room's
    YarmiplayServerTV (or the last saved server), which passes them on without storing your queries or files.
    The Android, TV and desktop apps talk to OpenSubtitles.com and Subdl directly.
  - *Voice chat, when the host turned it on:* if you leave **Voice chat** on in Settings, the app can send
    your microphone audio through that Syncplay server to the other people in the same room, and play theirs
    to you. The audio is streamed only while you are in the room; the app does not record it or keep it, and
    (per the server) neither does the host's software. A stock Syncplay server never receives voice. Turn voice
    off with **Voice chat** in Settings to hide the controls and stop sending or receiving audio.
- **Jellyfin servers you sign in to:** your Jellyfin user name and password (or a Quick Connect code) to sign
  in, then requests to browse, search and stream your library. Jellyfin shows the app as "YarmiplayTV" in its
  list of devices. You can sign in to several Jellyfin and Plex servers; each one only gets the requests for
  its own library, and signing out of one forgets its token.
- **plex.tv, when you use Plex:** to sign in, the app asks plex.tv for a link code, which you enter at
  plex.tv/link while signed in to your Plex account, and then gets a sign-in token for that account. It then
  asks plex.tv for the servers on your account and their addresses, and asks again when a server can't be
  reached at its saved address. These requests identify the app as "YarmiplayTV" with a device name and a
  random device id, and Plex handles them under the [Plex Privacy Policy](https://www.plex.tv/about/privacy-legal/).
- **Plex servers you pick:** requests to browse, search and stream your library, sent with the token for that
  server. Plex shows the app as "YarmiplayTV" in its list of devices.
- **Watch progress, to your own servers:** while you play a file that is on your Jellyfin or Plex server (also
  when it plays from a folder on your device), the app tells that server which item is playing, how far along
  it is and whether it's paused, and marks it watched once 90% is played. This goes only to the servers you
  signed in to, so they can show "continue watching" and watched marks. When several of your servers have the
  same file, each of them is told. Turn it off with **Report progress to your media servers** in Settings.
- **MyAnimeList, only when you sign in to it:** you sign in on MyAnimeList's own page (in your browser on a
  computer, or on Android in a web view showing that page); the app never sees or stores your MyAnimeList password, only the sign-in
  tokens MyAnimeList gives it (OAuth). With them the app asks MyAnimeList for your user name and picture, looks up
  the shows you watch (their titles, episode counts and related seasons) and your list entries for them, sends
  what you type in **Find a show** to search for shows, and,
  when you've watched an episode or change something in the MyAnimeList panel, updates your list: the episodes
  watched, status, score, rewatching and start and finish dates. MyAnimeList handles this under its
  [privacy policy](https://myanimelist.net/about/privacy_policy). Nothing about MyAnimeList goes anywhere else, except
  in the browser player, through the one server you choose for it, as described above.
- **AniList, only when you sign in to it:** you sign in on AniList's own page using OAuth (Implicit Grant, so
  no client secret is embedded in the app). On desktop, a temporary local-only listener on `127.0.0.1` receives
  the redirect and strips the token from browser history (`history.replaceState`); on Android and TV, an
  ephemeral in-app web view intercepts the redirect and wipes its cookies and storage when closed; in the browser
  player, the page captures the token and strips it from the URL immediately. YarmiplayTV never sees or stores
  your AniList password—only the OAuth access token, kept on your device. On **every** platform, including the
  browser player, YarmiplayTV talks directly to AniList (`graphql.anilist.co`); your AniList token and list never
  pass through any Syncplay server. With it, the app reads your profile (username, avatar, score format), searches
  shows, reads your lists, and sends your episode progress, status, score and rewatch updates. AniList handles
  this under the [AniList Terms and Privacy Policy](https://anilist.co/terms). Signing out deletes the token and
  all AniList data kept on your device.
- **MyAnimeList, AniList and AniSkip, to skip openings and endings:** when an anime episode plays, the app works
  out which MyAnimeList entry it is, also without a MyAnimeList account: it asks MyAnimeList, with the app's id
  rather than yours, about the show (its title, or the MyAnimeList id your media server has for it) and its
  seasons (or falls back to AniList's public catalog to look up the show's `idMal`). It then asks
  [AniSkip](https://aniskip.com) (api.aniskip.com) where the episode's opening and ending
  are, sending the MyAnimeList id, the episode number and the file's length, nothing else. When the video plays
  from your Jellyfin or Plex server, the app also asks that server for the episode's intro and credits markers.
  The answers are kept in memory only. In the browser player, these questions to MyAnimeList (and the shows you
  search for in **Find a show** when you aren't signed in) go through the YarmiplayServerTV server of the room you're
  in, or else of the last room you joined, which passes them on; they carry the app's id and the show, nothing
  about you or your account. In a room on a YarmiplayServerTV server, turning automatic skipping on or off goes to the
  server, which tells the room who changed it.
- **Online subtitles search (OpenSubtitles.com and Subdl), off by default:** while **Online subtitles search** is
  off in **Settings > Playback** (the default), YarmiplayTV makes no requests to subtitle providers. When you turn
  it on and use **Search online subtitles…** in the player's Subtitles menu, the app sends your search title,
  preferred language (English by default), season and episode numbers when known, and—when playing a local or
  completely cached file—a 64-bit OpenSubtitles file hash (computed from the file size and its first and last
  64 KB; it cannot be turned back into the video) to [OpenSubtitles.com](https://www.opensubtitles.com/)
  (`api.opensubtitles.com`) and [Subdl](https://subdl.com/) (`api.subdl.com`). If you optionally sign in with an
  OpenSubtitles account in Settings for a higher daily download limit, your username and password are sent once to
  OpenSubtitles.com to obtain a session token; only your username and token are kept on your device (never your
  password), and signing out deletes them. Downloaded subtitles stay in your device's temporary cache and are not
  sent to the room. OpenSubtitles.com and Subdl handle those requests under their own privacy policies.
- **Listening for openings and endings, on your device:** when nothing above knows where an episode's opening
  and ending are, the app reads the sound of the start and end of the episode and of the episodes before and after
  it, from your device or from your own Jellyfin or Plex server (from a server only on Wi-Fi or Ethernet unless you
  allow mobile data), and compares them. Only fingerprints of that sound (numbers that can't be turned back into
  it) and the times found are kept, in the app's cache folder on this device; nothing is sent anywhere. Turn it
  off with **Find openings and endings by listening** in Settings; clearing the app's cache deletes what was kept.
- **Your local network:** when you look for Jellyfin servers, the app sends a discovery message on the local
  network that Jellyfin servers answer. To connect to a Plex server, the app tries the addresses plex.tv lists
  for it, local ones first. The Syncplay connect screen also sends a discovery message, on Wi-Fi or Ethernet
  only, that YarmiplayServerTV servers answer. Signing in to MyAnimeList or AniList on a computer listens on this
  device only (`127.0.0.1` / `localhost`) for the provider's page to hand back the sign-in.
- **GitHub, to check for updates:** when it starts, the app from the download page (the APK and the desktop
  apps) reads the latest version number from the download page (tv.yarmiplay.com, hosted on GitHub Pages). The
  request contains nothing about you, your settings or your device; like any web request it reaches GitHub
  from your IP address, and GitHub handles it under the
  [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
  The developer doesn't receive it. Turn it off with **Check for updates when the app starts** in Settings;
  **Check for updates** in Settings asks the same page when you choose to. When you install an update, or
  **Automatic updates** installs one before the app opens, the new version is downloaded from the same page.
  The app from Google Play never reads that page: **Check for updates** asks Google Play, which handles it under
  [Google's Privacy Policy](https://policies.google.com/privacy), and the update comes from Google Play. The
  apps from the Microsoft Store, Flathub, the Snap Store and the AUR don't check: their store updates them.

Connections are encrypted when the server supports it: Syncplay servers with TLS, and Jellyfin and Plex
servers on HTTPS. plex.tv is always HTTPS. Media servers on a home network often use plain HTTP, and the app
allows that. The app asks before it connects without encryption to a Syncplay server (unless you turn that
question off in Settings) or to a Jellyfin address you gave as https://, and before it plays from a server whose
certificate it can't verify.

## What stays on your device

Your settings (servers, name, room and its password, playback preferences), the sign-in tokens of each Jellyfin and Plex server, saved playlists and the
folders you add to your local library are stored only on your device. For MyAnimeList and AniList, the app keeps on your
device only: their sign-in tokens, your user name and picture address (and AniList score format), the shows you
matched or chose not to track, and list updates that couldn't be sent yet. Details it reads from MyAnimeList or
AniList (titles, episode counts, related seasons) are kept only in memory. **Sign out** of MyAnimeList or AniList
in Settings deletes all data for that service. If you optionally sign in to OpenSubtitles, only your username and
session token are kept on your device until you sign out. Names you block in a room are kept only
in memory and forgotten when you leave the room. Video files you play from the device are
read from the device and are only uploaded through a YarmiplayServerTV file relay, as described above. Parts of
a file you watch through the relay, upcoming playlist items pre-downloaded in the background (when enabled in
Settings > Media; on by default for Wi-Fi/Ethernet and off for mobile data), and downloaded online subtitle files
are kept only in the app's temporary cache folder (or browser OPFS), up to the cache limit you choose (never more
than half of free space), and are deleted when removed from the playlist, when you leave the room, when you tap
**Clear cache**, after 24 hours, or when the app next starts. A copy you save with the player's save button is written
only where you choose, and stays there until you delete it. On Android, uninstalling the app or clearing its storage deletes
this data. On desktop it is in `%APPDATA%\YarmiplayTV` (Windows; the portable version uses the `data` folder
next to `YarmiplayTV.exe`), `~/Library/Application Support/YarmiplayTV` (macOS) or `~/.config/yarmiplaytv`
(Linux), and you can delete that folder.

## Permissions (Android)

- **Internet and network state:** to connect to Syncplay, Jellyfin and Plex servers, plex.tv, MyAnimeList,
  AniList, and (only when enabled) OpenSubtitles.com and Subdl; to look for servers on the local network only over
  Wi-Fi or Ethernet; and to respect your Wi-Fi vs. mobile-data settings for background playlist pre-downloading
  and skip detection.
- **Wi-Fi multicast:** to find Jellyfin and YarmiplayServerTV servers on the local network.
- **Wake lock:** to keep the screen on during playback.
- **Microphone:** only when you use voice chat in a room on a YarmiplayServerTV server. The app asks the first
  time you try to talk (listening alone does not need the microphone). On Android TV you tap Mic to talk
  (toggle on/off); a headset mic is preferred when connected. If the TV has no usable mic the app stays
  listen-only — join from your phone to talk.
- **Files:** only the videos and folders you pick in the system file picker.

## Children

The app isn't directed at children. Rooms include chat and optional voice with other people, so it is rated
for ages 13 and up. The [community rules](https://tv.yarmiplay.com/terms/) say what's not allowed in rooms and
how to block names, mute someone's voice on your device, or hide chat.

## Changes and contact

Changes to this policy are published on this page and in the app's source repository,
[github.com/Yarmiplay/YarmiplayTV](https://github.com/Yarmiplay/YarmiplayTV). Questions can be asked in its
[issues](https://github.com/Yarmiplay/YarmiplayTV/issues).
