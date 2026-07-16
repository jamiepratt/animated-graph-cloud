# 0001: Use one command-selected application image

- Status: Accepted
- Date: 2026-07-16

## Context

The API and renderer share domain code but have different Cloud Run lifecycle
models. Shipping separate images would duplicate dependency resolution and make
it easier for the service and job to run different application revisions.

## Decision

Build one multi-stage, digest-pinned JDK 21 image containing the application
uberjar. The image runs as a non-root user. Its entrypoint is `java -cp` and its
default arguments select `agg.api.main`; the Cloud Run Job replaces only those
arguments with `agg.renderer.main`.

## Consequences

One commit SHA identifies both deployed processes, and no build credentials or
service-account keys enter the image. The later FFmpeg renderer must add its
pinned binary to this image without changing the command-selection contract.
