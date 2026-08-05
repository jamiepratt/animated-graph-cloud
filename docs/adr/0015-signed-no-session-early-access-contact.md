# 0015: Signed no-session product-updates signup

- Status: Accepted
- Date: 2026-08-05

## Context

Signed-out visitors need a bounded way to ask for Alpha Compose product updates
without authenticating or creating retained application data. The submitted
email cannot be trusted without server-side validation. A retry must not send
duplicate mail, but Alpha Compose must not persist a signup, outbox, CRM record,
or analytics event.

## Decision

The signed-out homepage issues a signed proof with the distinct purpose
`product-updates-signup`, a random opaque notification id, and a 10-minute
expiry. The proof is posted only in the form body, grants no application
capability, and is rejected when missing, expired, tampered, or used for another
purpose. The server normalizes and validates the submitted email.

One plain-text notification is sent through the existing Resend notifier. The
provider `Idempotency-Key` is derived only from the opaque notification id, so
an exact replay within Resend's idempotency window returns the original result
without sending a second email.

The Resend client keeps bounded connection and request timeouts. Only HTTP 200
with a nonempty response `id` is success. Failure telemetry contains only a
category, safe upstream status, retryability, request id, and source location.

Alpha Compose does not persist the submitted email in Firestore, application
logs, analytics, or another application data store. Personal data exists only
during bounded request processing and in Resend and the recipient mailbox. The
existing `resend-api-key` secret, sender address, recipient, IAM, and guarded
Terraform-first deployment sequence are reused.

## Consequences

Provider idempotency supplies replay resistance without adding an application
database. Replays after the provider's idempotency retention window are outside
the proof's 10-minute lifetime. No new secret, DNS, migration, Terraform import,
or other manual release checkpoint is required.
