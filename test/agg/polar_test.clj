(ns agg.polar-test
  (:require [agg.telemetry.polar :as polar]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest polar-csv-parses-timestamped-heart-rate-samples
  (let [csv (slurp (io/resource "fixtures/polar/valid.csv"))]
    (is (= [{:timestamp (java.time.Instant/parse "2026-07-17T10:00:00Z")
             :heart-rate 120.0}
            {:timestamp (java.time.Instant/parse "2026-07-17T10:00:01Z")
             :heart-rate 124.0}
            {:timestamp (java.time.Instant/parse "2026-07-17T10:00:02Z")
             :heart-rate 128.0}]
           (polar/parse-csv csv)))))

(deftest malformed-polar-rows-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Malformed Polar CSV row"
                        (polar/parse-csv
                         "timestamp,heart_rate\n2026-07-17T10:00:00Z,"))))

(deftest polar-flow-semicolon-exports-use-the-heart-rate-column
  (let [samples (polar/parse-csv
                 (slurp (io/resource "fixtures/polar/polar-flow.csv")))]
    (is (= [120.0 124.0 128.0] (mapv :heart-rate samples)))
    (is (= (java.time.Instant/parse "2026-07-17T10:00:02Z")
           (:timestamp (last samples))))))
