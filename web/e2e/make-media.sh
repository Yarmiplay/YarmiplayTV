#!/usr/bin/env bash
# Short clips in the containers and codecs the browser player has to handle, for its tests: made with ffmpeg from
# test patterns, so nothing has to be downloaded. Writes to e2e/media/ (not committed).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p media
cd media

SECONDS_LONG="${CLIP_SECONDS:-20}"
# A moving test card with a burnt-in clock, so frames are easy to tell apart, and a tone that changes every second.
VIDEO="testsrc2=size=1920x1080:rate=24:duration=$SECONDS_LONG"
SMALL="testsrc2=size=640x360:rate=25:duration=$SECONDS_LONG"
AUDIO="sine=frequency=440:beep_factor=4:sample_rate=48000:duration=$SECONDS_LONG"
SURROUND="aevalsrc=sin(440*2*PI*t)|sin(550*2*PI*t)|sin(660*2*PI*t)|0.2*sin(110*2*PI*t)|sin(330*2*PI*t)|sin(770*2*PI*t):s=48000:d=$SECONDS_LONG:c=5.1"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

cat > subs.ass <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,60,1
Style: Sign,Arial,72,&H0000FFFF,&H000000FF,&H00400000,&H64000000,1,0,0,0,100,100,0,0,1,4,0,8,40,40,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,The first line, at one second
Dialogue: 0,0:00:05.00,0:00:09.00,Sign,,0,0,0,,{\fad(300,300)}A yellow sign at the top
Dialogue: 0,0:00:10.00,0:00:15.00,Default,,0,0,0,,{\i1}Italic{\i0} and {\c&H00A5FF&}orange{\c} at ten seconds
ASS

# The spike's target: 1080p HEVC with 5.1 DTS and styled ASS subtitles, in MKV.
ff -f lavfi -i "$VIDEO" -f lavfi -i "$SURROUND" -i subs.ass \
   -map 0:v -map 1:a -map 2:s -c:v libx265 -preset ultrafast -x265-params log-level=error -pix_fmt yuv420p \
   -c:a dca -strict -2 -b:a 768k -c:s ass -metadata:s:a:0 language=jpn -metadata:s:s:0 language=eng \
   -metadata:s:s:0 title="Full subtitles" hevc-dts-ass.mkv

# H.264 with AC-3 and a second (AAC) audio track, chapters named like an anime's, in MKV.
cat > chapters.txt <<'CH'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=4000
title=Prologue
[CHAPTER]
TIMEBASE=1/1000
START=4000
END=16000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=16000
END=20000
title=Part A
CH
ff -f lavfi -i "$VIDEO" -f lavfi -i "$SURROUND" -f lavfi -i "$AUDIO" -i chapters.txt \
   -map 0:v -map 1:a -map 2:a -map_chapters 3 -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
   -c:a:0 ac3 -b:a:0 384k -c:a:1 aac -b:a:1 128k \
   -metadata:s:a:0 language=jpn -metadata:s:a:1 language=eng h264-ac3-aac-chapters.mkv

# What browsers play anyway: H.264 and AAC in MP4.
ff -f lavfi -i "$SMALL" -f lavfi -i "$AUDIO" -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac \
   -movflags +faststart h264-aac.mp4

# Old formats: MPEG-4 Part 2 with MP3 in AVI, and MPEG-2 with AC-3 in a transport stream.
ff -f lavfi -i "$SMALL" -f lavfi -i "$AUDIO" -c:v mpeg4 -q:v 4 -c:a libmp3lame -q:a 4 mpeg4-mp3.avi
ff -f lavfi -i "$SMALL" -f lavfi -i "$AUDIO" -c:v mpeg2video -q:v 4 -c:a ac3 mpeg2-ac3.m2ts

# VP9 with Opus in WebM.
ff -f lavfi -i "$SMALL" -f lavfi -i "$AUDIO" -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 1M -c:a libopus webm-vp9-opus.webm

rm -f subs.ass chapters.txt
ls -la
