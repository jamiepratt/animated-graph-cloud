# 0007: Enforce the operating envelope at admission

- Status: Accepted
- Date: 2026-07-17

## Context

Cloud Tasks concurrency and billing notifications are safeguards, not durable
admission decisions. A burst can race across API instances, explicit retries
consume compute too, and a Cloud Run execution can terminate without releasing
its Firestore lease. Operations also need actionable signals without putting
job IDs, filenames, telemetry, tokens, or signed URLs in logs.

## Decision

One Firestore transaction admits each new idempotency key. It rejects when the
UTC-day counter has reached 100, when five unexpired capacity leases exist, or
when another conservative compute reservation would exceed the monthly ceiling.
The development ceiling is PLN 400 and each attempt reserves 125 grosz
(PLN 1.25), above the measured maximum-render compute cost. Its month resets at
Cloud Billing's fixed midnight UTC−8 boundary rather than daylight-saving-aware
Pacific local time.
Explicit retries reserve again; duplicate
submissions do not. Stable errors are `daily_submission_limit_exhausted`,
`capacity_exhausted`, and `monthly_budget_exhausted`. Existing executions are
never killed by a later budget decision.

Cloud Scheduler sends an audience-bound OIDC request every five minutes using a
dedicated `agg-scheduler` identity. Dispatch accepts only `agg-tasks`, while
reconciliation accepts only `agg-scheduler`. Cloud Run launches carry exact job
ID and attempt arguments. The reconciler lists executions with a custom
read-only role and adopts an active execution when Firestore still has an
unrecorded `launching` or `cancellation-requested` attempt. Adoption records the
execution, renews its capacity lease, and cancels it when cancellation was
already requested. If external cancellation fails, the exact recorded attempt
and execution remain `cancellation-requested` with renewed capacity; every
Scheduler run retries it, and completion uses an exact state/attempt/execution
compare-and-set. Completed or different attempts are never adopted. The
reconciler otherwise fails expired launching/running jobs with `stale_lease`,
completes an expired cancellation as cancelled, and removes expired,
mismatched, terminal, or missing-job capacity leases in the same transaction.
Reconciliation is idempotent.

The temporary bucket keeps its one-day delete lifecycle and `jobs.expireAt`
keeps its Firestore 90-day TTL. Structured application events expose only a
bounded admission reason, queue age, repair counts, or generic failure class.
Logs-based metrics, alert policies, and one dashboard cover dispatch queue age,
live Cloud Tasks backlog depth, failures, memory utilization, stale leases,
Drive reauthorization, and budget admission. Backlog depth comes directly from
Cloud Tasks, so dispatch authentication failures cannot hide it.
A separate Cloud Billing budget emits 50%, 80%, and 100% notifications.

## Consequences

Firestore, not API instance memory or billing alerts, is the admission source of
truth. Reservations are intentionally conservative and are not refunded after
failure or cancellation, so the application can stop early but cannot silently
overspend its configured compute envelope. Operators must update the Terraform
budget and deployment admission minor units together when the ceiling changes.
