#!/usr/bin/env bash
# Publishes one already-built .snap. snapcraft upload uses dashboard.snapcraft.io,
# storage.snapcraftcontent.com and login.ubuntu.com. Those answer from GitHub-hosted Ubuntu.
# api.snapcraft.io does not, so this does not install Snapcraft from the store: the Snapcraft
# image already contains it.
#
#   SNAPCRAFT_STORE_CREDENTIALS=... scripts/snap-publish.sh dist/yarmiplaytv_3.0.3_amd64.snap
#   SNAP_CHANNEL overrides the channel (default stable).
set -euo pipefail

snap_file=${1:?pass the .snap path}
channel=${SNAP_CHANNEL:-stable}
cd "$(dirname "$0")/.."
# shellcheck source=snap-store.sh
source scripts/snap-store.sh

[[ -f "$snap_file" ]] || { echo "::error::No such snap: $snap_file"; exit 1; }
if [[ -z "${SNAPCRAFT_STORE_CREDENTIALS:-}" ]]; then
  echo "::error::SNAPCRAFT_STORE_CREDENTIALS is empty, so the snap cannot be published."
  exit 1
fi

diagnose_store_route

upload_ok=1
probe_upload() {
  local url=$1 flag code
  code=""
  for flag in -4 -6; do
    code=$(curl "$flag" -sS -o /dev/null -w '%{http_code}' --connect-timeout 15 --max-time 25 "$url" || true)
    echo "UPLOAD $flag $url ${code:-000}"
    if [[ -n "$code" && "$code" != "000" ]]; then
      return 0
    fi
  done
  echo "::error title=Snap upload host unreachable::$url did not answer on IPv4 or IPv6."
  upload_ok=0
}

probe_upload https://dashboard.snapcraft.io/dev/api/account
probe_upload https://storage.snapcraftcontent.com/
probe_upload https://login.ubuntu.com/
if [[ "$upload_ok" -ne 1 ]]; then
  exit 1
fi

image="${SNAPCRAFT_IMAGE:-ghcr.io/canonical/snapcraft:8_core24}"
snap_rel=$(realpath --relative-to="$PWD" "$snap_file")
echo "Publishing ${snap_rel} to ${channel} with ${image}"
docker run --rm --network host \
  -v "$PWD:/work" -w /work \
  -e SNAPCRAFT_STORE_CREDENTIALS \
  -e SNAP_CHANNEL="$channel" \
  -e SNAP_FILE="/work/${snap_rel}" \
  --entrypoint /bin/bash \
  "$image" -c 'set -euo pipefail
    if command -v snapcraft >/dev/null 2>&1; then
      bin=$(command -v snapcraft)
    elif [[ -x /usr/bin/snapcraft ]]; then
      bin=/usr/bin/snapcraft
    else
      echo "snapcraft is not in the image" >&2
      find /usr /opt /bin -name snapcraft -type f 2>/dev/null | head
      exit 1
    fi
    echo "Using $bin"
    "$bin" version
    "$bin" upload --release="$SNAP_CHANNEL" "$SNAP_FILE"
  '
