# Proto infrastructure runbook

The `proto` branch is the only source of truth for the separate proto
application. Every push to `proto` triggers `.github/workflows/deploy-proto.yml`.
The workflow tests the proto code, builds and scans an immutable image, plans
and applies `infra/proto`, verifies the private Cloud Run origin, publishes the
pinned Firebase Hosting rewrite, and verifies `https://proto.alphacompose.com`.

Do not merge the proto workflow, Terraform root, Hosting template, or this
runbook into `main`. The development and production workflows must not deploy,
configure, expose, or publish `agg-proto`.

## Ownership boundary

`infra/proto` uses `gs://animated-graph-cloud-prod-jp-tfstate` with prefix
`proto`. It owns:

- `agg-proto` in `animated-graph-cloud-prod-jp`, `europe-central2`;
- the public invoker binding for that service;
- Firebase Hosting site `proto-alphacompose`;
- service account `agg-proto-github-deployer`;
- WIF provider `github/animated-graph-cloud-proto`;
- the deployer's required IAM bindings.

It reads the existing production `agg-api` URL and runtime service account but
does not manage production or development resources. The WIF condition accepts
only the immutable repository identity on `refs/heads/proto`, plus the guarded
one-time bootstrap tag prefix.

The shared development module contains only a `removed` migration declaration
for its former proto address, with `destroy = false`. This forgets any legacy
state entry without deleting the old service. Decommissioning a legacy
development-project service requires separate inventory and approval.

## Required one-time import

The service and Hosting site already exist. Never delete or recreate either to
resolve `AlreadyExists`. Import them into the new state before the first normal
push-triggered deployment.

First check out the reviewed `proto` commit and use an authorized human gcloud
account with the production project selected:

```sh
git switch proto
gcloud config set project animated-graph-cloud-prod-jp
export PROTO_BOOTSTRAP_CONFIRM=animated-graph-cloud-prod-jp/refs/heads/proto
script/bootstrap_proto_terraform.sh
```

The script creates or reconciles only the dedicated deployer, provider, and
bootstrap IAM. It creates no service-account key and does not push. Review its
output, then create and push the exact lightweight tag it prints before pushing
the `proto` branch:

```sh
commit="$(git rev-parse HEAD)"
git tag "proto-terraform-bootstrap-${commit}"
git push origin "proto-terraform-bootstrap-${commit}"
```

`.github/workflows/bootstrap-proto-terraform.yml` rejects a tag whose suffix is
not its exact commit or whose commit does not descend from the current remote
`proto` tip. It reads the live service image and production API origin, uses
declarative import blocks, blocks every delete or replacement, and applies the
reviewed proto root.

Before proceeding, inspect the workflow plan summary and state:

```sh
terraform -chdir=infra/proto init
terraform -chdir=infra/proto state list
```

The state must include the existing Cloud Run service, Hosting site, dedicated
deployer, and dedicated WIF provider. Stop on any missing import, destroy,
replacement, or unrelated production resource.

## Normal deployment

After bootstrap, push the reviewed commit to `proto`. The branch workflow is
Terraform-first for the proto runtime: direct `gcloud run deploy`, service
updates, and IAM mutations are forbidden. Automatic plans reject delete and
replacement actions.

The Google OAuth web client used by the proto runtime must include the exact
redirect `https://proto.alphacompose.com/v1/auth/login/callback`. Secret
payloads remain operator-managed Secret Manager versions and never enter
Terraform state or workflow output.

Rollback with a reviewed revert pushed to `proto`. Do not delete Terraform
state, `agg-proto`, the Hosting site, secrets, or OAuth credentials.

## Private playback range boundary

Firebase Hosting does not preserve the browser's `Range` header on the rewrite
to Cloud Run. The proto page therefore waits for a same-origin service worker
before loading sources. The worker copies only the byte-range expression into
the `__agg_range` query parameter. The API always prefers a received `Range`
header and uses the query value only when that header is absent, then applies
the same closed, open-ended, suffix, malformed, and 8 MiB bounding rules.

The query contains no playback authority or source identity. Playback authority
remains in the owner-bound HttpOnly cookie. If the service worker cannot control
the page, the proto harness fails closed before creating playback requests.

## Request-correlated playback logs

Use only the A/B/C labels and safe correlation values captured from response
headers. Never paste source identity, account data, authority, request bodies,
telemetry, or media into a logging query.

Each query is intentionally limited and projects only allowlisted operational
fields. Replace the uppercase placeholder, leaving the service filter intact.

