#!/bin/sh

set -eu

image="${1:-animated-graph-cloud:issue-12}"
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
artifacts="$root/.spike/local"
mkdir -p "$artifacts"

for preset in 1080p25 2.7k25; do
  media="$artifacts/$preset.mov"
  report="$artifacts/$preset.json"
  jfr="$artifacts/$preset.jfr"

  output="$(docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$artifacts:/artifacts" \
    "$image" \
    clojure.main -m agg.renderer.main \
    --preset "$preset" \
    --duration-seconds 10 \
    --output "/artifacts/$preset.mov" \
    --report "/artifacts/$preset.json" \
    --jfr "/artifacts/$preset.jfr")"

  test "$output" = '{"severity":"INFO","component":"renderer","event":"render_complete","message":"Renderer job completed"}'
  "$root/script/validate_media.sh" "$preset" 10 "$media" "$report" "$jfr"
done

jq -s \
  'map({preset,
        "wall-seconds": .["wall-seconds"],
        "effective-fps": .["effective-fps"],
        "peak-cgroup-memory-bytes": .["peak-cgroup-memory-bytes"],
        "output-bytes": .["output-bytes"],
        sha256,
        jfr})' \
  "$artifacts/1080p25.json" \
  "$artifacts/2.7k25.json"
