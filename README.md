# Animated Graph Cloud

Private Clojure service for generating telemetry graph overlays. This is a clean-room implementation; the reference TypeScript renderer is behavioral evidence only and none of its source or assets belong here.

## Local verification

Requires JDK 21, Clojure CLI, Terraform, Google Cloud CLI, and Docker Desktop.

```sh
clojure -M:test
clojure -T:build uber
terraform -chdir=infra/dev init
terraform -chdir=infra/dev validate
```

The uberjar contains both entry points:

```sh
java -cp target/animated-graph-cloud.jar clojure.main -m agg.api.main
java -cp target/animated-graph-cloud.jar clojure.main -m agg.renderer.main
```

The same JDK 21 image runs either entry point. The API is the default command;
the renderer is selected by replacing the image arguments:

```sh
docker build -t animated-graph-cloud:local .
test/container_smoke.sh animated-graph-cloud:local
docker run --rm animated-graph-cloud:local clojure.main -m agg.renderer.main
```

## ProRes renderer spike

The renderer streams one reusable Java2D RGBA buffer to the bundled FFmpeg
8.1.2 process. FFmpeg writes a seekable, non-fragmented ProRes 4444 MOV with
deterministic AAC-LC heartbeat audio. `prores_ks` receives
`yuva444p10le`; FFmpeg decodes the resulting `ap4h` stream as
`yuva444p12le`, the profile's decoder representation.

Run both bounded 10-second local cases and their media assertions:

```sh
docker build -t animated-graph-cloud:local .
script/render_local_spike.sh animated-graph-cloud:local
```

Run the gated Warsaw Cloud Run matrix with an immutable Artifact Registry
digest:

```sh
script/run_cloud_spike.sh \
  europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:DIGEST
```

To retry only a missing stage, append the existing UTC run ID and carry spend
from earlier run IDs conservatively:

```sh
PRIOR_ESTIMATED_COST_USD=0.20 script/run_cloud_spike.sh \
  europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:DIGEST \
  20260716T213215Z
```

The cloud script enforces one task at a time, zero retries, 8 vCPU, 32 GiB,
and a 60-minute timeout. Each successful render uploads its MOV, JFR, and JSON
report to the private temporary bucket, then removes local artifacts. Bucket
objects expire after one day. Reports contain dimensions, timings, effective
fps, cgroup peak memory, output size, checksum, FFprobe evidence, and JFR
summaries; structured logs omit filenames and synthetic telemetry values.

The retained maximum MOVs are inputs to the manual DaVinci Resolve check in
GitHub issue #3.

## Telemetry preview and overlay API

`POST /v1/preview` returns `image/png`; `POST /v1/overlay` returns a seekable
`video/quicktime` ProRes 4444 overlay with AAC heartbeat audio. Both accept the
same JSON body:

```json
{
  "telemetryFormat": "polar-csv",
  "telemetry": "timestamp,heart_rate\n2026-07-17T10:00:00Z,120\n...",
  "preset": "1080p25",
  "telemetrySyncAt": "2026-07-17T10:00:00Z",
  "cameraSyncAt": "2026-07-17T09:00:00Z",
  "sectionStartAt": "2026-07-17T09:00:00Z",
  "sectionEndAt": "2026-07-17T09:00:02Z"
}
```

Timestamps are ISO-8601 instants. `cameraSyncAt` must not follow the section;
section end must follow section start by a whole number of seconds. The anchor
offset maps the camera section onto Polar time, and Polar telemetry must cover
both mapped boundaries. The selected preset supplies size, 25 fps, and maximum
duration. Telemetry is limited to 10 MiB and the JSON envelope to 10 MiB plus
64 KiB. Accepted Polar CSV uses comma or semicolon delimiters, an absolute
`timestamp`/`datetime` column, and `heart_rate`, `heart rate`, `HR`, or
`HR (bpm)` values between 20 and 260 bpm.

