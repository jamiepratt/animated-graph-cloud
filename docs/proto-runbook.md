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
