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
The owner/admin production smoke is evidence only after the production
deployment workflow and authenticated owner or admin check complete.

| Area | Required evidence |
|---|---|
| Legal and identity | Owner/legal approval of the public homepage, Privacy policy, and Terms of service; public URLs captured. |
| Firebase and domain | Owner acceptance of Firebase Terms, Firebase project association, Cloudflare DNS records, domain verification, and valid TLS for `alphacompose.com`. |
| OAuth brand verification | Alpha Compose brand published in the production Google project with exact homepage, privacy/terms URLs, authorized domain, only the combined login callback, and exactly `openid email profile drive.file`. Confirm the old Drive callback was removed after deployment. |
| Owner/admin production smoke | Successful production deployment workflow run; owner and configured admin combined logins work without repeated routine consent; membership view exposes administration only to those roles. |
| Maximum-duration renders | Both `1080p25` and `2.7k25` maximum-duration outputs complete within the configured one-task, zero-retry, 60-minute envelope; capture job execution, output checksum, timing, memory, and cost. |
| Input matrix | Polar CSV, Garmin FIT, OxiWear heart-rate CSV, optional OxiWear SpO2, timer, and PNG watermark each produce the expected production overlay. |
| DaVinci Resolve | Manual DaVinci Resolve playback and alpha-composite inspection of both maximum presets, including seeking, duration, transparency, and heartbeat audio. |
| Google Drive | Combined sign-in, Picker opening, source selection, and durable delivery use only `drive.file`; retry after a failed grant save reuses the `Alpha Compose` folder; repeated worker delivery resumes the same output file. |
| Concurrency and admission | At most five live leases execute, the sixth request is refused, daily submission and PLN 400 monthly admission return their stable errors, and already-running work is not killed. |
| Lifecycle and security | Production cancellation, retry, member revocation, stale-lease reconciliation, and accepted-execution adoption are observed. Revoke Google authorization, confirm the session clears and durable submission is not accepted, then use the explicit Continue with Google recovery action and record consent recovery separately. |
| Monitoring | Dashboard and each alert signal are visible; notification delivery to `me@jamiep.org` is confirmed without exposing protected data in logs. |
| Rollback | Push a reviewed revert or restoration commit to protected `main`, or close public ingress using the runbook, then restore the accepted candidate; capture workflow or audit-log records. |

## Costed concurrency helper

`script/production_load_test.sh` submits at most five owner requests in parallel.
It refuses to run without an explicit cost acknowledgement, an owner personal
token, and a reviewed request JSON. Run it separately for each intended fixture;
it preserves each submission response as private JSON in a newly created
results directory, or in `ALPHA_COMPOSE_LOAD_RESULTS_DIR` when supplied. The
temporary curl configuration containing the bearer token is deleted on exit.
The helper does not poll, validate output media, or test the sixth-request
rejection; retain and augment the printed response-evidence directory manually.

## Release decision

Release remains configured-admin-scoped until every applicable production row has evidence,
legal copy is approved, OAuth is published, rollback has been exercised, and no
unresolved HIGH/CRITICAL security finding remains. Adding the first member is a
separate owner decision after this matrix is accepted.
