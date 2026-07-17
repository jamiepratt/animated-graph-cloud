(ns agg.jobs-test
  (:require [agg.api.main :as api]
            [agg.jobs.lifecycle :as jobs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.net ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

(defn- available-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn render-request []
  {:telemetryFormat "polar-csv"
   :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
   :preset "1080p25"
   :telemetrySyncAt "2026-07-17T10:00:00Z"
   :cameraSyncAt "2026-07-17T09:00:00Z"
   :sectionStartAt "2026-07-17T09:00:00Z"
   :sectionEndAt "2026-07-17T09:00:02Z"})

(defn- request! [port method path body headers]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:" port path)))
        _ (doseq [[name value] headers]
            (.header builder name value))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder
                               (HttpRequest$BodyPublishers/ofString
                                (json/write-str body))))]
    (.send (HttpClient/newHttpClient)
           (.build request)
           (HttpResponse$BodyHandlers/ofString))))

(defn- response-json [response]
  (json/read-str (.body response) :key-fn keyword))

(defn- submit! [port key]
  (request! port :post "/v1/jobs" (render-request)
            {"Content-Type" "application/json"
             "Idempotency-Key" key}))

(deftest submission-is-idempotent-and-returns-a-polling-resource
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [headers {"Content-Type" "application/json"
                     "Idempotency-Key" "polar-render-20260717"}
            first-response (request! port :post "/v1/jobs"
                                     (render-request) headers)
            duplicate-response (request! port :post "/v1/jobs"
                                         (render-request) headers)
            first-job (response-json first-response)
            duplicate-job (response-json duplicate-response)
            poll-response (request! port :get (:statusUrl first-job) nil {})]
        (is (= 202 (.statusCode first-response)))
        (is (= 200 (.statusCode duplicate-response)))
        (is (= (:id first-job) (:id duplicate-job)))
        (is (= "queued" (:state first-job)))
        (is (= (str "/v1/jobs/" (:id first-job)) (:statusUrl first-job)))
        (is (= first-job (response-json poll-response)))
        (is (= 1 (count @(:enqueued system)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest authenticated-duplicate-dispatch-holds-at-most-five-leases
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [job-ids (mapv (fn [index]
                            (:id (response-json
                                  (submit! port (str "render-" index)))))
                          (range 6))
            first-path (str "/internal/v1/jobs/" (first job-ids) "/dispatch")
            unauthenticated (request! port :post first-path {} {})
            duplicate-responses
            (->> (range 12)
                 (mapv (fn [delivery]
                         (future
                           (request! port :post first-path {}
                                     {"X-CloudTasks-TaskName"
                                      (str "tasks/duplicate-" delivery)}))))
                 (mapv deref))
            remaining-responses
            (mapv (fn [job-id]
                    (request! port :post
                              (str "/internal/v1/jobs/" job-id "/dispatch")
                              {}
                              {"X-CloudTasks-TaskName" (str "tasks/" job-id)}))
                  (rest job-ids))]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= 1 (count (filter #(= 202 (.statusCode %))
                                duplicate-responses))))
        (is (every? #(contains? #{200 202} (.statusCode %))
                    duplicate-responses))
        (is (= [202 202 202 202 503]
               (mapv #(.statusCode %) remaining-responses)))
        (is (= 5 (count @(:launched system))))
        (is (= "queued"
               (:state (response-json
                        (request! port :get
                                  (str "/v1/jobs/" (last job-ids)) nil {}))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest queued-and-running-jobs-cancel-durably-and-can-be-explicitly-retried
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [queued-id (:id (response-json (submit! port "cancel-queued")))
            queued-cancel
            (request! port :post (str "/v1/jobs/" queued-id "/cancel") {} {})
            retry-response
            (request! port :post (str "/v1/jobs/" queued-id "/retry") {} {})
            running-id (:id (response-json (submit! port "cancel-running")))
            _ (request! port :post
                        (str "/internal/v1/jobs/" running-id "/dispatch") {}
                        {"X-CloudTasks-TaskName" "tasks/cancel-running"})
            running-cancel
            (request! port :post (str "/v1/jobs/" running-id "/cancel") {} {})]
        (is (= 200 (.statusCode queued-cancel)))
        (is (= "cancelled" (:state (response-json queued-cancel))))
        (is (= 202 (.statusCode retry-response)))
        (is (= 2 (:attempt (response-json retry-response))))
        (is (= "queued" (:state (response-json retry-response))))
        (is (= 200 (.statusCode running-cancel)))
        (is (= "cancelled" (:state (response-json running-cancel))))
        (is (= 1 (count @(:cancelled system))))
        (is (= 3 (count @(:enqueued system)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest worker-crash-is-durable-and-releases-the-render-lease
  (let [worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (throw (ex-info "simulated worker crash" {}))))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        {:keys [job]} (jobs/submit-job! service "crashing-render"
                                        (render-request))
        job-id (:id job)]
    (jobs/dispatch-job! service job-id)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"worker failed"
                          (jobs/run-job! service job-id)))
    (is (= "failed" (:state (jobs/get-job service job-id))))
    (is (= "worker_failed" (:failureCode (jobs/get-job service job-id))))
    (is (nil? (get-in @(:state system) [:jobs job-id :lease])))))

(deftest worker-output-completes-or-fails-at-the-durable-size-boundary
  (let [result (atom {:output-bytes 1024
                      :object "jobs/sized/output.mov"
                      :sha256 (apply str (repeat 64 "a"))})
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request] @result))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        run! (fn [key]
               (let [job-id (get-in (jobs/submit-job! service key
                                                      (render-request))
                                    [:job :id])]
                 (jobs/dispatch-job! service job-id)
                 (jobs/run-job! service job-id)))]
    (let [completed (run! "bounded-output")]
      (is (= "succeeded" (:state completed)))
      (is (= "jobs/sized/output.mov" (get-in completed [:output :object]))))
    (reset! result {:output-bytes (inc jobs/max-output-bytes)
                    :object "jobs/oversized/output.mov"})
    (let [failed (run! "oversized-output")]
      (is (= "failed" (:state failed)))
      (is (= "output_too_large" (:failureCode failed)))
      (is (nil? (:output failed))))))

(deftest upload-grant-is-put-only-content-locked-and-short-lived
  (let [port (available-port)
        signer (reify jobs/UploadSigner
                 (signed-upload [_ object content-type expires-seconds]
                   (str "https://upload.test/" object
                        "?content-type=" content-type
                        "&expires=" expires-seconds)))
        server (api/start! port {:upload-signer signer})]
    (try
      (let [before (java.time.Instant/now)
            response (request! port :post "/v1/uploads"
                               {:contentType "application/json"
                                :contentLength 1024}
                               {"Content-Type" "application/json"})
            grant (response-json response)
            expires-at (java.time.Instant/parse (:expiresAt grant))]
        (is (= 201 (.statusCode response)))
        (is (= "PUT" (:method grant)))
        (is (= "application/json" (:contentType grant)))
        (is (re-find #"expires=900" (:uploadUrl grant)))
        (is (not (.isAfter expires-at (.plusSeconds before 901)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest idempotency-key-cannot-be-reused-for-a-different-request
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [headers {"Content-Type" "application/json"
                     "Idempotency-Key" "same-key"}
            _ (request! port :post "/v1/jobs" (render-request) headers)
            conflict (request! port :post "/v1/jobs"
                               (assoc (render-request)
                                      :sectionEndAt "2026-07-17T09:00:01Z")
                               headers)]
        (is (= 409 (.statusCode conflict)))
        (is (= {:error "idempotency_conflict"}
               (response-json conflict))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
