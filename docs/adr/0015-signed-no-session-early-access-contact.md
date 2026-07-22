# 0015: Signed no-session early-access contact

- Status: Accepted
- Date: 2026-07-22

## Context

A verified Google user who is not an active member previously received only an
allowlist denial. Alpha Compose needs a bounded way for that person to ask to
test the product without turning the request into authentication, membership,
Drive authorization, or retained application data.

The verified email cannot be trusted when posted back by the browser. A retry
must not send duplicate mail, but Alpha Compose must not persist a request,
outbox, CRM record, or analytics event.

## Decision

Use a signed no-session contact flow available only from a verified
`not-allowlisted` login result. The callback issues a signed proof with the
distinct purpose `early-access-contact`, the normalized verified email, a
random opaque notification id, and an expiry of 10 minutes. The proof is posted
only in the form body. It grants no session or other application capability and
is rejected when missing, expired, tampered, or used for another purpose.

The server ignores the posted email and derives Reply-To from the proof. It
trims the optional Instagram handle and message, enforces their limits, and
sends one plain-text notification through the Resend HTTPS API. The provider
`Idempotency-Key` is derived only from the opaque notification id, so an exact
replay within Resend's idempotency window returns the original result without a
second email.

The Resend client uses bounded connection and request timeouts behind the
application notifier protocol. Only HTTP 200 with a nonempty response `id` is
delivery success. Failure telemetry is restricted to category, safe upstream
status, retryability, request id, and source location.

Alpha Compose does not persist early-access contact data in Firestore, logs,
analytics, or another application store. Personal data exists only during the
bounded request and in Resend and the recipient mailbox. Terraform creates the
`resend-api-key` container and grants only the API runtime payload access. The
normal workflows inject the secret plus explicit sender and recipient values.
Development verifies an enabled version before publishing an image. Production
requires a successful development deployment for the exact release commit
before any production mutation. The gate has only Actions and repository read
permissions, accepts only the trusted development workflow on `main` from this
repository, and fails closed for missing, unfinished, failed, cancelled,
timed-out, ambiguous, or wrong-commit results. Workflow response fields are
compared as data and never executed. After the gate, production applies the
complete Terraform plan first, then verifies the enabled version before
publishing an image. A targeted, reviewed Terraform bootstrap can create the
container and IAM in both environments without changing an application
runtime. Manual recovery dispatches development on `main` first and production
on `main` only after the exact development run succeeds; no dispatch input
bypasses the gate.

## Consequences

Provider idempotency supplies replay protection without adding an application
database. Replays after the provider's idempotency retention window are outside
the proof's 10-minute lifetime. Release requires manual Resend account creation,
sender-domain DNS verification, reviewed Terraform bootstrap, and enabled
development and production secret versions before application promotion.
