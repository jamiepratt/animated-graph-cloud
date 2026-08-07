#!/usr/bin/env bash
set -euo pipefail

project_id='animated-graph-cloud-jp'
bucket='gs://animated-graph-cloud-jp-tfstate'
member='serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com'
role='roles/storage.objectAdmin'
target_account='agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com'
required_confirmation='grant development state bucket object admin'

print_plan() {
  printf '%s\n' \
    'Development Terraform backend IAM recovery plan' \
    '' \
    "Project: $project_id" \
    "Bucket: $bucket" \
    "Member: $member" \
    "Role: $role"
}

if (( $# != 1 )); then
  echo "Usage: $0 plan|apply" >&2
  exit 2
fi

case "${1:-}" in
  plan)
    print_plan
    printf '%s\n' \
      '' \
      'No cloud request or mutation was made.'
    ;;
  apply)
    print_plan
    if [[ "${CONFIRM_DEVELOPMENT_TERRAFORM_BACKEND_IAM_REPAIR:-}" != "$required_confirmation" ]]; then
      printf '%s\n' \
        '' \
        'Blocked before cloud access: obtain fresh human authority for exactly this binding.' \
        "Then set CONFIRM_DEVELOPMENT_TERRAFORM_BACKEND_IAM_REPAIR='$required_confirmation'." >&2
      exit 1
    fi

    command -v gcloud >/dev/null
    command -v jq >/dev/null
    active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
    if [[ -z "$active_account" ]]; then
      echo 'Blocked: no active gcloud operator identity.' >&2
      exit 1
    fi
    if [[ "$active_account" == "$target_account" ]]; then
      echo 'Blocked: the deployer cannot grant backend access to itself.' >&2
      exit 1
    fi

    gcloud storage buckets add-iam-policy-binding "$bucket" \
      --project="$project_id" \
      --member="$member" \
      --role="$role" \
      --condition=None \
      --quiet \
      --format=none
    policy="$(gcloud storage buckets get-iam-policy "$bucket" \
      --project="$project_id" \
      --format=json)"
    if ! jq -e --arg role "$role" --arg member "$member" '
      any(.bindings[]?;
        .role == $role and any(.members[]?; . == $member))
    ' <<<"$policy" >/dev/null; then
      echo 'Blocked: the exact development state-bucket binding could not be verified.' >&2
      exit 1
    fi
    echo 'Exact development state-bucket binding verified.'
    ;;
  *)
    echo "Usage: $0 plan|apply" >&2
    exit 2
    ;;
esac
