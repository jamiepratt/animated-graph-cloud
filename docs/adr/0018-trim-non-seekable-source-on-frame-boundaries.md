# 0018: Trim non-seekable source on output frame boundaries

- Status: Accepted
- Date: 2026-07-23

## Context

Source videos stream directly from Google Drive and cannot be randomly sought
or persisted. The output editor still needs exact start and end frames, matching
source audio, and source-backed preview frames.

## Decision

The locked 25 fps timeline accepts output durations in whole 40 ms frames.
For source composites, `sectionStartAt - sourceVideo.recordingStartAt` is a
nonnegative whole-frame trim offset. Known Drive duration bounds the selected
end. Preset limits remain 12,000 frames for 1080p25 and 6,000 frames for
2.7k25.

FFmpeg receives the Drive stream on `pipe:0`. Video uses `trim` and source audio
uses `atrim`, followed by timestamp rebasing. These output-side filters decode
and discard preceding bytes without random access or persistence. Gallery
selection adds the trim-frame offset to every requested output frame and stops
after the bounded selection is emitted.

The browser keeps the complete source playable. Two output handles snap to
frames, shade unused source, update section timestamps, and preserve valid raw
JSON ranges. Correcting the confirmed recording start shifts camera-relative
section, timer, and manual camera-sync instants while retaining the
activity-device synchronization instant.

## Consequences

H.264, ProRes 422, every audio mode, and selected-source previews share one
source-relative interval. Decode cost grows with trim offset because the input
is non-seekable, but existing preview and durable deadlines remain authoritative
and terminate both producer and consumer cleanly.
