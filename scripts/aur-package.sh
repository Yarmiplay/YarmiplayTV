#!/usr/bin/env bash
# Builds the AUR package (packaging/aur/PKGBUILD) in an Arch Linux container, installs it and plays a clip with
# it, then copies the PKGBUILD and .SRCINFO to publish into aur-out/.
#
#   docker run --rm -v "$PWD:/src" -w /src archlinux:latest bash scripts/aur-package.sh VERSION TAG [DEB]
#
# VERSION is the desktop version, TAG the release tag without the v. With DEB (before a release, in the Linux
# packages workflow) the package is built from that .deb and this checkout's packaging/linux instead of the
# release's downloads, and nothing is written for publishing.
set -euo pipefail

version=$1
tag=$2
local_deb=${3:-}

# A desktop has fonts; the container needs one for the app to draw text.
pacman -Syu --noconfirm --needed base-devel pacman-contrib xorg-server-xvfb mesa ttf-dejavu
useradd -m builder
echo 'builder ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/builder

work=/home/builder/pkg
mkdir -p "$work"
cp packaging/aur/PKGBUILD "$work/"
sed -i -E "s/^pkgver=.*/pkgver=$version/; s/^_tag=.*/_tag=$tag/; s/^pkgrel=.*/pkgrel=1/" "$work/PKGBUILD"
if [ -n "$local_deb" ]; then
  sed -i -E 's#::\$\{_raw\}/[^"]+##; s#https://github.com/Yarmiplay/YarmiplayTV/releases/download/v\$\{_tag\}/##' "$work/PKGBUILD"
  cp "$local_deb" "$work/yarmiplaytv_${version}_amd64.deb"
  cp packaging/linux/com.yarmiplay.TV.desktop "$work/com.yarmiplay.TV-$version.desktop"
  cp packaging/linux/com.yarmiplay.TV.metainfo.xml "$work/com.yarmiplay.TV-$version.metainfo.xml"
fi
chown -R builder "$work"
cd "$work"
sudo -u builder updpkgsums
sudo -u builder makepkg --printsrcinfo > .SRCINFO
sudo -u builder makepkg -si --noconfirm

# Desktops run with a language locale, which libmpv refuses unless the app resets it.
localedef --no-archive -i en_US -f UTF-8 en_US.UTF-8
locale -a | grep -qi '^en_US\.utf-\?8$'
export LC_ALL=en_US.UTF-8

yarmiplaytv --version | tee /tmp/version.txt
grep -q "YarmiplayTV $version, libmpv [0-9]" /tmp/version.txt
grep -q '^java-options=-Dyarmiplaytv.store=aur$' /opt/yarmiplaytv/lib/app/YarmiplayTV.cfg
timeout -k 10 180 xvfb-run -a -s "-screen 0 1920x1080x24" yarmiplaytv --benchmark /src/app/src/androidTest/assets/yarmiplaytv-sync-clip.mp4 \
  --seconds 10 --out /tmp/benchmark.txt || true
cat /tmp/benchmark.txt
grep -Eq "rendered [1-9]" /tmp/benchmark.txt

if [ -z "$local_deb" ]; then
  mkdir -p /src/aur-out
  cp PKGBUILD .SRCINFO /src/aur-out/
fi
