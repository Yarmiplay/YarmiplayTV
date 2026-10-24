#!/usr/bin/env python3
"""
Builds the YarmiplayTV download page: what the app does, then one card per OS (Android TVs, phones and tablets
share one APK), each with its download and install steps.

  python scripts/download_site.py --dist dist --out _site [--version 0.1.0] [--site-url URL]
      [--google-verification TOKEN] [--indexnow-key KEY] [--flathub-id ID] [--snap-name NAME] [--aur-package NAME]

Files in --dist are sorted onto platforms by extension (.apk: TV and phone/tablet, .exe/.msi/.zip: Windows,
.dmg/.pkg: macOS, .deb/.rpm/.AppImage: Linux) and copied under stable names such as YarmiplayTV.apk, so links
keep working across builds. <site>/a/ redirects to the APK for TV apps like Downloader, <site>/privacy/
is docs/privacy.md (the Play Store privacy policy), <site>/terms/ is docs/terms.md (the community rules the
apps ask users to agree to), <site>/test/ is docs/testers.md (how to join the Google Play
test), <site>/manual/ is docs/manual.md (the user manual, with a table of contents and the store screenshots),
<site>/changelog/ lists every version's patch notes from the Linux metainfo's <releases>, <site>/watch/ is the
browser version (web/, which the Pages workflow builds and copies there after this script), <site>/screenshots/ holds the desktop screenshots the Linux metainfo points to, and <site>/version.json
lists each platform's package
and version for the apps' update check. With --site-url the pages carry canonical URLs and the site a
sitemap.xml and a robots.txt pointing to it for search engines; --indexnow-key publishes the key file that lets the Pages workflow notify IndexNow.
Platforms without a file or store listing show how to run from source. The Pages workflow publishes the result; scripts/apk_server.py serves the
same page on the local network. Standard library only.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import html
import json
import os
import re
import shutil
import sys
from dataclasses import dataclass

NAME = "YarmiplayTV"
REPO_URL = "https://github.com/Yarmiplay/YarmiplayTV"
SERVER_URL = "https://servertv.yarmiplay.com/"
HOME_URL = "https://yarmiplay.com/"
# The browser version (web/), which the Pages workflow publishes under the site's watch/. Pages served elsewhere
# (scripts/apk_server.py) link to it in full.
WATCH_PATH = "watch/"
WATCH_URL = "https://tv.yarmiplay.com/watch/"
TITLE = f"{NAME}: watch videos in sync with friends (Syncplay for Google TV, Android, Windows, macOS, Linux)"
DESCRIPTION = ("Watch videos together with friends, wherever they are. A Syncplay client with mpv built in for "
               "Google TV, Android phones and tablets, Windows, macOS and Linux that plays from Jellyfin, Plex or "
               "your own files, and a browser version with nothing to install. Free and open source.")
# Link previews (Open Graph) need a raster image; this is the Play Store feature graphic.
PREVIEW_IMAGE = os.path.join("docs", "play", "feature-graphic-1024x500.png")
# The app icon as desktop/build.gradle.kts draws it: ic_logo.xml's triangles on the rounded gradient tile.
LOGO_SVG = (
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" aria-hidden="true">
  <defs>
    <linearGradient id="logo-bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#152238"/>
      <stop offset="50%" stop-color="#0D1524"/>
      <stop offset="100%" stop-color="#070B12"/>
    </linearGradient>
    <linearGradient id="facet-top" x1="0%" y1="0%" x2="100%" y2="50%">
      <stop offset="0%" stop-color="#A5F3FC"/>
      <stop offset="35%" stop-color="#67E8F9"/>
      <stop offset="100%" stop-color="#38BDF8"/>
    </linearGradient>
    <linearGradient id="facet-spine" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#38BDF8"/>
      <stop offset="50%" stop-color="#0EA5E9"/>
      <stop offset="100%" stop-color="#0284C7"/>
    </linearGradient>
    <linearGradient id="facet-bot" x1="50%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#0284C7"/>
      <stop offset="50%" stop-color="#0369A1"/>
      <stop offset="100%" stop-color="#075985"/>
    </linearGradient>
    <linearGradient id="squircle-rim" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#70C4FF" stop-opacity="0.28"/>
      <stop offset="100%" stop-color="#0E1E33" stop-opacity="0.0"/>
    </linearGradient>
  </defs>
  <!-- Edge-to-Edge Squircle Base Tile (Zero Outer Margin) -->
  <rect x="0" y="0" width="1024" height="1024" rx="230" fill="url(#logo-bg)"/>
  <rect x="2" y="2" width="1020" height="1020" rx="228" fill="none" stroke="url(#squircle-rim)" stroke-width="4"/>

  <!-- Centered Bold Play Prism (70% Scale Option B) -->
  <polygon points="460,512 903,512 263,906" fill="url(#facet-bot)"/>
  <polygon points="263,118 460,512 263,906" fill="url(#facet-spine)"/>
  <polygon points="263,118 903,512 460,512" fill="url(#facet-top)"/>

  <!-- Subtle Precision Crease Dividers -->
  <line x1="460" y1="512" x2="903" y2="512" stroke="#FFFFFF" stroke-opacity="0.8" stroke-width="3.8" stroke-linecap="round"/>
  <line x1="263" y1="118" x2="460" y2="512" stroke="#FFFFFF" stroke-opacity="0.6" stroke-width="3.2" stroke-linecap="round"/>
  <line x1="263" y1="906" x2="460" y2="512" stroke="#38BDF8" stroke-opacity="0.5" stroke-width="2.6" stroke-linecap="round"/>
</svg>"""
)
# Markdown docs published as pages: file in docs/, folder on the site, footer link, search description.
DOCS = (
    ("privacy.md", "privacy", "Privacy",
     f"{NAME} privacy policy: the app collects no data, has no accounts, analytics or ads, and only connects to "
     "the servers you choose."),
    ("terms.md", "terms", "Community rules",
     f"{NAME} community rules: what's not allowed in Syncplay rooms, and how to block names, hide chat or leave a "
     "room."),
    ("testers.md", "test", "Help test on Google Play",
     f"Help test {NAME} on Google Play: join the testers group, opt in, install it on your Android phone, tablet "
     "or Google TV and use it for 14 days."),
    ("manual.md", "manual", "User manual",
     f"The {NAME} user manual: joining Syncplay rooms, media servers and files, the player, managed rooms, "
     "MyAnimeList, picture-in-picture, updates and troubleshooting on Google TV, Android, Windows, macOS and Linux."),
)
# Pages whose numbered lists are steps, shown as numbered cards with a button (the testers page). Elsewhere "1. "
# lists are ordinary numbered lists.
STEP_PAGES = {"test"}
# Pages with a table of contents (their ## headings) at the top.
TOC_PAGES = {"manual"}
# Images the docs pages use, copied next to them: (folder in the repo, page folder, folder in the page).
DOC_IMAGES = (
    (os.path.join("docs", "play", "screenshots"), "manual", "screenshots"),
    (os.path.join("docs", "screenshots"), "manual", os.path.join("screenshots", "desktop")),
)
# The patch notes page, from the Linux metainfo's <releases> (every version's notes).
METAINFO = os.path.join("packaging", "linux", "com.yarmiplay.TV.metainfo.xml")
CHANGELOG = ("changelog", "Patch notes",
             f"{NAME} patch notes: what changed in each version of the app for Google TV, Android, Windows, macOS "
             "and Linux.")
# Store listings (label, url, note): the platform's first one is its main button, the rest and its files smaller
# links below.
STORE_LINKS = {
    "windows": [("Microsoft Store", "https://apps.microsoft.com/detail/9PDBVR6W069J", "Updates automatically")],
}
# Until the app is public on Google Play, Android's main button is the testers page (the "test" DOCS folder).
PLAY_TEST = ("Google Play", "test/", "Join the test")
# The desktop app's store screenshots, published under <site>/screenshots/ for the Linux packages' metainfo
# (packaging/linux/com.yarmiplay.TV.metainfo.xml).
SCREENSHOTS = os.path.join("docs", "store", "screenshots")


