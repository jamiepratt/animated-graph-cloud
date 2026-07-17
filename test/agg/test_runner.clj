(ns agg.test-runner
  (:require [agg.auth-test]
            [agg.auth-gcp-test]
            [agg.api-auth-test]
            [agg.contracts-test]
            [agg.deploy-workflow-test]
            [agg.garmin-test]
            [agg.drive-test]
            [agg.drive-gcp-test]
            [agg.gcp-jobs-test]
            [agg.jobs-test]
            [agg.oxiwear-test]
            [agg.polar-test]
            [agg.render-test]
            [agg.renderer-drive-test]
            [agg.smoke-test]
            [agg.timeline-property-test]
            [agg.watermark-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [error fail]} (test/run-tests 'agg.contracts-test
                                             'agg.api-auth-test
                                             'agg.auth-gcp-test
                                             'agg.auth-test
                                             'agg.deploy-workflow-test
                                             'agg.garmin-test
                                             'agg.drive-test
                                             'agg.drive-gcp-test
                                             'agg.gcp-jobs-test
                                             'agg.jobs-test
                                             'agg.oxiwear-test
                                             'agg.polar-test
                                             'agg.render-test
                                             'agg.renderer-drive-test
                                             'agg.smoke-test
                                             'agg.timeline-property-test
                                             'agg.watermark-test)]
    (System/exit (if (pos? (+ error fail)) 1 0))))
