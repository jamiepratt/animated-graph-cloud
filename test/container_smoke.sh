#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:smoke-test}"
expected_health='{"status":"ok"}'
expected_renderer='{"severity":"INFO","component":"renderer","event":"smoke_complete","message":"Renderer smoke job completed"}'

if [ "$(docker run --rm --entrypoint id "$image" -u)" = "0" ]; then
  echo "container must not run as root" >&2
  exit 1
fi

docker run --rm --entrypoint java "$image" -version 2>&1 | grep -q 'version "21\.'

renderer_output="$(docker run --rm "$image" clojure.main -m agg.renderer.main)"
if [ "$renderer_output" != "$expected_renderer" ]; then
  echo "unexpected renderer output: $renderer_output" >&2
  exit 1
fi

health_file="$(mktemp)"
container_id="$(docker run --rm -d -p 127.0.0.1::8080 "$image")"
cleanup() {
  docker rm -f "$container_id" >/dev/null 2>&1 || true
  rm -f "$health_file"
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
