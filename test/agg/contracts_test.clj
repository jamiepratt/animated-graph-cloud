(ns agg.contracts-test
  (:require [agg.contracts.render :as contract]
            [agg.telemetry.timeline :as timeline]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest request-envelope-fits-all-individually-bounded-inputs
  (is (> contract/max-render-request-bytes
         (* 2 contract/max-telemetry-bytes))))

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

(deftest every-approved-heart-rate-format-builds-the-shared-render-contract
  (let [requests
        [(valid-request)
         (assoc (valid-request)
                :telemetryFormat "garmin-fit"
                :telemetry (str/trim
                            (slurp (io/resource
                                    "fixtures/garmin/activity.fit.b64"))))
         (assoc (valid-request)
                :telemetryFormat "oxiwear-hr-csv"
                :telemetry (slurp (io/resource
                                   "fixtures/oxiwear/hr-midnight.csv"))
                :telemetrySyncAt "2026-07-17T21:59:59Z")]
        prepared (mapv contract/prepare requests)]
    (is (= 3 (count prepared)))
    (is (every? #(= {:id "1080p25"
                     :width 1920
                     :height 1080
                     :fps 25
                     :duration-seconds 2}
                    (select-keys % [:id :width :height :fps
                                    :duration-seconds]))
                prepared))
    (is (= [[120.0 124.0 128.0]
            [120.0 124.0 128.0]
            [120.0 124.0 128.0]]
           (mapv (fn [{:keys [telemetry]}]
                   (mapv #(timeline/heart-rate-at-seconds telemetry %)
                         [0.0 1.0 2.0]))
                 prepared)))))

(deftest optional-oxiwear-spo2-shares-the-section-timeline
  (let [prepared
        (contract/prepare
         (assoc (valid-request)
                :telemetryFormat "oxiwear-hr-csv"
                :telemetry (slurp (io/resource
                                   "fixtures/oxiwear/hr-midnight.csv"))
                :telemetrySyncAt "2026-07-17T21:59:59Z"
                :spo2 {:format "oxiwear-spo2-csv"
                       :telemetry (slurp
                                   (io/resource
                                    "fixtures/oxiwear/spo2-midnight.csv"))}))]
    (is (= [{:seconds 0.0 :spo2 97.0}
            {:seconds 2.0 :spo2 93.0}]
           (:spo2 prepared)))
    (is (= 95.0
           (timeline/value-at-seconds (:spo2 prepared) :spo2 1.0)))))

(deftest timer-interval-is-section-relative-across-local-midnight
  (let [prepared
        (contract/prepare
         (assoc (valid-request)
                :telemetryFormat "oxiwear-hr-csv"
                :telemetry (slurp (io/resource
                                   "fixtures/oxiwear/hr-midnight.csv"))
                :telemetrySyncAt "2026-07-17T21:59:59Z"
                :cameraSyncAt "2026-07-17T23:59:59+02:00"
                :sectionStartAt "2026-07-17T23:59:59+02:00"
                :sectionEndAt "2026-07-18T00:00:01+02:00"
                :timer {:startAt "2026-07-17T21:59:59.500Z"
                        :endAt "2026-07-17T22:00:00.500Z"}))]
    (is (= {:start-seconds 0.5 :end-seconds 1.5}
           (:timer prepared)))))

(deftest watermark-is-validated-png-content-with-bounded-dimensions
  (let [prepared
        (contract/prepare
         (assoc (valid-request)
                :watermark
                {:contentBase64
                 (str/trim
                  (slurp (io/resource "fixtures/watermark/tiny.png.b64")))}))]
    (is (= {:width 2 :height 2}
           (select-keys (:watermark prepared) [:width :height])))
    (is (instance? java.awt.image.BufferedImage
                   (get-in prepared [:watermark :image])))))

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
                              :telemetryFormat "unknown")))))
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
             (error-type (assoc (valid-request) :telemetry oversized))))))
  (testing "SpO2 format"
    (is (= ::contract/invalid-spo2
           (error-type (assoc (valid-request)
                              :spo2 {:format "unknown"
                                     :telemetry "reading_time,spo2\n"})))))
  (testing "timer ordering and section bounds"
    (is (= ::contract/invalid-timer-order
           (error-type (assoc (valid-request)
                              :timer {:startAt "2026-07-17T09:00:01Z"
                                      :endAt "2026-07-17T09:00:03Z"}))))))
