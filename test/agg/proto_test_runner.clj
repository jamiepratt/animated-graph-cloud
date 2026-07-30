(ns agg.proto-test-runner
  (:require [agg.test-runner :as runner]))

(defn -main [& _]
  (let [{:keys [error fail]} (runner/run-selected
                              (runner/selected-namespaces
                               ["--area" "proto"]))]
    (System/exit (if (pos? (+ error fail)) 1 0))))