Heart rate is linearly interpolated on one shared section timeline. Preview and
MOV use a fixed 40–220 bpm scale and a stable 30-second centered/clamped window
(or the whole section when shorter); heartbeat timing uses the same interpolated
values. See ADR 0004 for the rendering decision.

The same `telemetry` field also accepts these locked formats:

- `garmin-fit`: base64-encoded binary FIT content. Record messages must contain
  absolute timestamps and heart rate; decoding uses Garmin's Java FIT SDK.
- `oxiwear-hr-csv`: comma- or semicolon-delimited `reading_time,pulse_rate`.
  `reading_time` must include `Z` or an explicit UTC offset.

Optional inputs extend the same JSON body:

```json
{
  "spo2": {
    "format": "oxiwear-spo2-csv",
    "telemetry": "reading_time,spo2\n2026-07-17T10:00:00Z,97\n..."
  },
  "timer": {
    "startAt": "2026-07-17T09:00:00.200Z",
    "endAt": "2026-07-17T09:00:01.800Z"
  },
  "watermark": {"contentBase64": "iVBORw0KGgo..."}
}
```

SpO2 must cover the requested section and is linearly interpolated on the same
camera-aligned timeline. Timer bounds must remain inside the section; the timer
shows zero before its start and its final elapsed value after its end. A
watermark must decode as PNG, be at most 2 MiB, no larger than 1024 by 1024, and
contain at most 1,048,576 pixels. It is decoded once and composited without
creating frame collections.

All timestamps are absolute instants, so timezone offsets and local-midnight
crossings normalize deterministically. Missing one-second samples interpolate;
blank, non-finite, out-of-range, unordered, or timezone-ambiguous records fail
validation. CSV parsing is line-oriented, FIT decoding is streaming, every
input has a byte ceiling, and normalized series have a 900,000-sample ceiling.
See ADR 0006.

## Durable render jobs

`POST /v1/jobs` accepts the same validated render JSON as `/v1/overlay` and
requires an `Idempotency-Key` header of 1–128 characters. A new request returns
`202`; replaying the same key and body returns `200` with the same job ID.
Reusing a key for different content returns `409`.

The response documents its polling and command resources:

```json
{
  "id": "0c33c3cc-3696-4a55-b4c8-9a953819cceb",
  "state": "queued",
  "attempt": 1,
  "statusUrl": "/v1/jobs/0c33c3cc-3696-4a55-b4c8-9a953819cceb",
  "cancelUrl": "/v1/jobs/0c33c3cc-3696-4a55-b4c8-9a953819cceb/cancel",
  "retryUrl": "/v1/jobs/0c33c3cc-3696-4a55-b4c8-9a953819cceb/retry"
}
```

Poll with `GET statusUrl`; cancel or retry with `POST` to the corresponding
resource. State progresses through `queued`, `launching`, and `running` to
`succeeded`, `failed`, or `cancelled`; a running cancellation may briefly show
`cancellation-requested`. Only failed and cancelled jobs accept an explicit
retry. Successful polls include the private temporary object name, SHA-256, and
content type. Failed polls include a bounded `failureCode` and never a signed
URL, telemetry value, or filename.

Firestore transactions bind idempotency and cap active render leases at five.
Cloud Tasks calls only the private authenticated dispatcher. Each Cloud Run Job
receives only a job ID and has zero automatic render retries. Request and output
objects expire after one day; Firestore job metadata expires after 90 days.

New jobs are admitted atomically against a global 100-submission UTC-day limit,
the five live leases, and a conservative 25-cent reservation from the USD 30
monthly development ceiling using Cloud Billing's Pacific calendar month.
Explicit retries reserve compute again. Rejections
use stable `daily_submission_limit_exhausted`, `capacity_exhausted`, and
`monthly_budget_exhausted` error codes; already-running work may finish.

