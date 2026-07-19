# 0003: Use bounded multi-input FFmpeg rendering on a sized Cloud Run Job

- Status: Accepted, amended 2026-07-19
- Original date: 2026-07-16
- Amendments: add streamable Drive-video compositing and the expanded FFmpeg
  bundle; isolate synchronous overlays in one measured service envelope

## Context

The renderer must create production-shaped video without retaining image
sequences or unbounded frame collections. The original render path produces a
transparent ProRes 4444 MOV overlay. The new path also needs to take one video
selected from the requester's Google Drive, composite the telemetry overlay
over its first frames, and deliver a normal video output.

The selected source is capped at 2 GiB and is streamed through a non-seekable
input. A source is accepted only when the bundled FFmpeg can decode it from
that pipe; support is not claimed for every format that an unrestricted FFmpeg
build could decode from a seekable file. The source is never persisted as a
durable object or expanded into a frame collection.

Both paths retain the existing bounded render envelope: preset dimensions,
25 fps, a maximum section duration, a reusable Java2D RGBA buffer, local output
finalization, and a one-task Cloud Run Job with a 3,600-second timeout.

## Decision

Build FFmpeg 8.1.2 from the official release tarball whose SHA-256 is
`464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`.
The container build verifies that digest before extraction. The bundle still
starts from `--disable-everything`, but is no longer limited to the
overlay-only feature set. It remains network-free, uses only the `file` and
`pipe` protocols, and keeps FFmpeg's own libraries statically built while
enabling the in-tree demuxers, decoders, parsers, and bitstream filters needed
to accept streamable source media.

The bundle is built with Ubuntu Jammy's pinned
`libx264-dev=2:0.163.3060+git5db6aa6-2build1` package. FFmpeg enables GPL and
`libx264`; the final image carries the matching `libx264.so.163` runtime beside
the FFmpeg and FFprobe binaries. Output encoders are limited to native AAC,
`libx264`, and `prores_ks`. MOV and MP4 muxers are enabled. The filter set
includes source normalization, video overlay, frame-rate conversion, audio
mixing, resampling, and limiting: `scale`, `crop`, `pad`, `fps`, `format`,
`setsar`, `overlay`, `aformat`, `aresample`, `amix`, and `alimiter`.

The two render modes have separate media contracts:

- Overlay-only requests remain seekable, non-fragmented ProRes 4444 MOV files
  with transparent video and deterministic 48 kHz stereo AAC heartbeat audio.
- Requests with a selected Drive source use the preset canvas and 25 fps. The
  source begins at frame zero, runs for the telemetry section duration, and is
  rejected if it is shorter. The selected fit mode defaults to letterbox or
  pillarbox when absent. Composited output defaults to H.264 MP4, with ProRes
  422 MOV as the alternate format.
- Composited audio defaults to source audio plus heartbeat. The mix is bounded
  by normalization and limiting; source-only and heartbeat-only modes remain
  selectable.

Java2D continues to render into one reusable interleaved RGBA buffer. The
compositing path feeds source video and overlay frames as separate FFmpeg
inputs; it does not write a source copy, frame files, or a frame collection.
FFmpeg finalizes the output on the task's temporary filesystem. The renderer
validates the selected output contract, calculates SHA-256, uploads the
result/report artifacts, and deletes local artifacts only after successful
delivery.

The final command-selected JDK 21 image remains digest-pinned and non-root.
Terraform continues to own a generation-2 Warsaw `agg-renderer` Job with one
task, no parallelism, zero retries, no GPU, 8 vCPU, 32 GiB memory, and a hard
3,600-second timeout. Its image input must be an immutable digest.

The synchronous `/v1/overlay` path runs on a separate `agg-overlay` Cloud Run
service. It uses the same immutable application image and API service identity,
but an application profile exposes only health and authenticated exact
`/v1/overlay` requests. The normal `agg-api` service cannot render overlays,
including through its direct `run.app` origin.

