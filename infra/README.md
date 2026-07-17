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
temporary-object roles. The renderer remains one task with zero retries; task
redelivery can retry admission but cannot duplicate a non-queued execution.

```sh
terraform -chdir=infra/dev init
terraform -chdir=infra/dev plan -out=dev.tfplan
terraform -chdir=infra/dev apply dev.tfplan
```
