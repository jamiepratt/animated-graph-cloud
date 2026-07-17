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
revoked member; unrelated members are left unchanged. A bound former owner
retains its subject only as durable cleanup identity, accompanied by a pending
revocation record keyed by its membership generation. The revoked membership
immediately invalidates sessions and authenticated writes. Reactivation and
owner bootstrap reject that generation until cleanup is complete.

After token, Drive credential, and job components exist, startup reconciles
every pending owner rotation before returning application dependencies. It
revokes tokens, deletes the Drive grant, and cancels active jobs, then uses a
Firestore compare-and-set to remove both the pending record and the subject only
if the same revoked generation is still current. A cleanup failure leaves both
durable records intact and fails startup closed. Repeated or concurrent startup
is safe: cleanup operations are idempotent, only the compare-and-set winner
emits success, and a racing re-add cannot clobber cleanup identity. Re-adding a
former owner after cleanup creates a new membership generation and requires a
new Google login. Restarting with the same configured owner preserves its
current generation and subject.

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
immediately invalidates the former owner's membership-bound sessions without
disturbing ordinary active members; startup does not serve traffic until its
tokens, Drive grant, and active jobs have also been cleaned up.