Terraform keeps `agg-api` at its conventional 1 vCPU, 512 MiB, 300-second,
concurrency-80 envelope. `agg-overlay` is generation 2 with 8 vCPU, 32 GiB, a
3,600-second Cloud Run timeout, request-based billing, a minimum of zero and
maximum of two instances, and concurrency one. A second overlay request cannot
overlap the Java2D frame buffer, FFmpeg process, audio file, and in-memory
temporary output filesystem within one cgroup. Firebase Hosting routes only
the exact public `/v1/overlay` path to this service before its API catch-all.

Firebase Hosting terminates a dynamic Cloud Run rewrite after 60 seconds and
returns HTTP 504. The 3,600-second Cloud Run timeout is therefore a compute
envelope, not a claim that maximum-duration synchronous renders work through
`alphacompose.com`. Callers should use durable jobs for work that may not
finish inside the Hosting limit.

## Existing measurements and new acceptance

The measurements below are historical baseline evidence for the original
overlay-only path and the previous minimal FFmpeg bundle. They remain useful
for regression comparison but do not prove the new Drive-video or H.264 path.

The accepted baseline used the Linux/AMD64 image manifest
`europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:1e4cbd1e9af97487286af0a14791b5f08bead56363fa2f06ed4b45b669da44b6`
and object root
`gs://animated-graph-cloud-jp-temporary/renderer-spike/20260716T231234Z`.
The exact immutable image was built locally after a successful CI deployment;
no SLSA, SBOM, or build-provenance attestation was claimed. Artifact Analysis
reported `FINISHED_SUCCESS` with continuous analysis active and zero critical
or high effective vulnerabilities (65 medium, 13 low, and 1 minimal). An
earlier ARM64 startup attempt produced no renderer artifact.

| Case | Task wall | Render wall | Effective fps | Peak cgroup memory | Output bytes |
|---|---:|---:|---:|---:|---:|
| 1080p25, 480 s overlay | 524.627 s | 473.172 s | 25.361 | 7,675,768,832 | 2,327,694,978 |
| 2.7k25, 240 s overlay | 482.442 s | 444.055 s | 13.512 | 11,897,987,072 | 1,821,740,649 |

Both baseline MOVs validated as conventional seekable, non-fragmented ProRes
4444 `ap4h` containers with `yuva444p12le` decoding, 25 fps, and AAC-LC stereo
at 48 kHz. The original acceptance also covered midpoint/end decoding and
DaVinci Resolve alpha playback.

The amended decision requires new acceptance evidence before the compositing
path is released:

- the expanded bundle starts as a non-root image and exposes H.264 encode,
  common streamable video decode, MP4/MOV muxing, overlay, audio mixing, and
  limiting;
- streamable Drive-video fixtures cover the supported input matrix, the 2 GiB
  admission limit, short-source rejection, source/audio absence, and strict
  non-seekable failure behavior;
- both fit modes, all audio modes, H.264 MP4, and ProRes 422 MOV validate at
  both presets; and
- maximum-duration composited renders record wall time, effective fps, peak
  memory, output size, checksum, FFprobe evidence, and source non-persistence.

## Consequences

The FFmpeg build is larger and slower to compile because it includes broad
in-tree decode and demux support. Enabling x264 introduces GPL licensing and a
pinned runtime library that must remain part of image review and release
evidence. The restricted protocol set, source-size cap, streamability rule,
fixed render envelope, and output validation limit the resulting media and
resource risk.

The original transparent-overlay contract remains stable. The compositing path
can use the same telemetry timeline and frame renderer, but its two-input
FFmpeg orchestration, source streaming, output contracts, audio mix, and
performance envelope require separate tests and production evidence.

Only the dedicated overlay route uses the large service envelope; ordinary UI,
API, administration, job, token, and Drive traffic stays on the small API
service. Request-based billing and scale to zero bound idle cost. Concurrency
one is required because increasing only memory would still permit overlapping
FFmpeg processes to exhaust the cgroup.

Standard container finalization still needs local output space; the completed
file is delivered through the existing resumable Drive path rather than being
streamed as an unfinished container to object storage.
