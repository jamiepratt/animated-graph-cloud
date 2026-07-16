(ns agg.test-runner
  (:require [agg.deploy-workflow-test]
            [agg.render-test]
            [agg.smoke-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [{:keys [error fail]} (test/run-tests 'agg.deploy-workflow-test
                                             'agg.render-test
                                             'agg.smoke-test)]
    (when (pos? (+ error fail))
      (System/exit 1))))
