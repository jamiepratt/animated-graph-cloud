# Alpha Compose production runbook

Production is `animated-graph-cloud-prod-jp` (`488013150738`) in
`europe-central2`; development is the separate `animated-graph-cloud-jp`
project. Never reuse development OAuth credentials, secret versions, state, or
workload identity in production. Commands below are operator procedures, not
evidence that they have run.

## Owner checkpoints

Stop for explicit owner approval before each item:

1. Approve the homepage, privacy policy, and terms with appropriate legal
   advice. The repository copy is operationally accurate but is not legal
   advice.
2. In Firebase Console, add Firebase to `animated-graph-cloud-prod-jp` and
   accept the Firebase Terms. This association cannot be undone. Do not
   automate or imply this checkpoint has happened.
3. Approve Cloudflare DNS records, Firebase custom-domain verification, and
   Google Search Console/authorized-domain verification.
4. Publish the external Google OAuth app and approve the public legal, domain,
   and OAuth configuration.
5. Approve every costed production acceptance render and load test.
6. Complete the Resend bootstrap sequence below in both projects. Resend account
   creation and `alphacompose.com` sender-domain verification remain manual.
   No application image may be pushed or promoted until Terraform owns both
   secret containers and IAM bindings and both projects have an enabled version.

### Preview plus Submit approval template

Use this exact accounting boundary before a production exercise that requires a
Preview followed by durable submission:

> Approve exactly one Preview and one Submit. Preview reservation: PLN 1.25;
> durable Submit reservation: PLN 1.25; total maximum admission exposure: PLN 2.50.
> No Preview retry or second Preview, durable retry, or second Submit is
> approved.

Preview and durable reservations are independently configured and atomically
recorded. Repeating the same idempotency key and body does not reserve again.
A new Preview generation or explicit retry is a new attempt and reserves again.
Success, failure, cancellation, and 24-hour expiry do not release the Preview
reservation from the monthly admission counter. Record the current reserved and
remaining minor units before approval and after the exercise.

## Repository and GitHub preparation

Run repository-only acceptance first:

```sh
script/release_acceptance.sh
```

Protect the `main` branch with the intended required checks and restrict direct
pushes. The Terraform WIF condition requires the repository's immutable GitHub
OIDC subject
`repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/heads/main`;
a workflow from another ref or repository identity cannot impersonate the
production deployer.

The repository contains no secret values and no long-lived JSON credential file. Use
ambient operator credentials locally and GitHub Workload Identity Federation in
CI.

## Local operator quota project

In every new operator shell, set the production quota project before any local
Terraform or Firebase CLI command in this runbook:

```sh
export GOOGLE_CLOUD_QUOTA_PROJECT=animated-graph-cloud-prod-jp
```

Application Default Credentials can retain the Google Cloud CLI OAuth client's
project for client-based API quota and billing attribution, even after
`gcloud auth application-default set-quota-project`. The explicit environment
variable keeps Terraform, Firebase CLI, and related Google tooling attributed
to the production project. Keep it exported for the whole operator session;
re-export it in each new shell. This is not needed by the GitHub production
workflow, which uses Workload Identity Federation and passes project IDs
explicitly.

## Firebase and domain

After accepting the irreversible checkpoint, add the existing Google Cloud
project in Firebase Console. Create/choose its Hosting site, then attach both
`alphacompose.com` and `www.alphacompose.com` if the latter should redirect.
Copy the exact verification and routing records into Cloudflare DNS. Keep the
Firebase records DNS-only while Firebase provisions and renews TLS; do not
invent record values from this runbook.

The checked-in `firebase.json` rewrites exact `/v1/overlay` traffic to
`agg-overlay` before rewriting every other route to `agg-api`. Both services
run in Warsaw. Firebase Hosting has a hard limit of 60 seconds for a dynamic
Cloud Run rewrite and returns HTTP 504 after that point. The public synchronous
contract therefore accepts only one-second diagnostics and rejects longer
sections before encoding; the 3,600-second Cloud Run timeout is not a public
long-render contract. Use durable jobs for every production-length render. No
default Firebase project is checked in: every deployment must pass `--project`
explicitly. The production workflow invokes the pinned
`firebase-tools@15.24.0` CLI only after both private candidates pass health and
profile-isolation verification.

## Production OAuth

