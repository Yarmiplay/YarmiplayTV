#!/usr/bin/env bash
# Runs a command until it succeeds, up to ATTEMPTS times (default 5), waiting DELAY seconds (default 15) after the
# first failure and twice as long after each next one. For CI steps that only fail on flaky downloads.
#
#   scripts/retry.sh flatpak install -y flathub org.freedesktop.Sdk//26.08
set -u
attempts=${ATTEMPTS:-5}
delay=${DELAY:-15}
for ((i = 1; ; i++)); do
  "$@" && exit 0
  status=$?
  if ((i >= attempts)); then
    echo "retry.sh: failed $i times (exit $status): $*" >&2
    exit "$status"
  fi
  echo "retry.sh: attempt $i of $attempts failed (exit $status); retrying in ${delay}s: $*" >&2
  sleep "$delay"
  delay=$((delay * 2))
done