def linux_stores(flathub_id=None, snap_name=None, aur_package=None):
    """The Linux store listings that exist (the release workflow's variables):
    [(label, name in a sentence, url, install command)]."""
    stores = []
    if flathub_id:
        stores.append(("Flathub", "Flathub", f"https://flathub.org/apps/{flathub_id}",
                       f"flatpak install flathub {flathub_id}"))
    if snap_name:
        stores.append(("Snap Store", "the Snap Store", f"https://snapcraft.io/{snap_name}",
                       f"sudo snap install {snap_name}"))
    if aur_package:
        stores.append(("AUR", "the AUR (Arch Linux)", f"https://aur.archlinux.org/packages/{aur_package}",
                       f"yay -S {aur_package}"))
    return stores


def either(items):
    """"a", "a or b", "a, b or c"."""
    return " or ".join(filter(None, [", ".join(items[:-1]), items[-1]]))

# Lower-case extension -> platforms it installs on and the button label. Without a store listing the first
# file is the platform's main button.
EXTENSIONS = {
    ".apk": (("android",), "APK"),
    ".exe": (("windows",), "Installer (.exe)"),
    ".msi": (("windows",), "Installer (.msi)"),
    ".zip": (("windows",), "Portable (.zip)"),
    ".dmg": (("macos",), "Disk image (.dmg)"),
    ".pkg": (("macos",), "Installer (.pkg)"),
    ".deb": (("linux",), "Debian / Ubuntu (.deb)"),
    ".rpm": (("linux",), "Fedora / openSUSE (.rpm)"),
    ".appimage": (("linux",), "AppImage"),
}

# version.json: the package each platform's update check offers, by preference. Windows prefers the branded
# setup .exe (falls back to .msi); the portable app replaces its files from the portable zip.
UPDATE_PACKAGES = {
    "android": (".apk",),
    "windows": (".exe", ".msi"),
    "windows-portable": (".zip",),
    "macos": (".dmg", ".pkg"),
    "linux": (".deb", ".rpm", ".appimage"),
}


@dataclass
class Download:
    href: str
    label: str
    size: int
    sha256: str | None = None


@dataclass
class Platform:
    key: str
    title: str
    blurb: str
    steps: list[str]
    source: list[str]
    # Steps that are alternatives (one per device or package) rather than a sequence.
    alternatives: bool = False


def platforms(short_link, stores=(), play_test=False):
    """stores: linux_stores(); play_test: Android's main button is the Google Play testers page."""
    address = (f"<code>{html.escape(short_link)}</code>" if short_link
               else "this page's address followed by <code>/a</code>")
    run = "./gradlew :desktop:run"
    deb_step = ("Debian / Ubuntu: in the folder you downloaded it to, run "
                "<code>sudo apt install ./YarmiplayTV.deb</code> (installs libmpv too).")
    linux_steps = [deb_step]
    if stores:
        names = either([name for _, name, _, _ in stores])
        commands = either([f"<code>{html.escape(cmd)}</code>" for _, _, _, cmd in stores])
        keep = "keeps" if len(stores) == 1 else "keep"
        linux_steps = [f"Get it from {names}, which {keep} it up to date, or from a terminal: {commands}.",
                       deb_step]
    play_steps = (['<b>Google Play:</b> follow the testers page\'s three steps on your phone, tablet or Google TV; '
                   'Google Play then keeps it up to date.'] if play_test else [])
    return [
        Platform("android", "Android",
                 "Google TV / Android TV, phones and tablets: one app that picks the remote-friendly TV interface "
                 "or the touch interface by itself. Android 8.0 or newer.", play_steps + [
            # The TV step stays last: on a TV the page's script moves it to the top.
            '<b>Phone or tablet:</b> tap <b>APK</b>, allow the browser to install unknown apps when '
            'Android asks, then open the file from the notification or <b>Downloads</b> and choose <b>Install</b>.',
            f'<b>Google TV / Android TV:</b> enter {address} in <b>Downloader</b>, allow it to install unknown '
            f'apps when Android asks, then choose <b>Install</b>.',
        ], [], alternatives=True),
        Platform("windows", "Windows", "Windows 10 or 11, 64-bit.", [
            "Get it from the Microsoft Store, or run the installer, then start YarmiplayTV from the Start menu.",
            "Or unzip the portable version anywhere and run <b>YarmiplayTV.exe</b>: nothing is installed, and its "
            "settings stay in its folder.",
        ], ["Install JDK 17, clone the repository, then:", "gradlew.bat :desktop:run"], alternatives=True),
        Platform("macos", "macOS", "Uses mpv from Homebrew.", [
            "Install mpv: <code>brew install mpv</code>",
            "Open the disk image and drag YarmiplayTV to Applications.",
        ], ["Install JDK 17 and mpv (<code>brew install mpv</code>), clone the repository, then:", run]),
        Platform("linux", "Linux",
                 "64-bit." if stores else "Uses your distribution's libmpv.", linux_steps,
                 ["Install JDK 17 and libmpv, clone the repository, then:", run], alternatives=bool(stores)),
    ]


# Material Icons (Apache-2.0, the set the browser version uses): the paths of the few this page shows.
ICONS = {
    "android": '<path d="m17.6 9.48 1.84-3.18c.16-.31.04-.69-.26-.85a.637.637 0 0 0-.83.22l-1.88 3.24a11.463 11.463 0 0 0-8.94 0L5.65 5.67a.643.643 0 0 0-.87-.2c-.28.18-.37.54-.22.83L6.4 9.48A10.78 10.78 0 0 0 1 18h22a10.78 10.78 0 0 0-5.4-8.52zM7 15.25a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5zm10 0a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5z"/>',
    "desktop_windows": '<path d="M20 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h6v2H8v2h8v-2h-2v-2h6c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2"/>',
    "laptop_mac": '<path d="M20 18c1.1 0 1.99-.9 1.99-2L22 5c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2H0c0 1.1.9 2 2 2h20c1.1 0 2-.9 2-2h-4zM4 5h16v11H4V5zm8 14c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z"/>',
    "terminal": '<path d="M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16c1.1 0 2-.9 2-2V6a2 2 0 0 0-2-2zm0 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z"/>',
    "language": '<path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95a15.65 15.65 0 0 0-1.38-3.56A8.03 8.03 0 0 1 18.92 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56A7.987 7.987 0 0 1 5.08 16zm2.95-8H5.08a7.987 7.987 0 0 1 4.33-3.56A15.65 15.65 0 0 0 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 0 1-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"/>',
    "groups": '<path d="M12 12.75c1.63 0 3.07.39 4.24.9 1.08.48 1.76 1.56 1.76 2.73V18H6v-1.61c0-1.18.68-2.26 1.76-2.73 1.17-.52 2.61-.91 4.24-.91zM4 13c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm1.13 1.1c-.37-.06-.74-.1-1.13-.1-.99 0-1.93.21-2.78.58A2.01 2.01 0 0 0 0 16.43V18h4.5v-1.61c0-.83.23-1.61.63-2.29zM20 13c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm4 3.43c0-.81-.48-1.53-1.22-1.85A6.95 6.95 0 0 0 20 14c-.39 0-.76.04-1.13.1.4.68.63 1.46.63 2.29V18H24v-1.57zM12 6c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3z"/>',
    "video_library": '<path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z"/>',
    "subtitles": '<path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z"/>',
    "fast_forward": '<path d="m4 18 8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"/>',
    "dns": '<path d="M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/>',
    "sync": '<path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0 0 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 0 0 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/>',
    "download": '<path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/>',
    "play_circle": '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM9.5 16.5v-9l7 4.5-7 4.5z"/>',
    "help": '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 17h-2v-2h2v2zm2.07-7.75-.9.92C13.45 12.9 13 13.5 13 15h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26c.37-.36.59-.86.59-1.41 0-1.1-.9-2-2-2s-2 .9-2 2H8c0-2.21 1.79-4 4-4s4 1.79 4 4c0 .88-.36 1.68-.93 2.25z"/>',
}


def icon(name):
    return f'<svg viewBox="0 0 24 24" aria-hidden="true">{ICONS[name]}</svg>'


PLATFORM_ICONS = {"android": "android", "windows": "desktop_windows", "macos": "laptop_mac", "linux": "terminal",
                  "browser": "language"}

# What the app does, under the heading: (icon, title, text).
FEATURES = (
    ("groups", "Rooms with friends",
     "Play, pause and seek together, chat and share a playlist in any Syncplay room, even with friends on desktop "
     "Syncplay."),
    ("video_library", "Your own library",
     "Play from Jellyfin, Plex or the files on your device: the shared playlist finds each person's copy by name."),
    ("subtitles", "mpv built in",
     "MKV, HEVC, every audio track and styled anime subtitles play just as they do in mpv."),
    ("fast_forward", "Skip the intro",
     "Skip openings and endings for the whole room, and keep MyAnimeList up to date as you watch."),
)

