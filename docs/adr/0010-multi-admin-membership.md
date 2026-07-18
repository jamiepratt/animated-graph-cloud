# 0010: Support multiple membership administrators

- Status: Accepted
- Date: 2026-07-18

## Context

The owner was previously the only identity allowed to manage the Firestore
membership allowlist. That made routine user authorization depend on one
account, even though the membership record already carried a role.

## Decision

Keep one non-revocable `owner` selected by `AGG_OWNER_EMAIL`, and add an
`admin` role. `AGG_ADMIN_EMAILS` is a comma- or semicolon-separated runtime
setting. Startup transactionally ensures each configured address has an active
`admin` membership, promoting an existing active member without changing its
membership generation. A new or reactivated admin receives a new generation and
must complete Google login again.

Owners and active admins can list, add, and revoke memberships through the same
CSRF-protected browser and JSON routes. The owner remains the only owner and
cannot be revoked through those routes. Removing an address from
`AGG_ADMIN_EMAILS` does not revoke it; an owner or admin must explicitly revoke
the membership, preserving the existing generation-bound cleanup contract.

## Consequences

The live authorization check accepts `owner` or `admin` roles from the active
Firestore record, so role changes take effect on the next request without
trusting stale session role claims. Deployments must configure the intended
administrator set explicitly. Production deployment keeps the multi-admin
audience rather than silently broadening the previous owner-only release.
