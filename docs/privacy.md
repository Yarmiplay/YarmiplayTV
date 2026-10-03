# YarmiplayTV privacy policy

Effective 3 October 2026. This policy covers the YarmiplayTV app for Android TV, Android phones and tablets,
and desktop.

## Summary

YarmiplayTV doesn't collect any data. It has no accounts, no analytics, no advertising and no crash reporting,
and the developer runs no servers that the app talks to. The app connects to the Syncplay, Jellyfin and Plex
servers you enter or pick, to plex.tv when you sign in to Plex and, unless you turn it off, to GitHub to check
for a new version.

## What the app sends, and to whom

- **Syncplay servers you connect to:** the name you enter, the room name, the room password if you set one
  (hashed with MD5, as the Syncplay desktop client does), chat messages you write, your ready state, playback
  position and pause state, and the name, duration and size of the file you are playing. This is what the
  Syncplay protocol needs to keep everyone in the room in sync. The server and the other people in the room
  can see it. The server is run by whoever hosts it, not by the developer of YarmiplayTV.
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
- **Your local network:** when you look for Jellyfin servers, the app sends a discovery message on the local
  network that Jellyfin servers answer. To connect to a Plex server, the app tries the addresses plex.tv lists
  for it, local ones first.
- **GitHub, to check for updates:** when it starts, the app from the download page (the APK and the desktop
  apps) reads the latest version number from the download page on GitHub Pages (yarmiplay.github.io). The
  request contains nothing about you, your settings or your device; like any web request it reaches GitHub
  from your IP address, and GitHub handles it under the
  [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
  The developer doesn't receive it. Turn it off with **Check for updates** in Settings. When you install an
  update on Windows, the installer is downloaded from the same page. The apps from Google Play and the
  Microsoft Store don't check: their store updates them.

Connections are encrypted when the server supports it: Syncplay servers with TLS, and Jellyfin and Plex
servers on HTTPS. plex.tv is always HTTPS. Media servers on a home network often use plain HTTP, and the app
allows that.

## What stays on your device

Your settings (servers, name, room and its password, playback preferences), the sign-in tokens of each Jellyfin and Plex server, saved playlists and the
folders you add to your local library are stored only on your device. Video files you play from the device are
read from the device and are never uploaded. On Android, uninstalling the app or clearing its storage deletes
this data. On desktop it is in `%APPDATA%\YarmiplayTV` (Windows), `~/Library/Application Support/YarmiplayTV`
(macOS) or `~/.config/yarmiplaytv` (Linux), and you can delete that folder.

## Permissions (Android)

- **Internet and network state:** to connect to Syncplay, Jellyfin and Plex servers and plex.tv.
- **Wi-Fi multicast:** to find Jellyfin servers on the local network.
- **Wake lock:** to keep the screen on during playback.
- **Files:** only the videos and folders you pick in the system file picker.

## Children

The app isn't directed at children. Rooms include chat with other people, so it is rated for ages 13 and up.

## Changes and contact

Changes to this policy are published on this page and in the app's source repository,
[github.com/Yarmiplay/YarmiplayTV](https://github.com/Yarmiplay/YarmiplayTV). Questions can be asked in its
[issues](https://github.com/Yarmiplay/YarmiplayTV/issues).
