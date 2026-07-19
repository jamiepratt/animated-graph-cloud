# 0004: Normalize Polar data on a stable render timeline

- Status: Accepted
- Date: 2026-07-17

## Context

The renderer spike proved the media and Cloud Run envelope with synthetic data,
but its per-frame autoscaling made slowly changing heart rate look like a steep
edge-to-edge line. Preview, video, and heartbeat audio also need one shared
interpretation of synchronization timestamps.

## Decision

The issue-4 API accepts bounded JSON containing Polar CSV, two synchronization
anchors, and a camera-clock section. Polar timestamps are mapped onto the
camera clock by the anchor offset. The requested section must use whole seconds,
fit its selected preset, and be fully covered by strictly increasing telemetry.
Heart rate is linearly interpolated at section boundaries and everywhere the
renderer or heartbeat synthesizer samples it.

Polar's numeric zero represents a sensor gap, not a heart rate. A render request
ignores those rows only when their timestamps are strictly outside its mapped
telemetry section. Their timestamps still participate in strict raw CSV
ordering. Zero at either boundary or inside the section remains malformed, as
do all other out-of-range values regardless of timestamp. The remaining valid
samples must still cover both section boundaries, so ignoring an irrelevant
sensor gap cannot manufacture coverage.

The production trace uses a fixed 40–220 bpm vertical scale. It shows the whole
section when it is at most 30 seconds; longer sections use a 30-second window
centered on the current frame and clamped at section boundaries. The PNG preview
is the midpoint frame from that same renderer. Heartbeats start at sample zero
and schedule each next beat from the interpolated heart rate at the current beat.

Java2D frame generation implements `FrameRenderer`; FFmpeg/FFprobe encoding and
verification implement `VideoEncoder`. One RGBA buffer is reused while frames
stream directly into FFmpeg. Only the requested midpoint becomes a PNG; no
per-frame PNG sequence or frame collection is created.

## Consequences

Fixed inputs produce a golden-stable preview for each supported Java2D runtime;
the macOS and Linux fixtures are kept separately because logical font rasterizing
is host-dependent. The production image and its acceptance fixture use Linux.
A slow heart-rate change remains proportionally slow and does not acquire a
misleading full-height slope. Values outside the visual 40–220 bpm range are
clipped at the trace boundary while their original values remain available to
audio timing and later composition work.
