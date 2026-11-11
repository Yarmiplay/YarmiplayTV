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
# The rock's Python install leaves the GNOME extension's command-chain under site-packages, and
# an empty /usr/share/snapcraft/extensions makes Snapcraft look in the wrong place
# ("unable to determine source type of .../extensions/desktop/command-chain"). Point it at the files.
export ATTEMPTS="${STORE_BUILD_ATTEMPTS:-2}"
export DELAY="${STORE_BUILD_DELAY:-20}"
if retry_store docker run --rm --privileged --network host \
  -v "$PWD:/project" \
  -w /project \
  -e SNAPCRAFT_BUILD_ENVIRONMENT=host \
  -e SNAPCRAFT_BUILD_INFO=1 \
  -e "SNAPCRAFT_IMAGE_INFO=${info}" \
  -e PATH="/usr/libexec/snapcraft:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
  --entrypoint /bin/bash \
  "$image" -c '
    set -euo pipefail
    ext_root=$(find /usr -type d -path "*/site-packages/extensions" -print -quit 2>/dev/null || true)
    if [[ -z "${ext_root}" ]]; then
      echo "snapcraft extensions were not installed in the image" >&2
      exit 1
    fi
    mkdir -p /usr/share/snapcraft/extensions
    for dir in "${ext_root}"/*; do
      [[ -d "${dir}" ]] || continue
      name=$(basename "${dir}")
      [[ "${name}" == __pycache__ ]] && continue
      dest="/usr/share/snapcraft/extensions/${name}"
      if [[ -d "${dest}" && ! -L "${dest}" && ! -e "${dest}/command-chain/desktop-launch" && ! -e "${dest}/command-chain/run" ]]; then
        rm -rf "${dest}"
      fi
      if [[ ! -e "${dest}" ]]; then
        ln -s "${dir}" "${dest}"
      fi
    done
    exec /bin/run-snapcraft.sh pack
  '; then
  exit 0
else
  status=$?
fi

if [[ "$status" -eq "$STORE_FAILURE_STATUS" ]]; then
  skip_store "The Snap Store became unreachable while Snapcraft was building, so this run has no snap. The check stays green."
fi
echo "::error title=Snap build failed::Snapcraft exited ${status}. This is a build error, not a Snap Store outage."
exit "$status"