In Google Auth Platform for `animated-graph-cloud-prod-jp`:

- use the public name **Alpha Compose** and support email `me@jamiep.org`;
- set homepage `https://alphacompose.com`;
- set privacy policy `https://alphacompose.com/privacy`;
- set terms `https://alphacompose.com/terms`;
- verify `alphacompose.com` as an authorized domain;
- publish exactly `openid email profile drive.file` for the combined flow;
- create a production Web application client with the exact redirect
  `https://alphacompose.com/v1/auth/login/callback`;
- configure the intended `AGG_ADMIN_EMAILS` set; the release bootstraps those
  administrators but does not add ordinary allowlisted members.

Complete the public pages and DNS/TLS before submitting OAuth brand
verification. OAuth publication and verification are external Google actions;
record their result rather than treating `drive.file` classification as proof
of approval.

The combined callback issues no session until identity, membership, exact scope,
refresh-token encryption or validated reuse, output-folder setup, and grant
persistence all succeed. Routine login must not force consent. After a proven
unusable grant, the browser session is replaced by a signed recovery marker and
the explicit **Continue with Google** action may force consent.

Callback failures return bounded JSON error codes. `drive_grant_required`
means the owner should use the explicit recovery action; `invalid_drive_scopes`
means the flow must be restarted with exactly `openid email profile drive.file`;
`oauth_exchange_failed`, `drive_unavailable`, `kms_unavailable`, and
`grant_persistence_failed` are retryable service failures. The API emits one
`oauth_callback_failed` event
with only `requestId`, `category`, `status`, and severity. Use the response
`X-Request-Id` to correlate an owner report; never add OAuth codes, tokens,
email addresses, filenames, or request bodies to an issue or log.

Before releasing the combined flow, confirm the production client authorizes
the combined callback and the published scope set is exact. After deployment,
remove the former `https://alphacompose.com/v1/auth/drive/callback` redirect
immediately. OAuth transactions using that old route will fail and must be
restarted. Complete issue #48 publication/verification and run the combined
owner smoke before accepting the release.

## Terraform bootstrap

The state bucket is `gs://animated-graph-cloud-prod-jp-tfstate`, prefix `prod`.
Confirm bucket versioning, uniform access, and public-access prevention before
initialization. Backend initialization can read/write production state:

```sh
terraform -chdir=infra/prod init
```

The first Artifact Registry image and the Terraform-owned renderer job form a
bootstrap dependency. First enable only the Artifact Registry API and create
the repository (using any syntactically valid placeholder digest; the targeted
resource does not consume it):

```sh
terraform -chdir=infra/prod apply \
  -target=module.application.google_artifact_registry_repository.containers \
  -var='renderer_image=europe-central2-docker.pkg.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud@sha256:0000000000000000000000000000000000000000000000000000000000000000'
```

Review the target plan carefully. For a new production state it must show
exactly two additions: the Artifact Registry API service and the `containers`
repository. It must not enable any other project API. Then build and push a
candidate with the operator's ambient credentials, resolve its immutable
digest, and use that digest in a complete saved plan:

```sh
docker build -t europe-central2-docker.pkg.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud:bootstrap .
gcloud auth configure-docker europe-central2-docker.pkg.dev
docker push europe-central2-docker.pkg.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud:bootstrap
gcloud artifacts docker images describe \
  europe-central2-docker.pkg.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud:bootstrap \
  --format='value(image_summary.digest)'

terraform -chdir=infra/prod plan \
  -var='renderer_image=FULL_IMMUTABLE_PRODUCTION_IMAGE_URI' \
  -out=prod.tfplan
terraform -chdir=infra/prod apply prod.tfplan
```

The initial `api_service_url` is empty, so Scheduler is intentionally absent.
After the first API deployment, discover its `run.app` origin and copy the
workflow's promoted immutable digest. Create a saved plan with both
`-var='renderer_image=PROMOTED_IMMUTABLE_IMAGE_URI'` and
`-var='api_service_url=ORIGIN'`, inspect it, and apply it to add the
audience-bound reconciliation schedule without reverting the renderer. Every
later Terraform plan must use the currently promoted renderer digest.

