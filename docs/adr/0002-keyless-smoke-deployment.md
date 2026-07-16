# 0002: Deploy the smoke path keylessly from GitHub Actions

- Status: Accepted
- Date: 2026-07-16

## Context

The first cloud path must prove Artifact Registry, a private Cloud Run service,
a Cloud Run Job, runtime identities, and structured logging. Long-lived Google
Cloud service-account keys are prohibited.

## Decision

GitHub Actions exchanges its repository-scoped OIDC token through the existing
Workload Identity Federation provider and impersonates the dedicated deployer.
Terraform owns that trust and its IAM grants. The workflow builds and scans one
commit-tagged image before pushing it, then deploys the smoke service and job,
executes the job, and asserts health, runtime identities, and structured logs.

The workflow owns the temporary smoke runtime definitions. The renderer sizing
spike will promote the job configuration into Terraform once CPU, memory,
timeout, and media-tool requirements are measured.

## Consequences

No static cloud credential is stored in GitHub or the image. Deployment remains
reproducible from a commit, but the workflow and Terraform deliberately share a
boundary: Terraform owns identities and authorization; delivery owns the smoke
service and job until their production shape is known.
