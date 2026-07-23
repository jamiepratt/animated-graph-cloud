# 0016: Stream selected Drive video for browser playback

- Status: Accepted
- Date: 2026-07-23

## Context

The compose UI needs a timeline editor foundation that can play the exact
Google Drive video a member selected. The service holds only the restricted
`drive.file` grant. Drive IDs and OAuth access tokens must not appear in media
URLs or logs, playback must survive Cloud Run scale-to-zero, and Firebase
Hosting forwards only the specially named `__session` cookie.

Browsers seek with HTTP byte ranges. Some renderable source formats are not
portable browser media, and transcoding would add durable state, compute cost,
and startup delay to this first editor slice.

## Decision

An authenticated, CSRF-protected request revalidates selected-source metadata.
Only an original `video/mp4` is admitted to browser playback. Other supported
render inputs stay selected and receive a playback-only unsupported response.
The player does not transcode.

The service creates a random playback UUID and signs an owner-bound envelope
containing its purpose, Google subject, playback UUID, Drive file ID,
authoritative MIME type, byte size, and an expiry one hour in the future. That
authority is nested with the existing session in the Firebase-compatible
Secure, HttpOnly, SameSite `__session` cookie. The returned media URL contains
only the random UUID. Playback state is never persisted.

Each media request revalidates the active member session, cookie signature,
owner, path UUID, purpose, and expiry before refreshing restricted Drive
access. The proxy accepts one bounded, open-ended, or suffix `Range`. Open-ended
and suffix responses are capped at 8 MiB. It asks Drive for the exact resulting
range and requires status 206 plus matching `Content-Range` and
`Content-Length` before streaming. Invalid ranges return 416 with a truthful
unsatisfied `Content-Range`. Media responses are no-store and nosniff.

The UI uses the original source audio and a stable 16:9 stage. Output fit maps
to contain for letterbox or pillarbox and centered cover for crop. Browser
decode failure disables playback without clearing the render selection.

## Consequences

Playback remains stateless across instances and compatible with the production
Firebase cookie boundary. Drive IDs and OAuth tokens stay out of media URLs,
application logs, and browser JavaScript. Selecting a second video replaces
the previous playback authority.

The browser player initially supports MP4 as delivered. Codec incompatibility
is detected by the browser and explained as a playback-only limitation.
Transcoding, timeline markers, zoom, and pan remain outside this decision.
