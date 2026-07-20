# Alpha Compose

Private Clojure service for generating telemetry graph overlays. The deployable
artifact and repository retain the `animated-graph-cloud` technical name; the
public product and domain are Alpha Compose and `https://alphacompose.com`.
This is a clean-room implementation; the reference TypeScript renderer is
behavioral evidence only and none of its source or assets belong here.

## Local verification

Requires JDK 21, Clojure CLI, Terraform, Google Cloud CLI, and Docker Desktop.

```sh
clojure -M:test
clojure -T:build uber
terraform -chdir=infra/dev init
terraform -chdir=infra/dev validate
```

The versioned production OpenAPI contract is publicly available at
[`https://alphacompose.com/openapi.yaml`](https://alphacompose.com/openapi.yaml).
This is the canonical published contract URL; its source is `docs/openapi.yaml`.

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

`POST /v1/preview` starts an authenticated asynchronous key-moment gallery and
returns HTTP 202. Poll its `statusUrl` until `state` is `succeeded`, then read the
structured `result`. This is a breaking API change: clients that expected the
former synchronous midpoint `image/png` response must switch to operation
polling and authenticated image retrieval.

The result enumerates one generic section per rendered telemetry trace. Each
section contains chronological, actual-frame moments for video and trace
boundaries plus up to three prominent minima and maxima. Coincident events share
one frame reference and combine labels. Assets are deduplicated across sections.
Overlay-only requests expose checkerboard-ready transparent Overlay images.
Selected-source requests expose fitted Source and exact production-composited
Final images, merging them when the complete overlay is transparent. Thumbnail
and full-size PNG URLs are opaque, authenticated, owner-bound, bounded,
`Cache-Control: no-store`, and retained for 24 hours.

The browser renders eager responsive thumbnails. Desktop aligns Source and Final
rows across moment columns; narrow screens use vertical moment cards. A native
dialog provides keyboard-accessible larger images and restores focus when
closed. Starting a new preview or editing its request invalidates the old result.
The source video remains streamed and unpersisted; all unique requested frames
are batch-decoded in one worker workflow.

`POST /v1/overlay` still returns a seekable `video/quicktime` ProRes 4444 overlay
with AAC heartbeat audio. That synchronous route is limited to a one-second production diagnostic.
Longer requests return HTTP 422 before rendering and
identify `POST /v1/jobs` as the supported path. Full production renders use
durable jobs.

```json
{
  "telemetryFormat": "polar-csv",
  "telemetry": "timestamp,heart_rate\n2026-07-17T10:00:00Z,120\n...",
  "preset": "1080p25",
  "telemetrySyncAt": "2026-07-17T10:00:00Z",
  "cameraSyncAt": "2026-07-17T09:00:00Z",
  "sectionStartAt": "2026-07-17T09:00:00Z",
  "sectionEndAt": "2026-07-17T09:00:01Z"
}
```

The render JSON fields are:

| Field | Required | Accepted value or shape |
| --- | --- | --- |
| `telemetryFormat` | Yes | `polar-csv`, `garmin-fit`, or `oxiwear-hr-csv` |
| `telemetry` | Yes | CSV text, or base64 FIT content for `garmin-fit`; CSV is limited to 10 MiB and FIT base64 to 13,981,016 characters |
| `preset` | Yes | `1080p25` (1920×1080, 25 fps, up to 8 minutes) or `2.7k25` (2704×1520, 25 fps, up to 4 minutes) |
| `telemetrySyncAt`, `cameraSyncAt`, `sectionStartAt`, `sectionEndAt` | Yes | ISO-8601 instants with `Z` or an explicit UTC offset |
| `spo2` | No | `{ "format": "oxiwear-spo2-csv", "telemetry": "..." }`; CSV is limited to 10 MiB |
| `timer` | No | `{ "startAt": "...", "endAt": "..." }`, within the requested section |
| `watermark` | No | `{ "contentBase64": "..." }`, a valid PNG up to 2 MiB and 1024×1024 pixels |
| `sourceVideo` | No | `{ "fileId": "..." }`; optional client `name` and `mimeType` are ignored |
| `outputFormat` | No | With `sourceVideo`: `h264-mp4` or `prores-422-mov` |
| `fitMode` | No | With `sourceVideo`: `letterbox`, `pillarbox`, or `crop` |
| `audioMode` | No | With `sourceVideo`: `source+heartbeat`, `source-only`, or `heartbeat-only` |

Timestamps are ISO-8601 instants. `cameraSyncAt` must not follow the section;
section end must follow section start by a whole number of seconds. The anchor
offset maps the camera section onto Polar time, and Polar telemetry must cover
both mapped boundaries. The selected preset supplies size, 25 fps, and maximum
duration. Telemetry is limited to 10 MiB and the JSON envelope to 10 MiB plus
64 KiB. Accepted Polar CSV uses comma or semicolon delimiters, an absolute
`timestamp`/`datetime` column, and `heart_rate`, `heart rate`, `HR`, or
`HR (bpm)` values between 20 and 260 bpm. Polar sensor-gap values of exactly
zero may remain in an uploaded export when their timestamps are strictly before
or after the mapped requested section; their values are ignored, but their
timestamps must remain strictly increasing with every CSV row. Zero values at
either section boundary or within the section are malformed. Other invalid
values remain malformed everywhere, and valid samples must still cover both
mapped section boundaries.

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

To composite one Drive video, send only its server-verified file ID:

```json
{
  "sourceVideo": {"fileId": "drive-file-id"},
  "outputFormat": "h264-mp4",
  "fitMode": "letterbox",
  "audioMode": "source+heartbeat"
}
```

The API ignores client-supplied source names and MIME types, verifies Drive
metadata with `drive.file`, rejects sources above 2 GiB or shorter than the
requested section, and streams source bytes through a non-seekable FFmpeg pipe.
Supported outputs are H.264 MP4 (default) and ProRes 422 MOV. Fit defaults to
letterbox/pillarbox; audio defaults to source plus bounded heartbeat mix, with
source-only and heartbeat-only modes also available.

Selected-source composition has one 45-minute deadline shared by FFmpeg,
source streaming, overlay production, and the heartbeat-only fallback attempt.
Timeouts terminate FFmpeg, fail durably as `composition_timeout`, and release
the render lease before Cloud Run's one-hour task limit. Safe stage events and
at most eleven numeric frame-progress samples identify forward movement without
logging source or telemetry data. The production container smoke generates a
real 20-second H.264/AAC source, streams it through the non-seekable input, and
requires a verified 9-second H.264/AAC output within 30 seconds.
The production FFmpeg bundle includes every filter used by that command,
including `volume`; a nonzero FFmpeg exit fails immediately instead of waiting
on a pipe producer that no longer has a consumer.

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
content type. Failed render polls include a bounded `failureCode`, `stage`,
optional allowlisted `reason`, numeric upstream `status`, `retryable`, and
`elapsedMs`; `attempt`
correlates the diagnosis with the exact Cloud Run execution. The same bounded
fields appear in the renderer failure event and server-rendered job fragment.
They never include exception messages, stack traces, signed URLs, Drive IDs,
telemetry values, request bodies, tokens, or filenames. Unknown exceptions
remain `worker_failed` while retaining only the allowlisted stage.

Firestore transactions bind idempotency and cap active render leases at five.
Cloud Tasks calls only the private authenticated dispatcher. Each Cloud Run Job
receives only its job ID and attempt number and has zero automatic render
retries. Request and output objects expire after one day; Firestore job metadata
expires after 90 days.

New jobs are admitted atomically against a global 100-submission UTC-day limit,
the five live leases, and a conservative 125-grosz (PLN 1.25) reservation from
the PLN 400 monthly development ceiling using Cloud Billing's fixed UTC−8
calendar month. Explicit retries reserve compute again. Rejections
use stable `daily_submission_limit_exhausted`, `capacity_exhausted`, and
`monthly_budget_exhausted` error codes; already-running work may finish.

An OIDC-authenticated Cloud Scheduler request uses its own `agg-scheduler`
identity to reconcile Firestore every five minutes; the dispatcher accepts only
the separate `agg-tasks` identity. Before expiring a lease, reconciliation uses
the exact job ID and attempt arguments to adopt an active Cloud Run execution
that dispatch accepted but did not record. Adoption writes the execution name,
repairs running/cancellation state, and renews capacity atomically; a pending
cancellation then cancels that exact execution. A failed external cancellation
keeps the renewed lease and is retried by the next Scheduler run before any
durable cancellation is recorded. A Cloud Run cancellation operation that
reports its execution cancelled is terminal success: the API records
`cancelled` and releases the render lease instead of returning a render failure.
Thus a genuine cancellation failure converges on the next five-minute
reconciliation, while an accepted cancellation converges in the original
request. Expired unmatched running or
launching jobs become `failed` with `stale_lease`; expired cancellations become
`cancelled`; stale, terminal, and missing-job capacity leases are removed
atomically. Operational events contain only bounded reasons, durations, and
aggregate counts. Logs-based metrics, alerts, and the operations dashboard cover
dispatch queue age, live Cloud Tasks backlog depth, render failures, renderer
memory, stale leases, Drive reauthorization, and budget admission. Billing
budget notifications are advisory; application admission is the spend gate.

Run the owned Firestore transaction contract against the emulator:

```sh
script/test_firestore_emulator.sh
```

Development infrastructure targets `animated-graph-cloud-jp`; isolated
production infrastructure targets `animated-graph-cloud-prod-jp`. Both keep
application resources in Warsaw (`europe-central2`). Application Default
Credentials provide local authentication; do not create service-account key
files or commit credentials. See `docs/production-runbook.md` for production
checkpoints and `docs/release-acceptance.md` for the evidence matrix.

Pushes to `main` authenticate through GitHub Workload Identity Federation,
scan and push an immutable commit-tagged image, deploy the private `agg-api`
service, and execute `agg-renderer-smoke`. The workflow verifies the health
response, runtime identities, and the renderer's structured completion log.
Production follows the same protected-`main` model through its separate
automatic deployment workflow; each successful push can promote the public
application after the private candidate and smoke checks pass.

## Google login and Drive delivery

When `AGG_AUTH_ENABLED=true`, browser users choose **Continue with Google** at
`GET /v1/auth/login/start`. One PKCE-protected offline flow requests exactly
`openid email profile drive.file`. Its callback verifies identity and active
membership, encrypts or safely reuses the refresh token, ensures the user's
`Alpha Compose` folder, persists the grant, and issues the session last. Routine
login does not force consent. Only a signed recovery marker created after an
unusable grant enables `prompt=consent`. The active Firestore membership
generation is rechecked on every authenticated request. `AGG_OWNER_EMAIL`
bootstraps the non-revocable owner, while
`AGG_ADMIN_EMAILS` (comma- or semicolon-separated) bootstraps any number of
active administrators. Administrators can manage the allowlist but cannot
replace or revoke the owner. The authenticated page exposes a CSRF-protected
`POST /v1/auth/logout` form that expires the browser session and returns to the
signed-out homepage. `GET /v1/drive/picker` opens a no-store
Google Picker compatibility page for the same restricted grant. The
authenticated `/` entrypoint initializes the Picker in the page, keeps it
hidden until “Pick one video” is pressed, and shows the selected source after
the Picker closes. The Drive view shows selectable video files and the Picker's
Upload tab provides a supported path for adding a source video under
`drive.file`; the page does not broaden the server grant, open a wrapper
window, or silently download selected files. If the Drive view is empty, the
Picker offers a privacy-safe diagnostic report that records only bounded status
categories (token refresh, account binding, MIME selection, and Drive
indexing probe), never tokens, account values, or filenames.

Completed cloud jobs upload their local MOV to a preallocated Drive file ID.
Firestore retains the ID and resumable session so worker retries resume the
same file rather than creating duplicates. Polling success includes
`driveFileId` and `driveWebViewLink`.

Every durable submission refreshes the Drive grant before creating a job,
including overlay-only requests. A missing or revoked grant returns
`drive_grant_required` synchronously. Browser failures replace the session with
a short-lived signed recovery marker and show an explicit **Continue with
Google** action. Personal API tokens remain usable for non-Drive operations.

## Web workflow and personal API tokens

Authenticated users get a server-rendered HTMX workflow at `/`. Paste the render
request JSON once, then preview or submit it. Returned HTML fragments poll the
owned job and expose cancel or retry only when the current state permits it.
The same page creates, lists, and revokes personal API tokens. The Picker is
initialized and controlled by same-origin browser JavaScript in the main page.

The owner and admin pages list, add, and revoke members. The equivalent JSON
routes are `GET /v1/admin/members`, `POST /v1/admin/members`, and
`POST /v1/admin/members/revoke`; writes require an administrator's session and
CSRF token. Revocation invalidates the member's sessions and personal tokens, deletes
their encrypted Drive grant, and cancels queued or running work. Re-adding the
email creates a new membership generation, so the member must complete the
combined Google authorization again.

Owner and admin sessions can also open `/ui/admin/logs`. The page shows up to
100 recent privacy-safe structured events from the API and renderer, with
severity/component filters and a toggle between formatted event details and the
raw JSON emitted by the observability stack. Log copies are stored in Firestore
for 30 days on a best-effort basis; a persistence failure does not fail a request
or render.

Cookie-authenticated POST requests require the signed CSRF value supplied by
the page in `X-CSRF-Token`. Automation should create a personal token and send
it as `Authorization: Bearer TOKEN`; bearer requests do not use browser cookies.
`POST /v1/tokens` returns the full token once, `GET /v1/tokens` lists metadata
without secrets or hashes, and `POST /v1/tokens/{id}/revoke` revokes an owned
token. These token-management writes require a browser session and CSRF token.
Membership revocation immediately disables both sessions and personal tokens.

Personal tokens use a UUID selector plus 256 random secret bits. Firestore stores
only owner metadata, revocation state, and an HMAC-SHA256. The HMAC pepper comes
from the `token-hash-pepper` Secret Manager value and must contain at least 32
bytes.

The OAuth web-client JSON has this Secret Manager shape:

```json
{"web":{"client_id":"…","client_secret":"…"}}
```

For development, register the combined Cloud Run callback on the development
web client:

- `https://SERVICE_URL/v1/auth/login/callback`

Production uses a separate web client with
`https://alphacompose.com/v1/auth/login/callback`. Never reuse the development
client JSON. Remove the former `/v1/auth/drive/callback` URI from the production
OAuth client immediately after deploying the combined flow.

The runtime mounts `oauth-client-secret`, `session-key`, `picker-api-key`, and
`token-hash-pepper` from Secret Manager. Never place their values in Terraform,
workflow YAML, logs, or command arguments. Store the Picker key as its exact
bytes with no trailing newline. The key is API-restricted solely to `picker.googleapis.com`:
browser-referrer restrictions are incompatible because Picker validates the
developer key from its `docs.google.com` iframe.

Implementation work is tracked in GitHub Issues.
