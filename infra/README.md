# Infrastructure

Terraform state for development is stored in `gs://animated-graph-cloud-jp-tfstate` with object versioning and public access prevention. The state bucket is the only manually bootstrapped resource because Terraform cannot create its own backend.

`infra/dev` adopts the existing default Firestore database, manages required APIs, and creates foundational development resources. Secret containers intentionally have no versions; secret values are added only through a secure operator workflow.

Terraform grants the keyless GitHub deployer only the roles needed to push
images, deploy and invoke Cloud Run resources, read smoke-test logs, and attach
the dedicated API and renderer runtime identities. The deployment workflow owns
the smoke service and job configuration until the renderer sizing spike adds
the durable Cloud Run Job Terraform.

```sh
terraform -chdir=infra/dev init
terraform -chdir=infra/dev plan -out=dev.tfplan
terraform -chdir=infra/dev apply dev.tfplan
```
