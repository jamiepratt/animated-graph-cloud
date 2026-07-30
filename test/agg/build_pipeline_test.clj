(ns agg.build-pipeline-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private development (slurp ".github/workflows/deploy.yml"))
(def ^:private proto (slurp ".github/workflows/deploy-proto.yml"))

(deftest proto-runs-affected-feedback-and-complete-area-coverage
  (is (str/includes? proto "affected-tests:"))
  (is (str/includes? proto "fetch-depth: 0"))
  (is (str/includes? proto "clojure -M:test-changed"))
  (is (str/includes? proto "proto-tests:"))
  (is (str/includes? proto "needs: affected-tests"))
  (is (str/includes? proto "Run complete proto-area gate"))
  (is (str/includes? proto "test/proto_ci.sh")))

(deftest proto-builds-one-scanned-immutable-candidate-in-parallel
  (is (str/includes? proto "candidate-image:"))
  (is (not (re-find #"(?s)candidate-image:.*?needs: (?:affected-tests|proto-tests)"
                    proto)))
  (doseq [contract ["docker/setup-buildx-action@"
                    "docker/build-push-action@"
                    "cache-from: type=gha,scope=proto-image"
                    "cache-to: type=gha,mode=max,scope=proto-image"
                    "BUILD_COMMIT=${{ github.sha }}"
                    "RELEASE_MODE=production"
                    "aquasecurity/trivy-action@"
                    "test/proto_container_smoke.sh \"$IMAGE\" \"$GITHUB_SHA\""]]
    (testing contract
      (is (str/includes? proto contract)))))

(deftest proto-deploy-reuses-candidate-after-required-gates
  (let [deploy-position (str/index-of proto "  deploy:")
        deploy (if (number? deploy-position)
                 (subs proto deploy-position)
                 "")]
    (is (str/includes? deploy "needs: [proto-tests, candidate-image]"))
    (is (str/includes? deploy
                       "Resolve CI-built immutable proto candidate digest"))
    (is (not (str/includes? deploy "docker build")))
    (is (not (str/includes? deploy "docker push")))
    (is (not (str/includes? deploy "trivy-action")))))

(deftest proto-keeps-release-safety-and-hosting-contracts
  (let [terraform (str/index-of proto "Plan and apply proto Terraform")
        private-health (str/index-of proto
                                     "Verify proto health and landing page")
        hosting (str/index-of proto
                              "Publish pinned proto Firebase Hosting routes")]
    (is (every? number? [terraform private-health hosting]))
    (when (every? number? [terraform private-health hosting])
      (is (< terraform private-health hosting))))
  (doseq [contract ["Delete or replace actions are forbidden"
                    "Authorization: Bearer $PROTO_RUN_ID_TOKEN"
                    "Timing workspace playback prototype"
                    "firebase.proto.template.json"
                    "PROTO_FIREBASE_SITE"]]
    (testing contract
      (is (str/includes? proto contract)))))

(deftest development-build-uses-persistent-buildkit-cache
  (doseq [contract ["docker/setup-buildx-action@"
                    "docker/build-push-action@"
                    "cache-from: type=gha,scope=development-image"
                    "cache-to: type=gha,mode=max,scope=development-image"
                    "BUILD_COMMIT=${{ github.sha }}"]]
    (testing contract
      (is (str/includes? development contract)))))
