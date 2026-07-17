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
4. Publish the external Google OAuth app and approve the owner-only production
   release.
5. Approve every costed production acceptance render and load test.

## Repository and GitHub preparation

Run repository-only acceptance first:

```sh
script/release_acceptance.sh
```

Create a GitHub environment named `production`. Require the intended owner
approval and restrict deployment to protected release refs. The Terraform WIF
condition requires the exact OIDC subject
`repo:jamiepratt/animated-graph-cloud:environment:production`; a workflow job
without that environment cannot impersonate the production deployer.

The repository contains no secret values and no long-lived JSON credential file. Use
ambient operator credentials locally and GitHub Workload Identity Federation in
CI.

## Firebase and domain

After accepting the irreversible checkpoint, add the existing Google Cloud
project in Firebase Console. Create/choose its Hosting site, then attach both
`alphacompose.com` and `www.alphacompose.com` if the latter should redirect.
Copy the exact verification and routing records into Cloudflare DNS. Keep the
Firebase records DNS-only while Firebase provisions and renews TLS; do not
invent record values from this runbook.

The checked-in `firebase.json` rewrites every route to the `agg-api` Cloud Run
service in Warsaw. The production workflow invokes the pinned
`firebase-tools@15.24.0` CLI only after the private Cloud Run candidate passes
health verification.

## Production OAuth

In Google Auth Platform for `animated-graph-cloud-prod-jp`:

- use the public name **Alpha Compose** and support email `me@jamiep.org`;
- set homepage `https://alphacompose.com`;
- set privacy policy `https://alphacompose.com/privacy`;
- set terms `https://alphacompose.com/terms`;
- verify `alphacompose.com` as an authorized domain;
- request login scopes `openid email profile` and Drive scope `drive.file` only;
- create a production Web application client with exact redirects
  `https://alphacompose.com/v1/auth/login/callback` and
  `https://alphacompose.com/v1/auth/drive/callback`;
- keep the release owner only; do not add allowlisted members during release.

Complete the public pages and DNS/TLS before submitting OAuth brand
verification. OAuth publication and verification are external Google actions;
record their result rather than treating `drive.file` classification as proof
of approval.

## Terraform bootstrap

The state bucket is `gs://animated-graph-cloud-prod-jp-tfstate`, prefix `prod`.
Confirm bucket versioning, uniform access, and public-access prevention before
initialization. Backend initialization can read/write production state:

```sh
terraform -chdir=infra/prod init
```

The first Artifact Registry image and the Terraform-owned renderer job form a
bootstrap dependency. First create only the repository (using any syntactically
valid placeholder digest; the targeted resource does not consume it):

```sh
terraform -chdir=infra/prod apply \
  -target=module.application.google_artifact_registry_repository.containers \
  -var='renderer_image=europe-central2-docker.pkg.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud@sha256:0000000000000000000000000000000000000000000000000000000000000000'
```

Review the target plan carefully. Then build and push a candidate with the
operator's ambient credentials, resolve its immutable digest, and use that
digest in a complete saved plan:

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

## Secret Manager

Terraform creates containers only. Create independent production values; never
paste values into Terraform, workflow YAML, logs, issue comments, or command
arguments. Add payloads over standard input:

```sh
gcloud secrets versions add oauth-client-secret \
  --project=animated-graph-cloud-prod-jp --data-file=- < /secure/path/production-oauth-client.json
openssl rand 48 | gcloud secrets versions add session-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
openssl rand 48 | gcloud secrets versions add token-hash-pepper \
  --project=animated-graph-cloud-prod-jp --data-file=-
```

Create a Picker API key restricted only to `picker.googleapis.com`. Enter it
without a trailing newline:

```sh
read -rs PICKER_API_KEY
printf %s "$PICKER_API_KEY" | gcloud secrets versions add picker-api-key \
  --project=animated-graph-cloud-prod-jp --data-file=-
unset PICKER_API_KEY
```

Confirm all four enabled versions and IAM bindings without printing payloads.

## Owner-only release

From GitHub Actions, dispatch **Release Alpha Compose production** with an exact
reviewed commit/tag and confirmation `RELEASE ALPHA COMPOSE TO OWNER ONLY`.
Approve the `production environment` gate. The workflow builds that checked-out
commit, scans it, pushes an immutable digest, verifies the private service,
publishes Hosting, updates the durable renderer, and verifies health/privacy/
terms. It neither publishes OAuth nor adds a member.

Afterward, apply the Scheduler step above, complete
`docs/release-acceptance.md`, and store a populated copy of the evidence
template outside the repository or in the approved release record. Verify the
operations dashboard and alerts before costed acceptance.

## Rollback

Normal rollback is another manually approved production workflow run using the
last accepted commit or tag. This promotes the previous immutable code to both
API and durable renderer while retaining current data and Secret Manager
versions. Record the workflow URL and digest. A code rollback does not roll back
secrets or data; restore those only through a separately reviewed recovery
plan.

For an authentication, legal, or data-exposure emergency, close public origin
ingress first (Firebase will receive a forbidden backend response):

```sh
gcloud run services remove-iam-policy-binding agg-api \
  --project=animated-graph-cloud-prod-jp \
  --region=europe-central2 \
  --member=allUsers \
  --role=roles/run.invoker
```

Do not delete state, buckets, Firestore, secrets, Firebase, or OAuth clients as
a rollback. Diagnose using bounded logs, re-run repository acceptance, release
a known-good commit through the environment gate, verify owner access, then
restore public ingress only through the reviewed workflow.
