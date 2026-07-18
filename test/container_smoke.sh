#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:smoke-test}"
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
expected_health='{"status":"ok"}'
expected_renderer='{"severity":"INFO","component":"renderer","event":"smoke_complete","message":"Renderer smoke job completed"}'

if [ "$(docker run --rm --entrypoint id "$image" -u)" = "0" ]; then
  echo "container must not run as root" >&2
  exit 1
fi

docker run --rm --entrypoint java "$image" -version 2>&1 | grep -q 'version "21\.'

docker run --rm --entrypoint ffmpeg "$image" -version 2>&1 | grep -q 'ffmpeg version 8\.1\.2'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -encoders 2>&1 | grep -q 'prores_ks'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -encoders 2>&1 | grep -q 'libx264'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -encoders 2>&1 | grep -q 'aac'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -decoders 2>&1 | grep -q ' h264 '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -demuxers 2>&1 | grep -q ' matroska,webm '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -muxers 2>&1 | grep -q ' mp4 '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -h encoder=prores_ks 2>&1 | \
  grep -q 'Supported pixel formats:.*yuva444p10le'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -h encoder=libx264 2>&1 | \
  grep -q 'libx264'
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' overlay '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' amix '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' alimiter '

renderer_output="$(docker run --rm "$image" clojure.main -m agg.renderer.main)"
if [ "$renderer_output" != "$expected_renderer" ]; then
  echo "unexpected renderer output: $renderer_output" >&2
  exit 1
fi

render_output="$(docker run --rm "$image" \
  clojure.main -m agg.renderer.main \
  --preset 1080p25 \
  --duration-seconds 1 \
  --profile false)"
if [ "$render_output" != '{"severity":"INFO","component":"renderer","event":"render_complete","message":"Renderer job completed"}' ]; then
  echo "renderer media smoke failed" >&2
  exit 1
fi

health_file="$(mktemp)"
first_polar_media="$(mktemp)"
second_polar_media="$(mktemp)"
complete_1080_media="$(mktemp)"
complete_27_media="$(mktemp)"
garmin_preview="$(mktemp)"
container_id="$(docker run --rm -d -p 127.0.0.1::8080 "$image")"
cleanup() {
  docker rm -f "$container_id" >/dev/null 2>&1 || true
  rm -f "$health_file" "$first_polar_media" "$second_polar_media" \
    "$complete_1080_media" "$complete_27_media" "$garmin_preview"
}
trap cleanup EXIT INT TERM

host_port="$(docker port "$container_id" 8080/tcp | sed -n 's/.*://p' | tail -1)"
attempt=0
while [ "$attempt" -lt 50 ]; do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$host_port/health" >"$health_file" 2>/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

health_body="$(cat "$health_file")"
if [ "$health_body" != "$expected_health" ]; then
  echo "unexpected health response: $health_body" >&2
  docker logs "$container_id" >&2
  exit 1
fi

curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary "@$root/test/fixtures/garmin/request.json" \
  "http://127.0.0.1:$host_port/v1/preview" \
  --output "$garmin_preview"
garmin_signature="$(od -An -t x1 -N 8 "$garmin_preview" | tr -d ' \n')"
if [ "$garmin_signature" != '89504e470d0a1a0a' ]; then
  echo "Garmin FIT preview is not PNG" >&2
  exit 1
fi

for output in "$first_polar_media" "$second_polar_media"; do
  curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$root/test/fixtures/polar/request.json" \
    "http://127.0.0.1:$host_port/v1/overlay" \
    --output "$output"
  test -s "$output"
done

first_sha="$(shasum -a 256 "$first_polar_media" | awk '{print $1}')"
second_sha="$(shasum -a 256 "$second_polar_media" | awk '{print $1}')"
if [ "$first_sha" != "$second_sha" ]; then
  echo "fixed Polar input produced different MOV bytes" >&2
  exit 1
fi

for preset in 1080p25 2.7k25; do
  case "$preset" in
    1080p25)
      width=1920
      height=1080
      complete_media="$complete_1080_media"
      ;;
    2.7k25)
      width=2704
      height=1520
      complete_media="$complete_27_media"
      ;;
  esac

  curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data-binary "@$root/test/fixtures/complete/$preset.json" \
    "http://127.0.0.1:$host_port/v1/overlay" \
    --output "$complete_media"
  test -s "$complete_media"

  chmod 0644 "$complete_media"
  container_media="/tmp/complete-$preset.mov"
  docker cp "$complete_media" "$container_id:$container_media"
  probe="$(docker exec "$container_id" ffprobe \
    -v error \
    -show_entries \
    'format=format_name,duration:stream=codec_type,codec_name,profile,codec_tag_string,width,height,pix_fmt,r_frame_rate,sample_rate,channels' \
    -of json \
    "$container_media")"
  printf '%s' "$probe" | jq --exit-status \
    --argjson width "$width" \
    --argjson height "$height" \
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
      (.format.format_name | contains("mov")) and
      ((.format.duration | tonumber) - 1 | fabs) <= 0.04
    ' >/dev/null
done
