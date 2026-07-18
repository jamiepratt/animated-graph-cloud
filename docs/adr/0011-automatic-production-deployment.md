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
scans the pushed `github.sha`, pushes an immutable image, verifies the private
Cloud Run candidate and renderer smoke job, configures runtime, opens ingress,
publishes the pinned Firebase Hosting route, promotes the durable renderer, and
checks the public health and legal routes.

The workflow has no manual confirmation input and no GitHub environment gate.
Production Workload Identity Federation trusts only the repository's `main`
branch subject. Branch protection and required CI checks are therefore the
release authorization boundary; the workflow must not be broadened to other
refs without an accompanying IAM review.

## Consequences

Every successful push to `main` can change the public application, so changes
must merge through the protected branch and pass required checks. Rollback is a
reviewed revert or restoration commit pushed to `main`, which runs the same
immutable deployment path. Firebase, OAuth, legal, domain, costed-render, and
manual media-acceptance checkpoints remain separate operational concerns.
