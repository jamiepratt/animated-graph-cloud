#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:proto-smoke}"
expected_build="${2:-dev}"
expected_health='{"status":"ok"}'
expected_startup='"message":"Proto API server started"'
health_file="$(mktemp)"

case "$expected_build" in
  dev) short_build=dev ;;
  [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
    short_build="$(printf '%s' "$expected_build" | cut -c1-7)"
    ;;
  *)
    echo "expected build must be dev or an exact lowercase Git commit" >&2
    exit 2
    ;;
esac

ffprobe_protocols="$(docker run --rm --entrypoint ffprobe "$image" \
  -hide_banner -protocols 2>&1)"
for protocol in http tcp; do
  if ! printf '%s\n' "$ffprobe_protocols" | grep -q "^  $protocol\$"; then
    echo "proto image ffprobe lacks the $protocol protocol" >&2
    exit 1
  fi
done
proto_container_id="$(docker run --rm -d -p 127.0.0.1::8080 "$image" clojure.main -m agg.proto.main)"

cleanup() {
  docker rm -f "$proto_container_id" >/dev/null 2>&1 || true
  rm -f "$health_file"
}
trap cleanup EXIT INT TERM

proto_host_port="$(docker port "$proto_container_id" 8080/tcp | sed -n 's/.*://p' | tail -1)"
attempt=0
while [ "$attempt" -lt 50 ]; do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$proto_host_port/health" >"$health_file" 2>/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.2
done

health_body="$(cat "$health_file")"
if [ "$health_body" != "$expected_health" ]; then
  echo "unexpected proto health response: $health_body" >&2
  docker logs "$proto_container_id" >&2
  exit 1
fi

changelog="$(curl --fail --silent --show-error \
  "http://127.0.0.1:$proto_host_port/changelog")"
printf '%s' "$changelog" | grep -Fq "v0.8.0 · build $short_build"
if printf '%s' "$changelog" | grep -Fq 'Unreleased'; then
  echo "proto container changelog exposed Unreleased notes" >&2
  exit 1
fi

proto_logs="$(docker logs "$proto_container_id" 2>&1 || true)"
case "$proto_logs" in
  *"$expected_startup"*) ;;
  *)
    echo "proto entrypoint did not emit the expected startup event" >&2
    printf '%s\n' "$proto_logs" >&2
    exit 1
    ;;
esac
