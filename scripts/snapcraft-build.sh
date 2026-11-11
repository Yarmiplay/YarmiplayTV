#!/usr/bin/env bash
# Builds the snap in LXD the way snapcore/action-build does. GitHub runners often fail the first
# `snap install` with "unable to contact snap store"; the store calls are retried, and a snapcraft
# failure that is not the store still fails immediately.
set -euo pipefail

export ATTEMPTS="${ATTEMPTS:-6}"
export DELAY="${DELAY:-20}"

# Runners frequently black-hole the store's IPv6 addresses. snapd reports that as a store outage
# and does not fall back to IPv4 before the command times out.
sudo sysctl -w net.ipv6.conf.all.disable_ipv6=1
sudo systemctl restart snapd
timeout 180 sudo snap wait system seed.loaded

sudo groupadd --force --system lxd
sudo usermod --append --groups lxd "${USER}"

# Retries only when the captured log looks like the snap store (or DNS in front of it) failing.
retry_store() {
  local attempts="${ATTEMPTS}" delay="${DELAY}" i=1 status log
  while true; do
    log=$(mktemp)
    if "$@" 2>&1 | tee "$log"; then
      rm -f "$log"
      return 0
    else
      status=${PIPESTATUS[0]}
    fi
    if ! grep -Eqi 'unable to contact snap store|cannot communicate with server|no such host|connection reset|connection timed out|temporary failure|TLS handshake|i/o timeout|Bad Gateway|Service Unavailable|api\.snapcraft\.io' "$log"; then
      echo "snapcraft-build: not a store failure (exit $status): $*" >&2
      rm -f "$log"
      return "$status"
    fi
    rm -f "$log"
    if ((i >= attempts)); then
      echo "snapcraft-build: store still unreachable after $i attempts (exit $status): $*" >&2
      return "$status"
    fi
    echo "snapcraft-build: store unreachable (attempt $i of $attempts); retrying in ${delay}s: $*" >&2
    sleep "$delay"
    delay=$((delay * 2))
    i=$((i + 1))
  done
}

if snap list lxd > /dev/null 2>&1; then
  retry_store sudo snap refresh lxd || echo "snapcraft-build: keeping the LXD already on the runner"
else
  retry_store sudo snap install lxd
fi
sudo lxd waitready
if ! sudo lxc info > /dev/null 2>&1; then
  sudo lxd init --auto
fi
# Docker's firewall drops LXD's bridge. The snapcraft action opens it the same way.
sudo iptables -P FORWARD ACCEPT

if snap list snapcraft > /dev/null 2>&1; then
  retry_store sudo snap refresh snapcraft --classic || echo "snapcraft-build: keeping the Snapcraft already on the runner"
else
  retry_store sudo snap install snapcraft --classic
fi

export SNAPCRAFT_BUILD_ENVIRONMENT=lxd
export SNAPCRAFT_BUILD_INFO=1
export SNAPCRAFT_IMAGE_INFO="{\"build_url\":\"https://github.com/${GITHUB_REPOSITORY:-local}/actions/runs/${GITHUB_RUN_ID:-0}\"}"
retry_store sudo --user "${USER}" --preserve-env snapcraft
