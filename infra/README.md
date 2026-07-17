# Infrastructure

Terraform state for development is stored in `gs://animated-graph-cloud-jp-tfstate` with object versioning and public access prevention. The state bucket is the only manually bootstrapped resource because Terraform cannot create its own backend.

`infra/dev` adopts the existing default Firestore database, manages required APIs, and creates foundational development resources. Secret containers intentionally have no versions; secret values are added only through a secure operator workflow.

Terraform grants the keyless GitHub deployer only the roles needed to push
images, deploy and invoke Cloud Run resources, read smoke-test logs, and attach
the dedicated API and renderer runtime identities. The deployment workflow owns
the smoke service and smoke job. Terraform owns the durable `agg-renderer` Job:
one Warsaw generation-2 task, 8 vCPU, 32 GiB, zero retries, and a 60-minute
timeout. `renderer_image` must be an immutable Artifact Registry digest. No GPU
is attached.

The durable render path uses the Warsaw `agg-render` Cloud Tasks queue. Its
dispatch concurrency is five, and every request carries an OIDC token for the
dedicated `agg-tasks` identity and private API audience. Firestore owns job and
capacity-lease state, with TTL enabled on `jobs.expireAt`. API and renderer
identities receive only their required Firestore, queue, Run invocation, and
temporary-object roles. A custom execution-reader role gives the API only
`run.executions.get` and `run.executions.list`, allowing reconciliation to
correlate an accepted execution from its job ID and attempt arguments. The
renderer remains one task with zero retries; task redelivery can retry admission
but cannot duplicate a non-queued execution.

Cloud Scheduler calls the authenticated reconciliation route every five minutes
with a dedicated `agg-scheduler` identity and the API origin as its OIDC
audience; it cannot authenticate as the `agg-tasks` dispatcher. Terraform also
owns a USD 30 monthly billing budget with 50%, 80%, and 100% thresholds, five
logs-based metrics, seven alert policies, and the operations dashboard. A
sustained-backlog alert reads Cloud Tasks' built-in queue-depth metric, so OIDC
or endpoint failures remain visible without a successful API dispatch. Billing
and Monitoring notifications go to the configured owner email; they do not stop
resources. The API separately reserves 25 cents for
every new render attempt and refuses work once the configured fixed-UTC−8
monthly ceiling is
exhausted; the deployment workflow keeps those two values aligned.

Google OAuth runtime values are operator-managed Secret Manager versions:
`oauth-client-secret` contains the downloaded web-client JSON, `session-key`
contains at least 32 random bytes, and `picker-api-key` contains a Google API key
API-restricted solely to `picker.googleapis.com`. A browser-referrer
restriction is not compatible with Picker because Google validates the
developer key from the `docs.google.com` iframe. The Secret Manager payload is
the exact key bytes with no trailing newline. Terraform manages only the
secret containers, least-privilege secret access, and KMS access. The API also
reads `token-hash-pepper`, an operator-generated value of at least 32 bytes used
only to HMAC personal API tokens. Terraform never reads or stores secret
payloads. The durable renderer mounts the OAuth client
secret so it can refresh an encrypted user grant and upload the completed MOV
to Drive.

```sh
terraform -chdir=infra/dev init
terraform -chdir=infra/dev plan -out=dev.tfplan
terraform -chdir=infra/dev apply dev.tfplan
```
