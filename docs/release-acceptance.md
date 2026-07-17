# Alpha Compose release acceptance

This matrix separates repository automation from production, manual, and
third-party evidence. Copy `release-evidence.template.json` for each candidate;
do not edit the template or mark a check complete without a durable result URL,
artifact, screenshot, or operator note.

## Automated repository evidence

Run `script/release_acceptance.sh` from a clean checkout. It performs Clojure
lint/tests/build, validates development and production Terraform without remote
backend access, lints OpenAPI, scans production configuration for credential
patterns, builds and smoke-tests the container, and fails on unfixed HIGH or
CRITICAL image vulnerabilities.

The Clojure suite covers rendering and media contracts, all supported input formats,
admission ceilings, concurrency leases, cancellation, retry, revocation, and recovery
with local implementations or owned emulators. This is repository evidence, not proof
that those paths work in production.

The script never deploys, activates Firebase, publishes OAuth, contacts the
production application, or runs a costed render. Preserve its terminal log and
commit SHA under `automated.repositoryAcceptance`.

## Manual/external production evidence

All rows start `not-run`. Record exact candidate commit and immutable image digest.
The owner-only production smoke is evidence only after the approved workflow
and authenticated owner check complete.

| Area | Required evidence |
|---|---|
| Legal and identity | Owner/legal approval of the public homepage, Privacy policy, and Terms of service; public URLs captured. |
| Firebase and domain | Owner acceptance of Firebase Terms, Firebase project association, Cloudflare DNS records, domain verification, and valid TLS for `alphacompose.com`. |
| OAuth brand verification | Alpha Compose brand published in the production Google project with exact homepage, privacy/terms URLs, authorized domain, callbacks, and only `openid email profile drive.file`. |
| Owner-only production smoke | Approved GitHub `production` environment run; owner login works; membership view contains no non-owner active member. |
| Maximum-duration renders | Both `1080p25` and `2.7k25` maximum-duration outputs complete within the configured one-task, zero-retry, 60-minute envelope; capture job execution, output checksum, timing, memory, and cost. |
| Input matrix | Polar CSV, Garmin FIT, OxiWear heart-rate CSV, optional OxiWear SpO2, timer, and PNG watermark each produce the expected production overlay. |
| DaVinci Resolve | Manual DaVinci Resolve playback and alpha-composite inspection of both maximum presets, including seeking, duration, transparency, and heartbeat audio. |
| Google Drive | Manual Google Drive selection and delivery check using only `drive.file`; repeated worker delivery resumes the same file rather than duplicating it. |
| Concurrency and admission | At most five live leases execute, the sixth request is refused, daily submission and PLN 400 monthly admission return their stable errors, and already-running work is not killed. |
| Lifecycle and security | Production cancellation, retry, member revocation, stale-lease reconciliation, accepted-execution adoption, and Drive reauthorization are observed. Record cancellation, retry, revocation, and recovery separately. |
| Monitoring | Dashboard and each alert signal are visible; notification delivery to `me@jamiep.org` is confirmed without exposing protected data in logs. |
| Rollback | Re-release a known-good commit or close public ingress using the runbook, then restore the accepted candidate; capture both workflow or audit-log records. |

## Costed concurrency helper

`script/production_load_test.sh` submits at most five owner requests in parallel.
It refuses to run without an explicit cost acknowledgement, an owner personal
token, and a reviewed request JSON. Run it separately for each intended fixture;
it does not poll, validate output media, test the sixth-request rejection, or
write evidence automatically.

## Release decision

Release remains owner-only until every applicable production row has evidence,
legal copy is approved, OAuth is published, rollback has been exercised, and no
unresolved HIGH/CRITICAL security finding remains. Adding the first member is a
separate owner decision after this matrix is accepted.
