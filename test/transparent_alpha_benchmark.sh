#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:transparent-alpha-benchmark}"
duration_seconds="${2:-4}"
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

docker run --rm \
  --entrypoint java \
  --volume "$root/test:/benchmark:ro" \
  "$image" \
  -cp /app/animated-graph-cloud.jar:/benchmark \
  clojure.main \
  -m agg.transparent-alpha-benchmark \
  "$duration_seconds"
