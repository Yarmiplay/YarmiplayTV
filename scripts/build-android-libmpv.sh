#!/usr/bin/env bash
# Build the Android libmpv AAR from official upstream sources (jarnedemeulemeester/libmpv-android v1.1.0,
# mpv 0.42.0, FFmpeg 9.1) with:
#   - FFmpeg 9.1 (matching desktop/Flatpak)
#   - the visualizer filters enabled in libavfilter (asplit, showfreqs, showspectrum, aresample, ...)
#   - Android AudioTrack raw bitstream passthrough (AC3/E-AC3/DTS), multichannel IEC 61937,
#     route-change AO reload, and passthrough playhead smoothing (scripts/patches/mpv/010*.patch).
#
# Output: player-mpv/src/main/prebuilt-libmpv/{libmpv-classes.jar,jni/<abi>/*.so}
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_DIR="$REPO_ROOT/scripts/patches/mpv"
OUT_DIR="${1:-$REPO_ROOT/player-mpv/src/main/prebuilt-libmpv}"
WORK="${LIBMPV_WORK_DIR:-/tmp/yarmiplaytv-libmpv-build}"

LIBMPV_ANDROID_TAG="v1.1.0"
FFMPEG_VERSION="9.1"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_SHA256="1a4d394ae63d190a526cd052eb4415c156b5c5e2df37efab41a7de0b8fde2433"
VIS_FILTERS="asplit,showfreqs,showspectrum,aresample,aformat,anull,anullsink,afifo,fifo"

V_NDK="30.0.16248370"
V_CMAKE="4.1.2"
V_PLATFORM="android-36"
V_BUILD_TOOLS="36.0.0"
V_CMDLINE_TOOLS="16111833_latest"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="/usr/local/bin:$JAVA_HOME/bin:$PATH"

mkdir -p "$WORK"

# 1. Android SDK + NDK + CMake (idempotent)
SDK_DIR="$WORK/android-sdk-linux"
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "==> Downloading Android command-line tools..."
    mkdir -p "$SDK_DIR/cmdline-tools"
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${V_CMDLINE_TOOLS}.zip" -o "$WORK/cmdline-tools.zip"
    rm -rf "$WORK/cmdline-tools-unz"
    unzip -q "$WORK/cmdline-tools.zip" -d "$WORK/cmdline-tools-unz"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$WORK/cmdline-tools-unz/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -f "$WORK/cmdline-tools.zip"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

if [ ! -d "$SDK_DIR/ndk/$V_NDK" ] || [ ! -d "$SDK_DIR/cmake/$V_CMAKE" ] || [ ! -d "$SDK_DIR/platforms/$V_PLATFORM" ]; then
    echo "==> Installing Android SDK platform $V_PLATFORM, build-tools $V_BUILD_TOOLS, NDK $V_NDK, CMake $V_CMAKE..."
    set +o pipefail
    yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses > /dev/null || true
    yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" \
        "platforms;$V_PLATFORM" \
        "build-tools;$V_BUILD_TOOLS" \
        "ndk;$V_NDK" \
        "cmake;$V_CMAKE"
    set -o pipefail
fi

# 2. Clone jarnedemeulemeester/libmpv-android at pinned tag
SRC="$WORK/libmpv-android"
if [ ! -d "$SRC/.git" ]; then
    echo "==> Cloning libmpv-android $LIBMPV_ANDROID_TAG..."
    git clone --depth 1 --branch "$LIBMPV_ANDROID_TAG" https://github.com/jarnedemeulemeester/libmpv-android.git "$SRC"
else
    git -C "$SRC" reset --hard "$LIBMPV_ANDROID_TAG"
fi

# 3. Configure versions, compileSdk 36, FFmpeg 9.1, and visualizer filters
echo "==> Configuring buildscripts for FFmpeg $FFMPEG_VERSION + visualizer filters + compileSdk 36..."
sed -i \
    -e "s/^v_platform=.*/v_platform=${V_PLATFORM}/" \
    -e "s/^v_sdk_build_tools=.*/v_sdk_build_tools=${V_BUILD_TOOLS}/" \
    -e "s/^v_ffmpeg=.*/v_ffmpeg=${FFMPEG_VERSION}/" \
    "$SRC/buildscripts/include/depinfo.sh"

sed -i \
    -e "s/compileSdk = 37/compileSdk = 36/" \
    -e "s/buildToolsVersion = \"37.0.0\"/buildToolsVersion = \"${V_BUILD_TOOLS}\"/" \
    "$SRC/libmpv/build.gradle.kts"

sed -i \
    -e "s/--disable-{muxers,encoders,devices,filters}/--disable-{muxers,encoders,devices,filters} --enable-filter=${VIS_FILTERS}/" \
    "$SRC/buildscripts/scripts/ffmpeg.sh"

