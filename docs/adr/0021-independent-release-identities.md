# 0021: Track main and proto releases independently

- Status: Accepted
- Date: 2026-07-30

## Context

Alpha Compose main and Alpha Compose Proto deploy from different release streams.
They need human-readable semantic versions without losing the immutable source
identity needed to verify a running container. Retroactive versions would invent
history, while one shared version would couple unrelated main and proto changes.

Public changelogs must describe shipped user-visible behavior. Repository-only
future notes must not appear publicly, and a production container must never
present a development or malformed build identity.

## Decision

Main and proto own separate semantic-version resources, Markdown changelogs,
deployment verification, and Git tag namespaces. Main starts at `0.6.0` and
uses `vX.Y.Z`; proto starts at `0.8.0` on its own release stream and uses
`proto-vX.Y.Z`.

Before 1.0, user-visible additions and breaking changes bump the minor version,
fixes bump the patch version, and internal-only changes do not require a bump.
Each changelog retains an `Unreleased` section in its repository source while
the public renderer removes that section and escapes raw HTML.

Docker accepts the exact 40-character lowercase Git commit as a build argument
and retains it in `AGG_BUILD_COMMIT`. Complete HTML pages display the semantic
version and the first seven commit characters as one link to that app's
`/changelog`. Local builds use `build dev`. Production runs in strict release
mode and refuses a missing, malformed, or `dev` build identity.

The deployment workflow verifies the public changelog for the exact commit
before the operator tags that deployed commit. Neither release stream creates a
GitHub Release.

## Consequences

Users can identify the release and immutable build from every complete page.
Operators can connect a public deployment to one exact source commit without
exposing future notes. Main and proto can bump and deploy independently.

Changing a semantic version requires updating its newest released changelog
heading in the same commit. A deployment may complete without a new semantic
version for internal-only work, but it still displays its new immutable build.
Tags remain a post-deployment checkpoint and must never be moved to a different
commit after verification.
