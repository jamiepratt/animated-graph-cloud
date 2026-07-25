# 0019: Keep one canonical in-memory wizard state

- Status: Accepted
- Date: 2026-07-25

## Context

The compose page previously derived requests directly from a long collection of
DOM controls. A branching wizard needs stable navigation, route changes,
conditional overlay steps, Review, and a future portable Project JSON format
without losing work or weakening the private-data boundary.

## Decision

One canonical wizard state owns the active route, current and visited semantic
step IDs, shared input, synchronization and optional-overlay decisions, route
and overlay drafts, validation, completion, and the projected `RenderRequest`.
Pure functions derive the active route matrix, navigation eligibility,
completion, invalidation, and request projection.

Changing the output route or optional overlays keeps the inactive draft in
memory. Only the active route and selected overlays contribute to the projected
request. The DOM is an input and presentation adapter for this state, not a
second workflow model. Existing preview, durable job, and renderer endpoints
continue to receive the unchanged `RenderRequest` contract.

Wizard state is memory-only. It is not written automatically to browser
storage, cookies, Firestore, or another server store. A future Project JSON
export may serialize an explicitly allowlisted, versioned representation after
a user action. That envelope is separate from the nested `RenderRequest` and
must exclude credentials, CSRF values, signed URLs, playback position,
recording-clock candidates, previews, jobs, and results.

## Consequences

Route changes can preserve private user work while preventing inactive data
from reaching preview or render APIs. Stable step IDs support accessible
navigation and future import without coupling saved projects to displayed step
numbers. Reloading or closing the page still discards the workflow unless the
user explicitly downloads Project JSON after that feature is implemented.
