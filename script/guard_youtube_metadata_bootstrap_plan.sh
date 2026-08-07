#!/usr/bin/env bash
set -euo pipefail

plan_json="${1:?plan JSON path is required}"
authorized_address='google_project_iam_member.deployer_picker_api_keys_viewer'
required_noop_addresses='[
  "google_project_service.required[\"apikeys.googleapis.com\"]",
  "google_project_service.required[\"youtube.googleapis.com\"]",
  "google_secret_manager_secret.application[\"youtube-api-key\"]",
  "google_secret_manager_secret_iam_member.api_youtube_access",
  "google_secret_manager_secret_iam_member.deployer_youtube_access"
]'

jq -e '.resource_changes | type == "array"' "$plan_json" >/dev/null

if ! jq -e \
  --arg authorized_address "$authorized_address" \
  --argjson required_noop_addresses "$required_noop_addresses" '
    . as $plan
    | ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and
                 .change.actions != ["no-op"])
        | {address: .address, actions: .change.actions}
      ] == [{address: $authorized_address, actions: ["create"]}])
      and
      ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and
                 .address == $authorized_address)
        | .change.actions
      ] == [["create"]])
      and
      all($required_noop_addresses[]; . as $expected_address |
        ([
          $plan.resource_changes[]?
          | select(.mode == "managed" and
                   .address == $expected_address)
          | .change.actions
        ] == [["no-op"]]))
  ' "$plan_json" >/dev/null; then
  echo "Plan blocked: expected only creation of the development API Keys viewer IAM binding, with all other YouTube bootstrap targets unchanged." >&2
  exit 1
fi
