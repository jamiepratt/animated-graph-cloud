# 0007: Use server-rendered HTMX and HMAC-hashed personal tokens

- Status: Accepted
- Date: 2026-07-17

## Context

Private named users need the complete preview and durable-job workflow in a
browser, while automation needs credentials that do not reuse browser sessions.
The service must stay scale-to-zero and must not introduce a client-side
application framework or persist reusable bearer secrets.

## Decision

The authenticated root page renders HTML on the server and uses pinned HTMX
2.0.10 for preview, submission, polling, cancellation, retry, and personal-token
controls. Each returned job fragment contains its own next polling or command
hypermedia controls. The existing same-origin Google Picker bridge remains the
only custom browser JavaScript.

Signed, twelve-hour session cookies remain `Secure`, `HttpOnly`, and
`SameSite=Lax`. Every cookie-authenticated POST requires a signed, expiring CSRF
token bound to the current Google subject. The page sends that token through an
HTMX header. OAuth callbacks retain their separate signed state and PKCE
contract. Bearer requests do not use cookies and therefore do not use CSRF
tokens, but they still recheck the current email allowlist and job ownership.

A personal token consists of a random UUID selector and 256 bits of random
secret material. Creation returns the full value once. Firestore stores only
the selector, owner metadata, revocation state, and an HMAC-SHA256 made with the
Warsaw Secret Manager `token-hash-pepper`; list responses omit both the secret
and hash. Constant-time verification authenticates the bearer, and revocation
is owner-scoped.

All user-controlled content is HTML-encoded before it enters a page or fragment.
The HTMX dependency is version-pinned with subresource integrity and constrained
by the page Content Security Policy.

## Consequences

Cloud Run instances keep no UI, CSRF, or token state in memory. Rotating the
token pepper invalidates all personal tokens, which is an intentional emergency
revocation mechanism. Normal individual revocation remains durable in
Firestore. A just-created token cannot be recovered after its response is lost;
the user creates a replacement.
