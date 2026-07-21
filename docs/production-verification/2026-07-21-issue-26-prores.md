# Issue 26 production ProRes 422 acceptance

Date: 2026-07-21

Candidate commit: `cac8cea2e5260fea496f0021a3c645eed8091358`

## Guardrails

The owner approved exactly one production ProRes 422 attempt capped at PLN
1.25. A fresh Preview was required before Submit. No H.264 submission,
cancellation, retry, duplicate submission, second ProRes attempt, or full
DYN-B-1 composite was run.

Google Picker source selection was completed manually without inspecting its
credential-bearing frame content.

## Request and result

- The selected verified disposable source was longer than the bounded
  nine-second output.
- The 1080p25 request used ProRes 422 MOV, letterbox fit, and source plus
  heartbeat audio.
- Synthetic Polar telemetry increased from 120 to 140 bpm over nine seconds.
  The elapsed timer covered the first eight seconds.
- Preview succeeded and showed valid fitted-source and final-composite frames
  at `00:00.000` and `00:08.960`.
- Exactly one durable job was accepted as
  `3b644b52-3a22-447c-8b38-7d83164c4774`, attempt 1.
- The job reached `succeeded` and created one private Google Drive output. No
  Drive file ID, source file ID, account value, telemetry body, or credential
  is retained here.

## Drive and media verification

Drive metadata identified one new private `video/quicktime` output. It was
downloaded without overwrite and retained outside the repository in the
directory above the main working directory.

- Size: 200,117,502 bytes
- SHA-256: `95f6fd00c32776bf020a39244ce287dd4c0168284d8396411066a0709750985f`
- Container duration: 9.000 seconds
- Video: ProRes HQ `apch`, 1920x1080, `yuv422p10le`, 25 fps
- Audio: AAC-LC, 48 kHz, stereo
- Full FFmpeg video and audio decode completed without error.
- Decoded start and end frames retained the source framing and overlay. The
  visible timer and heart-rate values progressed from `T 00:00 / HR 120 /
  Timer 00:00` to `T 00:08 / HR 139 / Timer 00:08`.

The selected disposable source remains in Drive because deletion was not part
of this approval and no deletion was attempted through the Picker. The output
is retained as acceptance evidence. Temporary local validation frames were
deleted after inspection.

## Repository validation

`clojure -M:test` ran 305 tests with 2,057 assertions. It reported three
failures and one error in
`durable-submit-requires-fresh-owner-bound-successful-preview`: the pending
Preview submission returned `preview_required` instead of `preview_pending`,
then the test attempted to dispatch a missing job. An isolated rerun reproduced
the same result. No implementation change was made because issue #26 is a
production acceptance item, not an implementation worker item.

This run satisfies the ProRes 422 success and bounded Drive/media evidence
portion of issue #26. Cancellation, retry, and disposable-source cleanup remain
separate owner-approved exercises.
