# Project context

Animated Graph Cloud accepts private telemetry inputs, renders overlay-only ProRes 4444 MOV files, and writes completed outputs to the requesting user's Google Drive.

## Invariants

- One deployable Clojure application exposes `agg.api.main` and `agg.renderer.main` entry points.
- The API is a scale-to-zero Cloud Run service; rendering uses Cloud Run Jobs.
- Supported cloud resources live in Warsaw (`europe-central2`).
- Firestore is Native mode in `europe-central2`.
- User Drive access uses `openid email profile drive.file`; broad Drive scopes are forbidden.
- Runtime and CI identities use ambient credentials or Workload Identity Federation. Service-account key files are forbidden.
- Temporary cloud objects expire after 24 hours. Job metadata expires after 90 days.
- No source video is downloaded or composited.
- Rendering emits a standard seekable MOV with ProRes 4444 alpha, 25 fps, and heartbeat audio.
- Logs exclude filenames, tokens, telemetry values, and signed URLs.

## Current cloud identity

- Development project: `animated-graph-cloud-jp` (`891643499444`).
- Organization: `jamiep.org` (`567378404662`).
- Region: `europe-central2`.
- GitHub repository: `jamiepratt/animated-graph-cloud`.

