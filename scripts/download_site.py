#!/usr/bin/env python3
"""
Builds the YarmiplayTV download page: one card per device type / OS, each with its download and install steps.

  python scripts/download_site.py --dist dist --out _site [--version 0.1.0] [--site-url URL]

Files in --dist are sorted onto platforms by extension (.apk: TV and phone/tablet, .msi/.exe: Windows,
.dmg/.pkg: macOS, .deb/.rpm/.AppImage: Linux) and copied under stable names such as YarmiplayTV.apk, so links
keep working across builds. <site>/a/ redirects to the APK for TV apps like Downloader, <site>/privacy/
is docs/privacy.md (the Play Store privacy policy), and <site>/version.json lists each platform's package
and version for the apps' update check. Platforms without a file or store listing show how to run from source. The Pages workflow publishes the result; scripts/apk_server.py serves the
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
SERVER_URL = "https://yarmiplay.github.io/YarmiplayServerTV/"
# Store listings, shown before the platform's files.
STORE_LINKS = {
    "windows": ("Microsoft Store", "https://apps.microsoft.com/detail/9PDBVR6W069J"),
}

# Lower-case extension -> platforms it installs on and the button label.
EXTENSIONS = {
    ".apk": (("tv", "android"), "APK"),
    ".msi": (("windows",), "Installer (.msi)"),
    ".exe": (("windows",), "Installer (.exe)"),
    ".dmg": (("macos",), "Disk image (.dmg)"),
    ".pkg": (("macos",), "Installer (.pkg)"),
    ".deb": (("linux",), "Debian / Ubuntu (.deb)"),
    ".rpm": (("linux",), "Fedora / openSUSE (.rpm)"),
    ".appimage": (("linux",), "AppImage"),
}

# version.json: the package each platform's update check offers, by preference. Windows installs the .msi
# itself (msiexec), so only that counts there.
UPDATE_PACKAGES = {
    "android": (".apk",),
    "windows": (".msi",),
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


def platforms(short_link):
    downloader = (f"Enter <code>{html.escape(short_link)}</code> and download."
                  if short_link else "Enter this page's address followed by <code>/a</code> and download.")
    run = "./gradlew :desktop:run"
    return [
        Platform("tv", "Google TV / Android TV", "Remote-friendly interface. Android TV 8.0 or newer.", [
            "Install the free <b>Downloader</b> app (by AFTVnews) from the Play Store.",
            downloader,
            "Allow Downloader to install unknown apps when Android asks, then choose <b>Install</b>.",
        ], []),
        Platform("android", "Android phone &amp; tablet",
                 "Touch interface; plays files on the device and from Jellyfin. Android 8.0 or newer.", [
            "Tap the download button.",
            "Allow the browser to install unknown apps when Android asks.",
            "Open the file from the notification or <b>Downloads</b> and choose <b>Install</b>.",
        ], []),
        Platform("windows", "Windows", "Windows 10 or 11, 64-bit.", [
            "Get it from the Microsoft Store, or run the installer, then start YarmiplayTV from the Start menu.",
        ], ["Install JDK 17, clone the repository, then:", "gradlew.bat :desktop:run"]),
        Platform("macos", "macOS", "Uses mpv from Homebrew.", [
            "Install mpv: <code>brew install mpv</code>",
            "Open the disk image and drag YarmiplayTV to Applications.",
        ], ["Install JDK 17 and mpv (<code>brew install mpv</code>), clone the repository, then:", run]),
        Platform("linux", "Linux", "Uses your distribution's libmpv.", [
            "Install libmpv: <code>sudo apt install libmpv2</code> (Debian / Ubuntu) or "
            "<code>sudo dnf install mpv-libs</code> (Fedora).",
            "Install the package with your package manager, or mark the AppImage executable and run it.",
        ], ["Install JDK 17 and libmpv, clone the repository, then:", run]),
    ]


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


def render_page(downloads, version, built, short_link=None, privacy=False):
    """downloads: {platform key: [Download]}. Returns the page as a str."""
    cards = []
    for p in platforms(short_link):
        files = downloads.get(p.key, [])
        store = STORE_LINKS.get(p.key)
        if files or store:
            buttons = "".join(
                f'<a class="btn" href="{html.escape(d.href)}" download>{html.escape(d.label)}'
                f'<small>{fmt_size(d.size)}</small></a>' for d in files)
            if store:
                buttons = (f'<a class="btn" href="{html.escape(store[1])}">{html.escape(store[0])}'
                           f'<small>Updates automatically</small></a>' + buttons)
            steps = "".join(f"<li>{s}</li>" for s in p.steps)
            sums = "".join(f"<div>{html.escape(d.href.rsplit('/', 1)[-1])}<br><code>{d.sha256}</code></div>"
                           for d in files if d.sha256)
            body = (f'<div class="dl">{buttons}</div><ol>{steps}</ol>'
                    + (f"<details><summary>SHA-256</summary>{sums}</details>" if sums else ""))
        else:
            intro, cmd = p.source
            body = (f'<p class="none">No package for this platform yet.</p>'
                    f'<p class="src">{intro}</p><pre>{html.escape(cmd)}</pre>')
        cards.append(f'<section class="card" data-platform="{p.key}"><span class="badge">For this device</span>'
                     f'<h2>{p.title}</h2><p class="blurb">{p.blurb}</p>{body}</section>')

    meta = f"Version {html.escape(version)} &middot; built {html.escape(built)}"
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{NAME} downloads</title>
<meta name="description" content="Syncplay client with mpv built in, for Google TV, Android phones and tablets, and desktop.">
<style>
 :root {{ --bg:#0E1116; --card:#171B22; --line:#262C36; --text:#E8EAED; --muted:#9AA0A6; --accent:#3DA5F4; }}
 * {{ box-sizing:border-box; }}
 body {{ margin:0; background:var(--bg); color:var(--text); font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }}
 main {{ max-width:1100px; margin:0 auto; padding:6vh 4vw 4vh; }}
 header {{ text-align:center; margin-bottom:2.5em; }}
 h1 {{ font-size:clamp(2.2em,6vw,3.4em); margin:0; letter-spacing:-.02em; }}
 header p {{ color:var(--muted); font-size:1.15em; margin:.4em auto; max-width:46em; }}
 .meta {{ font-size:.95em; }}
 a {{ color:var(--accent); }}
 .grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(300px,1fr)); gap:1.2em; }}
 .card {{ background:var(--card); border:1px solid var(--line); border-radius:16px; padding:1.4em 1.5em; position:relative; }}
 .card.here {{ border-color:var(--accent); box-shadow:0 0 0 1px var(--accent); }}
 .badge {{ display:none; position:absolute; top:-.75em; left:1.2em; background:var(--accent); color:var(--bg);
          font-size:.8em; font-weight:700; padding:.1em .7em; border-radius:99px; }}
 .card.here .badge {{ display:block; }}
 h2 {{ margin:0 0 .2em; font-size:1.35em; }}
 .blurb, .src, ol, details {{ color:var(--muted); }}
 .blurb {{ margin:0 0 1em; }}
 .dl {{ display:flex; flex-wrap:wrap; gap:.6em; margin-bottom:.8em; }}
 .btn {{ display:inline-flex; flex-direction:column; padding:.6em 1.2em; border-radius:10px; background:var(--accent);
        color:var(--bg); text-decoration:none; font-weight:700; font-size:1.05em; }}
 .btn small {{ font-weight:500; opacity:.8; font-size:.8em; }}
 .btn:hover {{ filter:brightness(1.1); }}
 a:focus-visible, summary:focus-visible {{ outline:3px solid var(--text); outline-offset:3px; }}
 ol {{ padding-left:1.3em; margin:.4em 0; }}
 code, pre {{ font-family:ui-monospace,Consolas,monospace; font-size:.9em; }}
 code {{ background:var(--bg); padding:.1em .35em; border-radius:4px; color:var(--text); overflow-wrap:anywhere; }}
 pre {{ background:var(--bg); padding:.7em 1em; border-radius:8px; overflow-x:auto; color:var(--text); margin:.3em 0 0; }}
 .none {{ margin:0 0 .3em; }}
 details {{ margin-top:.8em; font-size:.85em; }} details div {{ margin-top:.5em; }}
 summary {{ cursor:pointer; }}
 .host {{ margin-top:1.6em; text-align:center; background:var(--card); border:1px solid var(--line); border-radius:16px; padding:1.2em 1.5em; }}
 .host p {{ color:var(--muted); margin:.3em 0 0; }}
 #ios {{ display:none; text-align:center; color:var(--muted); margin:-1em 0 2em; }}
 footer {{ text-align:center; color:var(--muted); margin-top:3em; font-size:.95em; }}
 @media (min-width:1600px) {{ body {{ font-size:20px; }} main {{ max-width:1500px; }} }}
</style></head>
<body><main>
<header>
 <h1>{NAME}</h1>
 <p>A Syncplay client with mpv built in. Watch in sync with desktop Syncplay users, from Jellyfin or your own files.</p>
 <p class="meta">{meta}</p>
</header>
<p id="ios">iPhone and iPad aren't supported.</p>
<div class="grid">
{chr(10).join(cards)}
</div>
<section class="host">
 <strong>Host your own server</strong>
 <p><a href="{SERVER_URL}">YarmiplayServerTV</a> runs a Syncplay server and a Jellyfin server from your Windows, macOS or Linux computer.</p>
</section>
<footer>Phones, tablets and TVs use the same APK: it picks the TV or touch interface by itself.
 &middot; <a href="{REPO_URL}">Source</a>{' &middot; <a href="privacy/">Privacy</a>' if privacy else ''}
 &middot; <a href="{REPO_URL}#code-signing-policy">Code signing policy</a></footer>
</main>
<script>
(function () {{
  var ua = navigator.userAgent, key = null;
  if (/Android/i.test(ua)) key = /\\bTV\\b|GoogleTV|AFT[A-Z]|BRAVIA|SMART-TV|Large Screen/i.test(ua) ? "tv" : "android";
  else if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) {{
    document.getElementById("ios").style.display = "block";
  }}
  else if (/Windows/.test(ua)) key = "windows";
  else if (/Macintosh|Mac OS X/.test(ua)) key = "macos";
  else if (/Linux|X11|CrOS/.test(ua)) key = "linux";
  var card = key && document.querySelector('[data-platform="' + key + '"]');
  if (!card) return;
  card.classList.add("here");
  card.parentNode.insertBefore(card, card.parentNode.firstChild);
  var btn = card.querySelector(".btn");
  if (btn && key === "tv") btn.focus();
}})();
</script>
</body></html>
"""


