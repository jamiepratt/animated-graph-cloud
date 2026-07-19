# 0011: Deploy production from every main push

- Status: Accepted
- Date: 2026-07-18

## Context

The production workflow was previously protected by a typed confirmation and a
GitHub `production` environment approval. That gate added a manual release
step after the repository had already selected and verified an immutable commit.
The desired operating model is continuous delivery from the protected `main`
branch.

## Decision

Production deployment triggers on every push to `main`. The workflow builds and
scans the pushed `github.sha`, plans and applies production Terraform against
the currently promoted renderer and API origin, pushes an immutable image,
verifies the private Cloud Run candidate and renderer smoke job, configures
runtime, opens ingress, publishes the pinned Firebase Hosting route, promotes
the durable renderer, and checks the public health and legal routes.

Automatic pushes stop before promotion when Terraform requires an existing
Firestore import or proposes a delete/replacement. The same workflow has
explicit manual-dispatch inputs for the reviewed import or destructive plan.
Other existing resources must receive a reviewed import path; deployment must
never delete them merely to satisfy Terraform.

Production sets `enable_observability_log_ttl = false` for the one-time
permission-only bootstrap. This keeps the deployer IAM and state-access review
separate from #38, whose TTL enablement authorizes deletion of expired
observability documents. After the permission bootstrap reaches a zero-change
steady state, #38 changes only that input and requires its own saved full plan.

The automatic push path has no manual confirmation input and no GitHub
environment gate. Manual inputs exist only for guarded recovery. Production
Workload Identity Federation trusts only the repository's `main` branch
subject. Branch protection and required CI checks are therefore the release
authorization boundary; the workflow must not be broadened to other refs
without an accompanying IAM review.

## Consequences

Every successful push to `main` can change the public application, so changes
must merge through the protected branch and pass required checks. Rollback is a
reviewed revert or restoration commit pushed to `main`, which runs the same
immutable deployment path. Firebase, OAuth, legal, domain, costed-render, and
manual media-acceptance checkpoints remain separate operational concerns. A
one-time owner Terraform apply must bootstrap the workflow's infrastructure
permissions before automatic Terraform deployment is enabled. The workflow
change must not merge until the owner accepts and applies the permission-only
bootstrap and verifies its zero-change steady-state plan.
