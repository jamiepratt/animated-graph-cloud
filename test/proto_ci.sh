#!/bin/sh

set -eu

# Keep the proto branch gate scoped to proto surfaces only.
# Do not expand this script to clojure -M:test-all.
clj-kondo --lint \
  src/agg/proto \
  test/agg/proto_test_runner.clj \
  test/agg/proto_release_test.clj \
  test/agg/proto_playback_test.clj \
  test/agg/proto_source_test.clj \
  test/agg/proto_ui_test.clj

terraform -chdir=infra/proto fmt -check
terraform -chdir=infra/proto init -backend=false -input=false
terraform -chdir=infra/proto validate

clojure -M:proto-test
