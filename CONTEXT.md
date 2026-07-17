# Project context

Alpha Compose is the public product. The `animated-graph-cloud` service accepts private telemetry inputs, renders overlay-only ProRes 4444 MOV files, and writes completed outputs to the requesting user's Google Drive.

## Invariants

- One deployable Clojure application exposes `agg.api.main` and `agg.renderer.main` entry points.
- The API is a scale-to-zero Cloud Run service; rendering uses Cloud Run Jobs.
- Supported cloud resources live in Warsaw (`europe-central2`).
- Firestore is Native mode in `europe-central2`.
- User Drive access uses `openid email profile drive.file`; broad Drive scopes are forbidden.
- The configured owner controls Firestore-backed memberships; revocation invalidates every access path and active job.
- Runtime and CI identities use ambient credentials or Workload Identity Federation. Service-account key files are forbidden.
- Temporary cloud objects expire after 24 hours. Job metadata expires after 90 days.
- No source video is downloaded or composited.
- Rendering emits a standard seekable MOV with ProRes 4444 alpha, 25 fps, and heartbeat audio.
- Logs exclude email addresses, Google subjects, filenames, tokens, telemetry values, Drive credentials, and signed URLs.

## Current cloud identity

- Development project: `animated-graph-cloud-jp` (`891643499444`).
- Production project: `animated-graph-cloud-prod-jp` (`488013150738`).
- Organization: `jamiep.org` (`567378404662`).
- Region: `europe-central2`.
- GitHub repository: `jamiepratt/animated-graph-cloud`.
