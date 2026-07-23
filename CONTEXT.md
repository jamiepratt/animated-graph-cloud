# Project context

Alpha Compose is the public product. The `animated-graph-cloud` service accepts private telemetry inputs, renders transparent overlay-only ProRes 4444 MOV files or bounded composites of one verified Google Drive video, and writes completed outputs to the requesting user's Google Drive.

## Invariants

- One deployable Clojure application exposes `agg.api.main` and `agg.renderer.main` entry points.
- The API is a scale-to-zero Cloud Run service; rendering uses Cloud Run Jobs.
- Supported cloud resources live in Warsaw (`europe-central2`).
- Firestore is Native mode in `europe-central2`.
- One PKCE-protected Google flow atomically establishes identity, active membership, and restricted `openid email profile drive.file` access before issuing a browser session; broad Drive scopes are forbidden.
- The configured owner controls Firestore-backed memberships; revocation invalidates every access path and active job.
- Runtime and CI identities use ambient credentials or Workload Identity Federation. Service-account key files are forbidden.
- Temporary cloud objects expire after 24 hours. Job metadata expires after 90 days.
- Source video is streamed directly from Google Drive into FFmpeg for durable compositing jobs or through short-lived owner-bound browser playback; it is never persisted by the service.
- Every durable submission preflights its Drive delivery grant before job creation, including transparent overlay jobs without a source video.
- Rendering emits a standard seekable MOV with ProRes 4444 alpha, 25 fps, and heartbeat audio.
- Logs exclude email addresses, Google subjects, filenames, tokens, telemetry values, Drive credentials, and signed URLs.
- Safe structured observability events from the API and renderer are copied best-effort to the Firestore `observability-logs` collection for 30 days. Owner and admin sessions can inspect the latest 100 events at `/ui/admin/logs` with severity/component filters and formatted or raw JSON views.

## Error metadata and source locations

Production application errors are raised through `agg.errors/raise!`. Every
deliberate error has a namespaced keyword `:type`, retains its existing message
and cause, and receives a nested `:source` map containing the macro callsite
`:file`, `:line`, and `:column`. Wrapping creates a new outer source location
while keeping the original exception object as the unchanged cause.

Only explicitly allowlisted, bounded diagnostic fields may accompany an error:
numeric status/offset/size/limit fields, bounded invariant fields such as
`line`, `field`, `component`, `job-id`, and `retryable`, and numeric `limits`.
Error context must never contain failed values, request bodies, telemetry,
tokens, credentials, filenames, or signed URLs. `raise!` never logs; API, job,
and worker boundaries emit at most one safe Telemere signal and translate
internal types to transport responses or public job `failureCode` values.

## Current cloud identity

- Development project: `animated-graph-cloud-jp` (`891643499444`).
- Production project: `animated-graph-cloud-prod-jp` (`488013150738`).
- Organization: `jamiep.org` (`567378404662`).
- Region: `europe-central2`.
- GitHub repository: `jamiepratt/animated-graph-cloud`.
