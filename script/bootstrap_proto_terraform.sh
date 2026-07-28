#!/bin/sh

set -eu

project_id="animated-graph-cloud-prod-jp"
project_number="488013150738"
pool_id="github"
provider_id="animated-graph-cloud-proto"
deployer_id="agg-proto-github-deployer"
deployer_email="${deployer_id}@${project_id}.iam.gserviceaccount.com"
api_email="agg-api@${project_id}.iam.gserviceaccount.com"
repository="jamiepratt/animated-graph-cloud"
subject="repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/heads/proto"
tag_subject_prefix="repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/tags/proto-terraform-bootstrap-"
confirmation="${project_id}/refs/heads/proto"

if [ "${PROTO_BOOTSTRAP_CONFIRM:-}" != "$confirmation" ]; then
  echo "Set PROTO_BOOTSTRAP_CONFIRM=$confirmation to confirm the target." >&2
  exit 2
fi

active_project="$(gcloud config get-value project 2>/dev/null)"
active_account="$(gcloud config get-value account 2>/dev/null)"
test "$active_project" = "$project_id"
case "$active_account" in
  *@*.gserviceaccount.com)
    echo "Use an authorized human gcloud account, not a service account." >&2
    exit 2
    ;;
  "") exit 2 ;;
esac

if ! gcloud iam service-accounts describe "$deployer_email" \
  --project="$project_id" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$deployer_id" \
    --project="$project_id" \
    --display-name="Alpha Compose proto GitHub deployer" \
    --description="Branch-bound deployer for the separate proto Terraform root"
fi

attribute_mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref"
attribute_condition="assertion.repository == '${repository}' && (assertion.sub == '${subject}' || assertion.sub.startsWith('${tag_subject_prefix}'))"

if gcloud iam workload-identity-pools providers describe "$provider_id" \
  --project="$project_id" \
  --location=global \
  --workload-identity-pool="$pool_id" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools providers update-oidc "$provider_id" \
    --project="$project_id" \
    --location=global \
    --workload-identity-pool="$pool_id" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="$attribute_mapping" \
    --attribute-condition="$attribute_condition"
else
  gcloud iam workload-identity-pools providers create-oidc "$provider_id" \
    --project="$project_id" \
    --location=global \
    --workload-identity-pool="$pool_id" \
    --display-name="Alpha Compose proto GitHub" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="$attribute_mapping" \
    --attribute-condition="$attribute_condition"
fi

gcloud iam service-accounts add-iam-policy-binding "$deployer_email" \
  --project="$project_id" \
  --member="principalSet://iam.googleapis.com/projects/${project_number}/locations/global/workloadIdentityPools/${pool_id}/attribute.repository/${repository}" \
  --role="roles/iam.workloadIdentityUser"

for role in \
  roles/artifactregistry.writer \
  roles/containeranalysis.occurrences.viewer \
  roles/firebasehosting.admin \
  roles/iam.serviceAccountAdmin \
  roles/iam.workloadIdentityPoolAdmin \
  roles/resourcemanager.projectIamAdmin \
  roles/run.admin \
  roles/serviceusage.serviceUsageConsumer \
  "projects/${project_id}/roles/aggTerraformStorageAdmin"
do
  gcloud projects add-iam-policy-binding "$project_id" \
    --member="serviceAccount:${deployer_email}" \
    --role="$role" \
    --condition=None
done

gcloud storage buckets add-iam-policy-binding \
  "gs://${project_id}-tfstate" \
  --member="serviceAccount:${deployer_email}" \
  --role="roles/storage.objectAdmin"

gcloud iam service-accounts add-iam-policy-binding "$api_email" \
  --project="$project_id" \
  --member="serviceAccount:${deployer_email}" \
  --role="roles/iam.serviceAccountUser"

commit="$(git rev-parse HEAD)"
test "$(git branch --show-current)" = "proto"
echo "Bootstrap prerequisites are ready. Review, then run:"
echo "  git tag proto-terraform-bootstrap-${commit}"
echo "  git push origin proto-terraform-bootstrap-${commit}"
echo "Wait for the bootstrap workflow to import state before pushing branch proto."
