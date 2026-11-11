#!/usr/bin/env bash
# Installs a locally built snap with the content snaps of its GNOME extension, checks that it starts with
# libmpv and plays a clip on a virtual display. For GitHub's Ubuntu runners.
#
#   scripts/snap-smoke-test.sh yarmiplaytv_1.4.5_amd64.snap
set -euo pipefail

snap=$1
dir=$(dirname "$0")
# shellcheck source=snap-store.sh
source "$dir/snap-store.sh"
# Same route as the build: IPv4 when the runner's IPv6 path to the store black-holes.
prefer_store_route
# Content snaps come from the store. An outage skips the smoke test instead of failing the job.
# A snap that installs and then fails to draw frames still fails the job.
export ATTEMPTS="${STORE_INSTALL_ATTEMPTS:-6}"
export DELAY="${STORE_INSTALL_DELAY:-15}"
install_status=0
retry_store sudo snap install core24 gtk-common-themes gnome-46-2404 mesa-2404 || install_status=$?
if [[ "$install_status" -eq "$STORE_FAILURE_STATUS" ]]; then
  skip_store "The Snap Store was unreachable, so the snap was not installed or played. The snap artifact from the build is unchanged. A playback failure still fails the job."
fi
if [[ "$install_status" -ne 0 ]]; then
  echo "::error title=Snap install failed::Installing the content snaps failed (exit ${install_status}), and the log was not a Snap Store outage."
  exit "$install_status"
fi
install_status=0
retry_store sudo snap install --dangerous "$snap" || install_status=$?
if [[ "$install_status" -eq "$STORE_FAILURE_STATUS" ]]; then
  skip_store "The Snap Store was unreachable while installing the locally built snap, so it was not played. A playback failure still fails the job."
fi
if [[ "$install_status" -ne 0 ]]; then
  echo "::error title=Snap install failed::Installing ${snap} failed (exit ${install_status})."
  exit "$install_status"
fi
# Content connections only happen by themselves for snaps from the store.
sudo snap connect yarmiplaytv:gnome-46-2404 gnome-46-2404
sudo snap connect yarmiplaytv:gpu-2404 mesa-2404:gpu-2404
sudo snap connect yarmiplaytv:gtk-3-themes gtk-common-themes:gtk-3-themes
snap connections yarmiplaytv

# Desktops run with a language locale, which libmpv refuses unless the app resets it.
locale -a | grep -qi '^en_US\.utf-\?8$' || sudo locale-gen en_US.UTF-8
export LC_ALL=en_US.UTF-8

/snap/bin/yarmiplaytv --version | tee "$HOME/snap-version.txt"
if ! grep -q "libmpv [0-9]" "$HOME/snap-version.txt"; then
  snap run --shell yarmiplaytv -c 'echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"; ldd "$SNAP/usr/lib/x86_64-linux-gnu/libmpv.so.2" | grep -v "=> /"' || true
  exit 1
fi

command -v xvfb-run > /dev/null || { sudo apt-get update -q && sudo apt-get install -y -q xvfb; }
cp app/src/androidTest/assets/yarmiplaytv-sync-clip.mp4 "$HOME/clip.mp4"
# No audio server here. mpv's pipewire output waits for one until this timeout, so use the null output.
# The check below is that frames were drawn.
timeout -k 10 180 xvfb-run -a -s "-screen 0 1920x1080x24" /snap/bin/yarmiplaytv --benchmark "$HOME/clip.mp4" \
  --ao null --seconds 10 --out "$HOME/snap-benchmark.txt" || true
cat "$HOME/snap-benchmark.txt"
grep -Eq "rendered [1-9]" "$HOME/snap-benchmark.txt"