# The heading's picture: one moment of a video on a TV and a phone at once, drawn rather than a screenshot so it
# stays sharp at any size.
ILLUSTRATION = (
    '<svg class="art" viewBox="0 0 560 420" role="img" '
    'aria-label="The same moment of a video playing on a TV and on a phone">'
    '<defs>'
    '<radialGradient id="art-glow"><stop offset="0" stop-color="#3DA5F4" stop-opacity=".22"/>'
    '<stop offset="1" stop-color="#3DA5F4" stop-opacity="0"/></radialGradient>'
    '<linearGradient id="art-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0F1C33"/>'
    '<stop offset=".55" stop-color="#2D5F93"/><stop offset="1" stop-color="#F0A868"/></linearGradient>'
    '<linearGradient id="art-shade" x1="0" y1="0" x2="0" y2="1"><stop offset=".55" stop-color="#000" stop-opacity="0"/>'
    '<stop offset="1" stop-color="#000" stop-opacity=".6"/></linearGradient>'
    '<symbol id="art-scene" viewBox="0 0 160 90" preserveAspectRatio="xMidYMid slice">'
    '<rect width="160" height="90" fill="url(#art-sky)"/><circle cx="112" cy="54" r="13" fill="#FFD28A"/>'
    '<path d="M0 64 30 44 52 58 78 36 108 62 132 48 160 60V90H0Z" fill="#1C3352"/>'
    '<path d="M0 74 24 62 50 72 84 54 118 72 142 64 160 70V90H0Z" fill="#0E1A2C"/></symbol>'
    '<clipPath id="art-tv"><rect x="20" y="28" width="428" height="241" rx="6"/></clipPath>'
    '<clipPath id="art-phone"><rect x="8" y="34" width="134" height="76"/></clipPath>'
    '</defs>'
    '<ellipse cx="280" cy="210" rx="280" ry="200" fill="url(#art-glow)"/>'
    # The TV.
    '<rect x="196" y="285" width="76" height="14" fill="#1A2029"/>'
    '<rect x="150" y="297" width="168" height="9" rx="4.5" fill="#232A35"/>'
    '<rect x="8" y="16" width="452" height="270" rx="16" fill="#0A0D12" stroke="#2A313C" stroke-width="2"/>'
    '<g clip-path="url(#art-tv)"><use href="#art-scene" x="20" y="28" width="428" height="241"/>'
    '<rect x="20" y="28" width="428" height="241" fill="url(#art-shade)"/></g>'
    '<rect x="34" y="42" width="112" height="26" rx="13" fill="#0A0D12" fill-opacity=".72"/>'
    '<circle cx="50" cy="55" r="4" fill="#4CC38A"/>'
    '<text x="60" y="59.5" font-size="12.5" font-weight="600" fill="#fff">3 watching</text>'
    '<text x="36" y="245" font-size="13" font-weight="600" fill="#fff">24:18</text>'
    '<rect x="36" y="254" width="340" height="4" rx="2" fill="#fff" fill-opacity=".3"/>'
    '<rect x="36" y="254" width="211" height="4" rx="2" fill="#3DA5F4"/>'
    '<circle cx="247" cy="256" r="6.5" fill="#fff"/>'
    # The phone, in a room: the same frame above the chat.
    '<g transform="translate(396 118)">'
    '<rect width="150" height="290" rx="24" fill="#0A0D12" stroke="#2A313C" stroke-width="2"/>'
    '<rect x="8" y="10" width="134" height="270" rx="17" fill="#12161D"/>'
    '<rect x="58" y="17" width="34" height="7" rx="3.5" fill="#0A0D12"/>'
    '<g clip-path="url(#art-phone)"><use href="#art-scene" x="8" y="34" width="134" height="76"/>'
    '<rect x="8" y="34" width="134" height="76" fill="url(#art-shade)"/></g>'
    '<rect x="16" y="101" width="118" height="3" rx="1.5" fill="#fff" fill-opacity=".3"/>'
    '<rect x="16" y="101" width="73" height="3" rx="1.5" fill="#3DA5F4"/>'
    '<circle cx="89" cy="102.5" r="4" fill="#fff"/>'
    '<text x="18" y="133" font-size="11" font-weight="700" fill="#E8EAED">Movie night</text>'
    '<text x="18" y="147" font-size="9" fill="#9AA0A6">3 ready · 24:18</text>'
    '<rect x="18" y="160" width="74" height="24" rx="12" fill="#232A35"/>'
    '<text x="29" y="176" font-size="10" fill="#E8EAED">Ready?</text>'
    '<rect x="66" y="192" width="66" height="24" rx="12" fill="#3DA5F4"/>'
    '<text x="78" y="208" font-size="10" font-weight="600" fill="#06121F">Ready!</text>'
    '<rect x="18" y="224" width="96" height="24" rx="12" fill="#232A35"/>'
    '<text x="29" y="240" font-size="10" fill="#E8EAED">Starting now</text>'
    '</g>'
    # Kept in sync.
    '<circle cx="404" cy="122" r="27" fill="#3DA5F4" stroke="#0E1116" stroke-width="6"/>'
    f'<g transform="translate(390 108) scale(1.17)" fill="#06121F">{ICONS["sync"]}</g>'
    '</svg>'
)


def card_head(key, title, blurb):
    return (f'<div class="head"><span class="pi">{icon(PLATFORM_ICONS[key])}</span><h3>{title}</h3></div>'
            f'<p class="blurb">{blurb}</p>')


def browser_card(watch):
    """The browser version's card: the main way in on devices without an app (games consoles, smart TVs that don't
    run Android, iPhone, iPad, Chromebooks), and a way to watch without installing anywhere. [watch] is escaped."""
    head = card_head("browser", "Web browser", "Nothing to install: PlayStation, Xbox, smart TVs, iPhone, iPad, "
                     "Chromebooks and any computer.")
    return (
        '<section class="card wide" data-platform="browser"><span class="badge">For this device</span>'
        f'<div class="row"><div>{head}</div>'
        f'<div class="dl"><a class="btn" href="{watch}">Watch in your browser<small>Nothing to install</small></a></div>'
        '</div><details class="steps"><summary>What it needs</summary><ul>'
        f'<li>A room on a <a href="{SERVER_URL}">YarmiplayServerTV</a> 1.8 or later with HTTPS set up (its DuckDNS '
        "certificate). The host's <b>Watch in a browser</b> link opens it with the server and room filled in. Plain "
        "Syncplay servers need the app.</li>"
        "<li>A recent Chrome, Edge, Firefox or Safari. It plays MKV, MP4, AVI, TS and WebM with its own player; "
        "HEVC needs a browser and device that support it.</li>"
        "</ul></details></section>"
    )


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def by_platform(downloads):
    """{extension: Download} -> {platform key: [Download]} in EXTENSIONS order."""
    result = {}
    for ext, (keys, _) in EXTENSIONS.items():
        if ext in downloads:
            for key in keys:
                result.setdefault(key, []).append(downloads[ext])
    return result


def fmt_size(n):
    return f"{n / 1e6:.1f} MB"


def page_url(site_url, path=""):
    return site_url.rstrip("/") + "/" + path


def search_head(description, site_url=None, path="", google_verification=None):
    """Description, canonical URL and Search Console verification tags for a page at site_url + path."""
    tags = [f'<meta name="description" content="{html.escape(description)}">']
    if site_url:
        tags.append(f'<link rel="canonical" href="{html.escape(page_url(site_url, path))}">')
    if google_verification:
        tags.append(f'<meta name="google-site-verification" content="{html.escape(google_verification)}">')
    return "\n".join(tags)


