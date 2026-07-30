# Test selection and proto CI

The canonical inventory is `test/agg/test_catalogue.clj`. Each repository test
namespace has one CI shard and one or more overlapping domain areas.
`clojure -M:test-all` fails before running tests when a discovered
`test/**/*_test.clj` namespace is missing from the catalogue.

## Local commands

```sh
clojure -M:test-all
clojure -M:test-changed
clojure -M:test-changed --base origin/proto --head HEAD
clojure -M:test-area derivative
clojure -M:test-area proto
clojure -M:test-ns agg.proto-playback-test
clojure -M:test-shard proto
```

Areas include `api`, `auth`, `render`, `derivative`, `drive`, `cloud`, `proto`,
and `release`. The selector reads changed Clojure namespaces and follows
reverse dependency reachability. It reports direct, transitive, and changed
test selections.

Workflow, Terraform, Dockerfile, script, OpenAPI, release identity, and
test-read documentation paths have explicit impact rules. Shared build or test
configuration, selector/catalogue changes, unknown production paths, unreadable
namespaces, and unexplained empty selections run the complete catalogue.
Deletion and rename selection reads the old namespace from Git.

## Proto CI and deployment

The proto workflow first runs affected tests, then the complete proto area
through `test/proto_ci.sh`. In parallel it uses persistent GitHub Actions
BuildKit caching to build, push, smoke-test, and scan one production-mode image
for the exact proto commit.

Deployment waits for both the complete proto gate and candidate image. It
resolves that already verified image by digest instead of rebuilding it.
Terraform planning, the destructive-plan guard, private authenticated health
checks, and pinned Firebase Hosting publication remain mandatory.