def inline_markdown(text):
    """`code`, **bold** and [text](url) in already-escaped text."""
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    return re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', text)


def markdown_to_html(md):
    """The Markdown docs/privacy.md uses: # headings, paragraphs and "- " lists, lines wrapped freely."""
    blocks, para, items = [], [], []

    def flush():
        if para:
            blocks.append(f"<p>{inline_markdown(html.escape(' '.join(para), quote=False))}</p>")
            para.clear()
        if items:
            lis = "".join(f"<li>{inline_markdown(html.escape(i, quote=False))}</li>" for i in items)
            blocks.append(f"<ul>{lis}</ul>")
            items.clear()

    for line in md.splitlines():
        stripped = line.strip()
        heading = re.match(r"(#{1,3}) (.+)", stripped)
        if not stripped:
            flush()
        elif heading:
            flush()
            level = len(heading.group(1))
            blocks.append(f"<h{level}>{inline_markdown(html.escape(heading.group(2), quote=False))}</h{level}>")
        elif stripped.startswith("- "):
            if para:
                flush()
            items.append(stripped[2:])
        elif items and line.startswith(" "):
            items[-1] += " " + stripped
        else:
            if items:
                flush()
            para.append(stripped)
    flush()
    return "\n".join(blocks)


def render_doc(md):
    """A Markdown document as a page in the download page's style."""
    title = next((l[2:].strip() for l in md.splitlines() if l.startswith("# ")), NAME)
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>
 body {{ margin:0; background:#0E1116; color:#E8EAED; font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }}
 main {{ max-width:760px; margin:0 auto; padding:6vh 5vw; }}
 h1 {{ font-size:2.2em; letter-spacing:-.02em; margin:0 0 .6em; }}
 h2 {{ font-size:1.3em; margin:1.6em 0 .4em; }}
 p, li {{ color:#C4C7CC; }}
 a {{ color:#3DA5F4; }}
 code {{ font-family:ui-monospace,Consolas,monospace; font-size:.9em; background:#171B22; padding:.1em .35em; border-radius:4px; }}
</style></head>
<body><main>
{markdown_to_html(md)}
<p><a href="../">{NAME} downloads</a></p>
</main></body></html>
"""


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


def build(dist, out, version, site_url, desktop_version=None):
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

    privacy = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "privacy.md")
    if os.path.isfile(privacy):
        os.makedirs(os.path.join(out, "privacy"))
        with open(privacy, encoding="utf-8") as src, \
                open(os.path.join(out, "privacy", "index.html"), "w", encoding="utf-8") as f:
            f.write(render_doc(src.read()))

    built = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(render_page(by_platform(downloads), version, built, short_link, os.path.isfile(privacy)))
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
    ap.add_argument("--site-url", default=None, help="public URL of the site, shown as the TV short link")
    a = ap.parse_args()
    build(a.dist, a.out, a.version or app_version(root), a.site_url, app_version(root, "desktop"))


if __name__ == "__main__":
    main()