def app_head(version, site_url=None, preview=False):
    """Favicon, Open Graph / Twitter card tags and SoftwareApplication structured data for the main page."""
    tags = ['<link rel="icon" href="favicon.svg?v=2" type="image/svg+xml">',
            '<meta property="og:type" content="website">',
            f'<meta property="og:site_name" content="{NAME}">',
            f'<meta property="og:title" content="{html.escape(TITLE)}">',
            f'<meta property="og:description" content="{html.escape(DESCRIPTION)}">']
    app = {"@context": "https://schema.org", "@type": "SoftwareApplication", "name": NAME,
           "description": DESCRIPTION, "operatingSystem": "Android, Windows, macOS, Linux, web browser",
           "applicationCategory": "MultimediaApplication", "softwareVersion": version,
           "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"}}
    if site_url:
        tags.append(f'<meta property="og:url" content="{html.escape(page_url(site_url))}">')
        app["url"] = page_url(site_url)
        if preview:
            image = page_url(site_url, "preview.png")
            tags += [f'<meta property="og:image" content="{html.escape(image)}">',
                     '<meta property="og:image:width" content="1024">',
                     '<meta property="og:image:height" content="500">',
                     '<meta name="twitter:card" content="summary_large_image">']
            app["image"] = image
    data = json.dumps(app, ensure_ascii=False).replace("</", "<\\/")
    tags.append(f'<script type="application/ld+json">{data}</script>')
    return "\n".join(tags)


# The look the main page and the docs pages share: the app's Dark theme colours, and the bar along the top.
BASE_CSS = """ :root { --bg:#0E1116; --surface:#151A21; --surface2:#1E242E; --line:#262C36; --text:#E8EAED; --soft:#C4C7CC;
         --muted:#9AA0A6; --accent:#3DA5F4; --on-accent:#06121F; --tint:rgba(61,165,244,.13); }
 * { box-sizing:border-box; }
 html { scroll-behavior:smooth; }
 body { margin:0; color:var(--text); font:16px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
        background:radial-gradient(1100px 620px at 78% -8%,rgba(61,165,244,.14),transparent 70%) no-repeat,var(--bg); }
 .wrap { max-width:1120px; margin:0 auto; padding:0 24px; }
 a { color:var(--accent); }
 .top { display:flex; align-items:center; justify-content:space-between; gap:1em; padding:1.1em 0; }
 .brand { display:inline-flex; align-items:center; gap:.5em; color:var(--text); text-decoration:none; font-weight:700;
          font-size:1.15em; letter-spacing:-.01em; }
 .brand svg { width:2em; height:2em; flex:none; }
 .top nav { display:flex; flex-wrap:wrap; gap:.2em; }
 .top nav a { color:var(--soft); text-decoration:none; padding:.4em .75em; border-radius:8px; font-weight:500; }
 .top nav a:hover, .top nav a[aria-current] { color:var(--text); background:var(--surface); }
 code, pre { font-family:ui-monospace,Consolas,monospace; font-size:.9em; }
 code { background:var(--bg); padding:.1em .35em; border-radius:4px; color:var(--text); overflow-wrap:anywhere; }
 a:focus-visible, summary:focus-visible { outline:3px solid var(--text); outline-offset:3px; border-radius:6px; }
 @media (max-width:620px) { .wrap { padding:0 16px; } .top nav a:not(:nth-child(-n+2)) { display:none; } }
 @media (min-width:1600px) { body { font-size:20px; } .wrap { max-width:1500px; } }"""


