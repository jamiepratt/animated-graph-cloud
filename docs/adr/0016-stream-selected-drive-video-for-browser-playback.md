# 0016: Stream selected Drive video for browser playback

- Status: Accepted; the no-transcoding consequence for browser-incompatible
  sources is superseded by ADR 0022
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
One read-only analysis request inspects the original source with bounded
`ffprobe` evidence. The browser decides direct playback from normalized
container and codec evidence using both `VideoDecoder.isConfigSupported()`
when available and `<video>.canPlayType()`. Direct playback proceeds only when
both checks agree playback is supported, or when WebCodecs is unavailable and
`canPlayType()` still supports playback. Unsupported direct playback keeps the
source selected, preserves timing state, and replaces the player with an
in-place explanation plus compact technical evidence. The player does not transcode.

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

The player can use additional original selected-source containers and codecs
when the browser proves direct support. Unsupported codec or container
combinations do not clear the render selection. ADR 0022 supersedes only this
decision's exclusion of transcoding by defining an explicit, private
preparation contract for browser-incompatible but renderable sources. Timeline
markers, zoom, and pan remain outside this decision.
