(ns agg.timeline-property-test
  (:require [agg.telemetry.timeline :as timeline]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(deftest linear-heart-rate-alignment-is-affine
  (let [result
        (tc/quick-check
         200
         (prop/for-all [left (gen/choose 20 260)
                        right (gen/choose 20 260)
                        millisecond (gen/choose 0 10000)]
                       (let [seconds (/ millisecond 1000.0)
                             ratio (/ seconds 10.0)
                             expected (+ left (* ratio (- right left)))
                             actual (timeline/heart-rate-at-seconds
                                     [{:seconds 0.0 :heart-rate (double left)}
                                      {:seconds 10.0 :heart-rate (double right)}]
                                     seconds)]
                         (< (Math/abs (- expected actual)) 1.0e-9))))]
    (is (:pass? result) (pr-str result))))
