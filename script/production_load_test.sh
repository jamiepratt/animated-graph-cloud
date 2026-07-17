#!/usr/bin/env bash
set -euo pipefail

MAX_CONCURRENCY=5
submissions="${ALPHA_COMPOSE_LOAD_SUBMISSIONS:-5}"
base_url="${ALPHA_COMPOSE_BASE_URL:-https://alphacompose.com}"
request_file="${ALPHA_COMPOSE_RENDER_REQUEST_FILE:-}"
token="${ALPHA_COMPOSE_TOKEN:-}"
requested_results_dir="${ALPHA_COMPOSE_LOAD_RESULTS_DIR:-}"

if [[ "${ALPHA_COMPOSE_ALLOW_COSTED_LOAD_TEST:-}" != "I ACCEPT PRODUCTION RENDER COSTS" ]]; then
  echo "Refusing a costed production test. Set ALPHA_COMPOSE_ALLOW_COSTED_LOAD_TEST='I ACCEPT PRODUCTION RENDER COSTS'." >&2
  exit 2
fi
if [[ "$base_url" != "https://alphacompose.com" ]]; then
  echo "ALPHA_COMPOSE_BASE_URL must be https://alphacompose.com for production acceptance." >&2
  exit 2
fi
if [[ ! -f "$request_file" ]]; then
  echo "Set ALPHA_COMPOSE_RENDER_REQUEST_FILE to a validated render-request JSON file." >&2
  exit 2
fi
if [[ ! "$token" =~ ^agg_pat_[0-9a-f-]{36}\.[A-Za-z0-9_-]+$ ]]; then
  echo "Set ALPHA_COMPOSE_TOKEN to an owner personal token." >&2
  exit 2
fi
if [[ ! "$submissions" =~ ^[1-5]$ ]]; then
  echo "ALPHA_COMPOSE_LOAD_SUBMISSIONS must be between 1 and 5." >&2
  exit 2
fi

umask 077
if [[ -n "$requested_results_dir" ]]; then
  if [[ -L "$requested_results_dir" ]]; then
    echo "ALPHA_COMPOSE_LOAD_RESULTS_DIR must not be a symbolic link." >&2
    exit 2
  fi
  mkdir -p -- "$requested_results_dir"
  results_dir="$(cd -- "$requested_results_dir" && pwd -P)"
else
  results_dir="$(mktemp -d "${TMPDIR:-/tmp}/alpha-compose-load-results.XXXXXX")"
fi
chmod 700 "$results_dir"
echo "Response evidence: $results_dir"

auth_config="$(mktemp "${TMPDIR:-/tmp}/alpha-compose-curl-auth.XXXXXX")"
trap 'rm -f -- "$auth_config"' EXIT
printf 'header = "Authorization: Bearer %s"\n' "$token" >"$auth_config"
chmod 600 "$auth_config"
unset token ALPHA_COMPOSE_TOKEN

submit_render() {
  local index="$1"
  local status
  status="$(curl --silent --show-error \
    --config "$auth_config" \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: production-load-$(date -u +%Y%m%dT%H%M%S)-$index-$RANDOM" \
    --data-binary "@$request_file" \
    --output "$results_dir/response-$index.json" \
    --write-out '%{http_code}' \
    "$base_url/v1/jobs")"
  case "$status" in
    200|202) printf 'submission %s accepted (%s)\n' "$index" "$status" ;;
    *)
      printf 'submission %s failed (%s)\n' "$index" "$status" >&2
      return 1
      ;;
  esac
}
export -f submit_render
export auth_config request_file results_dir base_url

seq 1 "$submissions" | xargs -n 1 -P "$MAX_CONCURRENCY" bash -c 'submit_render "$1"' _
echo "Accepted $submissions owner submissions at concurrency $MAX_CONCURRENCY."
echo "Poll the returned jobs and record cost, completion, and output evidence before deleting the personal token."
