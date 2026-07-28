(ns agg.deploy-workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private workflow (slurp ".github/workflows/deploy.yml"))
(def ^:private dockerfile (slurp "Dockerfile"))

(deftest docker-build-still-includes-runtime-resources
  (is (str/includes? dockerfile "COPY resources ./resources"))
  (is (str/includes? dockerfile "RUN clojure -T:build uber")))

(deftest proto-deploy-workflow-is-branch-local-and-proto-only
  (is (str/includes? workflow "name: Deploy proto smoke path"))
  (is (str/includes? workflow "branches: [codex/issue-161-proto-only]"))
  (is (str/includes? workflow "group: development-proto-deployment"))
  (is (str/includes? workflow "PROTO_SERVICE: agg-proto"))
  (is (str/includes? workflow "Discover existing development API URL"))
  (is (str/includes? workflow "gcloud run services describe agg-api"))
  (is (str/includes? workflow "gcloud run deploy \"$PROTO_SERVICE\""))
  (is (str/includes? workflow "AGG_SERVICE_PROFILE=proto"))
  (is (str/includes? workflow "AGG_DISPATCHER_URL=$API_SERVICE_URL"))
  (is (str/includes? workflow "AGG_PUBLIC_BASE_URL=$PROTO_RUN_SERVICE_URL"))
  (is (str/includes? workflow "AGG_OAUTH_CLIENT_CREDENTIALS=oauth-client-secret:latest"))
  (is (str/includes? workflow "AGG_SESSION_KEY=session-key:latest"))
  (is (str/includes? workflow "AGG_TOKEN_HASH_PEPPER=token-hash-pepper:latest"))
  (is (str/includes? workflow "Verify proto container health locally"))
  (is (str/includes? workflow "Open proto ingress"))
  (is (str/includes? workflow "Verify proto health and identity"))
  (is (not (str/includes? workflow "SERVICE: agg-api")))
  (is (not (str/includes? workflow "DURABLE_JOB: agg-renderer")))
  (is (not (str/includes? workflow "SMOKE_JOB: agg-renderer-smoke")))
  (is (not (str/includes? workflow "Deploy private API service")))
  (is (not (str/includes? workflow "Promote durable renderer")))
  (is (not (str/includes? workflow "renderer smoke job")))
  (is (not (str/includes? workflow "AGG_RESEND_API_KEY=resend-api-key:latest")))
  (is (not (str/includes? workflow "EARLY_ACCESS_SENDER: Alpha Compose <early-access@alphacompose.com>"))))
