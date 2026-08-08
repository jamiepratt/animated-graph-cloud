#!/usr/bin/env bash
set -euo pipefail

terraform_directory='infra/dev'

if (( $# != 1 )); then
  echo "Usage: $0 saved-plan" >&2
  exit 2
fi

plan="$1"
command -v gcloud >/dev/null
command -v terraform >/dev/null
active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
if [[ -z "$active_account" ]]; then
  echo 'Blocked: no active gcloud operator identity.' >&2
  exit 1
fi
if [[ "$active_account" == *.gserviceaccount.com ]]; then
  echo 'Blocked: an authorized human IAM operator is required.' >&2
  exit 1
fi
operator_access_token="$(gcloud auth print-access-token --account="$active_account")"
if [[ -z "$operator_access_token" ]]; then
  echo 'Blocked: the checked operator account did not provide an access token.' >&2
  exit 1
fi

renderer_image="$(gcloud run jobs describe agg-renderer \
  --project=animated-graph-cloud-jp \
  --region=europe-central2 \
  --account="$active_account" \
  --format='value(spec.template.spec.template.spec.containers[0].image)')"
api_service_url="$(gcloud run services describe agg-api \
  --project=animated-graph-cloud-jp \
  --region=europe-central2 \
  --account="$active_account" \
  --format='value(status.url)')"
test -n "$renderer_image"
test -n "$api_service_url"

plan_json="$(mktemp)"
trap 'rm -f "$plan_json"' EXIT
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" init -input=false
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" plan \
    -input=false \
    -lock-timeout=5m \
    -var="renderer_image=$renderer_image" \
    -var="api_service_url=$api_service_url" \
    -target='google_project_iam_custom_role.youtube_repair_refresh_reader[0]' \
    -target='google_project_iam_member.deployer_youtube_repair_refresh_reader[0]' \
    -out="$plan"
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" show "$plan"
GOOGLE_OAUTH_ACCESS_TOKEN="$operator_access_token" \
  terraform -chdir="$terraform_directory" show -json "$plan" >"$plan_json"
script/guard_youtube_metadata_refresh_plan.sh "$plan_json"
unset operator_access_token
