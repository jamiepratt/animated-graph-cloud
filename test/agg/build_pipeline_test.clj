(ns agg.build-pipeline-test
  (:require [agg.test-catalogue :as catalogue]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private ci (slurp ".github/workflows/ci.yml"))
(def ^:private production
  (slurp ".github/workflows/deploy-production.yml"))
(def ^:private development (slurp ".github/workflows/deploy.yml"))
(def ^:private proto (slurp ".github/workflows/deploy-proto.yml"))

(defn- matrix-shards [workflow]
  (->> (re-seq #"(?m)^\s+- shard: ([a-z-]+)\s*$" workflow)
       (map (comp keyword second))
       set))

(defn- ffmpeg-matrix-shards [workflow]
  (->> (re-seq #"(?m)^\s+- shard: ([a-z-]+)\n\s+ffmpeg: true\s*$"
               workflow)
       (map (comp keyword second))
       set))

(defn- catalogue-ffmpeg-shards []
  (->> catalogue/tests
       (filter #(contains? (:tools %) :ffmpeg))
       (map :shard)
       set))

(defn- occurs-before? [text earlier later]
  (let [earlier-index (str/index-of text earlier)
        later-index (str/index-of text later)]
    (and (number? earlier-index)
         (number? later-index)
         (< earlier-index later-index))))

(defn- section [text start-marker end-marker]
  (let [start (str/index-of text start-marker)
        end (str/index-of text end-marker start)]
    (when (and start end)
      (subs text start end))))

(deftest ci-runs-fast-affected-feedback-and-complete-production-coverage
  (is (str/includes? ci "name: Alpha Compose CI"))
  (is (str/includes? ci "clojure -M:test-changed"))
  (is (str/includes? ci "fetch-depth: 0"))
  (is (str/includes? ci "matrix:"))
  (is (= (catalogue/shards) (matrix-shards ci)))
  (is (str/includes? ci "clojure -M:test-shard ${{ matrix.shard }}"))
  (is (str/includes? ci "needs: affected-tests")))

(deftest ci-pins-browser-timezone-for-test-jobs
  (doseq [job [(section ci "  affected-tests:" "  complete-tests:")
               (section ci "  complete-tests:" "  candidate-image:")]]
    (is (str/includes? job "    env:\n      TZ: Europe/Warsaw")))
  (is (not (str/includes? (subs ci (str/index-of ci "  candidate-image:"))
                          "TZ: Europe/Warsaw"))))

(deftest ci-installs-ffmpeg-before-affected-selector
  (let [install (str "Install FFmpeg for affected-test fallback\n"
                     "        run: |\n"
                     "          sudo apt-get update\n"
                     "          sudo apt-get install --yes ffmpeg")]
    (is (occurs-before? ci
                        install
                        "Run affected tests with selection reasons"))))

(deftest ci-installs-ffmpeg-before-media-dependent-complete-shards
  (let [install (str "Install FFmpeg for media-dependent shard\n"
                     "        if: matrix.ffmpeg\n"
                     "        run: |\n"
                     "          sudo apt-get update\n"
                     "          sudo apt-get install --yes ffmpeg")]
    (is (occurs-before? ci install "Run complete catalogue shard")))
  (is (= #{:cloud :derivative} (catalogue-ffmpeg-shards)))
  (is (= (catalogue-ffmpeg-shards) (ffmpeg-matrix-shards ci))))

(deftest ci-builds-one-scanned-immutable-main-candidate-in-parallel
  (is (str/includes? ci "candidate-image:"))
  (is (str/includes? ci "if: github.event_name == 'push' && github.ref == 'refs/heads/main'"))
  (is (not (re-find #"(?s)candidate-image:.*?needs: (?:affected-tests|complete-tests)"
                    ci)))
  (doseq [contract ["docker/setup-buildx-action@"
                    "docker/build-push-action@"
                    "cache-from: type=gha,scope=production-image"
                    "cache-to: type=gha,mode=max,scope=production-image"
                    "BUILD_COMMIT=${{ github.sha }}"
                    "RELEASE_MODE=production"
                    "aquasecurity/trivy-action@"
                    "test/container_smoke.sh \"$IMAGE\" \"$GITHUB_SHA\""]]
    (testing contract (is (str/includes? ci contract)))))

(deftest production-consumes-the-successful-ci-commit-and-candidate
  (is (str/includes? production "workflow_run:"))
  (is (str/includes? production "workflows: [Alpha Compose CI]"))
  (is (str/includes? production "types: [completed]"))
  (is (str/includes? production
                     "github.event.workflow_run.conclusion == 'success'"))
  (is (str/includes? production
                     "github.event.workflow_run.head_sha || github.sha"))
  (is (str/includes? production "Verify successful CI for manual recovery"))
  (is (str/includes? production
                     "actions/workflows/ci.yml/runs?head_sha=$RELEASE_COMMIT"))
  (is (str/includes? production
                     "artifacts docker images describe \"$IMAGE_TAG\""))
  (is (not (str/includes? production "docker build")))
  (is (not (str/includes? production "trivy-action")))
  (is (not (str/includes? production "docker push"))))

(deftest production-keeps-terraform-and-safety-before-candidate-promotion
  (let [terraform (str/index-of production
                                "Plan and apply production Terraform")
        candidate (str/index-of production "Deploy private API candidate")]
    (is (number? terraform))
    (is (number? candidate))
    (is (< terraform candidate)))
  (is (str/includes? production "Destructive Terraform plan blocked"))
  (is (str/includes? production "Destructive runtime Terraform plan blocked"))
  (is (str/includes? production
                     "select(.change.actions | index(\"delete\"))")))

(deftest development-and-proto-builds-use-persistent-buildkit-caches
  (doseq [[name workflow scope]
          [["development" development "development-image"]
           ["proto" proto "proto-image"]]]
    (testing name
      (is (str/includes? workflow "docker/setup-buildx-action@"))
      (is (str/includes? workflow "docker/build-push-action@"))
      (is (str/includes? workflow (str "cache-from: type=gha,scope=" scope)))
      (is (str/includes? workflow
                         (str "cache-to: type=gha,mode=max,scope=" scope))))))
