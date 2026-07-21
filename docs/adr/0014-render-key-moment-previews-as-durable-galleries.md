# 0014: Render key-moment previews as durable galleries

- Status: Accepted
- Date: 2026-07-20
- Amended: 2026-07-21, manifest v2 and Final-only selected-source previews
- Supersedes: The preview portions of ADR 0003 and ADR 0004

## Context

One synchronous midpoint PNG could not explain trace boundaries or prominent
events. Selected-source composition also depended on finishing within the public
request deadline. Rendering each selected frame independently would repeatedly
download and decode the same private source video.

## Decision

`POST /v1/preview` creates an authenticated operation in the existing Firestore,
Cloud Tasks, and Cloud Run Job lifecycle. The render request carries an internal
version marker so the renderer produces a gallery instead of a durable Drive
delivery. Preview jobs expire after 24 hours.

Select actual 25 fps output frames from each normalized trace: the first and
final rendered video frames, trace boundaries, configured timer start and end,
plus at most three standard-prominence minima and maxima. Map every candidate to
a real output frame before deduplication, combine all coincident labels, and
share frame assets across trace sections.

Overlay-only previews store transparent overlay PNGs. Selected-source previews
stream the source once into one FFmpeg workflow, batch-select every unique frame,
apply the production fit and full overlay composition, and emit only the exact
composited Final PNG. Fitted unoverlaid Source PNGs are not generated, split,
stored, referenced, served, or displayed. A transparent complete overlay still
emits and retains the truthful Final frame. The source video is never persisted.

If that single decode reaches source EOF before every selected frame, retain
each available Final frame. At least one emitted moment is a successful
partial gallery with one bounded `source_duration_too_short` warning containing
requested, generated, and omitted counts. Zero emitted moments are a terminal
failure with the same bounded diagnosis. Nonzero decoder exits, source stream
errors, invalid output, and storage failures retain their unrelated failure
classification.

Store opaque preview assets in the existing private temporary bucket. Serve
owner-bound thumbnail and full-size routes with `Cache-Control: no-store` and
fixed size limits. The operation result is a generic trace-section manifest.
Manifest version 2 uses `mode: "final"`, `kind: "final"`, and one `image`
reference for selected-source assets. Overlay-only results also use version 2
with `mode: "overlay"`, `kind: "overlay"`, and their transparent `image`
reference. Version 1 is not preserved. The browser polls the operation, rejects
stale generations, and renders responsive, accessible galleries.

Within each trace section, Final cards retain chronological order, use fixed
approximately 128 CSS pixel desktop widths, wrap into centered rows without
stretching incomplete rows, and avoid horizontal gallery scrolling. Narrow
screens use larger centered viewport-bounded cards. The full-image viewer uses
trace section order then moment order, one image per selected-source moment.

## Consequences

The old synchronous midpoint `image/png` API is intentionally removed. Clients
must accept HTTP 202, poll the operation URL, then fetch authenticated image
references. Preview execution scales to zero with the existing renderer job and
does not add a continuously allocated service. Durable render submission and
Drive delivery remain unchanged.

The investigated short monotonic selected-source preview had two selected
frames, and the manifest and stored assets retained both. The apparent
single-moment result came from the former oversized horizontal presentation,
not renderer or manifest loss. The compact gallery and viewer enumerate every
manifest photograph; timer boundaries add distinct moments when they map to
distinct frames.

The browser renders source-duration warnings and failures as plain recovery
guidance, while the JSON operation and operational event retain bounded request
correlation, counts, duration, reason, and retryability. Durable submission and
render behavior are unchanged.