An OIDC-authenticated Cloud Scheduler request reconciles Firestore every five
minutes. Expired running or launching jobs become `failed` with
`stale_lease`; expired cancellations become `cancelled`; stale, terminal, and
missing-job capacity leases are removed atomically. Operational events contain
only bounded reasons, durations, and aggregate counts. Logs-based metrics,
alerts, and the operations dashboard cover queue age, render failures, renderer
memory, stale leases, Drive reauthorization, and budget admission. Billing
budget notifications are advisory; application admission is the spend gate.

Run the owned Firestore transaction contract against the emulator:

```sh
script/test_firestore_emulator.sh
```

Infrastructure targets project `animated-graph-cloud-jp` in Warsaw (`europe-central2`). Application Default Credentials provide local authentication; do not create service-account key files or commit credentials.

Pushes to `main` authenticate through GitHub Workload Identity Federation,
scan and push an immutable commit-tagged image, deploy the private `agg-api`
service, and execute `agg-renderer-smoke`. The workflow verifies the health
response, runtime identities, and the renderer's structured completion log.

## Google login and Drive delivery

When `AGG_AUTH_ENABLED=true`, browser users start login at
`GET /v1/auth/login/start`. Login requests only `openid email profile` and
rechecks `AGG_ALLOWED_EMAILS` on every authenticated request. Drive is connected
separately at `GET /v1/auth/drive/start` with only `drive.file`; the callback
encrypts the refresh token with the Warsaw KMS key and creates or reuses the
user's `Animated Graph Cloud` folder. `GET /v1/drive/picker` opens a no-store
Google Picker bridge for the same restricted grant. The authenticated `/`
entrypoint opens that bridge in a popup and accepts selected CSV, FIT-like
binary, or PNG metadata only from the same origin. A directly opened Picker
page also displays its selection, so the manual flow never discards it. The
bridge does not broaden the server grant or silently download selected files.

Completed cloud jobs upload their local MOV to a preallocated Drive file ID.
Firestore retains the ID and resumable session so worker retries resume the
same file rather than creating duplicates. Polling success includes
`driveFileId` and `driveWebViewLink`.

## Web workflow and personal API tokens

Authenticated users get a server-rendered HTMX workflow at `/`. Paste the render
request JSON once, then preview or submit it. Returned HTML fragments poll the
owned job and expose cancel or retry only when the current state permits it.
The same page creates, lists, and revokes personal API tokens. The only custom
browser JavaScript is the same-origin Google Picker bridge.

Cookie-authenticated POST requests require the signed CSRF value supplied by
the page in `X-CSRF-Token`. Automation should create a personal token and send
it as `Authorization: Bearer TOKEN`; bearer requests do not use browser cookies.
`POST /v1/tokens` returns the full token once, `GET /v1/tokens` lists metadata
without secrets or hashes, and `POST /v1/tokens/{id}/revoke` revokes an owned
token. These token-management writes require a browser session and CSRF token.
Allowlist removal immediately disables both sessions and personal tokens.

Personal tokens use a UUID selector plus 256 random secret bits. Firestore stores
only owner metadata, revocation state, and an HMAC-SHA256. The HMAC pepper comes
from the `token-hash-pepper` Secret Manager value and must contain at least 32
bytes.

The OAuth web-client JSON has this Secret Manager shape:

```json
{"web":{"client_id":"…","client_secret":"…"}}
```

Register both Cloud Run callbacks on that web client:

- `https://SERVICE_URL/v1/auth/login/callback`
- `https://SERVICE_URL/v1/auth/drive/callback`

The runtime mounts `oauth-client-secret`, `session-key`, `picker-api-key`, and
`token-hash-pepper` from Secret Manager. Never place their values in Terraform,
workflow YAML, logs, or command arguments. Store the Picker key as its exact
bytes with no trailing newline. The key is API-restricted solely to `picker.googleapis.com`:
browser-referrer restrictions are incompatible because Picker validates the
developer key from its `docs.google.com` iframe.

Implementation work is tracked in GitHub Issues.
