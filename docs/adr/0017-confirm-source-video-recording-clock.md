# 0017: Confirm the selected source video recording clock

- Status: Accepted
- Date: 2026-07-23

## Context

The full-source editor needs absolute video clock labels, but Google Drive
upload time is not recording time and container timestamps are inconsistent.
Some containers provide an explicit offset, some provide only a local date and
time, and movie and track values can disagree. Reading an entire source only to
look for a timestamp would violate the service's privacy and resource bounds.

## Decision

Selecting a source starts an authenticated advisory inspection alongside
browser playback. The service reads at most the first and last 256 KiB of the
original, with a three-second timeout on each Drive request. Bytes are held only
in memory for parsing and are never persisted. Drive `createdTime` is never
requested or used.

Credible explicit-offset candidates are preferred over offset-free candidates.
Conflicting candidates remain visible and have no automatic winner. Missing,
malformed, untrustworthy, or unavailable metadata falls back to manual entry.

The user must confirm an editable recording start and a valid IANA video
timezone. A fixed offset does not satisfy the timezone requirement. The
confirmed instant and zone are stored in `sourceVideo.recordingStartAt` and
`sourceVideo.timeZone`.

The full-source player's transport clock, ticks, tooltip, playhead accessible
value, and source begin/end summary use the confirmed video clock. The rendered
graph axis remains timer-relative or section-relative.

## Consequences

Container metadata accelerates entry without becoming automatic truth. The
editor can label DST changes and local-midnight crossings using the confirmed
IANA zone. Inspection remains bounded, owner-bound, stateless, and independent
of durable rendering.
