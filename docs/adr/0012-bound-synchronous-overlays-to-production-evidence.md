# 0012: Bound synchronous overlays to production evidence

- Status: Accepted
- Date: 2026-07-19

## Context

Firebase Hosting terminates the `alphacompose.com` public route and has a hard
60-second limit for dynamic Cloud Run rewrites. The isolated `agg-overlay`
service permits 3,600-second requests, but that backend timeout cannot extend
the Hosting limit. A healthy authenticated one-second production overlay took
8.1 seconds. Longer synchronous renders have no production evidence that they
fit the public limit and can continue consuming the large service after Hosting
returns HTTP 504.

A direct custom origin would require a global external load balancer because
native Cloud Run domain mapping is unavailable in `europe-central2`. It would
also add DNS and TLS operations, cross-origin authentication or broader cookie
scope, CORS policy, and another public origin. Alpha Compose already has durable
jobs for long overlays and composites, with bounded admission, polling,
cancellation, retry, and Drive delivery.

## Decision

Keep `https://alphacompose.com` as the only public API origin. Keep
`POST /v1/overlay` on the isolated, authenticated, scale-to-zero overlay service
for one-second diagnostics only. The service authenticates and validates the
request, then rejects any longer section before creating an output or starting
the encoder.

The rejection is HTTP 422 with
`synchronous_overlay_duration_exceeded`, the correlated request ID,
`maxDurationSeconds: 1`, and `durableJobsPath: /v1/jobs`. It emits one bounded
admission event containing no request body, telemetry, identity, or filename.
All production-length overlays and every source-video composite use
`POST /v1/jobs`.

The direct Cloud Run URL is an operational candidate-verification endpoint, not
a supported client origin. No CORS response is added, session cookie scope is
not broadened, and the large service exposes only health and authenticated
overlay routes. Request-based billing, concurrency one, minimum instances zero,
and maximum instances two remain unchanged.

## Consequences

Requests such as a 157-second composite are admitted to the durable renderer
instead of failing after the public connection closes. The synchronous limit is
deliberately conservative and matches evidence rather than an estimate. Raising
it requires new sanitized production timing evidence that leaves safe margin
under the Hosting limit.

Rejected requests can still cold-start the large service, but they stop before
media encoding and scale back to zero. Avoiding a load balancer and second
origin also avoids their fixed cost and additional authentication, CORS, DNS,
TLS, and incident-response surface.
