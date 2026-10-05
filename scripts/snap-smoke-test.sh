#!/usr/bin/env bash
# Installs a locally built snap with the content snaps of its GNOME extension, checks that it starts with
# libmpv and plays a clip on a virtual display. For GitHub's Ubuntu runners.
#
#   scripts/snap-smoke-test.sh yarmiplaytv_1.4.5_amd64.snap
set -euo pipefail

snap=$1
sudo snap install core24 gtk-common-themes gnome-46-2404 mesa-2404
sudo snap install --dangerous "$snap"
# Content connections only happen by themselves for snaps from the store.
sudo snap connect yarmiplaytv:gnome-46-2404 gnome-46-2404
sudo snap connect yarmiplaytv:gpu-2404 mesa-2404:gpu-2404
sudo snap connect yarmiplaytv:gtk-3-themes gtk-common-themes:gtk-3-themes
snap connections yarmiplaytv

/snap/bin/yarmiplaytv --version | tee "$HOME/snap-version.txt"
if ! grep -q "libmpv [0-9]" "$HOME/snap-version.txt"; then
  snap run --shell yarmiplaytv -c 'echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"; ldd "$SNAP/usr/lib/x86_64-linux-gnu/libmpv.so.2" | grep -v "=> /"' || true
  exit 1
fi

command -v xvfb-run > /dev/null || { sudo apt-get update -q && sudo apt-get install -y -q xvfb; }
cp app/src/androidTest/assets/yarmiplaytv-sync-clip.mp4 "$HOME/clip.mp4"
timeout -k 10 180 xvfb-run -a -s "-screen 0 1920x1080x24" /snap/bin/yarmiplaytv --benchmark "$HOME/clip.mp4" \
  --seconds 10 --out "$HOME/snap-benchmark.txt" || true
cat "$HOME/snap-benchmark.txt"
grep -Eq "rendered [1-9]" "$HOME/snap-benchmark.txt"
