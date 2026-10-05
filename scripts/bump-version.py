#!/usr/bin/env python3
"""
Sets the app version for a release: appVersion in app/build.gradle.kts (Android, which the release tag has to
match) and desktop/build.gradle.kts, and a <release> for the desktop version at the top of the Linux metainfo
(packaging/linux/com.yarmiplay.TV.metainfo.xml), which release.yml requires.

  python scripts/bump-version.py 1.5.0 [--notes "What changed"] [--android-only | --desktop-only]

Then commit, push and tag v<Android version>. Standard library only.
"""
from __future__ import annotations

import argparse
import datetime
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METAINFO = os.path.join(ROOT, "packaging", "linux", "com.yarmiplay.TV.metainfo.xml")
VERSION_LINE = re.compile(r'^val appVersion = "([^"]+)"', re.M)


def set_app_version(module, version):
    path = os.path.join(ROOT, module, "build.gradle.kts")
    with open(path, encoding="utf-8", newline="") as f:
        text = f.read()
    old = VERSION_LINE.search(text).group(1)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(VERSION_LINE.sub(f'val appVersion = "{version}"', text, count=1))
    print(f"{module}: {old} -> {version}")


def add_release(version, notes, day):
    with open(METAINFO, encoding="utf-8", newline="") as f:
        text = f.read()
    if f'<release version="{version}"' in text:
        print(f"metainfo: already has release {version}")
        return
    nl = "\r\n" if "\r\n" in text else "\n"
    entry = nl.join([f'    <release version="{version}" date="{day}">', "      <description>",
                     f"        <p>{html.escape(notes, quote=False)}</p>", "      </description>", "    </release>", ""])
    text, n = re.subn(r"(<releases>\r?\n)", lambda m: m.group(1) + entry, text, count=1)
    if not n:
        sys.exit(f"no <releases> in {METAINFO}")
    with open(METAINFO, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print(f"metainfo: added release {version} ({day})")


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("version", help="major.minor.patch")
    ap.add_argument("--notes", default="Bug fixes and improvements.", help="the metainfo release note")
    only = ap.add_mutually_exclusive_group()
    only.add_argument("--android-only", action="store_true")
    only.add_argument("--desktop-only", action="store_true")
    a = ap.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", a.version):
        sys.exit("the version has to be major.minor.patch")
    if not a.desktop_only:
        set_app_version("app", a.version)
    if not a.android_only:
        set_app_version("desktop", a.version)
        add_release(a.version, a.notes, datetime.date.today().isoformat())


if __name__ == "__main__":
    main()
