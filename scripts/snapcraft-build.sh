#!/usr/bin/env bash
# Builds the snap in Canonical's Snapcraft image. The image already contains Snapcraft and the core24
# base, so this does not install LXD or Snapcraft from the Snap Store. Content snaps the project still
# pulls (the GNOME extension) do need the store: if it is unreachable the snap is skipped and this
# script exits 0. A snapcraft error in the project exits non-zero, which fails the job.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=snap-store.sh
source scripts/snap-store.sh

export ATTEMPTS="${STORE_PROBE_ATTEMPTS:-3}"
export DELAY="${STORE_PROBE_DELAY:-10}"
if ! probe_store; then
  skip_store "The Snap Store did not answer, so this run did not build the snap. The check stays green. A snapcraft error in the project still fails the job."
fi

image="${SNAPCRAFT_IMAGE:-ghcr.io/canonical/snapcraft:8_core24}"
info="{\"build_url\":\"https://github.com/${GITHUB_REPOSITORY:-local}/actions/runs/${GITHUB_RUN_ID:-0}\"}"
echo "Building the snap with ${image}"
export ATTEMPTS="${STORE_BUILD_ATTEMPTS:-2}"
export DELAY="${STORE_BUILD_DELAY:-20}"
if retry_store docker run --rm --privileged --network host \
  -v "$PWD:/project" \
  -e SNAPCRAFT_BUILD_INFO=1 \
  -e "SNAPCRAFT_IMAGE_INFO=${info}" \
  "$image"; then
  exit 0
else
  status=$?
fi

if [[ "$status" -eq "$STORE_FAILURE_STATUS" ]]; then
  skip_store "The Snap Store became unreachable while Snapcraft was building, so this run has no snap. The check stays green."
fi
echo "::error title=Snap build failed::Snapcraft exited ${status}. This is a build error, not a Snap Store outage."
exit "$status"
