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

The accepted rerun uses the Linux/AMD64 image manifest
`europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:1e4cbd1e9af97487286af0a14791b5f08bead56363fa2f06ed4b45b669da44b6`
and object root
`gs://animated-graph-cloud-jp-temporary/renderer-spike/20260716T231234Z`.
The exact immutable image was built locally after a successful CI deployment;
no SLSA, SBOM, or build-provenance attestation is claimed. Artifact Analysis
reported `FINISHED_SUCCESS` with continuous analysis active and zero critical
or high effective vulnerabilities (65 medium, 13 low, and 1 minimal). An
earlier ARM64 startup attempt produced no renderer artifact. The corrected
one-minute gates produced these results:

| Case | Task wall | Render wall | Effective fps | Peak cgroup memory | Output bytes | SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| 1080p25, 60 s | 76.429 s | 64.227 s | 23.355 | 5,373,894,656 | 290,643,089 | `2a268ac71863378d7542352f4a5c2f17f5c3e1ee97011c14fe7b2776e13d1b2a` |
| 2.7k25, 60 s | 132.401 s | 118.230 s | 12.687 | 10,465,345,536 | 454,511,039 | `3d110fe232040212c2e070e2071d4b1f3107c23b05a6c1b136d240d761bdd988` |

Conservative linear projections were 10.19 minutes and 2.17 GiB for the
eight-minute 1080p case, and 8.83 minutes and 1.69 GiB for the four-minute 2.7K
case. Both stayed within the release gates, so both maximum cases ran and
produced:

| Case | Task wall | Render wall | Effective fps | Peak cgroup memory | Output bytes | SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| 1080p25, 480 s | 524.627 s | 473.172 s | 25.361 | 7,675,768,832 | 2,327,694,978 | `b81ee768f1d4fc109a87e66db0515cd43bc29acb72bab7d3292f5271fe6a2199` |
| 2.7k25, 240 s | 482.442 s | 444.055 s | 13.512 | 11,897,987,072 | 1,821,740,649 | `ca9c7978946f2d74edca0112b1d5a606bc2174417d2aa1ad42ee34b5880828b3` |

Both finished below 9 minutes, 12 GB peak cgroup memory, and 2.33 GB output,
well inside the 60-minute, 30 GiB, and 18 GiB acceptance bounds. Maximum JFR
evidence recorded 50,837 and 34,450 allocation samples representing
188,324,011,088 and 98,350,707,792 sampled bytes, 652 and 490 garbage
collections, and 1,304 and 980 heap summaries. The recordings were 10,587,521
and 6,528,668 bytes. The staged runner's conservative USD 0.990827 total
includes the successful rerun and USD 0.688 of prior spend.

Both maximum MOVs validate as conventional seekable, non-fragmented ProRes
4444 `ap4h` containers. FFprobe decodes `yuva444p12le` with 16 alpha bits at
the required dimensions and 25 fps. Each contains AAC-LC stereo at 48 kHz with
the exact 192 kbps encoder target preserved in the report; sparse identical-
channel heartbeat samples produce observed averages of 73,186 and 73,271 bps.
Local downloads matched the report byte sizes and SHA-256 digests, and frame
decoding succeeded at the midpoint and final second of each output.

## Consequences

Memory remains bounded by one frame buffer, the JVM/FFmpeg working sets, and
the temporary media files rather than duration-sized image data. Standard MOV
finalization needs local space and prevents streaming the unfinished container
directly to object storage; the measured headroom justifies that tradeoff.
Interrupted jobs may leave lifecycle-managed cloud objects, but zero retries
prevent duplicate compute and resume validation prevents silently accepting a
partial stage.

The regenerated maximum MOVs were imported into DaVinci Resolve, sought near
their midpoints and ends, alpha-composited over a checkerboard, and played with
active audio meters. This agent-operated acceptance completes the media handoff
for GitHub issue #3. The owner accepted the visible near-diagonal synthetic
trace for this infrastructure spike; trace-shape fidelity remains tracked in
GitHub issue #4 and does not change this decision.
