#!/usr/bin/env bash
set -euo pipefail

terraform_directory='infra/dev'
target_account='agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com'
required_confirmation='apply exact development youtube refresh reader'

if (( $# != 1 )); then
  echo "Usage: $0 saved-plan" >&2
  exit 2
fi

plan="$1"
if [[ "${CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR:-}" != "$required_confirmation" ]]; then
  echo 'Blocked before cloud access: obtain fresh human authority for the exact development refresh-reader role and binding.' >&2
  echo "Then set CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR='$required_confirmation'." >&2
  exit 1
fi

command -v gcloud >/dev/null
command -v terraform >/dev/null
active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
if [[ -z "$active_account" ]]; then
  echo 'Blocked: no active gcloud operator identity.' >&2
  exit 1
fi
if [[ "$active_account" == "$target_account" ]]; then
  echo 'Blocked: the deployer cannot grant refresh access to itself.' >&2
  exit 1
fi
operator_access_token="$(gcloud auth print-access-token --account="$active_account")"
if [[ -z "$operator_access_token" ]]; then
  echo 'Blocked: the checked operator account did not provide an access token.' >&2
  exit 1
fi

plan_json="$(mktemp)"
trap 'rm -f "$plan_json"' EXIT
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" show -json "$plan" >"$plan_json"
script/guard_youtube_metadata_refresh_plan.sh "$plan_json"
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" apply -input=false "$plan"
unset operator_access_token
