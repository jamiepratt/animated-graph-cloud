(ns agg.smoke-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.io OutputStream)
           (java.nio.file Files OpenOption)))

(defn- available-port []
  (test-http/available-port))

(deftest api-health-is-served-over-http
  (let [port (available-port)
        server (api/start! port)]
    (try
      (let [response (test-http/send-string!
                      :get (str "http://127.0.0.1:" port "/health") nil {})]
        (is (= 200 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (some-> response .headers (.firstValue "content-type") (.orElse nil))))
        (is (= "{\"status\":\"ok\"}" (.body response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest renderer-smoke-job-emits-a-structured-completion-log
  (is (= (str "{\"severity\":\"INFO\","
              "\"component\":\"renderer\","
              "\"event\":\"smoke_complete\","
              "\"message\":\"Renderer smoke job completed\"}\n")
         (with-out-str (renderer/-main)))))

(deftest polar-midpoint-preview-is-served-over-http
  (let [port (available-port)
        server (api/start! port)
        body (json/write-str
              {:telemetryFormat "polar-csv"
               :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
               :preset "1080p25"
               :telemetrySyncAt "2026-07-17T10:00:00Z"
               :cameraSyncAt "2026-07-17T09:00:00Z"
               :sectionStartAt "2026-07-17T09:00:00Z"
               :sectionEndAt "2026-07-17T09:00:02Z"})]
    (try
      (let [response (test-http/send-bytes!
                      :post (str "http://127.0.0.1:" port "/v1/preview")
                      body {"Content-Type" "application/json"})
            png (.body response)]
        (is (= 200 (.statusCode response)))
        (is (= "image/png"
               (some-> response .headers (.firstValue "content-type")
                       (.orElse nil))))
        (is (= [137 80 78 71 13 10 26 10]
               (mapv #(bit-and 0xff %) (take 8 png)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest polar-overlay-is-served-over-http-through-rendering-protocols
  (let [port (available-port)
        frame-renderer
        (reify frames/FrameRenderer
          (stream-frames! [_ _render-spec output]
            (.write ^OutputStream output (byte-array [1 2 3 4]))
            {:frame-count 50 :buffer-count 1})
          (render-preview! [_ _render-spec _output]
            (throw (UnsupportedOperationException.))))
        video-encoder
        (reify media/VideoEncoder
          (encode! [_ _render-spec _audio-path output-path write-frames!]
            (with-open [output (OutputStream/nullOutputStream)]
              (write-frames! output))
            (Files/write output-path
                         (.getBytes "deterministic-mov" "UTF-8")
                         (make-array OpenOption 0))
            {:exit-status 0})
          (verify! [_ _render-spec _output-path]
            {:video {:codec "prores" :profile "4444" :alpha true}
             :audio {:codec "aac" :profile "LC" :channels 2}}))
        auth-system (auth/system
                     {:client-id "client-id"
                      :client-secret "client-secret"
                      :base-url "https://app.example.com"
                      :allowlist #{"owner@example.com"}
                      :session-key
                      (.getBytes "01234567890123456789012345678901")
                      :oauth (reify auth/OAuthClient
                               (exchange-code! [_ _ _ _ _]
                                 (throw (UnsupportedOperationException.))))})
        user {:subject "owner-subject" :email "owner@example.com"}
        session (auth/issue-session auth-system user)
        csrf (auth/issue-csrf-token auth-system user)
        server (api/start! port {:service-profile "overlay"
                                 :auth-system auth-system
                                 :frame-renderer frame-renderer
                                 :video-encoder video-encoder})
        body (json/write-str
              {:telemetryFormat "polar-csv"
               :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
               :preset "1080p25"
               :telemetrySyncAt "2026-07-17T10:00:00Z"
               :cameraSyncAt "2026-07-17T09:00:00Z"
               :sectionStartAt "2026-07-17T09:00:00Z"
               :sectionEndAt "2026-07-17T09:00:02Z"})]
    (try
      (let [response (test-http/send-bytes!
                      :post (str "http://127.0.0.1:" port "/v1/overlay")
                      body {"Content-Type" "application/json"
                            "Cookie" (str "agg_session=" session)
                            "X-CSRF-Token" csrf})]
        (is (= 200 (.statusCode response)))
        (is (= "video/quicktime"
               (some-> response .headers (.firstValue "content-type")
                       (.orElse nil))))
        (is (= "no-store"
               (some-> response .headers (.firstValue "cache-control")
                       (.orElse nil))))
        (is (= "nosniff"
               (some-> response .headers
                       (.firstValue "x-content-type-options")
                       (.orElse nil))))
        (is (= "deterministic-mov" (String. (.body response) "UTF-8"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
