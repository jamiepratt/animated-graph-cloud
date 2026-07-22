#!/usr/bin/env bash
set -euo pipefail

expected_sha="${1:-}"
expected_repository="${2:-}"

if [[ ! "$expected_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "development gate requires an exact lowercase commit SHA" >&2
  exit 1
fi
if [[ ! "$expected_repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "development gate requires an exact repository identity" >&2
  exit 1
fi

decision="$(jq -c \
  --arg expected_sha "$expected_sha" \
  --arg expected_repository "$expected_repository" '
  if (.workflow_runs | type) != "array" then
    {state: "invalid"}
  else
    [.workflow_runs[]
     | select(
         (.id | type) == "number" and
         (.run_number | type) == "number" and
         .head_sha == $expected_sha and
         .head_branch == "main" and
         (.event == "push" or .event == "workflow_dispatch") and
         .path == ".github/workflows/deploy.yml" and
         .repository.full_name == $expected_repository and
         .head_repository.full_name == $expected_repository)] as $trusted
    | if ($trusted | length) == 0 then
        {state: "missing"}
      else
        ($trusted | map(.run_number) | max) as $latest_number
        | [$trusted[] | select(.run_number == $latest_number)] as $latest
        | if ($latest | length) != 1 then
            {state: "ambiguous"}
          else
            $latest[0] as $run
            | {state: "candidate",
               id: $run.id,
               status: $run.status,
               conclusion: $run.conclusion}
          end
      end
  end
')"

state="$(jq -r '.state' <<<"$decision")"
case "$state" in
  missing)
    echo "same-commit development result is not available yet" >&2
    exit 75
    ;;
  invalid|ambiguous)
    echo "same-commit development result is invalid or ambiguous" >&2
    exit 1
    ;;
  candidate) ;;
  *)
    echo "same-commit development result has an unknown decision" >&2
    exit 1
    ;;
esac

status="$(jq -r '.status // ""' <<<"$decision")"
conclusion="$(jq -r '.conclusion // ""' <<<"$decision")"
case "$status" in
  queued|in_progress|pending|requested|waiting)
    echo "same-commit development is still running" >&2
    exit 75
    ;;
  completed)
    case "$conclusion" in
      success)
        echo "same-commit development succeeded"
        exit 0
        ;;
      failure|cancelled|timed_out|action_required|neutral|skipped|stale|startup_failure)
        echo "same-commit development did not succeed" >&2
        exit 1
        ;;
      *)
        echo "same-commit development conclusion is invalid or ambiguous" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "same-commit development status is invalid or ambiguous" >&2
    exit 1
    ;;
esac
