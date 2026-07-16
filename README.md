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

## ProRes renderer spike

The renderer streams one reusable Java2D RGBA buffer to the bundled FFmpeg
8.1.2 process. FFmpeg writes a seekable, non-fragmented ProRes 4444 MOV with
deterministic AAC-LC heartbeat audio. `prores_ks` receives
`yuva444p10le`; FFmpeg decodes the resulting `ap4h` stream as
`yuva444p12le`, the profile's decoder representation.

Run both bounded 10-second local cases and their media assertions:

```sh
docker build -t animated-graph-cloud:local .
script/render_local_spike.sh animated-graph-cloud:local
```

Run the gated Warsaw Cloud Run matrix with an immutable Artifact Registry
digest:

```sh
script/run_cloud_spike.sh \
  europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:DIGEST
```

To retry only a missing stage, append the existing UTC run ID and carry spend
from earlier run IDs conservatively:

```sh
PRIOR_ESTIMATED_COST_USD=0.20 script/run_cloud_spike.sh \
  europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:DIGEST \
  20260716T213215Z
```

The cloud script enforces one task at a time, zero retries, 8 vCPU, 32 GiB,
and a 60-minute timeout. Each successful render uploads its MOV, JFR, and JSON
report to the private temporary bucket, then removes local artifacts. Bucket
objects expire after one day. Reports contain dimensions, timings, effective
fps, cgroup peak memory, output size, checksum, FFprobe evidence, and JFR
summaries; structured logs omit filenames and synthetic telemetry values.

The retained maximum MOVs are inputs to the manual DaVinci Resolve check in
GitHub issue #3.

Infrastructure targets project `animated-graph-cloud-jp` in Warsaw (`europe-central2`). Application Default Credentials provide local authentication; do not create service-account key files or commit credentials.

Pushes to `main` authenticate through GitHub Workload Identity Federation,
scan and push an immutable commit-tagged image, deploy the private `agg-api`
service, and execute `agg-renderer-smoke`. The workflow verifies the health
response, runtime identities, and the renderer's structured completion log.

Implementation work is tracked in GitHub Issues.
