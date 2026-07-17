(ns agg.test-runner
  (:require [agg.contracts-test]
            [agg.deploy-workflow-test]
            [agg.gcp-jobs-test]
            [agg.jobs-test]
            [agg.polar-test]
            [agg.render-test]
            [agg.smoke-test]
            [agg.timeline-property-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [error fail]} (test/run-tests 'agg.contracts-test
                                             'agg.deploy-workflow-test
                                             'agg.gcp-jobs-test
                                             'agg.jobs-test
                                             'agg.polar-test
                                             'agg.render-test
                                             'agg.smoke-test
                                             'agg.timeline-property-test)]
    (System/exit (if (pos? (+ error fail)) 1 0))))
