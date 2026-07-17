(ns agg.garmin-test
  (:require [agg.telemetry.garmin :as garmin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest garmin-fit-normalizes-record-heart-rate-to-instants
  (let [encoded (str/trim
                 (slurp (io/resource "fixtures/garmin/activity.fit.b64")))]
    (is (= [{:timestamp (java.time.Instant/parse "2026-07-17T10:00:00Z")
             :heart-rate 120.0}
            {:timestamp (java.time.Instant/parse "2026-07-17T10:00:01Z")
             :heart-rate 124.0}
            {:timestamp (java.time.Instant/parse "2026-07-17T10:00:02Z")
             :heart-rate 128.0}]
           (garmin/parse-fit-base64 encoded)))))

(deftest malformed-garmin-fit-is-rejected-deterministically
  (try
    (garmin/parse-fit-base64 "bm90LWZpdA==")
    (is false "corrupt FIT unexpectedly decoded")
    (catch clojure.lang.ExceptionInfo error
      (is (= ::garmin/malformed-fit (:type (ex-data error)))))))
