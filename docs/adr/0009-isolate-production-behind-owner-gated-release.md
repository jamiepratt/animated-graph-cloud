# 0009: Isolate production behind an owner-gated release

- Status: Accepted
- Date: 2026-07-17

## Context

Alpha Compose needs a public product domain while compute remains in Warsaw.
Development state, identities, secrets, OAuth credentials, budgets, and releases
must not become an accidental path into production. Direct Cloud Run domain
mapping is not available for `europe-central2`, and release authority remains
with the owner even when configured administrators use the product.

## Decision

Production uses the separate `animated-graph-cloud-prod-jp` project and its own
GCS Terraform state, service identities, secret containers, PLN 400 budget, and
Google OAuth web client. Development and production retain separate OAuth
credentials. Production Workload Identity Federation accepts only the exact
immutable GitHub OIDC subject (including owner and repository IDs) for the
`production` environment.

Firebase Hosting terminates TLS for `alphacompose.com` and rewrites requests to
the `agg-api` Cloud Run service in `europe-central2`. Adding Firebase to the
project and accepting Firebase Terms remain an explicit owner action because
that association cannot be undone. Cloudflare remains the registrar and DNS
authority; Firebase owns the application hosting route.

Production release is manual through the GitHub `production` environment. The
approver supplies an explicit owner-approved multi-admin confirmation and release ref. CI builds
and scans that checked-out commit, publishes an immutable digest, verifies the
private candidate, then opens ingress and deploys the pinned Firebase CLI
configuration. The workflow does not add members or publish OAuth.

Repository automation records only checks it actually runs. Costed load tests,
maximum renders, Drive and DaVinci checks, DNS/TLS, OAuth publication, legal
approval, monitoring, and rollback remain manual or external evidence.

## Consequences

Production compromise and spend are separated from development, and release
authority is visible in GitHub environment history. The public route adds
Firebase Hosting as an operational dependency. Initial bootstrap, domain
verification, secret versions, OAuth publication, and owner acceptance cannot
be made fully unattended without hiding consequential decisions, so the
production runbook keeps those checkpoints explicit.
