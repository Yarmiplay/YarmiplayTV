#!/usr/bin/env bash
# How this machine reaches the Snap Store.
#
# GitHub-hosted Ubuntu cannot open api.snapcraft.io (185.125.188.54-60): IPv4 times out and IPv6
# has no route. Uploading a snap does not use that API. snapcraft upload talks to
# dashboard.snapcraft.io, storage.snapcraftcontent.com, and login.ubuntu.com.
# diagnose_store_route prints DNS and both address families for the info API and those upload hosts.
# prefer_store_route pins the family that answered the info API, and when only IPv4 works it turns
# IPv6 off so snapd (which does not use gai.conf) cannot try the black-holed route.
set -u

store_api_url=https://api.snapcraft.io/v2/snaps/info/core24
store_api_headers=(-H 'Snap-Device-Series: 16' -H 'Snap-Device-Architecture: amd64')

store_curl_ok() {
  curl "$1" -fsS --connect-timeout 8 --max-time 15 -o /dev/null "${store_api_headers[@]}" "$store_api_url"
}

diagnose_store_route() {
  echo "::group::Snap Store DNS"
  local host
  for host in api.snapcraft.io dashboard.snapcraft.io storage.snapcraftcontent.com upload.apps.ubuntu.com public.apps.ubuntu.com login.ubuntu.com; do
    echo "-- $host"
    getent ahostsv4 "$host" || echo "(no A record)"
    getent ahostsv6 "$host" || echo "(no AAAA record)"
  done
  echo "::endgroup::"

  local flag
  for flag in -4 -6; do
    echo "::group::curl $flag $store_api_url"
    if curl "$flag" -v --connect-timeout 8 --max-time 15 -o /dev/null "${store_api_headers[@]}" "$store_api_url"; then
      echo "RESULT $flag ok"
    else
      echo "RESULT $flag fail $?"
    fi
    echo "::endgroup::"
  done

  local code
  for host in storage.snapcraftcontent.com upload.apps.ubuntu.com login.ubuntu.com dashboard.snapcraft.io public.apps.ubuntu.com; do
    for flag in -4 -6; do
      code=$(curl "$flag" -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 --max-time 12 "https://$host/" || echo fail)
      echo "RESULT $flag $host $code"
    done
  done

  # The hosts snapcraft upload actually calls. Any HTTP status means the route works; 000 does not.
  echo "::group::Snap upload endpoints"
  local url
  for url in \
    https://dashboard.snapcraft.io/dev/api/account \
    https://storage.snapcraftcontent.com/ \
    https://upload.apps.ubuntu.com/ \
    https://login.ubuntu.com/
  do
    for flag in -4 -6; do
      code=$(curl "$flag" -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 --max-time 12 "$url" || echo fail)
      echo "RESULT $flag $url $code"
    done
  done
  echo "::endgroup::"
}

# Call after diagnose, or on its own. Sets STORE_CURL_FLAGS to -4 or -6 when one family answers.
prefer_store_route() {
  if [[ -n "${STORE_ROUTE_READY:-}" ]]; then
    return 0
  fi
  local v4=1 v6=1
  if store_curl_ok -4; then v4=0; else true; fi
  if store_curl_ok -6; then v6=0; else true; fi
  if [[ "$v4" -eq 0 ]]; then
    export STORE_CURL_FLAGS=-4
    if [[ -n "${GITHUB_ENV:-}" ]]; then
      echo "STORE_CURL_FLAGS=-4" >> "$GITHUB_ENV"
    fi
  fi
  if [[ "$v4" -eq 0 && "$v6" -ne 0 ]]; then
    echo "Snap Store answers on IPv4 and not on IPv6. Using IPv4 for curl, snapd, and snapcraft."
    if [[ -f /etc/gai.conf ]] && ! grep -qE '^[[:space:]]*precedence ::ffff:0:0/96[[:space:]]+100' /etc/gai.conf; then
      echo 'precedence ::ffff:0:0/96  100' | sudo tee -a /etc/gai.conf > /dev/null
    fi
    sudo sysctl -w net.ipv6.conf.all.disable_ipv6=1 > /dev/null
    sudo sysctl -w net.ipv6.conf.default.disable_ipv6=1 > /dev/null
    sudo sysctl -w net.ipv6.conf.lo.disable_ipv6=1 > /dev/null || true
    if command -v systemctl > /dev/null 2>&1; then
      sudo systemctl try-restart snapd || true
    fi
  elif [[ "$v4" -eq 0 ]]; then
    echo "Snap Store answers on IPv4."
  elif [[ "$v6" -eq 0 ]]; then
    echo "Snap Store answers on IPv6 only."
    export STORE_CURL_FLAGS=-6
    if [[ -n "${GITHUB_ENV:-}" ]]; then
      echo "STORE_CURL_FLAGS=-6" >> "$GITHUB_ENV"
    fi
  else
    echo "Snap Store API did not answer on IPv4 or IPv6 from this host."
  fi
  export STORE_ROUTE_READY=1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  diagnose_store_route
fi
