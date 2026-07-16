# Animated Graph Cloud

Private Clojure service for generating telemetry graph overlays. This is a clean-room implementation; the reference TypeScript renderer is behavioral evidence only and none of its source or assets belong here.

## Local verification

Requires JDK 21, Clojure CLI, Terraform, Google Cloud CLI, and Docker Desktop.

```sh
clojure -M:test
clojure -T:build uber
terraform -chdir=infra/dev init
terraform -chdir=infra/dev validate
```

The uberjar contains both entry points:

```sh
java -cp target/animated-graph-cloud.jar clojure.main -m agg.api.main
java -cp target/animated-graph-cloud.jar clojure.main -m agg.renderer.main
```

The same JDK 21 image runs either entry point. The API is the default command;
the renderer is selected by replacing the image arguments:

```sh
docker build -t animated-graph-cloud:local .
test/container_smoke.sh animated-graph-cloud:local
docker run --rm animated-graph-cloud:local clojure.main -m agg.renderer.main
```

Infrastructure targets project `animated-graph-cloud-jp` in Warsaw (`europe-central2`). Application Default Credentials provide local authentication; do not create service-account key files or commit credentials.

Pushes to `main` authenticate through GitHub Workload Identity Federation,
scan and push an immutable commit-tagged image, deploy the private `agg-api`
service, and execute `agg-renderer-smoke`. The workflow verifies the health
response, runtime identities, and the renderer's structured completion log.

Implementation work is tracked in GitHub Issues.
