# 0006: Isolate Google login from Drive delivery authorization

- Status: Superseded by ADR 0013
- Date: 2026-07-17

## Context

Named users need application login, Picker-compatible access to files they
select, and delivery into their own Drive. A broad Drive scope, a refresh token
in plaintext, or trusting a caller-supplied Cloud Tasks header would make
public browser ingress unsafe. OAuth callbacks must also survive Cloud Run
scale-to-zero and instance changes.

## Decision

Google login and Drive authorization are separate authorization-code flows.
Login requests exactly `openid email profile`; Drive requests exactly
`drive.file`, offline access, and explicit consent. Both use independent PKCE
S256 verifiers and opaque state. The verifier, flow, expiry, state, and any
authenticated user are carried in an HMAC-signed, Secure, HttpOnly, SameSite
cookie, so callback validation is stateless across API instances. Login creates
a twelve-hour signed session, and every authenticated request rechecks the
current lowercase email allowlist. Job ownership is bound to the Google subject
and never exposed in the public job resource.

Production Firebase Hosting rewrites forward only the specially named `__session`
cookie to Cloud Run, so the signed browser envelope carries both the session token
and the in-progress OAuth state.

The OAuth web-client JSON, session key, and restricted Picker API key are
Secret Manager values. Refresh tokens are encrypted directly with the Warsaw
`drive-refresh-tokens` KMS key before Firestore storage. API and renderer
identities receive only secret access and KMS encrypt/decrypt permissions they
need. An expired or revoked refresh token marks the stored grant revoked and
requires the user to reconnect Drive.

The first Drive grant creates the public product folder `Alpha Compose` and stores its ID; later
grants verify and reuse it. The Picker bridge receives a short-lived refreshed
access token in a no-store page and uses the same `drive.file` grant. Its
same-origin entrypoint receives selected file metadata from the popup, while a
directly opened Picker displays the selection itself. The bridge does not
download selected content or widen the server grant. The Picker key is
API-restricted solely to `picker.googleapis.com`; browser-referrer restrictions
cannot be used because developer-key validation occurs inside Picker's
`docs.google.com` iframe. Its Secret Manager value contains the exact key bytes
without a trailing newline.

Before a renderer uploads bytes, it asks Drive for one generated file ID and
atomically reserves that ID in Firestore under the job ID. The resumable session
URI and completion state are durable. Retries query the saved session offset;
a fresh session instead sends byte zero immediately because probing a new Drive
session can invalidate it. An invalid or expired recovered session is replaced
while retaining the preallocated file ID, and a completed delivery is a no-op.
The local MOV is delivered before cleanup.

Browser ingress becomes public only after the authenticated application
revision and secrets are configured. User routes require a signed session.
Internal dispatch verifies the Google-signed OIDC token, issuer, exact service
audience, verified email, and dedicated Cloud Tasks service account; the task
name header alone grants nothing. `/health` remains intentionally public.

## Consequences

The service can scale to zero without losing OAuth state, and allowlist removal
takes effect immediately despite an unexpired session. Drive retries cannot
create a second output resource. Testing-mode Google refresh tokens may expire
after seven days, so reconnect behavior remains part of acceptance. Generic
OAuth branding, client creation, redirect registration, and consent remain
Google Auth Platform operator actions; no unsupported generic management API is
used.
