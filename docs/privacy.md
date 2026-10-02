# YarmiplayTV privacy policy

Effective 2 October 2026. This policy covers the YarmiplayTV app for Android TV, Android phones and tablets,
and desktop.

## Summary

YarmiplayTV doesn't collect any data. It has no accounts, no analytics, no advertising and no crash reporting,
and the developer runs no servers that the app talks to. The app only connects to the Syncplay and Jellyfin
servers you enter or pick.

## What the app sends, and to whom

- **Syncplay servers you connect to:** the name you enter, the room name, the room password if you set one
  (hashed with MD5, as the Syncplay desktop client does), chat messages you write, your ready state, playback
  position and pause state, and the name, duration and size of the file you are playing. This is what the
  Syncplay protocol needs to keep everyone in the room in sync. The server and the other people in the room
  can see it. The server is run by whoever hosts it, not by the developer of YarmiplayTV.
- **Jellyfin servers you sign in to:** your Jellyfin user name and password (or a Quick Connect code) to sign
  in, then requests to browse, search and stream your library. Jellyfin shows the app as "YarmiplayTV" in its
  list of devices.
- **Your local network:** when you look for Jellyfin servers, the app sends a discovery message on the local
  network that Jellyfin servers answer.

Connections are encrypted when the server supports it: Syncplay servers with TLS, and Jellyfin servers on
HTTPS. Jellyfin servers on a home network often use plain HTTP, and the app allows that.

## What stays on your device

Your settings (servers, name, room and its password, playback preferences), the Jellyfin sign-in token, saved playlists and the
folders you add to your local library are stored only on your device. Video files you play from the device are
read from the device and are never uploaded. On Android, uninstalling the app or clearing its storage deletes
this data. On desktop it is in `%APPDATA%\YarmiplayTV` (Windows), `~/Library/Application Support/YarmiplayTV`
(macOS) or `~/.config/yarmiplaytv` (Linux), and you can delete that folder.

## Permissions (Android)

- **Internet and network state:** to connect to Syncplay and Jellyfin servers.
- **Wi-Fi multicast:** to find Jellyfin servers on the local network.
- **Wake lock:** to keep the screen on during playback.
- **Files:** only the videos and folders you pick in the system file picker.

## Children

The app isn't directed at children. Rooms include chat with other people, so it is rated for ages 13 and up.

## Changes and contact

Changes to this policy are published on this page and in the app's source repository,
[github.com/Yarmiplay/YarmiplayTV](https://github.com/Yarmiplay/YarmiplayTV). Questions can be asked in its
[issues](https://github.com/Yarmiplay/YarmiplayTV/issues).
