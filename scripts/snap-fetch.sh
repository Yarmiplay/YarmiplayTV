#!/usr/bin/env bash
# Downloads the amd64 content snaps the snap build and smoke test need from the Snap Store API.
# GitHub-hosted Ubuntu runners cannot open the API (185.125.188.54-60); run this where that URL answers.
# Writes <name>.snap files into the directory given as the first argument (default .snap-seed).
set -euo pipefail

out=${1:-.snap-seed}
mkdir -p "$out"

echo "Snap Store fetch on $(uname -srm)"
if ! curl -fsS --connect-timeout 15 --max-time 30 -o /dev/null \
  -H 'Snap-Device-Series: 16' \
  -H 'Snap-Device-Architecture: amd64' \
  -A 'yarmiplaytv-snap-fetch' \
  https://api.snapcraft.io/v2/snaps/info/core24; then
  echo "api.snapcraft.io did not answer from this runner" >&2
  exit 1
fi

if command -v python3 >/dev/null 2>&1; then
  py=python3
else
  py=python
fi

"$py" - "$out" <<'PY'
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path

out = Path(sys.argv[1])
names = ["bare", "core24", "gtk-common-themes", "gnome-46-2404", "mesa-2404"]
headers = {
    "Snap-Device-Series": "16",
    "Snap-Device-Architecture": "amd64",
    "Accept": "application/json",
    "User-Agent": "yarmiplaytv-snap-fetch",
}

def fetch_json(url: str) -> dict:
    last = None
    for attempt in range(1, 5):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except Exception as exc:  # noqa: BLE001 - log and retry the store
            last = exc
            print(f"  info attempt {attempt} failed: {exc}", flush=True)
            time.sleep(5 * attempt)
    raise SystemExit(f"could not read {url}: {last}")

def pick(info: dict, name: str) -> dict:
    entries = info.get("channel-map") or info.get("result", {}).get("channel-map") or []
    usable = []
    for entry in entries:
        channel = entry.get("channel") or {}
        arch = channel.get("architecture") or entry.get("architecture")
        if arch not in ("amd64", "all"):
            continue
        url = (entry.get("download") or {}).get("url")
        if not url:
            continue
        usable.append(entry)
    def rank(entry: dict) -> tuple:
        channel = entry.get("channel") or {}
        arch = channel.get("architecture") or entry.get("architecture")
        risk = channel.get("risk") or ""
        track = channel.get("track") or ""
        return (
            0 if arch == "amd64" else 1,
            0 if risk == "stable" and track in ("latest", "") else 1 if risk == "stable" else 2,
        )
    if not usable:
        raise SystemExit(f"no amd64 download for {name}: keys={list(info)[:12]}")
    usable.sort(key=rank)
    return usable[0]

def download(url: str, dest: Path) -> None:
    partial = dest.with_suffix(".partial")
    last = None
    for attempt in range(1, 5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": headers["User-Agent"]})
            with urllib.request.urlopen(req, timeout=120) as resp, partial.open("wb") as fh:
                total = 0
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk:
                        break
                    fh.write(chunk)
                    total += len(chunk)
                    if total % (64 * 1024 * 1024) < 1024 * 1024:
                        print(f"  {dest.name} {total // (1024 * 1024)} MiB", flush=True)
            partial.replace(dest)
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            print(f"  download attempt {attempt} failed: {exc}", flush=True)
            partial.unlink(missing_ok=True)
            time.sleep(5 * attempt)
    raise SystemExit(f"could not download {url}: {last}")

for name in names:
    print(f"fetch {name}", flush=True)
    info = fetch_json(f"https://api.snapcraft.io/v2/snaps/info/{name}")
    entry = pick(info, name)
    url = entry["download"]["url"]
    dest = out / f"{name}.snap"
    print(f"  rev {entry.get('revision')} {url}", flush=True)
    download(url, dest)
    digest = (entry.get("download") or {}).get("sha3-384")
    if digest:
        hasher = hashlib.sha3_384()
        with dest.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                hasher.update(chunk)
        got = hasher.hexdigest()
        if got != digest:
            raise SystemExit(f"checksum mismatch for {name}: {got} != {digest}")
    print(f"  wrote {dest} ({dest.stat().st_size} bytes)", flush=True)
PY
