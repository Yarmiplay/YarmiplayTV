#!/bin/bash
# Rebuild FFmpeg 8.1 libavfilter (the same version as dev.jdtech.mpv:libmpv:1.0.0)
# with the desktop visualizer graph's filters. Everything else stays the upstream .so files.
# The libraries this produces are committed at player-mpv/src/main/prebuilt-libavfilter/<abi>/.
# Needs the FFmpeg 8.1 source in /tmp/ffmpeg-8.1, NDK r29 in /tmp/ndk/android-ndk-r29, and the
# unpacked 1.0.0 AAR jni/ tree in /tmp/libmpv-aar/unz/jni (the symbol check links against it).
set -euo pipefail

SRC=/tmp/ffmpeg-8.1
NDK_BIN=/tmp/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=/tmp/lavfi-out
AAR_JNI=/tmp/libmpv-aar/unz/jni
FILTERS=asplit,showfreqs,showspectrum,aresample,aformat,anull,anullsink,afifo,fifo
mkdir -p "$OUT"

build_host() {
  local b=/tmp/ffmpeg-host
  rm -rf "$b"
  mkdir -p "$b"
  cd "$b"
  "$SRC/configure" \
    --disable-all --disable-autodetect --disable-doc --disable-debug \
    --enable-avutil --enable-avcodec --enable-avformat --enable-swscale \
    --enable-swresample --enable-avfilter --enable-ffmpeg \
    --enable-filter="$FILTERS,sine" \
    --enable-protocol=file --enable-muxer=null,rawvideo \
    --enable-encoder=rawvideo,pcm_s16le --enable-decoder=pcm_s16le \
    --prefix="$b/install"
  make -j"$(nproc)"
  make install
  local ff="$b/install/bin/ffmpeg"
  local graph="sine=frequency=440:sample_rate=48000:duration=1,asplit=2[ao][vis];[vis]showfreqs=s=960x540:mode=bar:cmode=combined:colors=0x3DA5F4:fscale=log:ascale=cbrt:win_size=2048:win_func=hann:overlap=0:rate=30:averaging=1[vo]"
  "$ff" -hide_banner -loglevel error -filter_complex "$graph" -map "[ao]" -f null - -map "[vo]" -c:v rawvideo -frames:v 3 -f rawvideo "$b/bars.raw"
  local spec="sine=frequency=440:sample_rate=48000:duration=1,asplit=2[ao][vis];[vis]showspectrum=s=960x540:mode=combined:color=cool:scale=cbrt:fscale=log:slide=replace:win_func=hann:overlap=0:fps=30:legend=0[vo]"
  "$ff" -hide_banner -loglevel error -filter_complex "$spec" -map "[ao]" -f null - -map "[vo]" -c:v rawvideo -frames:v 3 -f rawvideo "$b/spec.raw"
  echo "HOST_GRAPH_OK"
}

ln -sfn armv7a-linux-androideabi26-clang "$NDK_BIN/arm-linux-androideabi26-clang"

build_android() {
  local arch=$1
  local abi triple cpu cpuflags extra_disable_asm=0
  case "$arch" in
    arm64)
      abi=arm64-v8a; triple=aarch64-linux-android; cpu=armv8-a; cpuflags="" ;;
    arm)
      abi=armeabi-v7a; triple=arm-linux-androideabi; cpu=armv7-a; cpuflags="-mfpu=neon -mcpu=cortex-a8" ;;
    x86_64)
      abi=x86_64; triple=x86_64-linux-android; cpu=generic; cpuflags="" ;;
    x86)
      abi=x86; triple=i686-linux-android; cpu="i686"; cpuflags=""; extra_disable_asm=1 ;;
    *) echo "bad arch $arch"; exit 1 ;;
  esac
  local b=/tmp/ffmpeg-android-$arch
  rm -rf "$b"
  mkdir -p "$b"
  cd "$b"
  export PATH="$NDK_BIN:$PATH"
  local cc="$triple"26-clang
  local extra=()
  if [ "${extra_disable_asm:-}" = 1 ]; then extra+=(--disable-asm); fi
  "$SRC/configure" \
    --target-os=android --enable-cross-compile --cross-prefix="$triple-" --cc="$cc" \
    --arch="${triple%%-*}" --cpu="$cpu" --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib \
    --extra-cflags="$cpuflags" \
    --extra-ldflags="-Wl,-O1 -Wl,-z,max-page-size=16384" \
    --disable-all --disable-autodetect --disable-debug --disable-stripping \
    --disable-static --enable-shared --enable-pic \
    --enable-avutil --enable-swresample --enable-avfilter \
    --enable-gpl --enable-version3 \
    --enable-filter="$FILTERS" \
    "${extra[@]}"
  make -j"$(nproc)"
  local so="$b/libavfilter/libavfilter.so"
  llvm-strip --strip-unneeded "$so"
  mkdir -p "$OUT/$abi"
  cp "$so" "$OUT/$abi/libavfilter.so"
  python3 - "$so" "$AAR_JNI/$abi/libavutil.so" "$AAR_JNI/$abi/libswresample.so" <<'PY'
import subprocess, sys
so, avutil, swr = sys.argv[1:]
def undef(path):
    out = subprocess.check_output(["nm", "-D", "-u", path], text=True, errors="replace")
    names = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[-2] == "U":
            names.add(parts[-1].split("@")[0])
    return names
def defined(path):
    out = subprocess.check_output(["nm", "-D", "--defined-only", path], text=True, errors="replace")
    names = set()
    for line in out.splitlines():
        parts = line.split()
        if parts:
            names.add(parts[-1].split("@")[0])
    return names
need = undef(so)
have = defined(avutil) | defined(swr)
# Only FFmpeg symbols have to be in the AAR's libavutil/libswresample. libc and libm come from the device.
missing = sorted(n for n in need if n not in have and (n.startswith("av") or n.startswith("swr") or n.startswith("ff_")))
if missing:
    print("MISSING", len(missing))
    print("\n".join(missing[:40]))
    sys.exit(1)
text = open(so, "rb").read()
for name in (b"showfreqs\0", b"showspectrum\0", b"asplit\0", b"aresample\0"):
    if name not in text:
        print("string missing", name)
        sys.exit(1)
print("SYMBOLS_OK", so, "bytes", len(text))
PY
  echo "BUILT $abi $(stat -c%s "$OUT/$abi/libavfilter.so")"
}

build_host
for arch in arm64 arm x86_64 x86; do
  build_android "$arch"
done
echo ALL_OK
ls -l "$OUT"/*/*.so
