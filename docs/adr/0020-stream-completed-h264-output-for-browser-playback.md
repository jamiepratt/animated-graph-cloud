# 0020: Stream completed H.264 output for browser playback

- Status: Accepted
- Date: 2026-07-25

## Context

Successful durable H.264 jobs already deliver an MP4 into the member's Drive
folder and expose the retained Drive link in the completion card. The product
now also needs inline browser playback of that delivered output without making
the Drive file public, copying it to a second location, or persisting playback
state outside the existing durable job record.

The same privacy constraints as selected-source playback still apply. Drive
file IDs, OAuth tokens, signed authority, filenames, and media bytes must stay
out of URLs, JavaScript-visible tokens, and logs. Playback must remain
stateless across Cloud Run instances and must fail closed when membership or
ownership changes.

## Decision

The completion card asks the API for a completed-output playback session at a
new owner-scoped job endpoint. The API resolves the Drive file through the
durable job record, so only the owner of a succeeded H.264 job can mint
authority. ProRes outputs and non-terminal or failed jobs never receive
browser playback authority.

The service signs a separate completed-output playback envelope into the
Firebase-compatible `__session` HttpOnly cookie. That envelope contains a
distinct purpose, the Google subject, durable job UUID, playback UUID,
authoritative MIME type, byte size, and one-hour expiry. The returned media
URL contains only the durable job UUID and a random playback UUID. No playback
state is persisted server-side.

Each media request revalidates the active member session, cookie signature,
owner, job UUID, playback UUID, purpose, and expiry, then resolves the current
Drive file ID through the durable job again before refreshing restricted Drive
access. Range parsing, 8 MiB open-ended and suffix caps, upstream 206
truthfulness checks, `no-store`, and `nosniff` behavior reuse ADR 0016.

## Consequences

Completed-output playback stays stateless, owner-bound, and compatible with
the production Firebase cookie boundary. The UI keeps the retained Drive link
while adding inline playback for the ready H.264 result. Reissuing playback
authority replaces the previous playback cookie without affecting the signed-in
session.

Inline browser playback is limited to delivered MP4 outputs. Other durable
formats remain downloadable through Drive only. If membership is revoked, the
session expires, the job no longer belongs to the caller, or Drive no longer
serves truthful byte ranges, playback fails closed.
