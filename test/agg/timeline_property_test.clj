(ns agg.timeline-property-test
  (:require [agg.telemetry.timeline :as timeline]
            [agg.render.spec :as spec]
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

(deftest section-alignment-is-invariant-under-common-timezone-shifts
  (let [result
        (tc/quick-check
         200
         (prop/for-all [shift-seconds (gen/choose -50400 50400)
                        left (gen/choose 20 260)
                        right (gen/choose 20 260)]
                       (let [origin (java.time.Instant/parse
                                     "2026-07-17T10:00:00Z")
                             shifted (.plusSeconds origin shift-seconds)
                             samples (fn [start]
                                       [{:timestamp start
                                         :heart-rate (double left)}
                                        {:timestamp (.plusSeconds start 10)
                                         :heart-rate (double right)}])]
                         (= (timeline/section (samples origin)
                                              (.plusSeconds origin 2)
                                              (.plusSeconds origin 8))
                            (timeline/section (samples shifted)
                                              (.plusSeconds shifted 2)
                                              (.plusSeconds shifted 8))))))]
    (is (:pass? result) (pr-str result))))

(deftest every-valid-preset-duration-has-an-exact-frame-count
  (let [result
        (tc/quick-check
         200
         (prop/for-all [preset-id (gen/elements ["1080p25" "2.7k25"])
                        candidate (gen/choose 1 480)]
                       (let [preset (spec/preset preset-id)
                             duration (min candidate (:duration-seconds preset))
                             bounded (spec/with-duration preset duration)]
                         (= (* 25 duration) (spec/frame-count bounded)))))]
    (is (:pass? result) (pr-str result))))
