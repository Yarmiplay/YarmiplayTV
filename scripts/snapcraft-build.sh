#!/usr/bin/env bash
# Builds the snap in Canonical's Snapcraft image. The image already contains Snapcraft and the core24
# base, so this does not install LXD or Snapcraft from the Snap Store.
#
# GitHub-hosted Ubuntu cannot open api.snapcraft.io (the 185.125.188.54-60 addresses: TCP times out,
# and from other cloud networks the TLS handshake never completes). Content snaps are downloaded by
# scripts/snap-fetch.sh on a runner that can reach the API and unpacked here, so packing does not
# talk to the store. With no seed directory, this probes the store and skips only after that fails.
# A snapcraft error in the project exits non-zero.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=snap-store.sh
source scripts/snap-store.sh

seed=0
if compgen -G '.snap-seed/*.snap' > /dev/null; then
  seed=1
  echo "Content snaps are on disk; packing without the Snap Store."
  ls -lh .snap-seed
else
  # Still-dead route: several slow retries, then the caller may skip. A pull request fails that skip.
  prefer_store_route
  export ATTEMPTS="${STORE_PROBE_ATTEMPTS:-6}"
  export DELAY="${STORE_PROBE_DELAY:-15}"
  if ! probe_store; then
    skip_store "The Snap Store did not answer on IPv4 or IPv6 after retries, and no content snaps were fetched from another runner, so this run did not build the snap."
  fi
fi

image="${SNAPCRAFT_IMAGE:-ghcr.io/canonical/snapcraft:8_core24}"
info="{\"build_url\":\"https://github.com/${GITHUB_REPOSITORY:-local}/actions/runs/${GITHUB_RUN_ID:-0}\"}"
echo "Building the snap with ${image}"

run_pack() {
  docker run --rm --privileged --network host \
    -v "$PWD:/project" \
    -w /project \
    -e SNAPCRAFT_BUILD_ENVIRONMENT=host \
    -e SNAPCRAFT_BUILD_INFO=1 \
    -e "SNAPCRAFT_IMAGE_INFO=${info}" \
    -e PATH="/usr/libexec/snapcraft:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    --entrypoint /bin/bash \
    "$image" /project/scripts/snapcraft-pack.sh
}

if [[ "$seed" -eq 1 ]]; then
  # A failure here is a build error. Do not turn it into a store skip.
  set +e
  run_pack
  status=$?
  set -e
  if [[ "$status" -ne 0 ]]; then
    echo "::error title=Snap build failed::Snapcraft exited ${status}."
    exit "$status"
  fi
  exit 0
fi

export ATTEMPTS="${STORE_BUILD_ATTEMPTS:-3}"
export DELAY="${STORE_BUILD_DELAY:-20}"
if retry_store run_pack; then
  exit 0
else
  status=$?
fi

if [[ "$status" -eq "$STORE_FAILURE_STATUS" ]]; then
  skip_store "The Snap Store became unreachable while Snapcraft was building, so this run has no snap."
fi
echo "::error title=Snap build failed::Snapcraft exited ${status}. This is a build error, not a skipped store probe."
exit "$status"
