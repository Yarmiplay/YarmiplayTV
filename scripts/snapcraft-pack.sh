#!/usr/bin/env bash
# Runs inside ghcr.io/canonical/snapcraft:8_core24 with the repo mounted at /project.
# The image's Python install leaves the GNOME extension's command-chain under site-packages, and an
# empty /usr/share/snapcraft/extensions makes Snapcraft look in the wrong place. Point it at the files.
# Content snaps are unpacked from /project/.snap-seed when that directory was filled by a runner that
# can reach the store. Snapcraft skips installing build-snaps inside a container, and the cleanup part
# only needs the files under /snap/<name>/current.
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

seed=/project/.snap-seed
if compgen -G "${seed}/*.snap" > /dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq squashfs-tools
  install_tree() {
    local name=$1 dest="/snap/${name}/current" file="${seed}/${name}.snap"
    if [[ -d "${dest}" ]] && [[ -n "$(find "${dest}" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
      echo "${name} is already on disk at ${dest}"
      return 0
    fi
    [[ -f "${file}" ]] || { echo "missing ${file}" >&2; exit 1; }
    echo "Unpacking ${file} to ${dest}"
    rm -rf "${dest}"
    mkdir -p "$(dirname "${dest}")"
    unsquashfs -d "${dest}" "${file}"
  }
  install_tree core24
  install_tree gtk-common-themes
  install_tree gnome-46-2404
  test -d /snap/core24/current
  test -d /snap/gtk-common-themes/current
  test -d /snap/gnome-46-2404/current
else
  # Store is reachable from this host. Install the content snaps through snapd, with a short backoff.
  if [[ ! -S /run/snapd.socket && -x /usr/lib/snapd/snapd ]]; then
    mkdir -p /run/snapd
    /usr/lib/snapd/snapd &
    for _ in $(seq 1 40); do
      if [[ -S /run/snapd.socket ]]; then
        break
      fi
      sleep 0.5
    done
  fi
  if [[ -S /run/snapd.socket ]]; then
    timeout 120 snap wait system seed.loaded || echo "snapd did not finish seeding; snapcraft will try the store itself" >&2
    install_content() {
      local name=$1 try delay=10
      for try in 1 2 3 4 5 6; do
        if snap install "$name"; then
          return 0
        fi
        echo "snap install ${name} failed (try ${try}); retrying in ${delay}s" >&2
        sleep "$delay"
        delay=$((delay * 2))
      done
      return 1
    }
    install_content core24
    install_content gtk-common-themes
    install_content gnome-46-2404
  fi
fi

exec /bin/run-snapcraft.sh pack
