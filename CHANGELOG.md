# Changelog

User-visible additions, changes, and fixes for Alpha Compose.

## Unreleased

### Added

- Added private video preview preparation for browser-incompatible source videos. The original Drive file is unchanged, each private preview expires after 24 hours, and every charged attempt uses the displayed processing allowance.

## 0.6.0 - 2026-07-30

### Added

- Added a public, release-specific changelog and visible semantic version plus immutable build identity across the complete Alpha Compose web experience.

### Changed

- Local builds identify themselves as `build dev`; deployed builds identify the exact source commit using its first seven characters.

## Before version tracking - through 2026-07-30

### 2026-07-30

- Added direct, resumable Google Drive source-video uploads, an accessible branching compose wizard, and a choice of 8-bit or 10-bit transparent ProRes overlays.

### 2026-07-25

- Added explicit Project JSON export and import, route-aware review, frame-accurate output selection, optional timer and SpO2 overlays, and owner-bound playback of completed H.264 videos.

### 2026-07-23

- Added private Google Drive source playback, shared-clock and matching-moment synchronization, recording-clock confirmation, keyboard and fullscreen timing controls, contextual help, and the public product FAQ.

### 2026-07-22

- Added the Alpha Compose finished-video workflow, authenticated Google Drive delivery, key-moment previews, early-access requests, member administration, personal API tokens, and operational logs.

### Earlier milestones

- Added bounded transparent ProRes rendering with generated heartbeat audio and support for Polar CSV, Garmin FIT, and OxiWear heart-rate and SpO2 activity data.
