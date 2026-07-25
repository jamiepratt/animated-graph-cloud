# 0017: Confirm the selected source video recording clock

- Status: Accepted
- Date: 2026-07-23

## Context

The full-source editor needs absolute video clock labels, but Google Drive
upload time is not recording time and container timestamps are inconsistent.
Some containers provide an explicit offset, some provide only a local date and
time, and movie and track values can disagree. Reading an entire source only to
look for a timestamp would violate the service's privacy and resource bounds.

Cross-device synchronization also needs one clear clock authority. Activity
data normally comes from a watch or phone whose time is set automatically from
a network, paired phone, location service, or satellite signal. Camera clocks
are easier to leave wrong and may drift.

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

Activity-data timestamps are the synchronization authority. In user-facing
copy, an unqualified `time` or `current time` means activity-device time.
Camera, video, and output-clock values must be labelled explicitly. In
`shared-clock` mode the camera already follows the activity clock and no
synchronization point is needed. In `manual-anchor` mode the matching point
maps the camera clock onto activity-data time.

The full-source player's transport clock, ticks, tooltip, playhead accessible
value, and source begin/end summary use the confirmed video clock. The rendered
graph axis remains timer-relative or section-relative. The rendered local clock
uses the explicitly selected video/output timezone. These named video/output
views do not make camera metadata the synchronization authority.

## Consequences

Container metadata accelerates entry without becoming automatic truth. The
editor can label DST changes and local-midnight crossings using the confirmed
IANA zone. Inspection remains bounded, owner-bound, stateless, and independent
of durable rendering. Help and interface copy can lead with the more reliable
activity-device clock while still showing video time where the distinction is
useful and explicit.