By request ID:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-proto" AND jsonPayload.requestId="REPLACE_REQUEST_ID"' \
  --freshness=24h --limit=100 \
  --format='table(timestamp,jsonPayload.event,jsonPayload.operation,jsonPayload.status,jsonPayload.reason,jsonPayload.requestId,jsonPayload.trace,jsonPayload.revision,jsonPayload.rangeSource,jsonPayload.receivedRange,jsonPayload.rangeStart,jsonPayload.rangeEnd,jsonPayload.bytesRequested,jsonPayload.bytesTransferred,jsonPayload.upstreamStatus,jsonPayload.elapsedMs,jsonPayload.exceptionClass,jsonPayload.exceptionStack)'
```

By trace:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-proto" AND jsonPayload.trace="REPLACE_TRACE"' \
  --freshness=24h --limit=100 --format=json
```

By operation:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-proto" AND jsonPayload.operation="REPLACE_OPERATION"' \
  --freshness=24h --limit=100 --format=json
```

By deployed revision:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-proto" AND jsonPayload.revision="REPLACE_REVISION"' \
  --freshness=24h --limit=100 --format=json
```

By bounded failure reason:

```sh
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="agg-proto" AND jsonPayload.reason="REPLACE_REASON"' \
  --freshness=24h --limit=100 --format=json
```

## Derivative preparation observability

Use the request ID returned by the preparation response. Keep the resource
filter intact. These queries project bounded operational fields only.

Follow one preparation across the API and worker:

```sh
gcloud logging read \
  '(resource.type="cloud_run_revision" OR resource.type="cloud_run_job") AND jsonPayload.requestId="REPLACE_REQUEST_ID"' \
  --freshness=24h --limit=100 \
  --format='table(timestamp,jsonPayload.event,jsonPayload.operation,jsonPayload.status,jsonPayload.reason,jsonPayload.requestId,jsonPayload.trace,jsonPayload.revision,jsonPayload.elapsedMs,jsonPayload.queueAgeMs,jsonPayload.attempt,jsonPayload.durationBucket,jsonPayload.rangeStart,jsonPayload.rangeEnd,jsonPayload.bytesRequested,jsonPayload.bytesTransferred,jsonPayload.sourceBytes,jsonPayload.upstreamBytes,jsonPayload.outputBytes,jsonPayload.cacheOutcome,jsonPayload.profileVersion,jsonPayload.reservedMinorUnits,jsonPayload.retryable,jsonPayload.errorType,jsonPayload.exceptionClass)'
```

Distinguish lifecycle boundaries:

| Boundary | `jsonPayload.operation` |
|---|---|
| Preparation cache | `derivative_cache` |
| Task queue | `derivative_queue` |
| Worker launch | `derivative_dispatch` |
| FFmpeg and range proxy | `derivative_encode` |
| Output validation | `derivative_verification` |
| Private storage publication | `derivative_publication` |
| Owner-bound byte range | `derivative_playback` |
| Cancellation | `derivative_cancellation` |
| Expiry and repair | `derivative_reconciliation` |

Query those boundaries for one request:

```sh
gcloud logging read \
  '(resource.type="cloud_run_revision" OR resource.type="cloud_run_job") AND jsonPayload.requestId="REPLACE_REQUEST_ID" AND jsonPayload.operation=("derivative_cache" OR "derivative_queue" OR "derivative_dispatch" OR "derivative_encode" OR "derivative_verification" OR "derivative_publication" OR "derivative_playback" OR "derivative_cancellation" OR "derivative_reconciliation")' \
  --freshness=24h --limit=100 \
  --format='table(timestamp,jsonPayload.event,jsonPayload.operation,jsonPayload.status,jsonPayload.reason,jsonPayload.elapsedMs,jsonPayload.queueAgeMs,jsonPayload.rangeStart,jsonPayload.rangeEnd,jsonPayload.bytesRequested,jsonPayload.bytesTransferred,jsonPayload.upstreamBytes,jsonPayload.outputBytes,jsonPayload.cacheOutcome,jsonPayload.reservedMinorUnits)'
```

Find terminal failures by bounded reason:

```sh
gcloud logging read \
  '(resource.type="cloud_run_revision" OR resource.type="cloud_run_job") AND jsonPayload.event="derivative_preparation_terminal" AND jsonPayload.status=("failed" OR "rejected" OR "cancelled" OR "expired")' \
  --freshness=24h --limit=100 \
  --format='table(timestamp,jsonPayload.requestId,jsonPayload.trace,jsonPayload.revision,jsonPayload.status,jsonPayload.reason,jsonPayload.attempt,jsonPayload.elapsedMs,jsonPayload.cancellationLagMs,jsonPayload.retryable,jsonPayload.errorType,jsonPayload.exceptionClass)'
```
