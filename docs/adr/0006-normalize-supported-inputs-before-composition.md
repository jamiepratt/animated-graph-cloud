# 0006: Normalize supported inputs before composition

- Status: Accepted
- Date: 2026-07-17

## Context

The Polar path established one camera-aligned heart-rate timeline, but v1 also
accepts Garmin FIT and OxiWear heart-rate data, optional OxiWear SpO2, a timer
interval, and a user PNG watermark. These inputs cross timezone and local-date
boundaries and must not make frame memory grow with render duration.

## Decision

`polar-csv`, `garmin-fit`, and `oxiwear-hr-csv` all normalize to ordered
absolute-instant heart-rate samples before the existing synchronization anchors
map them onto section-relative seconds. Garmin FIT is base64 in the current JSON
transport and is decoded with the official Java FIT SDK 21.205.0. OxiWear CSV
requires offset-bearing `reading_time`; naive local timestamps are rejected.
Absent samples are linearly interpolated and duplicate or reversed timestamps
are rejected.

Optional `oxiwear-spo2-csv` uses the same synchronization and coverage contract.
Its cyan 70–100% visual scale is separate from the fixed red 40–220 bpm scale.
The timer is validated inside the camera section and renders a clamped elapsed
value. A base64 watermark is accepted only after its PNG signature and IHDR are
checked; encoded and decoded bytes, width, height, and pixel count are bounded
before ImageIO decoding. The decoded image is reused for every frame.

CSV readers process one line at a time. FIT decoding dispatches record messages
from a bounded byte array. Primary telemetry and SpO2 each have a 10 MiB decoded
or UTF-8 ceiling, PNG has a 2 MiB ceiling, and every normalized series has a
900,000-sample ceiling. The JSON envelope accounts for FIT/PNG base64 expansion
and the optional second telemetry series.

## Consequences

All approved heart-rate formats feed the same interpolation, frame, audio, and
media contracts. UTC offsets, midnight crossings, malformed values, missing
samples, and optional-input coverage have deterministic outcomes. Rendering
still retains one RGBA frame buffer, one decoded watermark, and bounded input
series; it never retains a frame collection. Complete golden previews and
container media checks lock both `1080p25` and `2.7k25` presets.
