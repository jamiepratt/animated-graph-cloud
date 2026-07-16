# 0003: Use bounded ProRes streaming on a sized Cloud Run Job

- Status: Accepted
- Date: 2026-07-16

## Context

The renderer must create transparent, production-shaped MOV overlays without
retaining image sequences or unbounded frame collections. The maximum locked
cases are 12,000 1920x1080 frames and 6,000 2704x1520 frames, both at 25 fps.
The output must remain directly inspectable as a standard, seekable MOV while
the Cloud Run task stays below its time, memory, output-size, and cost gates.

## Decision

Build FFmpeg 8.1.2 from the official release tarball whose SHA-256 is
`464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c`.
The container build verifies that digest before extraction. It disables
autodetection, networking, shared libraries, documentation, and all default
features, then enables only FFmpeg, FFprobe, the file and pipe protocols, MOV,
raw video, WAV, ProRes, AAC, PCM, and their required conversion filters. The
final command-selected JDK 21 image remains digest-pinned and non-root.

Java2D renders into one reusable interleaved RGBA byte buffer. Each frame is
written directly to FFmpeg's stdin; no frame file or frame collection exists.
Heartbeat synthesis writes identical 48 kHz PCM stereo samples through one
bounded chunk buffer to a temporary WAV input. FFmpeg is invoked with
`prores_ks`, profile 4, `yuva444p10le`, and 16 alpha bits, plus native AAC-LC
with `-b:a 192k`.

The 4444 `ap4h` decoder representation needs an explicit distinction: FFmpeg's
encoder accepts `yuva444p10le`, while FFmpeg 8.1.2 selects 12-bit decoding for
the 4444 tag and FFprobe reports `yuva444p12le`. Tests and reports lock both
values instead of weakening the check to a format prefix. Likewise, 192 kbps is
the exact native AAC encoder target. FFprobe reports the lower observed average
for sparse identical-channel heartbeat content; reports preserve both values.
Padding, added noise, and extra codecs are rejected as ways to manufacture a
nominal measured bitrate.

FFmpeg finalizes a conventional non-fragmented MOV on the task's temporary
filesystem. The renderer validates FFprobe fields and top-level `moov`/`mdat`
atoms, rejects `moof`, calculates SHA-256, uploads MOV/JFR/report objects to the
private temporary bucket, and deletes local artifacts only after all uploads
succeed. The bucket's one-day lifecycle supplies the human handoff without
turning spike outputs into durable storage.

Terraform owns a generation-2 Warsaw `agg-renderer` Job with one task, no
parallelism, zero retries, no GPU, 8 vCPU, 32 GiB memory, and a hard 3,600-second
timeout. Its image input must be an immutable digest. The staged runner executes
one case at a time, carries prior retry cost into a conservative USD 3 ceiling,
and can resume only missing stages from a validated run ID.

## Measurements

The accepted cloud run uses image digest
`sha256:a07c03b957b5d16422e2773f430168a78f477ef07ea50dd6fcaf08271b2a89b9`
and object root `gs://animated-graph-cloud-jp-temporary/renderer-spike/20260716T213215Z`.
The one-minute gates produced these results:

| Case | Task wall | Render wall | Effective fps | Peak cgroup memory | Output bytes | SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| 1080p25, 60 s | 142.421 s | 129.278 s | 11.603 | 1,145,954,304 | 247,192,377 | `3f5229f04a147b57d62f4ff889f5a6d8c1dcc58af80fb45256c860d66507d57d` |
| 2.7k25, 60 s | 262.439 s | 245.318 s | 6.115 | 1,570,242,560 | 361,293,250 | `8f166fe1db723cb002144157ccbdcb0b57e516d673fef1ac0e510e39682fae9d` |

Conservative linear projections were 18.99 minutes and 1.84 GiB for the
eight-minute 1080p case, and 17.50 minutes and 1.35 GiB for the four-minute
2.7K case. Both used less than 1.6 GB peak cgroup memory, so both maximum gates
opened. The maximum cases then produced:

| Case | Task wall | Render wall | Effective fps | Peak cgroup memory | Output bytes | SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| 1080p25, 480 s | 750.432 s | 710.141 s | 16.898 | 2,978,852,864 | 1,967,526,968 | `4d56f8f6d46cdb2760c25260e739ef7d47f9f037225aac616cd0fbf9c529a3da` |
| 2.7k25, 240 s | 882.436 s | 841.657 s | 7.129 | 2,884,014,080 | 1,438,775,896 | `c18c34b252472b39ffc59a3e5c2eeacb6af8e3c059c6835a13fd42eed85d0288` |

Both finished below 15 minutes, 3 GB peak cgroup memory, and 2 GB output.
Maximum JFR evidence recorded 199,650 and 241,293 allocation samples, 2,400
and 2,006 garbage collections, and 4,800 and 4,012 heap summaries. The staged
runner's conservative USD 0.673768 total includes the successful matrix, prior
attempts, cancellation overhead, and a deliberately rounded-up prior-spend
allowance.

## Consequences

Memory remains bounded by one frame buffer, the JVM/FFmpeg working sets, and
the temporary media files rather than duration-sized image data. Standard MOV
finalization needs local space and prevents streaming the unfinished container
directly to object storage; the measured headroom justifies that tradeoff.
Interrupted jobs may leave lifecycle-managed cloud objects, but zero retries
prevent duplicate compute and resume validation prevents silently accepting a
partial stage.

The retained maximum MOVs are evidence for the manual DaVinci Resolve import
and compositing check in GitHub issue #3; that human check is not part of this
automated decision.
