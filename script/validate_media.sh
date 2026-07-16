#!/bin/sh

set -eu

if [ "$#" -ne 5 ]; then
  echo "usage: validate_media.sh PRESET DURATION MOV REPORT JFR" >&2
  exit 2
fi

preset="$1"
duration="$2"
media="$3"
report="$4"
jfr="$5"

case "$preset" in
  1080p25)
    width=1920
    height=1080
    ;;
  2.7k25)
    width=2704
    height=1520
    ;;
  *)
    echo "unsupported preset" >&2
    exit 2
    ;;
esac

test -s "$media"
test -s "$report"
test -s "$jfr"

checksum="$(shasum -a 256 "$media" | awk '{print $1}')"

jq --exit-status \
  --arg preset "$preset" \
  --argjson width "$width" \
  --argjson height "$height" \
  --argjson duration "$duration" \
  --arg checksum "$checksum" \
  '
    .preset == $preset and
    .width == $width and
    .height == $height and
    .fps == 25 and
    .["duration-seconds"] == $duration and
    .["frame-count"] == ($duration * 25) and
    .["ffmpeg-exit-status"] == 0 and
    .["output-bytes"] > 0 and
    .sha256 == $checksum and
    .media.video.codec == "prores" and
    .media.video.profile == "4444" and
    .media.video.alpha == true and
    .media.video["encoder-input-pixel-format"] == "yuva444p10le" and
    .media.video["pixel-format"] == "yuva444p12le" and
    .media.video["alpha-bits"] == 16 and
    .media.audio.codec == "aac" and
    .media.audio.profile == "LC" and
    .media.audio.channels == 2 and
    .media.audio["sample-rate"] == 48000 and
    .media.audio["target-bitrate"] == 192000 and
    .media.audio["observed-bitrate"] > 0 and
    .media.container.format == "mov" and
    .media.container.seekable == true and
    .media.container.fragmented == false and
    ((.media.container["duration-seconds"] - $duration) | fabs) <= 0.04 and
    .jfr["recording-bytes"] > 0 and
    .jfr["allocation-sample-count"] > 0 and
    .jfr["heap-summary-count"] > 0
  ' "$report" >/dev/null

probe="$(mktemp)"
cleanup() {
  rm -f "$probe"
}
trap cleanup EXIT INT TERM

ffprobe -v error \
  -show_entries 'format=format_name,duration,size,probe_score:stream=index,codec_type,codec_name,profile,codec_tag_string,width,height,pix_fmt,r_frame_rate,sample_rate,channels,channel_layout,bit_rate' \
  -of json \
  "$media" >"$probe"

jq --exit-status \
  --argjson width "$width" \
  --argjson height "$height" \
  --argjson duration "$duration" \
  '
    ([.streams[] | select(.codec_type == "video")][0]) as $video |
    ([.streams[] | select(.codec_type == "audio")][0]) as $audio |
    $video.codec_name == "prores" and
    $video.profile == "4444" and
    $video.codec_tag_string == "ap4h" and
    $video.width == $width and
    $video.height == $height and
    $video.pix_fmt == "yuva444p12le" and
    $video.r_frame_rate == "25/1" and
    $audio.codec_name == "aac" and
    $audio.profile == "LC" and
    $audio.sample_rate == "48000" and
    $audio.channels == 2 and
    $audio.channel_layout == "stereo" and
    (.format.format_name | contains("mov")) and
    ((.format.duration | tonumber) - $duration | fabs) <= 0.04
  ' "$probe" >/dev/null

python3 - "$media" <<'PY'
import os
import struct
import sys

path = sys.argv[1]
length = os.path.getsize(path)
atoms = []
offset = 0
with open(path, "rb") as stream:
    while offset + 8 <= length:
        stream.seek(offset)
        size, atom_type = struct.unpack(">I4s", stream.read(8))
        if size == 1:
            size = struct.unpack(">Q", stream.read(8))[0]
        elif size == 0:
            size = length - offset
        if size < 8 or offset + size > length:
            raise SystemExit("invalid MOV atom table")
        atoms.append(atom_type)
        offset += size

if offset != length or b"moov" not in atoms or b"mdat" not in atoms or b"moof" in atoms:
    raise SystemExit("MOV must be seekable and non-fragmented")
PY

echo "media contract passed: $preset ${duration}s"
