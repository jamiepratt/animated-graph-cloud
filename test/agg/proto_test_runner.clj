(ns agg.proto-test-runner
  (:require [agg.api-derivative-preparation-test]
            [agg.derivative-preparation-test]
            [agg.derivative-worker-test]
            [agg.observability-test]
            [agg.proto-release-test]
            [agg.proto-playback-test]
            [agg.proto-source-test]
            [agg.proto-ui-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [error fail]} (test/run-tests 'agg.api-derivative-preparation-test
                                             'agg.derivative-preparation-test
                                             'agg.derivative-worker-test
                                             'agg.observability-test
                                             'agg.proto-release-test
                                             'agg.proto-playback-test
                                             'agg.proto-source-test
                                             'agg.proto-ui-test)]
    (System/exit (if (pos? (+ error fail)) 1 0))))
