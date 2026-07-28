#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:proto-smoke}"
expected_health='{"status":"ok"}'
expected_startup='"message":"Proto API server started"'
health_file="$(mktemp)"
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

proto_logs="$(docker logs "$proto_container_id" 2>&1 || true)"
case "$proto_logs" in
  *"$expected_startup"*) ;;
  *)
    echo "proto entrypoint did not emit the expected startup event" >&2
    printf '%s\n' "$proto_logs" >&2
    exit 1
    ;;
esac
