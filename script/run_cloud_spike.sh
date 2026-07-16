#!/bin/sh

set -eu

case "$#" in
  1)
    run_id="$(date -u +%Y%m%dT%H%M%SZ)"
    resume="false"
    ;;
  2)
    run_id="$2"
    resume="true"
    ;;
  *)
    echo "usage: run_cloud_spike.sh IMAGE_DIGEST [RUN_ID]" >&2
    exit 2
    ;;
esac

image="$1"
case "$image" in
  *@sha256:*) ;;
  *)
    echo "cloud spike requires an immutable image digest" >&2
    exit 2
    ;;
esac

project="animated-graph-cloud-jp"
region="europe-central2"
job="agg-renderer-spike"
service_account="agg-renderer@animated-graph-cloud-jp.iam.gserviceaccount.com"
bucket="animated-graph-cloud-jp-temporary"
case "$run_id" in
  [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) ;;
  *)
    echo "run ID must use YYYYMMDDTHHMMSSZ" >&2
    exit 2
    ;;
esac
object_root="renderer-spike/$run_id"
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
reports="$root/.spike/cloud/$run_id"
mkdir -p "$reports"

rate_per_second="0.000208"
budget="3.0"
estimated_cost="${PRIOR_ESTIMATED_COST_USD:-0}"

gcloud run jobs deploy "$job" \
  --project "$project" \
  --region "$region" \
  --image "$image" \
  --service-account "$service_account" \
  --args clojure.main,-m,agg.renderer.main \
  --tasks 1 \
  --max-retries 0 \
  --task-timeout 60m \
  --cpu 8 \
  --memory 32Gi \
  --execution-environment gen2 \
  --quiet

reset_job() {
  gcloud run jobs update "$job" \
    --project "$project" \
    --region "$region" \
    --args clojure.main,-m,agg.renderer.main \
    --quiet >/dev/null 2>&1 || true
}
trap reset_job EXIT INT TERM

validate_stage_report() {
  preset="$1"
  duration="$2"
  report="$3"

  jq --exit-status \
    --arg preset "$preset" \
    --argjson duration "$duration" \
    '
      .preset == $preset and
      .["duration-seconds"] == $duration and
      .["frame-count"] == ($duration * 25) and
      .["ffmpeg-exit-status"] == 0 and
      .["wall-seconds"] > 0 and
      .["task-wall-seconds"] >= .["wall-seconds"] and
      .["effective-fps"] > 0 and
      .["peak-cgroup-memory-bytes"] > 0 and
      .["output-bytes"] > 0 and
      (.sha256 | test("^[0-9a-f]{64}$")) and
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
      .media.container.seekable == true and
      .media.container.fragmented == false and
      .jfr["allocation-sample-count"] > 0 and
      .jfr["heap-summary-count"] > 0
    ' "$report" >/dev/null
}

record_cost() {
  report="$1"

  wall_seconds="$(jq -r '.["task-wall-seconds"]' "$report")"
  stage_cost="$(awk -v wall="$wall_seconds" -v rate="$rate_per_second" \
    'BEGIN { printf "%.6f", (wall + 60) * rate }')"
  estimated_cost="$(awk -v total="$estimated_cost" -v stage="$stage_cost" \
    'BEGIN { printf "%.6f", total + stage }')"
  if ! awk -v total="$estimated_cost" -v ceiling="$budget" \
    'BEGIN { exit !(total < ceiling) }'; then
    echo "cloud compute ceiling reached" >&2
    exit 1
  fi
}

print_stage() {
  report="$1"
  jq '{preset,
       "duration-seconds": .["duration-seconds"],
       "wall-seconds": .["wall-seconds"],
       "task-wall-seconds": .["task-wall-seconds"],
       "effective-fps": .["effective-fps"],
       "peak-cgroup-memory-bytes": .["peak-cgroup-memory-bytes"],
       "output-bytes": .["output-bytes"],
       sha256,
       jfr}' "$report"
}

