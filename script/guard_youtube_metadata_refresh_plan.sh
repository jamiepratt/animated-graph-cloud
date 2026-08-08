#!/usr/bin/env bash
set -euo pipefail

plan_json="${1:?plan JSON path is required}"
required_service_account='google_service_account.deployer'
refresh_role_address='google_project_iam_custom_role.youtube_repair_refresh_reader[0]'
refresh_binding_address='google_project_iam_member.deployer_youtube_repair_refresh_reader[0]'
authorized_changes='[
  {
    "address": "google_project_iam_custom_role.youtube_repair_refresh_reader[0]",
    "actions": ["create"]
  },
  {
    "address": "google_project_iam_member.deployer_youtube_repair_refresh_reader[0]",
    "actions": ["create"]
  }
]'

jq -e '.resource_changes | type == "array"' "$plan_json" >/dev/null

if ! jq -e \
  --arg required_service_account "$required_service_account" \
  --arg refresh_role_address "$refresh_role_address" \
  --arg refresh_binding_address "$refresh_binding_address" \
  --argjson authorized_changes "$authorized_changes" '
    . as $plan
    | ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and .change.actions != ["no-op"])
        | {address: .address, actions: .change.actions}
      ] | sort_by(.address)) == $authorized_changes
      and
      ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and .address == $refresh_role_address)
        | .change.after
        | {
            project,
            role_id,
            permissions: (.permissions | sort)
          }
      ] == [{
        project: "animated-graph-cloud-jp",
        role_id: "aggYoutubeRepairRefreshReader",
        permissions: [
          "iam.serviceAccounts.get",
          "serviceusage.services.list"
        ]
      }])
      and
      ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and .address == $refresh_binding_address)
        | .change.after
        | {project, role, member}
      ] == [{
        project: "animated-graph-cloud-jp",
        role: "projects/animated-graph-cloud-jp/roles/aggYoutubeRepairRefreshReader",
        member: "serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"
      }])
      and
      ([
        $plan.resource_changes[]?
        | select(.mode == "managed" and
                 .address == $required_service_account)
        | .change.actions
      ] == [["no-op"]])
  ' "$plan_json" >/dev/null; then
  echo "Plan blocked: expected only creation of the development YouTube repair refresh-reader role and binding, with the deployer service account unchanged." >&2
  exit 1
fi
