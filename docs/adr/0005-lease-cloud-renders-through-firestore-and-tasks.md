# 0005: Lease cloud renders through Firestore and Cloud Tasks

- Status: Accepted
- Date: 2026-07-17

## Context

The synchronous Polar renderer proves media behavior, but a maximum render is a
multi-minute operation. Submission must survive API scale-to-zero, Cloud Tasks
redelivery, worker failure, cancellation, and an explicit user retry without
duplicating expensive Cloud Run executions.

## Decision

`POST /v1/jobs` requires a bounded `Idempotency-Key`. A Firestore transaction
binds the key and canonical request digest to one UUID job. Reusing the key with
the same request returns the original polling resource; using it with different
content is a conflict. The validated request body is stored under the job UUID
in the private one-day temporary bucket so Firestore's document limit cannot
reduce the 10 MiB input contract.

Named Cloud Tasks invoke the private `/internal/v1/jobs/{id}/dispatch` route
with an OIDC token minted for a dedicated task identity and the API service
audience. One Firestore transaction changes `queued` to `launching` and adds a
65-minute capacity lease. A singleton capacity document admits at most five
unexpired leases. Duplicate delivery observes the non-queued state and does not
start another execution. Capacity rejection is retryable by Cloud Tasks.

The dispatcher starts the one-task `agg-renderer` Cloud Run Job with zero task
retries, overriding its command with `--job-id <UUID> --attempt <number>`. The
worker loads all other data by job ID, uses the issue-4 render seam, and uploads
an attempt-unique MOV. Polling exposes `queued`, `launching`, `running`,
`cancellation-requested`, and the terminal states `succeeded`, `failed`, or
`cancelled`. Worker and launch crashes use explicit failure codes; output over
18 GiB fails as `output_too_large`. Only failed or cancelled jobs can be
explicitly retried, which increments the attempt and creates a new named task.

Jobs receive a Firestore TTL timestamp 90 days in the future. Temporary request
and output objects retain the bucket's one-day lifecycle. V4 signed request
uploads, when used by a client, are PUT-only, content-type locked, and expire in
at most 15 minutes.

## Consequences

Firestore is the durable source of truth and Cloud Tasks is only a delivery
mechanism. Atomic admission prevents duplicate compute and enforces the five-job
limit without keeping an API instance alive. If dispatch crashes after Cloud Run
accepts an execution but before Firestore records its name, Scheduler correlates
the active execution by exact job ID and attempt. It adopts that execution and
renews the same durable capacity lease instead of permitting a duplicate; zero
automatic render retries prevents that ambiguity from multiplying compute.