run_stage() {
  preset="$1"
  duration="$2"
  stage="$3"
  object_prefix="$object_root/$stage"
  report="$reports/$stage.json"

  gcloud run jobs update "$job" \
    --project "$project" \
    --region "$region" \
    --args "clojure.main,-m,agg.renderer.main,--preset,$preset,--duration-seconds,$duration,--bucket,$bucket,--object-prefix,$object_prefix" \
    --quiet

  gcloud run jobs execute "$job" \
    --project "$project" \
    --region "$region" \
    --wait

  execution="$(gcloud run jobs executions list \
    --job "$job" \
    --project "$project" \
    --region "$region" \
    --limit 1 \
    --format 'value(name)')"
  execution_times="$(gcloud run jobs executions describe "$execution" \
    --project "$project" \
    --region "$region" \
    --format 'value(status.startTime,status.completionTime)')"
  task_wall_seconds="$(python3 - $execution_times <<'PY'
from datetime import datetime
import sys

started = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
completed = datetime.fromisoformat(sys.argv[2].replace("Z", "+00:00"))
print((completed - started).total_seconds())
PY
)"

  gcloud storage cat "gs://$bucket/$object_prefix.json" >"$report"
  augmented_report="$report.augmented"
  jq --argjson task_wall_seconds "$task_wall_seconds" \
    '. + {"task-wall-seconds": $task_wall_seconds}' \
    "$report" >"$augmented_report"
  mv "$augmented_report" "$report"

  validate_stage_report "$preset" "$duration" "$report"
  record_cost "$report"
  print_stage "$report"
}

resume_stage() {
  preset="$1"
  duration="$2"
  stage="$3"
  report="$reports/$stage.json"

  if [ -s "$report" ]; then
    validate_stage_report "$preset" "$duration" "$report"
    record_cost "$report"
    print_stage "$report"
  else
    run_stage "$preset" "$duration" "$stage"
  fi
}

projection_gate() {
  report="$1"
  full_duration="$2"
  projected_wall="$(jq -r --argjson full "$full_duration" \
    '.["task-wall-seconds"] * ($full / 60)' "$report")"
  projected_output="$(jq -r --argjson full "$full_duration" \
    '.["output-bytes"] * ($full / 60)' "$report")"
  peak_memory="$(jq -r '.["peak-cgroup-memory-bytes"]' "$report")"

  if ! awk -v wall="$projected_wall" -v memory="$peak_memory" -v bytes="$projected_output" \
    'BEGIN { exit !(wall < 3000 && memory < 30064771072 && bytes < 18253611008) }'; then
    echo "one-minute projection gate failed" >&2
    exit 1
  fi

  projected_cost="$(awk -v wall="$projected_wall" -v rate="$rate_per_second" \
    'BEGIN { printf "%.6f", (wall + 60) * rate }')"
  if ! awk -v spent="$estimated_cost" -v projected="$projected_cost" -v ceiling="$budget" \
    'BEGIN { exit !(spent + projected < ceiling) }'; then
    echo "projected run would exceed cloud compute ceiling" >&2
    exit 1
  fi
}

remaining_budget_gate() {
  first_report="$1"
  first_duration="$2"
  second_report="$3"
  second_duration="$4"
  first_wall="$(jq -r --argjson full "$first_duration" \
    '.["task-wall-seconds"] * ($full / 60)' "$first_report")"
  second_wall="$(jq -r --argjson full "$second_duration" \
    '.["task-wall-seconds"] * ($full / 60)' "$second_report")"
  projected_remaining="$(awk \
    -v first="$first_wall" \
    -v second="$second_wall" \
    -v rate="$rate_per_second" \
    'BEGIN { printf "%.6f", (first + second + 120) * rate }')"

  if ! awk -v spent="$estimated_cost" -v remaining="$projected_remaining" -v ceiling="$budget" \
    'BEGIN { exit !(spent + remaining < ceiling) }'; then
    echo "combined projected runs would exceed cloud compute ceiling" >&2
    exit 1
  fi
}

maximum_gate() {
  report="$1"
  jq --exit-status \
    '.["task-wall-seconds"] < 3600 and
     .["peak-cgroup-memory-bytes"] < 32212254720 and
     .["output-bytes"] < 19327352832' \
    "$report" >/dev/null
}

if [ "$resume" = "true" ]; then
  resume_stage "1080p25" "60" "1080p25-1m"
  resume_stage "2.7k25" "60" "2.7k25-1m"
else
  run_stage "1080p25" "60" "1080p25-1m"
  run_stage "2.7k25" "60" "2.7k25-1m"
fi

projection_gate "$reports/1080p25-1m.json" 480
projection_gate "$reports/2.7k25-1m.json" 240
remaining_budget_gate \
  "$reports/1080p25-1m.json" 480 \
  "$reports/2.7k25-1m.json" 240

resume_stage "1080p25" "480" "1080p25-maximum"
maximum_gate "$reports/1080p25-maximum.json"

resume_stage "2.7k25" "240" "2.7k25-maximum"
maximum_gate "$reports/2.7k25-maximum.json"

echo "$object_root" >"$reports/object-prefix.txt"
jq -n \
  --arg object_prefix "$object_root" \
  --argjson estimated_cost_usd "$estimated_cost" \
  '{object_prefix: $object_prefix,
    estimated_cost_usd: $estimated_cost_usd,
    stages: ["1080p25-1m", "2.7k25-1m", "1080p25-maximum", "2.7k25-maximum"]}'