# Link our prepared SDK and download gas-preprocessor
mkdir -p "$SRC/buildscripts/sdk/bin"
ln -sfn "$SDK_DIR" "$SRC/buildscripts/sdk/android-sdk-linux"
if [ ! -x "$SRC/buildscripts/sdk/bin/gas-preprocessor.pl" ]; then
    curl -fsSL "https://github.com/FFmpeg/gas-preprocessor/raw/master/gas-preprocessor.pl" \
        -o "$SRC/buildscripts/sdk/bin/gas-preprocessor.pl"
    chmod +x "$SRC/buildscripts/sdk/bin/gas-preprocessor.pl"
fi

# Pre-populate FFmpeg 9.1 from official release tarball (verified by SHA-256)
mkdir -p "$SRC/buildscripts/deps"
if [ ! -d "$SRC/buildscripts/deps/ffmpeg" ]; then
    echo "==> Downloading FFmpeg $FFMPEG_VERSION tarball..."
    curl -fsSL "$FFMPEG_URL" -o "$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    echo "${FFMPEG_SHA256}  $WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz" | sha256sum -c -
    mkdir -p "$SRC/buildscripts/deps/ffmpeg"
    tar -xJf "$WORK/ffmpeg-${FFMPEG_VERSION}.tar.xz" -C "$SRC/buildscripts/deps/ffmpeg" --strip-components=1
fi

# Download remaining upstream dependencies (mbedtls, dav1d, libass, libplacebo, curl, mpv, ...)
echo "==> Downloading remaining upstream dependencies..."
(cd "$SRC/buildscripts" && ./include/download-deps.sh)

# 4. Apply our AudioTrack passthrough patches to mpv 0.42.0
echo "==> Applying ao_audiotrack patches to mpv..."
git -C "$SRC/buildscripts/deps/mpv" checkout -- .
for patch in "$PATCH_DIR"/010*.patch; do
    echo "    applying $(basename "$patch")"
    git -C "$SRC/buildscripts/deps/mpv" apply --check "$patch"
    git -C "$SRC/buildscripts/deps/mpv" apply "$patch"
done

# 5. Build all 4 ABIs and assemble the AAR
echo "==> Building libmpv AAR for all 4 ABIs (this takes ~10-20 minutes)..."
rm -rf "$SRC"/buildscripts/deps/*/_build* "$SRC"/buildscripts/prefix
(cd "$SRC/buildscripts" && ./build.sh)

BUILT_AAR="$SRC/libmpv/build/outputs/aar/libmpv-release.aar"
test -f "$BUILT_AAR"

# 6. Verify every ABI, FFmpeg 9.1, visualizer filters, and ao_audiotrack raw passthrough
echo "==> Verifying built AAR: $BUILT_AAR"
python3 - "$BUILT_AAR" <<'PY'
import sys, zipfile

aar = sys.argv[1]
abis = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
with zipfile.ZipFile(aar) as z:
    names = set(z.namelist())
    for abi in abis:
        mpv = f"jni/{abi}/libmpv.so"
        avf = f"jni/{abi}/libavfilter.so"
        avu = f"jni/{abi}/libavutil.so"
        for req in (mpv, avf, avu, f"jni/{abi}/libavcodec.so", f"jni/{abi}/libavformat.so", f"jni/{abi}/libmpv_jni.so" if f"jni/{abi}/libmpv_jni.so" in names else f"jni/{abi}/libplayer.so"):
            pass
        assert mpv in names, f"missing {mpv}"
        assert avf in names, f"missing {avf}"
        assert avu in names, f"missing {avu}"
        mpv_bytes = z.read(mpv)
        for marker in (b"0.42.0", b"raw-passthrough", b"route-reload", b"using raw passthrough"):
            assert marker in mpv_bytes, f"{mpv} missing marker {marker!r}"
        avf_bytes = z.read(avf)
        for flt in (b"showfreqs\0", b"showspectrum\0", b"asplit\0", b"aresample\0"):
            assert flt in avf_bytes, f"{avf} missing filter {flt!r}"
        avu_bytes = z.read(avu)
        assert b"9.1" in avu_bytes, f"{avu} does not look like FFmpeg 9.1"
    meta = z.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode()
    print("AAR metadata:\n" + meta.strip())
    jni_entries = sorted(n for n in names if n.startswith("jni/"))
    print(f"Verified {len(jni_entries)} native libs across {len(abis)} ABIs.")
PY

rm -rf "$OUT_DIR/jni"
mkdir -p "$OUT_DIR"
python3 - "$BUILT_AAR" "$OUT_DIR" <<'PY'
import os, sys, zipfile
aar, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z:
    with open(os.path.join(dst, "libmpv-classes.jar"), "wb") as f:
        f.write(z.read("classes.jar"))
    for info in z.infolist():
        if info.filename.startswith("jni/") and not info.is_dir():
            out = os.path.join(dst, info.filename)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, "wb") as f:
                f.write(z.read(info.filename))
PY
ls -lh "$OUT_DIR/libmpv-classes.jar" "$OUT_DIR"/jni/*/*.so
echo "==> DONE: $OUT_DIR"
