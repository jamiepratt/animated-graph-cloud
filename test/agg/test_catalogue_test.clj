(ns agg.test-catalogue-test
  (:require [agg.test-catalogue :as catalogue]
            [agg.test-runner :as runner]
            [clojure.test :refer [deftest is testing]]))

(deftest catalogue-accounts-for-every-repository-test
  (is (= (catalogue/discovered-test-namespaces)
         (set (catalogue/all-namespaces))))
  (is (= (count (catalogue/all-namespaces))
         (count (distinct (catalogue/all-namespaces))))))

(deftest catalogue-supports-overlapping-domain-areas
  (let [areas (set (mapcat :areas catalogue/tests))]
    (doseq [area #{:api :auth :render :derivative :drive :cloud :proto :release}]
      (is (contains? areas area) (str "missing area " area))))
  (is (contains? (set (catalogue/namespaces-for-areas [:api]))
                 'agg.api-auth-test))
  (is (contains? (set (catalogue/namespaces-for-areas [:auth]))
                 'agg.api-auth-test))
  (is (= #{'agg.api-derivative-preparation-test
           'agg.derivative-lifecycle-test
           'agg.derivative-media-test
           'agg.derivative-preparation-test
           'agg.derivative-worker-test
           'agg.drive-range-proxy-test
           'agg.observability-test
           'agg.proto-identity-test
           'agg.proto-playback-test
           'agg.proto-release-test
           'agg.proto-source-test
           'agg.proto-ui-test
           'agg.build-pipeline-test}
         (set (catalogue/namespaces-for-areas [:proto])))))

(deftest runner-selects-complete-area-shard-and-explicit-namespace-suites
  (is (= (catalogue/all-namespaces)
         (runner/selected-namespaces [])))
  (is (= (catalogue/namespaces-for-areas [:derivative])
         (runner/selected-namespaces ["--area" "derivative"])))
  (is (= (catalogue/namespaces-for-shards [:proto])
         (runner/selected-namespaces ["--shard" "proto"])))
  (is (= ['agg.api-routes-test 'agg.derivative-contract-test]
         (runner/selected-namespaces
          ["--namespace" "agg.api-routes-test"
           "--namespace" "agg.derivative-contract-test"])))
  (testing "unknown selectors fail instead of silently running nothing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown test area"
                          (runner/selected-namespaces ["--area" "missing"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown test namespace"
                          (runner/selected-namespaces
                           ["--namespace" "agg.missing-test"])))))
