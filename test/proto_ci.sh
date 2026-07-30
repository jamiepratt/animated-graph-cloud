#!/bin/sh

set -eu

# Keep the proto branch gate scoped to proto surfaces only.
# Do not expand this script to clojure -M:test-all.
clj-kondo --lint \
  src/agg/observability.clj \
  src/agg/derivative/worker.clj \
  src/agg/drive/range_proxy.clj \
  src/agg/proto \
  src/agg/render/derivative.clj \
  src/agg/render/media.clj \
  test/agg/derivative_media_test.clj \
  test/agg/derivative_worker_test.clj \
  test/agg/drive_range_proxy_test.clj \
  test/agg/proto_test_runner.clj \
  test/agg/proto_release_test.clj \
  test/agg/proto_playback_test.clj \
  test/agg/proto_source_test.clj \
  test/agg/proto_ui_test.clj

terraform -chdir=infra/proto fmt -check
terraform -chdir=infra/proto init -backend=false -input=false
terraform -chdir=infra/proto validate

clojure -M:proto-test
