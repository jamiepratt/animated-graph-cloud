(ns agg.contracts-test
  (:require [agg.contracts.render :as contract]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)))

(defn valid-request []
  {:telemetryFormat "polar-csv"
   :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
   :preset "1080p25"
   :telemetrySyncAt "2026-07-17T10:00:00Z"
   :cameraSyncAt "2026-07-17T09:00:00Z"
   :sectionStartAt "2026-07-17T09:00:00Z"
   :sectionEndAt "2026-07-17T09:00:02Z"})

(deftest request-compiles-sync-and-section-to-a-shared-timeline
  (let [prepared (contract/prepare (valid-request))]
    (is (= {:id "1080p25"
            :width 1920
            :height 1080
            :fps 25
            :duration-seconds 2}
           (select-keys prepared
                        [:id :width :height :fps :duration-seconds])))
    (is (= [{:seconds 0.0 :heart-rate 120.0}
            {:seconds 1.0 :heart-rate 124.0}
            {:seconds 2.0 :heart-rate 128.0}]
           (:telemetry prepared)))))

(defn- error-type [request]
  (try
    (contract/prepare request)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:type (ex-data error)))))

(deftest request-schema-rejects-each-locked-boundary
  (testing "telemetry format"
    (is (= ::contract/unsupported-format
           (error-type (assoc (valid-request)
                              :telemetryFormat "garmin-fit")))))
  (testing "camera timestamp ordering"
    (is (= ::contract/invalid-timestamp-order
           (error-type (assoc (valid-request)
                              :cameraSyncAt "2026-07-17T09:00:01Z")))))
  (testing "telemetry coverage"
    (is (= ::contract/insufficient-coverage
           (error-type (assoc (valid-request)
                              :sectionEndAt "2026-07-17T09:00:03Z")))))
  (testing "preset maximum duration"
    (is (= ::contract/duration-too-long
           (error-type (assoc (valid-request)
                              :sectionEndAt "2026-07-17T09:08:01Z")))))
  (testing "whole-second duration"
    (is (= ::contract/fractional-duration
           (error-type (assoc (valid-request)
                              :sectionEndAt "2026-07-17T09:00:01.500Z")))))
  (testing "telemetry byte limit"
    (let [oversized (String. (byte-array (inc contract/max-telemetry-bytes))
                             StandardCharsets/US_ASCII)]
      (is (= ::contract/telemetry-too-large
             (error-type (assoc (valid-request) :telemetry oversized)))))))
