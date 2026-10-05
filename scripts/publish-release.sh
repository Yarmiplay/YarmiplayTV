#!/usr/bin/env bash
# Attaches files to the GitHub release of the tag being built ($GITHUB_REF_NAME), creating the release if it
# doesn't exist yet. release.yml's Windows and Linux jobs both call it, in either order or at the same time.
#
#   scripts/publish-release.sh FILE...
#
# Environment: GH_TOKEN, VERSION (the desktop appVersion), WINGET_ID, AUR_PACKAGE, SNAP_NAME, FLATHUB_REPO
# (optional, named in the notes), SIGNED=true when the Windows installers are code signed, and SET_NOTES=true to
# rewrite the notes of an existing release (the Windows job, the only one that knows SIGNED).
set -euo pipefail

tag="$GITHUB_REF_NAME"
repo="$GITHUB_REPOSITORY"

notes() {
  local linux="yarmiplaytv_${VERSION}_amd64.deb for Debian and Ubuntu (needs libmpv: sudo apt install libmpv2)"
  [ -n "${FLATHUB_REPO:-}" ] && linux="$linux, Flathub: https://flathub.org/apps/${FLATHUB_REPO##*/}"
  [ -n "${SNAP_NAME:-}" ] && linux="$linux, Snap Store: https://snapcraft.io/$SNAP_NAME"
  [ -n "${AUR_PACKAGE:-}" ] && linux="$linux, AUR: https://aur.archlinux.org/packages/$AUR_PACKAGE"
  echo "YarmiplayTV $VERSION for Windows: the installer (.msi${WINGET_ID:+, or \`winget install $WINGET_ID\`}) and a portable zip. Microsoft Store: https://apps.microsoft.com/detail/9PDBVR6W069J. Linux: $linux. Other platforms: https://tv.yarmiplay.com/"
  if [ "${SIGNED:-}" = true ]; then
    echo
    echo "Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org). See the [code signing policy](https://github.com/$repo#code-signing-policy)."
  fi
}

if ! gh release view "$tag" -R "$repo" > /dev/null 2>&1; then
  if gh release create "$tag" "$@" -R "$repo" --verify-tag --title "YarmiplayTV $tag" --notes "$(notes)"; then
    exit 0
  fi
  echo "Creating the release failed; the other job may have just created it. Uploading instead."
  sleep 10
fi
gh release upload "$tag" "$@" -R "$repo" --clobber
if [ "${SET_NOTES:-}" = true ]; then
  gh release edit "$tag" -R "$repo" --notes "$(notes)"
fi
