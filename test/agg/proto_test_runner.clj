(ns agg.proto-test-runner
  (:require [agg.proto-release-test]
            [agg.proto-source-test]
            [agg.proto-ui-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [error fail]} (test/run-tests 'agg.proto-release-test
                                             'agg.proto-source-test
                                             'agg.proto-ui-test)]
    (System/exit (if (pos? (+ error fail)) 1 0))))