The first release containing the Terraform-owned `agg-api` service performs a
declarative import of the existing service. Before pushing that release, verify
the production deployer can read and write the GCS state and review a saved
plan with the live immutable image and origin. The plan must import `agg-api`,
retain its 1 vCPU, 512 MiB, concurrency-80, 300-second envelope, create the new
`agg-overlay` service with no replacement or destroy, and update the renderer
image when applicable. Do not push if state access or the import is unresolved.

Before uploading a candidate, the production workflow applies a complete saved
Terraform plan with the currently promoted renderer digest and live API
`run.app` origin. That full apply creates the Terraform-owned overlay if needed
without exposing unverified candidate code. The workflow then deploys the same
immutable candidate privately to API and overlay, configures and verifies both,
and applies a later saved plan targeted only to API, overlay, and the durable
renderer. Public invocation is restored afterward, followed by pinned Firebase
routes. The targeted candidate reconciliation cannot conceal infrastructure
drift because the complete plan already ran before candidate promotion.

## Overlay cost and observability

`agg-api` remains at 1 vCPU and 512 MiB. `agg-overlay` uses 8 vCPU and 32 GiB,
request-based billing, a minimum instance count of zero, a maximum of two, and
concurrency of one. Only authenticated overlay rendering and release health
checks activate the large service envelope. UI, preview, administration, job,
token, Drive, and internal routes are unavailable on the overlay profile even
through its direct `run.app` URL.

The public synchronous contract accepts only one-second diagnostic overlays.
After authentication and request validation, a longer section returns HTTP 422
before encoder startup with `synchronous_overlay_duration_exceeded`, the
response `X-Request-Id`, `maxDurationSeconds: 1`, and `durableJobsPath:
/v1/jobs`. Production-length overlays and all composites use durable jobs. The
direct `run.app` URL is not a public fallback: do not add CORS, broaden cookie
scope, or publish it as a second client origin.

Inspect overlay request volume, latency, failures, and memory separately using
the Cloud Run service label. Never log request bodies, telemetry, tokens,
filenames, subjects, or email addresses:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-overlay"' \
  --project=animated-graph-cloud-prod-jp \
  --limit=100
```

Use the same `service_name` label in Cloud Monitoring and billing reports to
separate the large overlay envelope from ordinary API usage. Correlate rejected
long requests by `requestId` and reason
`synchronous_overlay_duration_exceeded`; logs may contain only bounded duration
and limit numbers. An HTTP 504 for an accepted one-second diagnostic is a
Firebase Hosting boundary, not evidence that Cloud Run exceeded its timeout.

## Secret Manager

Terraform creates containers only. Create independent production values; never
paste values into Terraform, workflow YAML, logs, issue comments, or command
arguments. Add payloads over standard input:

```sh
gcloud secrets versions add oauth-client-secret \
  --project=animated-graph-cloud-prod-jp --data-file=- < /secure/path/production-oauth-client.json
openssl rand -base64 48 | tr -d '\n' | gcloud secrets versions add session-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
openssl rand -base64 48 | tr -d '\n' | gcloud secrets versions add token-hash-pepper \
  --project=animated-graph-cloud-prod-jp --data-file=-
```

Create the Picker API key in the production project, restrict it only to
`picker.googleapis.com`, and enter it without a trailing newline. The
production deployment verifies the key's owning project and API restriction
before it deploys the candidate:

```sh
read -rs PICKER_API_KEY
printf %s "$PICKER_API_KEY" | gcloud secrets versions add picker-api-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
unset PICKER_API_KEY
```

### Resend bootstrap and guarded workflow recovery

Follow these sections in order. Use the reviewed checkout containing the issue
#103 Terraform changes. The development workflow checks the version before its
image push. The production workflow remains Terraform-first: its complete apply
can create the container and IAM, then its guarded version check halts before
the image push. A halt is expected and recoverable; it is not permission to
bypass the check or inject a placeholder value.

#### 1. Create the Resend account and verify sender DNS

Create the Resend account, add `alphacompose.com`, copy only the exact DNS
records Resend supplies into the authoritative DNS provider, and wait for
Resend to report the domain verified. Do not infer or invent DNS values. The
sender configured by both workflows is
`Alpha Compose <early-access@alphacompose.com>`.

#### 2. Apply the Resend Terraform bootstrap

Initialize both existing remote states. Plan only the Resend secret container,
API payload binding, and deployer metadata binding. The production plan still
needs the currently promoted renderer digest to satisfy the root input, but the
targets do not update an application runtime:

```sh
export GOOGLE_CLOUD_QUOTA_PROJECT=animated-graph-cloud-jp
terraform -chdir=infra/dev init
terraform -chdir=infra/dev plan \
  -input=false \
  -lock-timeout=5m \
  -target='google_secret_manager_secret.application["resend-api-key"]' \
  -target=google_secret_manager_secret_iam_member.api_resend_access \
  -target=google_secret_manager_secret_iam_member.deployer_resend_metadata \
  -out=resend-bootstrap-dev.tfplan
