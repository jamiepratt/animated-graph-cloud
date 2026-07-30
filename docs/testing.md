# Test selection and CI

The canonical inventory is `test/agg/test_catalogue.clj`. Each repository test
namespace has one CI shard and one or more overlapping domain areas.
`clojure -M:test-all` fails before running tests when a discovered
`test/**/*_test.clj` namespace is missing from the catalogue.

## Local commands

```sh
clojure -M:test-all
clojure -M:test-changed
clojure -M:test-changed --base origin/main --head HEAD
clojure -M:test-area derivative
clojure -M:test-ns agg.derivative-lifecycle-test
clojure -M:test-shard cloud
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

## CI and deployment

Pull requests and `main` run affected tests first, then every non-overlapping
catalogue shard in parallel. A `main` push also builds one production-mode
candidate in parallel. GitHub Actions persists BuildKit layers by workflow
scope, including the stable FFmpeg compilation.

Production starts only from a successful **Alpha Compose CI** workflow run. It
checks out that run's exact commit and resolves the already smoke-tested and
scanned commit-tagged image. Manual recovery refuses a commit without a
successful CI run. Terraform planning, import checks, destructive-plan guards,
and the full apply remain before candidate promotion.

The proto workflow uses the same affected selector and cached immutable
candidate pattern. Its complete proto-area gate and Terraform destructive-plan
guard remain mandatory before deployment.
