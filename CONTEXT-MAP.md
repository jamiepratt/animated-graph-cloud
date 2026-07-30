# Context map

| Area | Source of truth |
|---|---|
| Product and security invariants | `CONTEXT.md` |
| Local development and verification | `README.md`, `docs/testing.md` |
| Development infrastructure | `infra/dev/` |
| Production infrastructure | `infra/prod/` |
| Delivery scope and dependencies | GitHub Issues |
| API contract | `docs/openapi.yaml` |
| Main release history | `resources/agg/main-version.edn`, `CHANGELOG.md` |
| Proto release history | Proto release stream: proto version resource, `docs/proto/CHANGELOG.md` |
| Production operation and release evidence | `docs/production-runbook.md`, `docs/release-acceptance.md` |
| Architectural decisions | ADRs added with the implementing change |
| Recording-clock authority | `docs/adr/0017-confirm-source-video-recording-clock.md` |
| Frame-accurate source trimming | `docs/adr/0018-trim-non-seekable-source-on-frame-boundaries.md` |
| Canonical wizard state, Project JSON boundary, and explicit save semantics | `docs/adr/0019-canonical-wizard-state-and-project-boundary.md` |
| Completed-output playback privacy boundary | `docs/adr/0020-stream-completed-h264-output-for-browser-playback.md` |
| Independent release identities and tags | `docs/adr/0021-independent-release-identities.md` |
| Production private-video-preview contract and proto isolation | `docs/adr/0022-isolate-production-private-video-previews.md` |