terraform -chdir=infra/dev show resend-bootstrap-dev.tfplan

export GOOGLE_CLOUD_QUOTA_PROJECT=animated-graph-cloud-prod-jp
production_renderer_image="$(gcloud run jobs describe agg-renderer \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --format='value(spec.template.spec.template.spec.containers[0].image)')"
test -n "$production_renderer_image"
terraform -chdir=infra/prod init
terraform -chdir=infra/prod plan \
  -input=false \
  -lock-timeout=5m \
  -var="renderer_image=$production_renderer_image" \
  -target='module.application.google_secret_manager_secret.application["resend-api-key"]' \
  -target=module.application.google_secret_manager_secret_iam_member.api_resend_access \
  -target=module.application.google_secret_manager_secret_iam_member.deployer_resend_metadata \
  -out=resend-bootstrap-prod.tfplan
terraform -chdir=infra/prod show resend-bootstrap-prod.tfplan
```

Review both saved plans. They may create only the Resend container and the two
least-privilege bindings, plus a required dependency that is genuinely absent.
Stop on any delete, replacement, unrelated runtime update, missing-state
resource, or import requirement. Apply only the reviewed plans:

```sh
terraform -chdir=infra/dev apply resend-bootstrap-dev.tfplan
terraform -chdir=infra/prod apply resend-bootstrap-prod.tfplan
```

#### 3. Add enabled development and production versions

Use separate environment values and standard input so neither value enters
shell history, Terraform state, workflow output, source, or logs:

```sh
read -rs DEV_RESEND_API_KEY
printf %s "$DEV_RESEND_API_KEY" | gcloud secrets versions add resend-api-key \
  --project=animated-graph-cloud-jp --data-file=-
unset DEV_RESEND_API_KEY

read -rs PROD_RESEND_API_KEY
printf %s "$PROD_RESEND_API_KEY" | gcloud secrets versions add resend-api-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
unset PROD_RESEND_API_KEY

test "$(gcloud secrets versions describe latest \
  --project=animated-graph-cloud-jp --secret=resend-api-key \
  --format='value(state)')" = "ENABLED"
test "$(gcloud secrets versions describe latest \
  --project=animated-graph-cloud-prod-jp --secret=resend-api-key \
  --format='value(state)')" = "ENABLED"
```

#### 4. Rerun the guarded deployment workflows

After the reviewed issue #103 commit is on `main`, rerun development first and
production second through their existing `workflow_dispatch` entry points:

```sh
gh-axi workflow run deploy.yml --ref main
gh-axi workflow run deploy-production.yml --ref main
```

Do not rerun production when the development workflow failed or when either
metadata check above is not `ENABLED`. Neither workflow reads the secret
payload during its preflight.

Rotate the value by adding and verifying the new enabled version before
disabling the prior version. Replace `PREVIOUS_VERSION_NUMBER` only with the
numeric version selected from the metadata-only list command:

```sh
gcloud secrets versions list resend-api-key \
  --project=animated-graph-cloud-prod-jp \
  --filter='state=ENABLED' --format='table(name,state,createTime)'
read -rs RESEND_API_KEY
printf %s "$RESEND_API_KEY" | gcloud secrets versions add resend-api-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
unset RESEND_API_KEY
gcloud secrets versions describe latest \
  --project=animated-graph-cloud-prod-jp --secret=resend-api-key \
  --format='value(state)'
gcloud secrets versions disable PREVIOUS_VERSION_NUMBER \
  --project=animated-graph-cloud-prod-jp --secret=resend-api-key
