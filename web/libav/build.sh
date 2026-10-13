#!/usr/bin/env bash
# Builds the player's FFmpeg (libav.js) with the formats and codecs in config.json: demuxers for MKV, MP4, AVI, TS
# and the rest, and decoders for what browsers' WebCodecs can't decode (AC-3, E-AC-3, DTS, TrueHD, MPEG-2, MPEG-4
# Part 2, VC-1...). Video the browser can decode in hardware (H.264, HEVC, VP9, AV1) only needs its parser here.
#
# Needs emsdk's emcc on PATH (or EMSDK set), plus make, node, curl, xz and pkg-config. Writes the files the page
# loads to web/public/libav/. Takes a while the first time; build/ is kept between runs.
set -euo pipefail

LIBAVJS_VERSION=6.10.9
VARIANT=yarmiplay
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${LIBAV_WORK:-$HERE/build}"
OUT="$HERE/../public/libav"

if ! command -v emcc >/dev/null; then
    if [ -n "${EMSDK:-}" ] && [ -f "$EMSDK/emsdk_env.sh" ]; then
        # shellcheck disable=SC1091
        source "$EMSDK/emsdk_env.sh" >/dev/null
    else
        echo "emcc not found: install emsdk 6.0.5 and set EMSDK" >&2
        exit 1
    fi
fi

mkdir -p "$WORK"
cd "$WORK"
if [ ! -f "src/Makefile" ]; then
    rm -rf pkg src
    npm pack "libav.js@$LIBAVJS_VERSION" --silent >/dev/null
    mkdir -p pkg src
    tar xzf "libav.js-$LIBAVJS_VERSION.tgz" -C pkg
    tar xJf pkg/package/sources/libav.js.tar.xz -C src
    # The release carries its dependencies' sources; put them where the Makefile looks before downloading.
    mkdir -p src/build
    cp pkg/package/sources/*.tar.* src/build/ 2>/dev/null || true
    rm -f src/build/libav.js.tar.xz
fi

cd src
(cd configs && node mkconfig.js "$VARIANT" "$(tr -d '\n' < "$HERE/config.json")")

FULL="$LIBAVJS_VERSION.0"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
# The minifier runs through node: an npm from another system (WSL using Windows' npm) leaves .bin scripts unrunnable.
make -j"$JOBS" MINIFIER="node node_modules/terser/bin/terser" \
    "dist/libav-$FULL-$VARIANT.mjs" \
    "dist/libav-$FULL-$VARIANT.wasm.mjs" \
    dist/libav.types.d.ts

# Only the single-threaded build: with emsdk 6.0.5 the threaded one lacks some of libav.js's helpers in its worker
# (ff_init_demuxer_file fails on AVFormatContext_nb_streams), and demuxing and sound don't need threads.
mkdir -p "$OUT"
cp "dist/libav-$FULL-$VARIANT.mjs" \
   "dist/libav-$FULL-$VARIANT.wasm.mjs" "dist/libav-$FULL-$VARIANT.wasm.wasm" \
   "$OUT/"
cp dist/libav.types.d.ts "$HERE/libav.types.d.ts"
echo "libav.js $VARIANT built into $OUT"
