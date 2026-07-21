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
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' volume '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' hstack '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' select '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' setpts '
docker run --rm --entrypoint ffmpeg "$image" -hide_banner -filters 2>&1 | grep -q ' split '

docker run --rm "$image" clojure.main -e '
(require (quote [agg.render.audio :as audio])
         (quote [agg.render.media :as media])
         (quote [agg.renderer.main :as renderer]))
(import (quote java.awt.image.BufferedImage)
        (quote java.io.ByteArrayOutputStream)
        (quote java.nio.file.Files)
        (quote java.nio.file.OpenOption)
        (quote javax.imageio.ImageIO))
(let [directory (Files/createTempDirectory
                 "agg-selected-source-smoke-"
                 (make-array java.nio.file.attribute.FileAttribute 0))
      image-path (.resolve directory "source.png")
      audio-path (.resolve directory "source.wav")
      source-path (.resolve directory "source.mp4")
      short-source-path (.resolve directory "short-source.mp4")
      durable-path (.resolve directory "durable.mp4")
      image (BufferedImage. 64 36 BufferedImage/TYPE_INT_ARGB)
      overlay-output (ByteArrayOutputStream.)
      source-stream-count (atom 0)]
  (try
    (ImageIO/write image "png" (.toFile image-path))
    (with-open [output (Files/newOutputStream audio-path (make-array OpenOption 0))]
      (audio/write-wav! {:duration-seconds 20
                         :telemetry [{:seconds 0.0 :heart-rate 120.0}
                                     {:seconds 20.0 :heart-rate 120.0}]}
                        output))
    (let [process (.start
                   (doto (ProcessBuilder.
                          ^java.util.List
                          ["ffmpeg" "-hide_banner" "-nostdin" "-loglevel" "error"
                           "-loop" "1" "-framerate" "25" "-i" (str image-path)
                           "-i" (str audio-path) "-t" "20" "-c:v" "libx264"
                           "-pix_fmt" "yuv420p" "-c:a" "aac" "-movflags"
                           "+faststart" "-y" (str source-path)])
                     (.inheritIO)))]
      (when-not (zero? (.waitFor process))
        (throw (ex-info "source fixture generation failed" {}))))
    (let [process (.start
                   (doto (ProcessBuilder.
                          ^java.util.List
                          ["ffmpeg" "-hide_banner" "-nostdin" "-loglevel" "error"
                           "-loop" "1" "-framerate" "25" "-i" (str image-path)
                           "-t" "1" "-c:v" "libx264" "-pix_fmt" "yuv420p"
                           "-y" (str short-source-path)])
                     (.inheritIO)))]
      (when-not (zero? (.waitFor process))
        (throw (ex-info "short source fixture generation failed" {}))))
    (ImageIO/write image "png" overlay-output)
    (let [frames (atom [])
          result
          (media/render-composite-gallery!
           (media/ffmpeg-video-encoder)
           {:width 64 :height 36 :fps 25 :duration-seconds 1
            :fit-mode "letterbox"}
           (fn [output]
             (swap! source-stream-count inc)
             (with-open [input (Files/newInputStream
                                short-source-path (make-array OpenOption 0))]
               (.transferTo input output)))
           [{:frameIndex 0 :overlay (.toByteArray overlay-output)}
            {:frameIndex 24 :overlay (.toByteArray overlay-output)}
            {:frameIndex 49 :overlay (.toByteArray overlay-output)}]
           (fn [frame-index source-png final-png]
             (swap! frames conj [frame-index (alength source-png)
                                 (alength final-png)])))]
      (when-not (and (= {:requested-frame-count 3
                         :generated-frame-count 2
                         :omitted-frame-count 1
                         :reason "source_duration_too_short"
                         :source-decodes 1}
                        result)
                     (= 1 @source-stream-count)
                     (= [0 24] (mapv first @frames)))
        (throw (ex-info "short selected-source gallery smoke failed"
                        {:result result
                         :source-stream-count @source-stream-count
                         :frame-indexes (mapv first @frames)}))))
    (let [started (System/nanoTime)
          result
          (renderer/render!
           {:id "1080p25"
            :width 64 :height 36 :fps 25 :duration-seconds 9
            :output-format "h264-mp4"
            :fit-mode "letterbox"
            :audio-mode "source+heartbeat"
            :source-video {:file-id "smoke-fixture"}
            :telemetry [{:seconds 0.0 :heart-rate 120.0}
                        {:seconds 9.0 :heart-rate 140.0}]
            :timeout-ms media/durable-composite-smoke-bound-ms
            :output-path durable-path
            :profile? false}
           {:video-encoder (media/ffmpeg-video-encoder)
            :source-stream!
            (fn [output]
              (with-open [input (Files/newInputStream
                                 source-path (make-array OpenOption 0))]
                (.transferTo input output)))})
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (when-not (and (< elapsed-ms media/durable-composite-smoke-bound-ms)
                     (= 225 (:frame-count result))
                     (= "h264" (get-in result [:media :video :codec]))
                     (= "aac" (get-in result [:media :audio :codec]))
                     (= 9.0 (get-in result [:media :container
                                            :duration-seconds]))
                     (pos? (:output-bytes result)))
        (throw (ex-info "durable selected-source smoke failed"
                        {:elapsed-ms elapsed-ms}))))
    (finally
      (Files/deleteIfExists durable-path)
      (Files/deleteIfExists short-source-path)
      (Files/deleteIfExists source-path)
      (Files/deleteIfExists audio-path)
      (Files/deleteIfExists image-path)
      (Files/deleteIfExists directory)
      (shutdown-agents))))
'

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
garmin_preview_operation="$(mktemp)"
api_container_id="$(docker run --rm -d -p 127.0.0.1::8080 "$image")"
overlay_container_id="$(docker run --rm -d -p 127.0.0.1::8080 \
  -e AGG_SERVICE_PROFILE=overlay "$image")"
cleanup() {
  docker rm -f "$api_container_id" "$overlay_container_id" >/dev/null 2>&1 || true
  rm -f "$health_file" "$garmin_preview_operation"
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

garmin_preview_status="$(curl --silent --show-error \
  --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data-binary "@$root/test/fixtures/garmin/request.json" \
  "http://127.0.0.1:$api_host_port/v1/preview" \
  --output "$garmin_preview_operation")"
if [ "$garmin_preview_status" != '202' ]; then
  echo "Garmin FIT preview status was $garmin_preview_status, expected 202" >&2
  exit 1
fi
garmin_preview_operation_normalized="$(tr -d '\\' <"$garmin_preview_operation")"
if ! printf '%s' "$garmin_preview_operation_normalized" | \
     grep -q '"operationKind":"key-moment-gallery"' || \
   ! printf '%s' "$garmin_preview_operation_normalized" | \
     grep -q '"statusUrl":"/v1/previews/'; then
  echo "Garmin FIT preview operation response is invalid" >&2
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
