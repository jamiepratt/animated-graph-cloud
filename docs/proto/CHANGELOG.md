# Alpha Compose Proto changelog

Released changes for the independent Alpha Compose Proto playback harness.

## Unreleased

## 0.8.0 - 2026-07-30

### Added

- Authenticated fixed-folder source discovery and private owner-bound playback.
- Recording-clock inspection, range-aware media streaming, and bounded browser diagnostics.
- On-demand private H.264 browser previews with durable preparation, cancellation, retry, reconciliation, and cache reuse.

### Changed

- Browser previews now adapt supported source streams while preserving requested playback ranges.
- Proto infrastructure, deployment, identity, and spend controls remain isolated from the main Alpha Compose release.

### Fixed

- Recovered media loads clear stale failure diagnostics.
- Timecode and auxiliary-stream handling no longer prevents otherwise valid browser previews.

## Pre-version milestones

### 2026-07-29

- Added a batched browser playback capability matrix, authenticated range forwarding, and correlated playback observability.

### 2026-07-28

- Established the separate proto service, fixed-folder playback harness, private deployment workflow, and isolated Terraform state.
