#!/usr/bin/env bash
# Builds the snap the way snapcore/action-build does, and retries the Snap Store installs.
# The action installs LXD once; a store blip ("unable to contact snap store") fails the job
# before snapcraft runs. A refresh of a snap that is already installed is not required.
#
# Writes snap=<absolute path> to $GITHUB_OUTPUT when that file is set.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -x /usr/bin/snap ]]; then
  sudo apt-get update -q
  sudo apt-get install -qy snapd
fi
# Some runner images have a non-root /, which snap-confine rejects.
if [[ $(stat -c '%u:%g' /) != 0:0 ]]; then
  sudo chown root:root /
fi

sudo groupadd --force --system lxd
sudo usermod --append --groups lxd "${USER}"

if [[ ! -x /snap/bin/lxd ]]; then
  ATTEMPTS=8 DELAY=20 bash scripts/retry.sh sudo snap install lxd
fi
ATTEMPTS=8 DELAY=5 bash scripts/retry.sh sudo lxd init --auto
# Docker on the runner otherwise drops LXD's bridge traffic.
sudo iptables -P FORWARD ACCEPT

if [[ ! -x /snap/bin/snapcraft ]]; then
  ATTEMPTS=8 DELAY=20 bash scripts/retry.sh sudo snap install --classic --channel stable snapcraft
fi

export SNAPCRAFT_BUILD_ENVIRONMENT=lxd
export SNAPCRAFT_BUILD_INFO=1
export SNAPCRAFT_IMAGE_INFO="{\"build_url\":\"https://github.com/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}\"}"

sudo -u "${USER}" -E /snap/bin/snapcraft

shopt -s nullglob
snaps=(*.snap)
if ((${#snaps[@]} == 0)); then
  echo "snapcraft produced no .snap" >&2
  exit 1
fi
if ((${#snaps[@]} > 1)); then
  echo "more than one snap in ${PWD}: ${snaps[*]}" >&2
fi
snap="${PWD}/${snaps[0]}"
echo "Built ${snap}"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  echo "snap=${snap}" >> "${GITHUB_OUTPUT}"
fi
