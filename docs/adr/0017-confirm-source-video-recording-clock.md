# 0017: Confirm the selected source video recording clock

- Status: Accepted
- Date: 2026-07-23

## Context

The full-source editor needs useful clock labels, but it cannot assume an
absolute video clock. Google Drive upload time is not recording time and
container timestamps are inconsistent. Some containers provide an explicit
offset, some provide only a local date and time, and movie and track values can
disagree. Reading an entire source only to look for a timestamp would violate
the service's privacy and resource bounds.

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

Activity-data timestamps are the synchronization authority. In user-facing
copy, an unqualified `time` or `current time` means activity-device time.
Camera, video, and output-clock values must be labelled explicitly. In
`shared-clock` mode the camera already follows the activity clock and no
synchronization point is needed. The user confirms the advisory recording start
and a valid IANA video timezone. A fixed offset does not satisfy the timezone
requirement.

`manual-anchor` mode does not ask the user to enter or confirm an absolute
camera time. Before a match, every full-source timeline label is elapsed time
from the beginning of the source. The user's primary synchronization state is
the selected source position in seconds, the matching activity-data instant,
and the selected IANA display timezone. The browser derives:

`recording start = activity-data instant - selected source position`

For compatibility with the existing renderer contract, it submits the derived
start as `sourceVideo.recordingStartAt`, the IANA zone as
`sourceVideo.timeZone`, and the activity-data instant as both
`telemetrySyncAt` and `cameraSyncAt`. Derived values are projections, not
separately confirmed user input.

After the match, the full-source transport clock, ticks, tooltip, and accessible
playhead value change to synced recording time in the selected IANA zone. A
persistent label says either `Elapsed time` or
`Synced recording time · <zone>`, including in fullscreen, and the transition
is announced politely. The rendered graph axis remains timer-relative or
section-relative. The rendered local clock uses the explicitly selected
video/output timezone. These named video/output views do not make camera
metadata the synchronization authority.

## Consequences

Container metadata accelerates the shared-clock route without becoming
automatic truth. Manual synchronization remains usable when the camera clock
is wrong or unknown. The editor can label DST changes and local-midnight
crossings using the selected IANA zone. Inspection remains bounded,
owner-bound, stateless, and independent of durable rendering. Help and
interface copy can lead with the more reliable activity-device clock while
still showing video time where the distinction is useful and explicit.
