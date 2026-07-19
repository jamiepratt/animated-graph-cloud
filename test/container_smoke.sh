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
if ! png_encoder_help="$(docker run --rm --entrypoint ffmpeg "$image" \
  -hide_banner -h encoder=png 2>&1)"; then
  echo "could not inspect the container PNG encoder" >&2
  exit 1
fi
case "$png_encoder_help" in
  *'Encoder png [PNG (Portable Network Graphics) image]:'*) ;;
  *)
    echo "container FFmpeg lacks the PNG encoder" >&2
    exit 1
    ;;
esac
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -decoders 2>&1 | grep -q ' h264 '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -demuxers 2>&1 | grep -q ' matroska,webm '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -muxers 2>&1 | grep -q ' mp4 '
if ! image2pipe_muxer_help="$(docker run --rm --entrypoint ffmpeg "$image" \
  -hide_banner -h muxer=image2pipe 2>&1)"; then
  echo "could not inspect the container image2pipe muxer" >&2
  exit 1
fi
case "$image2pipe_muxer_help" in
  *'Muxer image2pipe [piped image2 sequence]:'*) ;;
  *)
    echo "container FFmpeg lacks the image2pipe muxer" >&2
    exit 1
    ;;
esac
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
garmin_preview="$(mktemp)"
api_container_id="$(docker run --rm -d -p 127.0.0.1::8080 "$image")"
overlay_container_id="$(docker run --rm -d -p 127.0.0.1::8080 \
  -e AGG_SERVICE_PROFILE=overlay "$image")"
cleanup() {
  docker rm -f "$api_container_id" "$overlay_container_id" >/dev/null 2>&1 || true
  rm -f "$health_file" "$garmin_preview"
}
trap cleanup EXIT INT TERM

api_host_port="$(docker port "$api_container_id" 8080/tcp | sed -n 's/.*://p' | tail -1)"
attempt=0
while [ "$attempt" -lt 50 ]; do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$api_host_port/health" >"$health_file" 2>/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

health_body="$(cat "$health_file")"
if [ "$health_body" != "$expected_health" ]; then
  echo "unexpected API health response: $health_body" >&2
  docker logs "$api_container_id" >&2
  exit 1
fi

curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary "@$root/test/fixtures/garmin/request.json" \
  "http://127.0.0.1:$api_host_port/v1/preview" \
  --output "$garmin_preview"
garmin_signature="$(od -An -t x1 -N 8 "$garmin_preview" | tr -d ' \n')"
if [ "$garmin_signature" != '89504e470d0a1a0a' ]; then
  echo "Garmin FIT preview is not PNG" >&2
  exit 1
fi

api_overlay_status="$(curl --silent --show-error --output /dev/null \
  --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data '{}' \
  "http://127.0.0.1:$api_host_port/v1/overlay")"
if [ "$api_overlay_status" != '404' ]; then
  echo "API profile overlay status was $api_overlay_status, expected 404" >&2
  exit 1
fi

overlay_host_port="$(docker port "$overlay_container_id" 8080/tcp | sed -n 's/.*://p' | tail -1)"
attempt=0
while [ "$attempt" -lt 50 ]; do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$overlay_host_port/health" >"$health_file" 2>/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

health_body="$(cat "$health_file")"
if [ "$health_body" != "$expected_health" ]; then
  echo "unexpected overlay health response: $health_body" >&2
  docker logs "$overlay_container_id" >&2
  exit 1
fi

overlay_status="$(curl --silent --show-error --output /dev/null \
  --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data '{}' \
  "http://127.0.0.1:$overlay_host_port/v1/overlay")"
if [ "$overlay_status" != '401' ]; then
  echo "overlay profile unauthenticated status was $overlay_status, expected 401" >&2
  exit 1
fi
