# 0008: Bind access and work to revocable membership generations

- Status: Accepted
- Date: 2026-07-17

## Context

An environment allowlist can reject a removed email, but it cannot give the
owner a usable administration workflow. Re-adding the same email would also
resurrect a previously signed session unless the application distinguishes the
old membership from the new one. Job submission needs a stronger boundary than
a check before its Firestore transaction, or a revocation can race the write and
leave new work active.

## Decision

`AGG_OWNER_EMAIL` transactionally bootstraps exactly one active owner record.
Startup serializes owner changes through a fixed administration record. When
the configured email changes, every previous owner is atomically demoted to a
revoked member and its bound subject is removed; unrelated members are left
unchanged. Re-adding a former owner therefore creates a new membership
generation and requires a new Google login. Restarting with the same configured
owner preserves its current generation and subject.

The owner manages member records through session- and CSRF-protected JSON or
HTMX routes. Each activation has a random membership generation. Google login
atomically binds the active email and generation to one Google subject.
Sessions and personal tokens carry that generation, and every use rechecks the
active Firestore record. Re-adding any revoked email creates a new generation
with no bound subject, so old sessions remain invalid and a new Google login is
required.

Revocation first marks the member inactive. It then revokes every stored
personal token, deletes the encrypted Drive grant, and cancels queued,
launching, running, or cancellation-requested jobs for the bound subject. An
authenticated job submission reads the exact member generation in the same
Firestore transaction that writes the job and idempotency record. Therefore a
submission committed before revocation is found by the cancellation scan, while
a transaction racing after revocation conflicts, retries, and is rejected.
Retrying a pre-revocation job checks its stored generation in the same way. A
Drive callback also saves its encrypted grant in a transaction that reads the
member generation, preventing a racing callback from restoring credentials
after the revocation delete.

Successful add and revoke actions emit structured `security` events. Events use
the SHA-256 member document identifier and aggregate side-effect counts; they do
not include email addresses, Google subjects, telemetry, tokens, Drive
credentials, filenames, or signed URLs.

## Consequences

The Firestore membership record is the live authorization source for browser
sessions and personal tokens. Deploying this decision invalidates legacy
credentials that do not contain a membership generation. Revoked members can
return only after an owner action followed by fresh Google login and, for Drive
delivery, a fresh `drive.file` authorization. Changing `AGG_OWNER_EMAIL`
immediately invalidates the former owner's membership-bound sessions and tokens
without disturbing ordinary active members.
