(ns agg.jobs-test
  (:require [agg.api.main :as api]
            [agg.jobs.lifecycle :as jobs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.net ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Instant)))

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

(defn- raw-post! [port path body headers]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:" port path)))
        _ (doseq [[name value] headers]
            (.header builder name value))]
    (.send (HttpClient/newHttpClient)
           (.build (.POST builder
                          (HttpRequest$BodyPublishers/ofString body)))
           (HttpResponse$BodyHandlers/ofString))))

(defn- response-json [response]
  (json/read-str (.body response) :key-fn keyword))

(defn- submit! [port key]
  (request! port :post "/v1/jobs" (render-request)
            {"Content-Type" "application/json"
             "Idempotency-Key" key}))

(defn- exception-type [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:type (ex-data error)))))

(deftest daily-admission-accepts-exactly-one-hundred-unique-submissions
  (let [service (:service (jobs/in-memory-system))
        request (render-request)]
    (doseq [index (range 100)]
      (is (:created? (jobs/submit-job! service (str "daily-" index) request))))
    (is (false? (:created? (jobs/submit-job! service "daily-99" request)))
        "an idempotent replay does not consume admission")
    (is (= ::jobs/daily-submission-limit-exhausted
           (exception-type
            #(jobs/submit-job! service "daily-100" request))))))

(deftest admission-limits-fail-closed-on-invalid-configuration
  (doseq [options [{:daily-limit 0}
                   {:monthly-budget-cents 0}
                   {:render-reservation-cents 0}
                   {:render-reservation-cents -1}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"positive integers"
                          (jobs/in-memory-system options)))))

(deftest monthly-budget-admission-reserves-before-enqueue
  (let [system (jobs/in-memory-system {:monthly-budget-cents 50
                                       :render-reservation-cents 25})
        service (:service system)
        request (render-request)]
    (is (:created? (jobs/submit-job! service "budget-1" request)))
    (is (:created? (jobs/submit-job! service "budget-2" request)))
    (is (false? (:created? (jobs/submit-job! service "budget-2" request))))
    (is (= ::jobs/monthly-budget-exhausted
           (exception-type
            #(jobs/submit-job! service "budget-3" request))))
    (is (= 2 (count @(:enqueued system)))
        "rejected work never reaches the render queue")))

(deftest explicit-retry-reserves-compute-before-requeue
  (let [system (jobs/in-memory-system {:monthly-budget-cents 25
                                       :render-reservation-cents 25})
        service (:service system)
        job-id (get-in (jobs/submit-job! service "retry-budget"
                                         (render-request))
                       [:job :id])]
    (jobs/cancel-job! service job-id)
    (is (= ::jobs/monthly-budget-exhausted
           (exception-type #(jobs/retry-job! service job-id))))
    (is (= 1 (count @(:enqueued system)))
        "a rejected retry does not enqueue another execution")))

(deftest submission-admission-rejects-while-five-renders-are-active
  (let [service (:service (jobs/in-memory-system))
        request (render-request)
        active-job-ids
        (mapv (fn [index]
                (get-in (jobs/submit-job! service (str "active-" index) request)
                        [:job :id]))
              (range jobs/max-active-leases))]
    (doseq [job-id active-job-ids]
      (jobs/dispatch-job! service job-id))
    (is (= ::jobs/capacity-exhausted
           (exception-type
            #(jobs/submit-job! service "active-overflow" request))))))

(deftest admission-rejections-have-stable-http-errors
  (letfn [(second-submission [options key-prefix]
            (let [port (available-port)
                  system (jobs/in-memory-system options)
                  server (api/start! port {:job-service (:service system)})]
              (try
                (submit! port (str key-prefix "-1"))
                (submit! port (str key-prefix "-2"))
                (finally
                  (.close ^java.lang.AutoCloseable server)))))]
    (let [daily (second-submission {:daily-limit 1
                                    :monthly-budget-cents 1000}
                                   "daily-http")
          budget (second-submission {:monthly-budget-cents 25
                                     :render-reservation-cents 25}
                                    "budget-http")]
      (is (= 429 (.statusCode daily)))
      (is (= {:error "daily_submission_limit_exhausted"}
             (response-json daily)))
      (is (= 429 (.statusCode budget)))
      (is (= {:error "monthly_budget_exhausted"}
             (response-json budget))))))

(deftest reconciliation-repairs-a-render-abandoned-after-dispatch
  (let [system (jobs/in-memory-system)
        service (:service system)
        job-id (get-in (jobs/submit-job! service "abandoned-render"
                                         (render-request))
                       [:job :id])]
    (jobs/dispatch-job! service job-id)
    (swap! (:state system)
           (fn [state]
             (-> state
                 (assoc-in [:jobs job-id :lease :expires-at] Instant/EPOCH)
                 (assoc-in [:jobs job-id :state] :running))))
    (is (= {:repaired-jobs 1 :released-leases 1}
           (jobs/reconcile-jobs! service)))
    (is (= "failed" (:state (jobs/get-job service job-id))))
    (is (= "stale_lease" (:failureCode (jobs/get-job service job-id))))
    (is (= {:repaired-jobs 0 :released-leases 0}
           (jobs/reconcile-jobs! service))
        "reconciliation is idempotent")))

(deftest scheduler-invokes-only-the-authenticated-reconciliation-route
  (let [port (available-port)
        system (jobs/in-memory-system)
        service (:service system)
        job-id (get-in (jobs/submit-job! service "scheduled-repair"
                                         (render-request))
                       [:job :id])
        _ (jobs/dispatch-job! service job-id)
        _ (swap! (:state system) assoc-in
                 [:jobs job-id :lease :expires-at] Instant/EPOCH)
        server (api/start! port {:job-service service})]
    (try
      (let [unauthenticated (request! port :post
                                      "/internal/v1/jobs/reconcile" {} {})
            reconciled (request! port :post "/internal/v1/jobs/reconcile" {}
                                 {"X-CloudScheduler" "true"})]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= {:error "authenticated_scheduler_required"}
               (response-json unauthenticated)))
        (is (= 200 (.statusCode reconciled)))
        (is (= {:repairedJobs 1 :releasedLeases 1}
               (response-json reconciled))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest operational-events-expose-only-bounded-aggregate-fields
  (let [port (available-port)
        events (atom [])
        system (jobs/in-memory-system {:daily-limit 1})
        service (:service system)
        server (api/start! port {:job-service service
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj (assoc fields :event event)))})]
    (try
      (let [job-id (:id (response-json (submit! port "private-key")))]
        (request! port :post (str "/internal/v1/jobs/" job-id "/dispatch") {}
                  {"X-CloudTasks-TaskName" "private-task-name"})
        (swap! (:state system) assoc-in
               [:jobs job-id :lease :expires-at] Instant/EPOCH)
        (request! port :post "/internal/v1/jobs/reconcile" {}
                  {"X-CloudScheduler" "true"})
        (submit! port "second-private-key"))
      (is (= #{"render_dispatched" "reconciliation_complete"
               "admission_rejected"}
             (set (map :event @events))))
      (is (nat-int? (:queueAgeMs
                     (first (filter #(= "render_dispatched" (:event %))
                                    @events)))))
      (is (= "daily_submission_limit_exhausted"
             (:reason (first (filter #(= "admission_rejected" (:event %))
                                     @events)))))
      (let [serialized (pr-str @events)]
        (is (not (re-find #"private-key|private-task-name|timestamp|heart_rate"
                          serialized))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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

(deftest invalid-job-submission-is-rejected-before-enqueue
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [response (request! port :post "/v1/jobs"
                               (assoc (render-request) :preset "unknown")
                               {"Content-Type" "application/json"
                                "Idempotency-Key" "invalid-render"})]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"} (response-json response)))
        (is (empty? @(:enqueued system))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest malformed-job-submission-is-an-invalid-request
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [response (raw-post! port "/v1/jobs" "{not-json"
                                {"Content-Type" "application/json"
                                 "Idempotency-Key" "malformed-render"})]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"} (response-json response)))
        (is (empty? @(:enqueued system))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest job-submission-stores-the-original-request
  (let [port (available-port)
        received-request (promise)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id request]
                   (deliver received-request request)
                   {:output-bytes 10
                    :object "jobs/original/output.mov"}))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        server (api/start! port {:job-service service})
        submitted-request (render-request)]
    (try
      (let [submission (request! port :post "/v1/jobs" submitted-request
                                 {"Content-Type" "application/json"
                                  "Idempotency-Key" "original-render"})
            job-id (:id (response-json submission))]
        (request! port :post (str "/internal/v1/jobs/" job-id "/dispatch") {}
                  {"X-CloudTasks-TaskName" "tasks/original-render"})
        (jobs/run-job! service job-id)
        (is (= submitted-request @received-request)))
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

(deftest launch-failure-is-visible-in-the-polling-resource
  (let [port (available-port)
        launcher (reify jobs/JobLauncher
                   (launch-job! [_ _job-id]
                     (throw (ex-info "simulated launch failure" {})))
                   (cancel-execution! [_ _execution]))
        system (jobs/in-memory-system {:launcher launcher})
        server (api/start! port {:job-service (:service system)})]
    (try
      (let [job-id (:id (response-json (submit! port "launch-failure")))
            dispatch (request! port :post
                               (str "/internal/v1/jobs/" job-id "/dispatch") {}
                               {"X-CloudTasks-TaskName" "tasks/launch-failure"})
            polled (response-json
                    (request! port :get (str "/v1/jobs/" job-id) nil {}))]
        (is (= 502 (.statusCode dispatch)))
        (is (= "failed" (:state polled)))
        (is (= "launch_failed" (:failureCode polled))))
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
