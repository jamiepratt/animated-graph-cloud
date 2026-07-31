# 0022: Isolate production private video previews

- Status: Accepted
- Date: 2026-07-30

## Context

ADR 0016 keeps directly playable selected Drive videos on private, stateless
range passthrough. Some selected videos are renderable but not browser
compatible. The proven proto derivative design supplies useful lifecycle and
media evidence, but its identity, OAuth scope, fixed-folder UI, infrastructure
names, and persistence namespaces cannot become production contracts.

Production login must remain exactly `openid email profile drive.file`.
Production and proto jobs, caches, idempotency records, active-job limits,
fingerprints, and object paths must never collide.

## Decision

Browser-supported sources continue to use direct private Drive range
passthrough and submit no derivative work. Browser-incompatible but renderable
sources require an explicit preparation request.

Production adopts a versioned `production-private-preview-contract-v1`.
It fixes the H.264 High Level 4.0, AAC-LC, MP4 fast-start media profile and the
approved duration, size, transfer, compute, concurrency, retention, cache, and
cost limits. Every job records environment `production`, profile version,
attempt, state, timestamps, and expiry. States are `queued`, `running`,
`cancellation-requested`, `succeeded`, `failed`, `cancelled`, `expired`, and
`revoked`. Cancellation of running work is not terminal until acknowledged.
Only retryable failures and cancellations can create another charged attempt.

Environment is included in cache and idempotency HMAC domains. Firestore job,
cache, idempotency, and active-job namespaces and object prefixes are separately
named for production and proto. Missing immutable Drive version limits reuse to
the current job. Browser-visible resources expose opaque UUIDs, stable state,
attempt, profile, expiry, safe failure code, retryability, and relative URLs,
never object keys or source/account identity.

Preparation transport accepts only `fileId` plus a bounded Idempotency-Key.
Public diagnostics allow only stable failure code, UUID request ID,
retryability, and bounded attempt. Derivative routes are an API-profile
contract and remain unavailable on overlay and proto profiles.

Safe correlated observability uses the submission response UUID as the durable
production request ID. The API, queue dispatch, worker, Drive ranges, encoder,
verification, publication, playback, cancellation, expiry, reconciliation, and
terminal transition emit bounded events with operation, status, attempt,
profile, revision, environment, and only applicable numeric measurements.
Correlation is rejected unless the event environment is exactly `production`.
Drive IDs, filenames, source and account identity, OAuth material, cookies,
object keys, signed URLs, request bodies, and private telemetry are never event
fields or metric labels.

## Consequences

ADR 0016 remains authoritative for direct range passthrough. This decision
supersedes only its no-transcoding consequence for browser-incompatible but
renderable sources.

Infrastructure, durable preparation execution, owner-bound derivative
playback, Timing UI, deployment, and live acceptance remain separate
implementation slices. This decision creates no additional authorization.
