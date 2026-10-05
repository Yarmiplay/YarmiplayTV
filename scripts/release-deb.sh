#!/usr/bin/env bash
# Downloads the .deb of a GitHub release into deb/ for release.yml's Linux store jobs: the tag being built, or
# the latest release in a manual run. Writes tag, version (the desktop version, from the file name) and path to
# $GITHUB_OUTPUT.
set -euo pipefail

repo="$GITHUB_REPOSITORY"
if [ "$GITHUB_REF_TYPE" = tag ]; then
  tag="$GITHUB_REF_NAME"
else
  tag=$(gh release view -R "$repo" --json tagName -q .tagName)
fi
rm -rf deb
gh release download "$tag" -R "$repo" -p 'yarmiplaytv_*_amd64.deb' -D deb ||
  { echo "::error::Release $tag has no yarmiplaytv_<version>_amd64.deb"; exit 1; }
path=$(ls deb/*.deb)
version=$(basename "$path" | sed -E 's/^yarmiplaytv_(.+)_amd64\.deb$/\1/')
echo "Release $tag: $path (version $version)"
{
  echo "tag=$tag"
  echo "version=$version"
  echo "path=$path"
} >> "$GITHUB_OUTPUT"
