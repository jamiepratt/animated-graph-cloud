#!/bin/sh

set -eu

# Keep the proto branch gate scoped to proto surfaces only.
# Do not expand this script to clojure -M:test-all.
clj-kondo --lint \
  src/agg/proto \
  test/agg/proto_test_runner.clj \
  test/agg/proto_release_test.clj \
  test/agg/proto_source_test.clj \
  test/agg/proto_ui_test.clj

clojure -M:proto-test
