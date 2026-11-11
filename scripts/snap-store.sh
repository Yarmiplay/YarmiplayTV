#!/usr/bin/env bash
# Shared by the snap build and the smoke test. A Snap Store outage is not a failed package check:
# callers turn STORE_FAILURE_STATUS into a warning and exit 0. Any other failure stays a real error.
STORE_FAILURE_STATUS=86

store_log_is_outage() {
  grep -Eqi 'unable to contact snap store|unable to reach the snap store|api\.snapcraft\.io|store\.snapcraft\.io|Failed to install or refresh a snap' "$1"
}

# Runs a command. Store outages are retried. Returns 0, STORE_FAILURE_STATUS when the store never
# answers, or the command's own status for any other failure (that one is not retried).
retry_store() {
  local attempts="${ATTEMPTS:-3}" delay="${DELAY:-10}" i=1 status log
  while true; do
    log=$(mktemp)
    if "$@" 2>&1 | tee "$log"; then
      rm -f "$log"
      return 0
    else
      status=${PIPESTATUS[0]}
    fi
    if ! store_log_is_outage "$log"; then
      rm -f "$log"
      return "$status"
    fi
    rm -f "$log"
    if ((i >= attempts)); then
      echo "snap store still unreachable after $i attempts: $*" >&2
      return "$STORE_FAILURE_STATUS"
    fi
    echo "snap store unreachable (attempt $i of $attempts); retrying in ${delay}s: $*" >&2
    sleep "$delay"
    delay=$((delay * 2))
    i=$((i + 1))
  done
}

# Network failure or a 5xx from the store. Any other HTTP answer means the store is there.
probe_store() {
  local attempts="${ATTEMPTS:-3}" delay="${DELAY:-10}" i=1 code
  while true; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
      -H "Snap-Device-Series: 16" \
      -H "Snap-Device-Architecture: amd64" \
      https://api.snapcraft.io/v2/snaps/info/core24) || code=000
    if [[ "$code" == "200" ]]; then
      return 0
    fi
    echo "snap store probe returned ${code} (attempt $i of $attempts)" >&2
    if [[ "$code" != "000" && ! "$code" =~ ^5 ]]; then
      echo "snap store answered HTTP ${code}; continuing with the build" >&2
      return 0
    fi
    if ((i >= attempts)); then
      return "$STORE_FAILURE_STATUS"
    fi
    sleep "$delay"
    delay=$((delay * 2))
    i=$((i + 1))
  done
}

# Warn, and do not fail the job. A real snapcraft error must not call this.
skip_store() {
  echo "::warning title=Snap Store unreachable::$1"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '%s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 0
}
