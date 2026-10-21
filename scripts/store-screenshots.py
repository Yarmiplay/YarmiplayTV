#!/usr/bin/env python3
"""Replace the Microsoft Store desktop screenshots with docs/store/screenshots.

Uses the same Entra app as the release workflow (MSSTORE_TENANT_ID, MSSTORE_CLIENT_ID,
MSSTORE_CLIENT_SECRET). A submission already in certification cannot be edited, so this waits
until that one is published, then opens a new submission that only changes the screenshots.

  python scripts/store-screenshots.py
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

APP = "9PDBVR6W069J"
API = "https://manage.devcenter.microsoft.com/v1.0/my"
ROOT = Path(__file__).resolve().parents[1]
SHOTS = ROOT / "docs" / "store" / "screenshots"
CAPTIONS = {
    "01-home.png": "Home",
    "02-home-sky.png": "Sky theme",
    "03-home-grass.png": "Grass theme",
    "04-join.png": "Join a Syncplay room",
    "05-appearance.png": "Appearance, including Ember, Snow, Grass, and Sky",
}
# Anything except a draft we can still edit.
BUSY = {
    "CommitStarted",
    "PreProcessing",
    "Certification",
    "Release",
    "Publishing",
    "Published",
}


def env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"missing {name}")
    return value


def request(method: str, url: str, token: str | None = None, body: bytes | None = None, headers: dict | None = None):
    hdrs = dict(headers or {})
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    if body is not None and "Content-Type" not in hdrs:
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req) as res:
            raw = res.read()
            return res.status, json.loads(raw) if raw and "json" in res.headers.get("Content-Type", "") else raw
    except urllib.error.HTTPError as err:
        detail = err.read().decode("utf-8", "replace")
        sys.exit(f"{method} {url} -> {err.code}\n{detail[:2000]}")


def token() -> str:
    data = urllib.parse.urlencode({
        "client_id": env("MSSTORE_CLIENT_ID"),
        "client_secret": env("MSSTORE_CLIENT_SECRET"),
        "grant_type": "client_credentials",
        "scope": "https://manage.devcenter.microsoft.com/.default",
    }).encode()
    status, payload = request(
        "POST",
        f"https://login.microsoftonline.com/{env('MSSTORE_TENANT_ID')}/oauth2/v2.0/token",
        body=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    if status != 200 or "access_token" not in payload:
        sys.exit(f"token request failed: {status}")
    return payload["access_token"]


def app_resource(auth: str) -> dict:
    _, payload = request("GET", f"{API}/applications/{APP}", auth)
    return payload


def submission(auth: str, sub_id: str) -> dict:
    _, payload = request("GET", f"{API}/applications/{APP}/submissions/{sub_id}", auth)
    return payload


def wait_until_editable(auth: str) -> dict | None:
    """The pending submission when it is still a draft, or None once the Store has no pending one."""
    deadline = time.time() + 5 * 60 * 60
    while True:
        app = app_resource(auth)
        pending = app.get("pendingApplicationSubmission")
        if not pending:
            print("no pending submission")
            return None
        sub_id = pending["id"]
        current = submission(auth, sub_id)
        status = current.get("status")
        print(f"pending submission {sub_id}: {status}")
        if status == "PendingCommit":
            return current
        if status == "CommitFailed":
            print(json.dumps(current.get("statusDetails"), indent=2)[:2000])
            request("DELETE", f"{API}/applications/{APP}/submissions/{sub_id}", auth)
            print("deleted the failed draft")
            return None
        if status not in BUSY:
            print(f"unexpected status {status}; leaving it alone")
            sys.exit(1)
        if time.time() > deadline:
            sys.exit("the Store submission stayed busy for 5 hours")
        time.sleep(120)


def pngs() -> list[Path]:
    files = sorted(SHOTS.glob("0*.png"))
    if not files:
        sys.exit(f"no png files in {SHOTS}")
    # Stdlib only: PNG width and height sit at bytes 16..24 of the IHDR chunk.
    for path in files:
        data = path.read_bytes()
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        if width < 1366 or height < 768:
            sys.exit(f"{path.name} is {width}x{height}; the Store wants at least 1366x768")
        print(f"{path.name} {width}x{height}")
    return files


def replace_screenshots(current: dict, files: list[Path]) -> dict:
    listings = current.get("listings") or {}
    if not listings:
        sys.exit("the submission has no listings")
    language = "en-us" if "en-us" in listings else next(iter(listings))
    images = listings[language]["baseListing"].get("images") or []
    kept = []
    for image in images:
        if image.get("imageType") == "Screenshot":
            image = dict(image)
            image["fileStatus"] = "PendingDelete"
        kept.append(image)
    for path in files:
        kept.append({
            "fileName": path.name,
            "fileStatus": "PendingUpload",
            "imageType": "Screenshot",
            "description": CAPTIONS.get(path.name, ""),
        })
    listings[language]["baseListing"]["images"] = kept
    current.pop("fileUploadUrl", None)
    return current


def upload(url: str, files: list[Path]) -> None:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.name)
    request("PUT", url, body=buf.getvalue(), headers={
        "x-ms-blob-type": "BlockBlob",
        "Content-Type": "application/octet-stream",
    })
    print(f"uploaded {len(files)} screenshots")


def main() -> None:
    files = pngs()
    auth = token()
    current = wait_until_editable(auth)
    if current is None:
        _, created = request("POST", f"{API}/applications/{APP}/submissions", auth)
        sub_id = created["id"]
        print(f"created submission {sub_id}")
        current = submission(auth, sub_id)
    else:
        sub_id = current["id"]
    updated = replace_screenshots(current, files)
    _, saved = request(
        "PUT",
        f"{API}/applications/{APP}/submissions/{sub_id}",
        auth,
        json.dumps(updated).encode(),
    )
    upload_url = saved.get("fileUploadUrl")
    if not upload_url:
        sys.exit("the Store did not return a file upload URL")
    upload(upload_url, files)
    request("POST", f"{API}/applications/{APP}/submissions/{sub_id}/commit", auth)
    print(f"committed submission {sub_id}")
    for _ in range(30):
        time.sleep(20)
        current = submission(auth, sub_id)
        status = current.get("status")
        print(f"status {status}")
        if status == "CommitFailed":
            print(json.dumps(current.get("statusDetails"), indent=2)[:4000])
            sys.exit(1)
        if status and status != "CommitStarted":
            return
    sys.exit("commit did not leave CommitStarted")


if __name__ == "__main__":
    main()
