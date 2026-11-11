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

# Runners often complete TCP to the store on IPv6 and then stall the TLS handshake.
# snapd reports that as "unable to contact snap store". Prefer IPv4 for this job.
sudo sysctl -w net.ipv6.conf.all.disable_ipv6=1 || true
sudo systemctl restart snapd.socket snapd.service || true

if [[ ! -x /snap/bin/lxd ]]; then
  # Each attempt already waits on snapd's own store timeout (~40s). Keep the backoff short.
  ATTEMPTS=4 DELAY=15 bash scripts/retry.sh sudo snap install lxd
fi
ATTEMPTS=8 DELAY=5 bash scripts/retry.sh sudo lxd init --auto
# Docker on the runner otherwise drops LXD's bridge traffic.
sudo iptables -P FORWARD ACCEPT

if [[ ! -x /snap/bin/snapcraft ]]; then
  ATTEMPTS=4 DELAY=15 bash scripts/retry.sh sudo snap install --classic --channel stable snapcraft
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
