# 0013: Combine Google login and Drive authorization

- Status: Accepted
- Date: 2026-07-19
- Supersedes: ADR 0006

## Context

Every durable Alpha Compose render is delivered to Google Drive. The previous
split flow issued a browser session after identity login and treated Drive as
optional. It could therefore accept a job before discovering asynchronously
that no usable delivery grant existed. Requiring consent on every separate
Drive connection also repeated Google's consent screen unnecessarily.

## Decision

Use one authorization-code flow at `/v1/auth/login/start` and
`/v1/auth/login/callback`. It requests exactly `openid`, `email`, `profile`, and
`drive.file`, with PKCE S256, offline access, opaque state, and a signed
short-lived state cookie. Routine login does not set `prompt=consent`.

The callback verifies the ID token and active membership, requires the approved
identity scopes plus `drive.file` while accepting Google's equivalent normalized
identity scope names, rejects every other scope, obtains a refresh token or validates an
existing encrypted token for the same Google subject, encrypts new refresh
tokens with KMS, discovers or creates the user's `Alpha Compose` folder, and
persists the grant. It issues the browser session only after every step
succeeds. Folder discovery makes a retry after a persistence failure reuse the
folder created by the earlier attempt.

New sessions contain a combined-authorization marker. A legacy session without
the marker receives one refresh-backed grant validation and is replaced with a
marked session only after success. An unusable grant removes the session and
sets a signed, non-session recovery marker. The blocking recovery action starts
the same login flow with consent; a caller-supplied recovery query without that
signed marker cannot force consent.

Every durable job submission preflights Drive before writing a job, regardless
of whether the request includes a source video. Browser Drive failures clear
the session and return an explicit `Continue with Google` action. Personal API
tokens remain valid for non-Drive routes, while Drive-backed routes return
`drive_grant_required` synchronously.

The separate `/v1/auth/drive/start` and `/v1/auth/drive/callback` routes are
removed without aliases. The restricted Picker continues to use the same
`drive.file` grant.

## Consequences

The browser cannot enter an authenticated state without a persisted, usable
Drive delivery grant. Routine returning users can reuse an existing refresh
token without repeated consent. Revocation recovery is explicit and cannot be
triggered by an unsigned query alone. OAuth transactions in progress across a
deployment that removes the old callback must be restarted.

Production release still requires an external Google Auth Platform checkpoint:
the combined callback and exact published scope set must be confirmed, the old
Drive callback URI must be removed after deployment, and OAuth publication and
owner smoke evidence remain tracked by issue #48.
