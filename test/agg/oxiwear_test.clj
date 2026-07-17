(ns agg.oxiwear-test
  (:require [agg.telemetry.oxiwear :as oxiwear]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest oxiwear-heart-rate-uses-offset-aware-instants-across-midnight
  (is (= [{:timestamp (java.time.Instant/parse "2026-07-17T21:59:59Z")
           :heart-rate 120.0}
          {:timestamp (java.time.Instant/parse "2026-07-17T22:00:01Z")
           :heart-rate 128.0}]
         (oxiwear/parse-heart-rate-csv
          (slurp (io/resource "fixtures/oxiwear/hr-midnight.csv"))))))

(deftest oxiwear-spo2-normalizes-to-the-same-instants
  (is (= [{:timestamp (java.time.Instant/parse "2026-07-17T21:59:59Z")
           :spo2 97.0}
          {:timestamp (java.time.Instant/parse "2026-07-17T22:00:01Z")
           :spo2 93.0}]
         (oxiwear/parse-spo2-csv
          (slurp (io/resource "fixtures/oxiwear/spo2-midnight.csv"))))))

(deftest malformed-and-timezone-ambiguous-oxiwear-rows-are-rejected
  (doseq [csv ["reading_time,pulse_rate\n2026-07-17T00:00:00Z,\n"
               "reading_time,pulse_rate\n2026-07-17T00:00:00,120\n"]]
    (try
      (oxiwear/parse-heart-rate-csv csv)
      (is false "malformed OxiWear CSV unexpectedly decoded")
      (catch clojure.lang.ExceptionInfo error
        (is (= ::oxiwear/malformed-row (:type (ex-data error))))))))
