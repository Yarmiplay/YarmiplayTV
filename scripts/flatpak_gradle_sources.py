#!/usr/bin/env python3
"""
Lists every file a Gradle build downloaded as flatpak-builder sources, so the Flatpak build (which has no
network) finds them in a local Maven folder: run the build once with an empty Gradle home, then

  python3 scripts/flatpak_gradle_sources.py GRADLE_USER_HOME packaging/flatpak/gradle-sources.json

Each file in the Gradle cache becomes a "file" source with its URL on the first repository serving the same bytes
(Maven Central, Google, the Gradle Plugin Portal: settings.gradle.kts's repositories) and its sha256, saved under
offline-repository/ in Maven layout. The manifest builds with -PofflineRepo pointing there. Standard library only.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

REPOSITORIES = (
    "https://repo.maven.apache.org/maven2/",
    "https://dl.google.com/dl/android/maven2/",
    "https://plugins.gradle.org/m2/",
)
DEST = "offline-repository"


def cached_files(gradle_home):
    """Maven path -> {sha1: local file} for each file in Gradle's module cache, whose directory names are the
    files' SHA-1 in hex without leading zeros."""
    root = os.path.join(gradle_home, "caches", "modules-2", "files-2.1")
    found = {}
    for group in sorted(os.listdir(root)):
        for module in sorted(os.listdir(os.path.join(root, group))):
            for version in sorted(os.listdir(os.path.join(root, group, module))):
                version_dir = os.path.join(root, group, module, version)
                for digest in sorted(os.listdir(version_dir)):
                    for name in sorted(os.listdir(os.path.join(version_dir, digest))):
                        path = f"{group.replace('.', '/')}/{module}/{version}/{name}"
                        found.setdefault(path, {})[digest.lstrip("0")] = os.path.join(version_dir, digest, name)
    return found


def fetch(url, method="GET"):
    """The response body (b"" for HEAD), or None if the repository doesn't have it."""
    request = urllib.request.Request(url, method=method, headers={"User-Agent": "flatpak-gradle-sources"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code in (403, 404):
                return None
        except (urllib.error.URLError, TimeoutError):
            pass
    raise RuntimeError(f"couldn't reach {url}")


def locate(item):
    """(URL, local file) on the first repository serving the same bytes as one of the cached copies. Some
    artifacts differ between repositories, so each one's .sha1 is compared; a repository without one only counts
    when none matches."""
    path, copies = item
    unverified = None
    for repo in REPOSITORIES:
        sha1 = fetch(repo + path + ".sha1")
        if sha1 is not None:
            digest = sha1.decode("ascii", "replace").split()[0].lower().lstrip("0") if sha1.strip() else ""
            if digest in copies:
                return repo + path, copies[digest]
        elif unverified is None and len(copies) == 1 and fetch(repo + path, "HEAD") is not None:
            unverified = repo + path, next(iter(copies.values()))
    return unverified


def sha256_of(file):
    h = hashlib.sha256()
    with open(file, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    gradle_home, out = sys.argv[1:]
    files = cached_files(gradle_home)
    with concurrent.futures.ThreadPoolExecutor(32) as pool:
        located = dict(zip(files, pool.map(locate, files.items())))
    missing = [path for path, found in located.items() if found is None]
    if missing:
        sys.exit("Not on any repository:\n  " + "\n  ".join(missing))
    sources = []
    for path, (url, file) in sorted(located.items()):
        directory, name = path.rsplit("/", 1)
        sources.append({"type": "file", "url": url, "sha256": sha256_of(file),
                        "dest": f"{DEST}/{directory}", "dest-filename": name})
    with open(out, "w", encoding="utf-8") as f:
        json.dump(sources, f, indent=1)
        f.write("\n")
    print(f"wrote {out}: {len(sources)} files")


if __name__ == "__main__":
    main()