def render_page(downloads, version, built, short_link=None, docs=(), site_url=None,
                google_verification=None, preview=False, stores=(), watch=WATCH_PATH):
    """downloads: {platform key: [Download]}; docs: the DOCS entries published next to it; stores: linux_stores();
    watch: the browser version's address. Returns the page as a str."""
    cards = []
    play_test = any(folder == PLAY_TEST[1].rstrip("/") for _, folder, _, _ in docs)
    store_links = dict(STORE_LINKS, linux=[(label, url, "Updates automatically") for label, _, url, _ in stores])
    if play_test:
        store_links["android"] = [PLAY_TEST]
    for p in platforms(short_link, stores, play_test):
        files = downloads.get(p.key, [])
        listings = store_links.get(p.key, [])
        if files or listings:
            links = [(html.escape(url), "", html.escape(label), html.escape(note)) for label, url, note in listings]
            links += [(html.escape(d.href), " download", html.escape(d.label), fmt_size(d.size)) for d in files]
            (href, attr, label, note), rest = links[0], links[1:]
            buttons = f'<a class="btn" href="{href}"{attr}>{label}<small>{note}</small></a>'
            if rest:
                buttons += '<p class="more">' + "".join(
                    f'<a href="{href}"{attr}>{label} <small>{note}</small></a>' for href, attr, label, note in rest
                ) + "</p>"
            tag = "ul" if p.alternatives else "ol"
            steps = (f'<details class="steps"><summary>How to install</summary><{tag}>'
                     + "".join(f"<li>{s}</li>" for s in p.steps) + f"</{tag}></details>")
            sums = "".join(f"<div>{html.escape(d.href.rsplit('/', 1)[-1])}<br><code>{d.sha256}</code></div>"
                           for d in files if d.sha256)
            body = (f'<div class="dl">{buttons}</div>{steps}'
                    + (f'<details class="sums"><summary>SHA-256</summary>{sums}</details>' if sums else ""))
        else:
            body = '<p class="none">No package for this platform yet.</p>'
            if p.source:
                intro, cmd = p.source
                body += f'<p class="src">{intro}</p><pre>{html.escape(cmd)}</pre>'
        cards.append(f'<section class="card" data-platform="{p.key}"><span class="badge">For this device</span>'
                     f'{card_head(p.key, p.title, p.blurb)}{body}</section>')
    cards.append(browser_card(html.escape(watch)))

    features = "".join(f'<div class="feature"><span class="fi">{icon(name)}</span><h3>{title}</h3><p>{text}</p></div>'
                       for name, title, text in FEATURES)
    pages = {folder: label for _, folder, label, _ in docs}
    nav = [(HOME_URL, "Home"), ("#download", "Download")] + [(f"{f}/", pages[f]) for f in ("manual", "changelog") if f in pages]
    nav.append((REPO_URL, "Source"))
    legal = ("privacy", "terms")
    columns = (
        ("Help", [(f"{folder}/", label) for folder, label in pages.items() if folder not in legal]),
        ("Project", [(HOME_URL, "yarmiplay.com"), (watch, "Watch in your browser"), (SERVER_URL, "YarmiplayServerTV"),
                     (REPO_URL, "Source code")]),
        ("Legal", [(f"{folder}/", pages[folder]) for folder in legal if folder in pages]),
    )
    footer_columns = "".join(
        f'<div><h4>{title}</h4><ul>'
        + "".join(f'<li><a href="{html.escape(href)}">{html.escape(label)}</a></li>' for href, label in links)
        + "</ul></div>" for title, links in columns if links)
    w = html.escape(watch)
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0E1116">
<title>{html.escape(TITLE)}</title>
{search_head(DESCRIPTION, site_url, "", google_verification)}
{app_head(version, site_url, preview)}
<style>
{BASE_CSS}
 .hero {{ display:grid; grid-template-columns:1.05fr .95fr; align-items:center; gap:3em; padding:2.5em 0 1em; }}
 .eyebrow {{ display:inline-block; color:var(--accent); background:var(--tint); font-weight:600; font-size:.88em;
            padding:.3em .9em; border-radius:99px; margin:0 0 1.2em; }}
 h1 {{ font-size:clamp(2.4em,5.2vw,3.8em); line-height:1.06; letter-spacing:-.03em; margin:0 0 .45em; }}
 h1 span {{ color:var(--accent); }}
 .lede {{ color:var(--soft); font-size:1.18em; margin:0 0 1.7em; max-width:32em; }}
 .cta {{ display:flex; flex-wrap:wrap; gap:.75em; }}
 .hero-meta {{ color:var(--muted); font-size:.9em; margin:1.3em 0 0; }}
 .art {{ width:100%; height:auto; display:block; }}
 .section {{ padding:3.5em 0 0; }}
 h2 {{ font-size:clamp(1.6em,3vw,2.1em); letter-spacing:-.02em; margin:0 0 .25em; }}
 .sub {{ color:var(--muted); margin:0 0 1.6em; font-size:1.05em; }}
 .features {{ display:grid; grid-template-columns:repeat(4,1fr); gap:1em; }}
 .feature {{ background:var(--surface); border:1px solid var(--line); border-radius:18px; padding:1.4em; }}
 .fi {{ width:2.75em; height:2.75em; border-radius:.75em; background:var(--tint); color:var(--accent); display:flex;
       align-items:center; justify-content:center; margin-bottom:.9em; }}
 .fi svg {{ width:1.5em; height:1.5em; fill:currentColor; }}
 .feature h3 {{ margin:0 0 .3em; font-size:1.1em; }}
 .feature p {{ margin:0; color:var(--muted); font-size:.95em; }}
 .grid {{ display:grid; grid-template-columns:repeat(4,1fr); gap:1em; }}
 .card {{ background:var(--surface); border:1px solid var(--line); border-radius:18px; padding:1.4em; position:relative;
         display:flex; flex-direction:column; }}
 .card.here {{ border-color:var(--accent); box-shadow:0 0 0 1px var(--accent),0 16px 48px -16px rgba(61,165,244,.45); }}
 .badge {{ display:none; position:absolute; top:-.8em; left:1.2em; background:var(--accent); color:var(--on-accent);
          font-size:.78em; font-weight:700; padding:.15em .75em; border-radius:99px; }}
 .card.here .badge {{ display:block; }}
 .head {{ display:flex; align-items:center; gap:.75em; margin-bottom:.6em; }}
 .pi {{ width:2.5em; height:2.5em; border-radius:.7em; background:var(--surface2); color:var(--text); display:flex;
       align-items:center; justify-content:center; flex:none; }}
 .here .pi {{ background:var(--tint); color:var(--accent); }}
 .pi svg {{ width:1.4em; height:1.4em; fill:currentColor; }}
 h3 {{ margin:0; font-size:1.2em; }}
 .blurb {{ color:var(--muted); margin:0 0 1.2em; font-size:.95em; }}
 .dl {{ display:flex; flex-direction:column; gap:.6em; margin:0 0 1em; }}
 .btn {{ display:flex; flex-direction:column; padding:.65em 1.1em; border-radius:12px; background:var(--tint);
        color:var(--accent); text-decoration:none; font-weight:700; line-height:1.3; border:1px solid transparent; }}
 .btn small {{ font-weight:500; opacity:.8; font-size:.8em; }}
 .btn:hover {{ background:rgba(61,165,244,.22); }}
 .here .btn, .btn.primary {{ background:var(--accent); color:var(--on-accent); }}
 .here .btn:hover, .btn.primary:hover {{ background:#5DB5F6; }}
 .btn.big {{ display:inline-flex; flex-direction:row; align-items:center; gap:.75em; padding:.8em 1.5em .8em 1.1em;
            font-size:1.05em; }}
 .btn.big svg {{ width:1.5em; height:1.5em; fill:currentColor; flex:none; }}
 .btn.big span {{ display:flex; flex-direction:column; }}
 .btn.ghost {{ background:none; color:var(--text); border-color:var(--line); }}
 .btn.ghost:hover {{ background:var(--surface); border-color:var(--muted); }}
 .more {{ display:flex; flex-wrap:wrap; gap:.5em; margin:0; }}
 .more a {{ padding:.35em .75em; border:1px solid var(--line); border-radius:9px; text-decoration:none; color:var(--text);
           font-size:.88em; font-weight:600; white-space:nowrap; }}
 .more a:hover {{ border-color:var(--accent); }}
 .more small {{ color:var(--muted); font-weight:400; }}
 details {{ color:var(--muted); font-size:.92em; }}
 details + details {{ margin-top:.45em; }}
 summary {{ cursor:pointer; color:var(--soft); }}
 summary:hover {{ color:var(--text); }}
 details ol, details ul {{ padding-left:1.25em; margin:.5em 0 .2em; }}
 details li + li {{ margin-top:.45em; }}
 .sums {{ font-size:.82em; }}
 .sums div {{ margin-top:.5em; }}
 pre {{ background:var(--bg); padding:.7em 1em; border-radius:8px; overflow-x:auto; color:var(--text); margin:.3em 0 0; }}
 .none, .src {{ color:var(--muted); margin:0 0 .4em; }}
 .card.wide {{ grid-column:1/-1; }}
 .wide .row {{ display:flex; flex-wrap:wrap; align-items:center; gap:1em 2em; }}
 .wide .row > div:first-child {{ flex:1 1 20em; }}
 .wide .blurb {{ margin:0; }}
 .wide .dl {{ margin:0; min-width:15em; }}
 .wide details {{ margin-top:1em; }}
 .host {{ display:flex; align-items:center; flex-wrap:wrap; gap:1.2em 1.6em; margin-top:3.5em; padding:1.8em 2em;
         border:1px solid var(--line); border-radius:20px; background:linear-gradient(135deg,#16212F,var(--surface) 60%); }}
 .host .fi {{ margin:0; width:3.25em; height:3.25em; }}
 .host div {{ flex:1 1 20em; }}
 .host h2 {{ font-size:1.35em; margin:0 0 .15em; }}
 .host p {{ margin:0; color:var(--muted); }}
 .host .btn {{ display:inline-flex; }}
 footer {{ display:grid; grid-template-columns:1.6fr repeat(3,1fr); gap:1.5em; border-top:1px solid var(--line);
          margin-top:4em; padding:2.5em 0 3em; color:var(--muted); font-size:.93em; }}
 footer p {{ margin:.7em 0 0; font-size:.92em; }}
 footer h4 {{ color:var(--text); margin:.3em 0 .7em; font-size:.8em; text-transform:uppercase; letter-spacing:.08em; }}
 footer ul {{ list-style:none; padding:0; margin:0; }}
 footer li + li {{ margin-top:.4em; }}
 footer ul a {{ color:var(--muted); text-decoration:none; }}
 footer ul a:hover {{ color:var(--text); }}
 @media (max-width:1060px) {{ .grid, .features {{ grid-template-columns:repeat(2,1fr); }} }}
 @media (max-width:900px) {{
  .hero {{ grid-template-columns:1fr; gap:2em; padding-top:1em; }}
  .art {{ max-width:30em; margin:0 auto; }}
 }}
 @media (max-width:760px) {{ footer {{ grid-template-columns:1fr 1fr; }} footer > div:first-child {{ grid-column:1/-1; }} }}
 @media (max-width:620px) {{
  .grid, .features {{ grid-template-columns:1fr; }}
  .cta .btn {{ flex:1 1 100%; }}
  .host {{ padding:1.4em; }}
 }}
</style></head>
<body><div class="wrap">
<header class="top">
 <a class="brand" href="./">{LOGO_SVG}{NAME}</a>
 <nav>{"".join(f'<a href="{html.escape(href)}">{html.escape(label)}</a>' for href, label in nav)}</nav>
</header>
<main>
<section class="hero">
 <div>
  <p class="eyebrow">Free and open source &middot; Works with Syncplay</p>
  <h1>Watch together,<br><span>in sync.</span></h1>
  <p class="lede">{NAME} plays the same moment for everyone in the room, wherever they are: on Google TV, phones,
  computers or straight from a browser.</p>
  <div class="cta">
   <a class="btn primary big" id="get" href="#download">{icon("download")}<span><b>Download</b><small>Google TV, Android, Windows, macOS, Linux</small></span></a>
   <a class="btn ghost big" id="alt" href="{w}">{icon("language")}<span><b>Watch in your browser</b><small>Nothing to install</small></span></a>
  </div>
  <p class="hero-meta">Version {html.escape(version)} &middot; No accounts, ads or tracking</p>
 </div>
 {ILLUSTRATION}
</section>
<section class="section" aria-labelledby="what">
 <h2 id="what">Everything movie night needs</h2>
 <p class="sub">A Syncplay client with mpv built in, made for the couch as much as the desk.</p>
 <div class="features">{features}</div>
</section>
<section class="section" id="download" aria-labelledby="get-it">
 <h2 id="get-it">Get {NAME}</h2>
 <p class="sub">One app for Google TV, Android phones and tablets, and every computer.</p>
 <div class="grid">
{chr(10).join(cards)}
 </div>
</section>
<section class="host">
 <span class="fi">{icon("dns")}</span>
 <div><h2>Host your own server</h2>
 <p>YarmiplayServerTV runs a Syncplay server and a Jellyfin server from your Windows, macOS or Linux computer.</p></div>
 <a class="btn" href="{SERVER_URL}">YarmiplayServerTV<small>servertv.yarmiplay.com</small></a>
</section>
</main>
<footer>
 <div><a class="brand" href="./">{LOGO_SVG}{NAME}</a>
 <p>Version {html.escape(version)} &middot; built {html.escape(built)}</p></div>
 {footer_columns}
</footer>
</div>
<script>
(function () {{
  var ua = navigator.userAgent, key = null;
  var tv = /Android/i.test(ua) && /\\bTV\\b|GoogleTV|AFT[A-Z]|BRAVIA|SMART-TV|Large Screen/i.test(ua);
  // Games consoles and smart TVs that don't run Android have no app: the browser version is theirs. (Xbox's
  // browser says Windows, and webOS's and Tizen's say Linux, so they're checked first.)
  var noApp = !/Android/i.test(ua) &&
    /PlayStation|Xbox|Nintendo|Tizen|SMART-TV|SmartTV|Web0S|webOS|NetCast|VIDAA|Viera|Saphi|Roku|HbbTV/i.test(ua);
  if (/Android/i.test(ua)) key = "android";
  else if (noApp) key = "browser";
  // iPhone, iPad (whose Safari can say Macintosh) and Chromebooks: no app made for them either.
  else if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) key = "browser";
  else if (/CrOS/.test(ua)) key = "browser";
  else if (/Windows/.test(ua)) key = "windows";
  else if (/Macintosh|Mac OS X/.test(ua)) key = "macos";
  else if (/Linux|X11/.test(ua)) key = "linux";
  var card = key && document.querySelector('[data-platform="' + key + '"]');
  if (!card) return;
  card.classList.add("here");
  card.parentNode.insertBefore(card, card.parentNode.firstChild);
  var btn = card.querySelector(".btn"), get = document.getElementById("get"), alt = document.getElementById("alt");
  // The heading's main button gets this device's: the browser version, or the app's first download.
  if (key === "browser") {{
    var href = get.getAttribute("href"), inner = get.innerHTML;
    get.setAttribute("href", alt.getAttribute("href"));
    get.innerHTML = alt.innerHTML;
    alt.setAttribute("href", href);
    alt.innerHTML = inner;
    alt.querySelector("b").textContent = "Get the app";
  }} else if (btn) {{
    get.setAttribute("href", btn.getAttribute("href"));
    if (btn.hasAttribute("download")) get.setAttribute("download", "");
    get.querySelector("b").textContent = "Get it for " + card.querySelector("h3").textContent;
    get.querySelector("small").textContent = btn.firstChild.nodeValue;
  }}
  if (noApp) get.focus();
  if (tv) {{
    var steps = card.querySelector("ul");
    if (steps && steps.lastElementChild) steps.insertBefore(steps.lastElementChild, steps.firstElementChild);
    if (btn) btn.focus();
  }}
}})();
</script>
</body></html>
"""


def inline_markdown(text):
    """`code`, **bold**, ![alt](image) and [text](url) in already-escaped text."""
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)", r'<img src="\2" alt="\1" loading="lazy">', text)
    return re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', text)


def slug(text):
    """A heading's anchor: "Joining a room" -> "joining-a-room", "1.8.2" -> "1-8-2"."""
    text = re.sub(r"<[^>]+>|&[a-z]+;", "", text).lower()
    return re.sub(r"[^a-z0-9]+", "-", text).strip("-") or "section"


def headings(md, level=2):
    """The text of the document's headings of [level] (## by default), for a table of contents."""
    marks = "#" * level
    return [l.strip()[level + 1:].strip() for l in md.splitlines() if re.match(rf"{marks} \S", l.strip())]


def markdown_to_html(md):
    """The Markdown the docs pages use: # to #### headings (with anchors), paragraphs, "- " and "1. " lists, a
    line with only an image as a figure captioned with its alt text, lines wrapped freely."""
    blocks, para, items = [], [], []
    list_tag = "ul"

    def flush():
        if para:
            blocks.append(f"<p>{inline_markdown(html.escape(' '.join(para), quote=False))}</p>")
            para.clear()
        if items:
            lis = "".join(f"<li>{inline_markdown(html.escape(i, quote=False))}</li>" for i in items)
            blocks.append(f"<{list_tag}>{lis}</{list_tag}>")
            items.clear()

    for line in md.splitlines():
        stripped = line.strip()
        heading = re.match(r"(#{1,4}) (.+)", stripped)
        image = re.fullmatch(r"!\[[^\]]*\]\([^)\s]+\)", stripped)
        item = re.match(r"(-|\d+\.) (.+)", stripped)
        if not stripped:
            flush()
        elif heading:
            flush()
            level = len(heading.group(1))
            text = heading.group(2)
            anchor = f' id="{slug(text)}"' if level > 1 else ""
            blocks.append(f"<h{level}{anchor}>{inline_markdown(html.escape(text, quote=False))}</h{level}>")
        elif image:
            flush()
            alt = re.match(r"!\[([^\]]*)\]", stripped).group(1)
            caption = f"<figcaption>{html.escape(alt, quote=False)}</figcaption>" if alt else ""
            blocks.append(f"<figure>{inline_markdown(html.escape(stripped, quote=False))}{caption}</figure>")
        elif item:
            tag = "ul" if item.group(1) == "-" else "ol"
            if para or (items and tag != list_tag):
                flush()
            list_tag = tag
            items.append(item.group(2))
        elif items and line.startswith(" "):
            items[-1] += " " + stripped
        else:
            if items:
                flush()
            para.append(stripped)
    flush()
    return "\n".join(blocks)


# The manual's starting points, as tiles under its introduction: (heading's anchor, title, icon, text). Tiles whose
# heading is gone are left out.
GUIDE_TILES = (
    ("getting-started", "Getting started", "download", "Install it on a TV, phone or computer, or use a browser"),
    ("joining-a-syncplay-room", "Join a room", "groups", "Servers, rooms, and the rooms you saved"),
    ("the-player", "The player", "play_circle", "Controls, the remote, keys and picture-in-picture"),
    ("troubleshooting", "Troubleshooting", "help", "Connecting, staying in sync and files that aren't found"),
)


def outline(md):
    """The ## headings, each with the ### headings under it: [(text, [text])]."""
    result = []
    for line in md.splitlines():
        m = re.match(r"(#{2,3}) (\S.*)", line.strip())
        if m and len(m.group(1)) == 2:
            result.append((m.group(2).strip(), []))
        elif m and result:
            result[-1][1].append(m.group(2).strip())
    return result


def toc_html(md):
    """The contents beside a guide: its ## headings, each with its ### headings, which the page's script opens for
    the section being read."""
    def link(h):
        return f'<a href="#{slug(h)}">{inline_markdown(html.escape(h, quote=False))}</a>'

    entries = "".join(f"<li>{link(h)}" + (f"<ul>{''.join(f'<li>{link(s)}</li>' for s in subs)}</ul>" if subs else "")
                      + "</li>" for h, subs in outline(md))
    return (f'<nav class="side" id="contents" aria-label="Contents"><h2>Contents</h2><ol>{entries}</ol></nav>'
            if entries else "")


def guide_head(body):
    """[body]'s title and the paragraph introducing it as the guide's heading, with the starting points under them:
    (heading, the rest of the body)."""
    m = re.match(r"\s*(<h1>.*?</h1>)\s*(<p>.*?</p>)?", body, re.S)
    if not m:
        return "", body
    tiles = "".join(f'<a class="tile" href="#{anchor}"><span class="fi">{icon(name)}</span><span><b>{title}</b>'
                    f'<small>{text}</small></span></a>'
                    for anchor, title, name, text in GUIDE_TILES if f'id="{anchor}"' in body)
    lede = m.group(2).replace("<p>", '<p class="lede">', 1) if m.group(2) else ""
    return (f'<header class="doc-head">{m.group(1)}{lede}'
            + (f'<div class="tiles">{tiles}</div>' if tiles else "") + "</header>", body[m.end():])


GUIDE_CSS = """ .wrap { max-width:76em; }
 .doc { display:grid; grid-template-columns:15em minmax(0,46em); grid-template-areas:"side head" "side body";
        column-gap:3.5em; justify-content:center; }
 .doc-head { grid-area:head; padding:1.5em 0 .5em; }
 .doc main { grid-area:body; padding-top:0; }
 .doc-head h1 { font-size:clamp(2.1em,5vw,3em); margin:0 0 .4em; letter-spacing:-.03em; }
 .lede { font-size:1.15em; color:var(--soft); margin:0; }
 .tiles { display:grid; grid-template-columns:repeat(2,1fr); gap:.75em; margin:1.8em 0 0; }
 .tile { display:flex; gap:.9em; align-items:flex-start; padding:1em 1.1em; background:var(--surface);
         border:1px solid var(--line); border-radius:16px; text-decoration:none; color:var(--text); line-height:1.35; }
 .tile:hover { border-color:var(--accent); }
 .tile b { display:block; }
 .tile small { display:block; color:var(--muted); font-size:.88em; margin-top:.2em; }
 .fi { width:2.4em; height:2.4em; border-radius:.7em; background:var(--tint); color:var(--accent); display:flex;
       align-items:center; justify-content:center; flex:none; }
 .fi svg { width:1.35em; height:1.35em; fill:currentColor; }
 .side { grid-area:side; align-self:start; position:sticky; top:0; max-height:100vh; overflow:auto;
         padding:1.6em .4em 2em 0; font-size:.92em; }
 .side h2 { font-size:.78em; color:var(--muted); text-transform:uppercase; letter-spacing:.08em; margin:0 0 .7em .9em; }
 .side ol, .side ul { list-style:none; margin:0; padding:0; }
 .side li { margin:0; }
 .side a { display:block; color:var(--soft); text-decoration:none; padding:.32em .9em; border-radius:8px; line-height:1.35; }
 .side a:hover { color:var(--text); background:var(--surface); }
 .side ul { display:none; margin:.1em 0 .5em .9em; border-left:1px solid var(--line); }
 .side ul a { color:var(--muted); font-size:.94em; padding:.24em .8em; border-radius:0 8px 8px 0; }
 .side li.open > ul { display:block; }
 .side li.open > a { color:var(--text); font-weight:600; }
 .side a.active, .side li.open > a.active { color:var(--accent); background:var(--tint); }
 .side ul a.active { background:none; box-shadow:inset 2px 0 var(--accent); }
 main h2 { font-size:1.6em; margin:2.2em 0 .5em; padding-top:1.3em; border-top:1px solid var(--line); }
 main h3 { font-size:1.18em; margin:1.8em 0 .4em; }
 .anchor { color:var(--muted); text-decoration:none; font-weight:400; margin-left:.3em; opacity:0; }
 h2:hover .anchor, h3:hover .anchor, .anchor:focus { opacity:1; }
 figure { margin:1.6em 0; text-align:center; }
 figure img { width:auto; max-height:34em; margin:0 auto; }
 figcaption { color:var(--muted); font-size:.88em; margin-top:.6em; }
 .to-top { display:none; position:fixed; right:16px; bottom:16px; padding:.55em 1em; border-radius:99px;
           background:var(--surface2); border:1px solid var(--line); color:var(--text); text-decoration:none;
           font-weight:600; font-size:.9em; box-shadow:0 8px 24px rgba(0,0,0,.4); }
 @media (max-width:960px) {
  .doc { display:flex; flex-direction:column; }
  .doc-head { order:1; padding-top:1em; }
  .side { order:2; position:static; max-height:none; overflow:visible; margin:1.8em 0 .5em; padding:1em .6em;
          background:var(--surface); border:1px solid var(--line); border-radius:16px; }
  .side ol { columns:2; column-gap:1em; }
  .side li { break-inside:avoid; }
  .side li.open > ul, .side ul { display:none; }
  .side li.open > a, .side a.active, .side li.open > a.active { font-weight:400; color:var(--soft); background:none; }
  .doc main { order:3; }
  body.scrolled .to-top { display:block; }
 }
 @media (max-width:600px) { .tiles { grid-template-columns:1fr; } .side ol { columns:1; } .lede { font-size:1.05em; } }"""

# Marks the section being read in the contents, and opens its subsections there.
GUIDE_JS = """<script>
(function () {
  var nav = document.getElementById("contents");
  if (!nav) return;
  var list = nav.querySelector("ol"), links = {}, all = nav.querySelectorAll("a");
  var heads = document.querySelectorAll("main h2[id], main h3[id]");
  for (var i = 0; i < all.length; i++) links[all[i].getAttribute("href").slice(1)] = all[i];
  var current = null, open = null, queued = false;
  function update() {
    queued = false;
    document.body.classList.toggle("scrolled", window.pageYOffset > 900);
    var found = null;
    for (var j = 0; j < heads.length && heads[j].getBoundingClientRect().top <= 140; j++) found = heads[j];
    var a = found ? links[found.id] : null;
    if (a === current) return;
    if (current) current.classList.remove("active");
    current = a;
    var li = a ? a.parentNode : null;
    while (li && li.parentNode !== list) li = li.parentNode;
    if (open !== li) {
      if (open) open.classList.remove("open");
      if (li) li.classList.add("open");
      open = li;
    }
    if (!a) return;
    a.classList.add("active");
    // Keeps the entry in view when the contents are taller than the window.
    var r = a.getBoundingClientRect(), n = nav.getBoundingClientRect();
    if (nav.scrollHeight > nav.clientHeight && (r.top < n.top || r.bottom > n.bottom))
      nav.scrollTop += r.top - n.top - n.height / 3;
  }
  window.addEventListener("scroll", function () {
    if (!queued) { queued = true; window.requestAnimationFrame(update); }
  });
  update();
})();
</script>"""


def render_doc(md, description, site_url=None, path="", steps=False, toc=False):
    """A Markdown document as a page in the download page's style, one folder below the main page. With [steps] its
    numbered lists are step cards (the testers page); with [toc] it's a guide: its title and introduction head it with
    tiles for the main sections, and its contents stay beside it on wide screens, marking the section being read."""
    title = next((l[2:].strip() for l in md.splitlines() if l.startswith("# ")), NAME)
    body = markdown_to_html(md)
    end = f'<p class="end"><a href="../">{NAME} downloads</a> &middot; <a href="{HOME_URL}">yarmiplay.com</a></p>'
    if toc:
        body = re.sub(r'<(h[23]) id="([^"]+)">(.*?)</\1>',
                      r'<\1 id="\2">\3<a class="anchor" href="#\2" aria-label="Link to this section">#</a></\1>', body)
        head, body = guide_head(body)
        content = (f'<div class="doc">{head}{toc_html(md)}<main>{body}\n{end}</main></div>'
                   '<a class="to-top" href="#contents">Contents</a>' + GUIDE_JS)
    else:
        content = f"<main>\n{body}\n{end}\n</main>"
    list_style = STEP_CSS if steps else PLAIN_LIST_CSS
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
{search_head(description, site_url, path)}
<link rel="icon" href="../favicon.svg?v=2" type="image/svg+xml">
<meta name="theme-color" content="#0E1116">
<style>
{BASE_CSS}
 body {{ line-height:1.6; }}
 .wrap {{ max-width:52em; }}
 main {{ padding:2em 0 3em; }}
 h1 {{ font-size:clamp(1.9em,5vw,2.4em); letter-spacing:-.02em; line-height:1.15; margin:0 0 .6em; }}
 h2 {{ font-size:1.35em; margin:1.8em 0 .4em; letter-spacing:-.01em; }}
 p, li {{ color:var(--soft); }}
 code {{ background:var(--surface); }}
 h3 {{ font-size:1.1em; margin:1.4em 0 .3em; }}
 h4 {{ font-size:1em; margin:1.2em 0 .2em; }}
 h2[id], h3[id], h4[id] {{ scroll-margin-top:1em; }}
 figure {{ margin:1.2em 0; }}
 figure img {{ max-width:100%; height:auto; border-radius:12px; border:1px solid var(--line); display:block; }}
 .end {{ border-top:1px solid var(--line); margin-top:3em; padding-top:1.5em; color:var(--muted); }}
{list_style}
{GUIDE_CSS if toc else ""}
</style></head>
<body><div class="wrap">
<header class="top">
 <a class="brand" href="../">{LOGO_SVG}{NAME}</a>
 <nav>{"".join(f'<a href="{href}"' + (' aria-current="page"' if href == f"../{path}" else "") + f">{label}</a>"
               for href, label in ((HOME_URL, "Home"), ("../#download", "Download"), ("../manual/", "Manual"), ("../changelog/", "Patch notes")))}</nav>
</header>
{content}
</div></body></html>
"""


STEP_CSS = """ ol { list-style:none; counter-reset:step; padding:0; }
 ol li { counter-increment:step; position:relative; background:var(--surface); border:1px solid var(--line); border-radius:16px;
         padding:1em 1.2em 1.1em 3.8em; margin:.8em 0; }
 ol li::before { content:counter(step); position:absolute; left:1.1em; top:1em; width:1.8em; height:1.8em; border-radius:50%;
                 background:var(--accent); color:var(--on-accent); font-weight:700; display:flex; align-items:center; justify-content:center; }
 ol li b { display:block; color:var(--text); font-size:1.1em; }
 ol li a { display:block; width:max-content; margin-top:.7em; padding:.5em 1.1em; border-radius:10px; background:var(--accent);
           color:var(--on-accent); font-weight:700; text-decoration:none; }
 ol li a:hover { filter:brightness(1.1); }"""
PLAIN_LIST_CSS = """ ol, ul { padding-left:1.4em; }
 li { margin:.25em 0; }"""


def changelog_markdown(metainfo_path):
    """The patch notes page as Markdown: every <release> in the metainfo, newest first, with its date and notes."""
    import xml.etree.ElementTree as ET
    root = ET.parse(metainfo_path).getroot()
    lines = ["# Patch notes", "",
             f"What changed in each version of {NAME}. The app shows the same notes under **Settings > About > "
             "What's new**.", ""]
    releases = root.findall("./releases/release")
    releases.sort(key=lambda r: [int(x) if x.isdigit() else 0 for x in r.get("version", "0").split(".")], reverse=True)
    for release in releases:
        version = release.get("version", "")
        lines += [f"## {version}", ""]
        date = release.get("date")
        if date:
            try:
                day = datetime.date.fromisoformat(date)
                lines += [f"Released {day.day} {day.strftime('%B %Y')}.", ""]
            except ValueError:
                pass
        description = release.find("description")
        for el in (list(description) if description is not None else []):
            if el.tag == "p":
                lines += [" ".join("".join(el.itertext()).split()), ""]
            elif el.tag in ("ul", "ol"):
                bullet = "-" if el.tag == "ul" else "1."
                lines += [f"{bullet} {' '.join(''.join(li.itertext()).split())}" for li in el.findall("li")] + [""]
    return "\n".join(lines)


def redirect_page(target):
    t = html.escape(target)
    return (f'<!doctype html><meta charset="utf-8"><title>{NAME}</title>'
            f'<meta http-equiv="refresh" content="0; url={t}"><a href="{t}">Download {NAME}</a>\n')


def file_version(name):
    """The version in a package name such as YarmiplayTV-1.1.0.msi or yarmiplaytv_1.1.0-1_amd64.deb, or None."""
    m = re.search(r"[-_](\d+(?:\.\d+)+)", name)
    return m.group(1) if m else None


def update_manifest(found, downloads, version, desktop_version):
    """found: {extension: original file name}; downloads: {extension: Download}. Paths are relative to the site."""
    platforms = {}
    for key, exts in UPDATE_PACKAGES.items():
        ext = next((e for e in exts if e in downloads), None)
        if ext is None:
            continue
        fallback = version if ext == ".apk" else desktop_version
        platforms[key] = {"version": file_version(found[ext]) or fallback, "file": downloads[ext].href,
                          "sha256": downloads[ext].sha256}
    return {"page": "./", "platforms": platforms}


def sitemap(site_url, paths, day):
    urls = "".join(f"<url><loc>{html.escape(page_url(site_url, p))}</loc><lastmod>{day}</lastmod></url>\n"
                   for p in paths)
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{urls}</urlset>\n')


def build(dist, out, version, site_url, desktop_version=None, google_verification=None, indexnow_key=None,
          stores=()):
    found = {}
    for name in sorted(os.listdir(dist)):
        path = os.path.join(dist, name)
        ext = os.path.splitext(name)[1].lower()
        if not os.path.isfile(path) or ext not in EXTENSIONS:
            continue
        if ext in found:
            sys.exit(f"two {ext} files in {dist}: {found[ext]} and {name}")
        found[ext] = name

    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    downloads = {}
    for ext, name in found.items():
        src = os.path.join(dist, name)
        target = NAME + os.path.splitext(name)[1]
        shutil.copyfile(src, os.path.join(out, target))
        downloads[ext] = Download(target, EXTENSIONS[ext][1], os.path.getsize(src), sha256_of(src))
        print(f"  {name} -> {target}")

    short_link = None
    if ".apk" in downloads:
        os.makedirs(os.path.join(out, "a"))
        with open(os.path.join(out, "a", "index.html"), "w", encoding="utf-8") as f:
            f.write(redirect_page(f"../{downloads['.apk'].href}"))
        if site_url:
            short_link = site_url.rstrip("/") + "/a"

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pages = [""]
    docs = []
    for doc in DOCS:
        name, folder, _, description = doc
        md = os.path.join(root, "docs", name)
        if not os.path.isfile(md):
            continue
        os.makedirs(os.path.join(out, folder))
        with open(md, encoding="utf-8") as src, \
                open(os.path.join(out, folder, "index.html"), "w", encoding="utf-8") as f:
            f.write(render_doc(src.read(), description, site_url, f"{folder}/",
                               steps=folder in STEP_PAGES, toc=folder in TOC_PAGES))
        pages.append(f"{folder}/")
        docs.append(doc)
    for images, page, sub in DOC_IMAGES:
        if os.path.isdir(os.path.join(root, images)) and os.path.isdir(os.path.join(out, page)):
            shutil.copytree(os.path.join(root, images), os.path.join(out, page, sub), dirs_exist_ok=True)
    if os.path.isfile(os.path.join(root, METAINFO)):
        folder, label, description = CHANGELOG
        os.makedirs(os.path.join(out, folder))
        with open(os.path.join(out, folder, "index.html"), "w", encoding="utf-8") as f:
            f.write(render_doc(changelog_markdown(os.path.join(root, METAINFO)), description, site_url, f"{folder}/"))
        pages.append(f"{folder}/")
        docs.append(("", folder, label, description))

    with open(os.path.join(out, "favicon.svg"), "w", encoding="utf-8") as f:
        f.write(LOGO_SVG)
    preview = os.path.isfile(os.path.join(root, PREVIEW_IMAGE))
    if preview:
        shutil.copyfile(os.path.join(root, PREVIEW_IMAGE), os.path.join(out, "preview.png"))
    if os.path.isdir(os.path.join(root, SCREENSHOTS)):
        shutil.copytree(os.path.join(root, SCREENSHOTS), os.path.join(out, "screenshots"))

    now = datetime.datetime.now(datetime.timezone.utc)
    built = now.strftime("%Y-%m-%d %H:%M UTC")
    pages.append(WATCH_PATH)
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(render_page(by_platform(downloads), version, built, short_link, docs, site_url,
                            google_verification, preview, stores))
    if site_url:
        with open(os.path.join(out, "sitemap.xml"), "w", encoding="utf-8") as f:
            f.write(sitemap(site_url, pages, now.strftime("%Y-%m-%d")))
        with open(os.path.join(out, "robots.txt"), "w", encoding="utf-8") as f:
            f.write(f"User-agent: *\nAllow: /\n\nSitemap: {page_url(site_url, 'sitemap.xml')}\n")
    if indexnow_key:
        with open(os.path.join(out, f"{indexnow_key}.txt"), "w", encoding="utf-8") as f:
            f.write(indexnow_key)
    with open(os.path.join(out, "version.json"), "w", encoding="utf-8") as f:
        json.dump(update_manifest(found, downloads, version, desktop_version or version), f, indent=1)
    open(os.path.join(out, ".nojekyll"), "w").close()
    print(f"wrote {out} ({len(downloads)} downloads)")


def app_version(root, module="app"):
    with open(os.path.join(root, module, "build.gradle.kts"), encoding="utf-8") as f:
        m = re.search(r'val appVersion\s*=\s*"([^"]+)"', f.read())
    return m.group(1) if m else "dev"


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist", required=True, help="folder with the built packages")
    ap.add_argument("--out", required=True, help="site folder to (re)create")
    ap.add_argument("--version", default=None, help="defaults to the app's appVersion")
    ap.add_argument("--site-url", default=None,
                    help="public URL of the site: the TV short link, canonical URLs and sitemap.xml")
    ap.add_argument("--google-verification", default=None, help="Google Search Console HTML-tag token")
    ap.add_argument("--indexnow-key", default=None, help="IndexNow key, published as <key>.txt")
    ap.add_argument("--flathub-id", default=None, help="Flathub app ID, once the app is on Flathub")
    ap.add_argument("--snap-name", default=None, help="Snap Store name, once the snap is published")
    ap.add_argument("--aur-package", default=None, help="AUR package name, once it is published")
    a = ap.parse_args()
    build(a.dist, a.out, a.version or app_version(root), a.site_url, app_version(root, "desktop"),
          a.google_verification, a.indexnow_key, linux_stores(a.flathub_id, a.snap_name, a.aur_package))


if __name__ == "__main__":
    main()