```

Confirm all five enabled versions and IAM bindings without printing payloads.
The production workflow checks only that the latest Resend secret version is
enabled. It cannot prove Resend account creation or sender-domain verification,
so stop the release if either external checkpoint is not complete.

## Automatic production deployment

Every push to protected `main` triggers **Deploy Alpha Compose production**. The
workflow builds and scans the pushed commit, applies the complete production
Terraform configuration using the currently promoted renderer digest and API
origin, pushes a new immutable digest, verifies both private services,
reconciles API, overlay, and the durable renderer, publishes Hosting, and
verifies health/privacy/terms. It neither publishes OAuth nor adds ordinary
members; configured administrators are bootstrapped from `AGG_ADMIN_EMAILS`.

Terraform automation needs a one-time owner apply after
`enable_terraform_deployments` is introduced. That reviewed bootstrap grants the
keyless production deployer its checked-in project roles and billing budget
access. Production deliberately keeps
`enable_observability_log_ttl = false` during this permission-only bootstrap so
the owner does not approve the document-deleting TTL behavior from #38 at the
same time. Use the current live renderer digest and API origin in the saved
full plan.

```sh
renderer_image="$(gcloud run jobs describe agg-renderer \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --format='value(spec.template.spec.template.spec.containers[0].image)')"
api_service_url="$(gcloud run services describe agg-api \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --format='value(status.url)')"
terraform -chdir=infra/prod plan \
  -var="renderer_image=$renderer_image" \
  -var="api_service_url=$api_service_url" \
  -out=terraform-automation-bootstrap.tfplan
terraform -chdir=infra/prod show terraform-automation-bootstrap.tfplan
```

Before applying, inspect the saved full plan. It must contain only the intended
deployer project roles, three least-privilege custom roles and bindings, state
bucket object access, Scheduler service-account use, and billing budget access,
with no TTL change, replacement, or destroy. Stop on any existing untracked
resource and add a reviewed import path. Apply only that accepted saved plan,
then require a zero-change full plan with the same live inputs:

```sh
terraform -chdir=infra/prod apply terraform-automation-bootstrap.tfplan
terraform -chdir=infra/prod plan \
  -detailed-exitcode \
  -var="renderer_image=$renderer_image" \
  -var="api_service_url=$api_service_url" \
  -out=terraform-automation-steady-state.tfplan
```

The steady-state command must exit 0; exit 2 means it found changes and the
checkpoint failed. Do not push or merge the workflow change until both the
permission-only bootstrap apply and zero-change steady-state review succeed.

Only then may #38 change the production input to
`enable_observability_log_ttl = true`. Its saved full production plan must show
exactly one `observability-logs.expireAt` TTL addition, with
zero unrelated changes or destroys. The owner must separately approve the deletion
of expired observability documents before applying that plan. Do not use a
targeted apply for either checkpoint.

Automatic pushes refuse Terraform delete and replacement actions. Review the
plan summary, then manually dispatch the same workflow with
`allow_destructive_terraform` only when the action is intended. If the default
Firestore database exists but is absent from state, the workflow stops with an
import warning. Never delete the database. Manually dispatch with
`import_existing_firestore` to perform the exact state-only import before the
plan. For any other `AlreadyExists` result, stop, identify the existing resource,
and add a reviewed import path rather than replacing production state.

Afterward, complete `docs/release-acceptance.md` and store a populated copy of
the evidence template outside the repository or in the approved release
record. Verify the operations dashboard and alerts before costed acceptance.
The canonical public OpenAPI contract URL is
`https://alphacompose.com/openapi.yaml`; the production workflow checks its
`application/yaml` response and versioned document marker.

## Rollback

Normal rollback is a reviewed revert or restoration commit pushed to protected
`main`. This promotes the resulting immutable code to both API and durable
renderer while retaining current data and Secret Manager versions. Record the
workflow URL and digest. A code rollback does not roll back secrets or data;
restore those only through a separately reviewed recovery plan.

For an authentication, legal, or data-exposure emergency, close public origin
ingress first (Firebase will receive a forbidden backend response):

```sh
gcloud run services remove-iam-policy-binding agg-api \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --member=allUsers \
  --role=roles/run.invoker
gcloud run services remove-iam-policy-binding agg-overlay \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --member=allUsers \
  --role=roles/run.invoker
```

Do not delete state, buckets, Firestore, secrets, Firebase, or OAuth clients as
a rollback. Diagnose using bounded logs, re-run repository acceptance, release
a known-good commit through the environment gate, verify owner access, then
restore public ingress only through the reviewed workflow.
