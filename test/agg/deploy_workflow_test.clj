(ns agg.deploy-workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private workflow (slurp ".github/workflows/deploy.yml"))

(deftest private-health-probe-uses-an-audience-correct-wif-id-token
  (is (str/includes? workflow "token_format: id_token"))
  (is (str/includes? workflow
                     "id_token_audience: ${{ steps.service-url.outputs.url }}"))
  (is (str/includes? workflow
                     "CLOUD_RUN_ID_TOKEN: ${{ steps.health-auth.outputs.id_token }}"))
  (is (not (str/includes? workflow "gcloud auth print-identity-token"))))
