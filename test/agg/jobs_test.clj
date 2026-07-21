(ns agg.jobs-test
  (:require [agg.admin.core :as admin]
            [agg.api.main :as api]
            [agg.errors :as errors]
            [agg.http-test-support :as test-http]
            [agg.jobs.lifecycle :as jobs]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.time Clock Instant ZoneOffset)
           (java.util.concurrent CancellationException)))

(defn- available-port []
  (test-http/available-port))

(defn- start-api!
  ([port] (start-api! port {}))
  ([port dependencies]
   (api/start! port dependencies)))

(defn render-request []
  {:telemetryFormat "polar-csv"
   :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
   :preset "1080p25"
   :telemetrySyncAt "2026-07-17T10:00:00Z"
   :cameraSyncAt "2026-07-17T09:00:00Z"
   :sectionStartAt "2026-07-17T09:00:00Z"
   :sectionEndAt "2026-07-17T09:00:02Z"})

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (= :post method) (json/write-str body)) headers))

(defn- raw-post! [port path body headers]
  (test-http/send-string! :post (str "http://127.0.0.1:" port path)
                          body headers))

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

(defn- mutable-clock [now]
  (letfn [(clock-for [zone]
            (proxy [Clock] []
              (getZone [] zone)
              (withZone [new-zone] (clock-for new-zone))
              (instant [] @now)
              (millis [] (.toEpochMilli ^Instant @now))))]
    (clock-for ZoneOffset/UTC)))

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
                   {:monthly-budget-minor-units 0}
                   {:render-reservation-minor-units 0}
                   {:render-reservation-minor-units -1}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"positive integers"
                          (jobs/in-memory-system options)))))

(deftest default-admission-policy-is-pln-minor-units
  (is (= 40000 jobs/default-monthly-budget-minor-units))
  (is (= 125 jobs/default-render-reservation-minor-units)))

(deftest internal-failures-map-to-stable-public-failure-codes
  (is (= "worker_failed"
         (:failureCode (jobs/job-resource {:state :failed
                                           :failure ::jobs/worker-failed}))))
  (is (= "launch_failed"
         (:failureCode (jobs/job-resource {:state :failed
                                           :failure ::jobs/launch-failed}))))
  (is (= "worker_failed"
         (:failureCode (jobs/job-resource {:state :failed
                                           :failure "existing_code"})))))

(deftest durable-job-actions-follow-server-retry-eligibility
  (let [fragment (fn [state & [retryable]]
                   (ui/job-fragment
                    (cond-> {:id "job-id" :state state :attempt 1}
                      (some? retryable) (assoc :retryable retryable))))
        retry-action? #(str/includes? % "/ui/jobs/job-id/retry")
        cancel-action? #(str/includes? % "/ui/jobs/job-id/cancel")]
    (is (retry-action? (fragment "failed" true)))
    (is (not (retry-action? (fragment "failed" false))))
    (is (retry-action? (fragment "cancelled")))
    (doseq [state ["queued" "launching" "running"]]
      (is (cancel-action? (fragment state)))
      (is (not (retry-action? (fragment state)))))
    (doseq [state ["cancellation-requested" "succeeded"]]
      (is (not (cancel-action? (fragment state))))
      (is (not (retry-action? (fragment state)))))
    (let [requested (fragment "cancellation-requested")
          cancelled (fragment "cancelled")]
      (is (str/includes? requested "<h2>Cancellation requested</h2>"))
      (is (str/includes? requested "hx-trigger=\"load delay:2s\""))
      (is (str/includes? cancelled "<h2>Cancelled</h2>"))
      (is (not (str/includes? cancelled "hx-get="))))))

(deftest preview-work-is-distinguished-from-durable-render-jobs
  (let [service (:service (jobs/in-memory-system))
        preview (get-in (jobs/submit-job!
                         service "preview-operation"
                         (assoc (render-request)
                                :previewOperation "key-moment-gallery-v1"))
                        [:job])
        render (get-in (jobs/submit-job! service "durable-render"
                                         (render-request))
                       [:job])]
    (is (= "preview" (:operationKind preview)))
    (is (nil? (:operationKind render)))
    (is (= (* 24 60 60) jobs/preview-retention-seconds))))

(deftest future-trace-opacity-is-normalized-before-durable-storage-and-retry
  (doseq [[requested expected] [[::omitted 25] [0 0] [100 100]]]
    (let [received-request (promise)
          worker (reify jobs/RenderWorker
                   (perform-render! [_ _job-id request]
                     (deliver received-request request)
                     {:output-bytes 10
                      :object "jobs/normalized/output.mov"}))
          system (jobs/in-memory-system {:worker worker})
          service (:service system)
          request (cond-> (render-request)
                    (not= ::omitted requested)
                    (assoc :futureTraceOpacityPercent requested))
          job-id (get-in (jobs/submit-job! service
                                           (str "opacity-" requested)
                                           request)
                         [:job :id])]
      (jobs/cancel-job! service job-id)
      (jobs/retry-job! service job-id)
      (jobs/dispatch-job! service job-id)
      (jobs/run-job! service job-id)
      (is (= expected (:futureTraceOpacityPercent @received-request))
          (str "requested " requested)))))

(deftest preview-evidence-matches-omitted-opacity-to-the-normalized-default
  (let [identity {:subject "subject-1" :membership-version "generation-1"}
        request (assoc (render-request)
                       :previewOperation jobs/preview-operation-version
                       :requesterSubject (:subject identity)
                       :requesterMembershipVersion (:membership-version identity))
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _ _]
                   {:output-bytes 0 :sections [] :assets []}))
        service (:service (jobs/in-memory-system {:worker worker}))
        job-id (get-in (jobs/submit-job! service "opacity-preview" request)
                       [:job :id])]
    (jobs/dispatch-job! service job-id)
    (jobs/run-job! service job-id)
    (is (true? (jobs/require-preview-evidence!
                service job-id
                (dissoc request :previewOperation :requesterSubject
                        :requesterMembershipVersion)
                identity)))
    (is (true? (jobs/require-preview-evidence!
                service job-id
                (assoc (dissoc request :previewOperation :requesterSubject
                               :requesterMembershipVersion)
                       :futureTraceOpacityPercent 25)
                identity)))
    (is (= ::jobs/preview-stale
           (exception-type
            #(jobs/require-preview-evidence!
              service job-id
              (assoc (dissoc request :previewOperation :requesterSubject
                             :requesterMembershipVersion)
                     :futureTraceOpacityPercent 0)
              identity))))))

(deftest member-cleanup-cancels-only-its-generation-and-legacy-jobs
  (let [service (:service (jobs/in-memory-system))
        request (assoc (render-request)
                       :requesterSubject "member-subject"
                       :requesterEmail "member@example.com")
        submit (fn [key membership-version]
                 (get-in (jobs/submit-job!
                          service key
                          (cond-> request
                            membership-version
                            (assoc :requesterMembershipVersion
                                   membership-version)))
                         [:job :id]))
        old-id (submit "old-generation-job" "old-generation")
        legacy-id (submit "legacy-generation-job" nil)
        new-id (submit "new-generation-job" "new-generation")]
    (is (= 2 (admin/cancel-member-jobs!
              service {:subject "member-subject"
                       :membership-version "old-generation"})))
    (is (= "cancelled" (:state (jobs/get-job service old-id))))
    (is (= "cancelled" (:state (jobs/get-job service legacy-id))))
    (is (= "queued" (:state (jobs/get-job service new-id))))))

(deftest monthly-budget-admission-reserves-before-enqueue
  (let [system (jobs/in-memory-system {:monthly-budget-minor-units 50
                                       :render-reservation-minor-units 25})
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

(deftest monthly-admission-configuration-uses-currency-minor-units
  (let [system (jobs/in-memory-system
                {:monthly-budget-minor-units 250
                 :render-reservation-minor-units 125})
        service (:service system)
        request (render-request)]
    (is (:created? (jobs/submit-job! service "minor-unit-budget-1" request)))
    (is (:created? (jobs/submit-job! service "minor-unit-budget-2" request)))
    (is (= ::jobs/monthly-budget-exhausted
           (exception-type
            #(jobs/submit-job! service "minor-unit-budget-3" request))))
    (is (= 2 (count @(:enqueued system))))))

(deftest billing-month-resets-at-fixed-utc-minus-eight
  (let [now (atom (Instant/parse "2026-07-31T23:00:00Z"))
        service (:service
                 (jobs/in-memory-system
                  {:clock (mutable-clock now)
                   :monthly-budget-minor-units 25
                   :render-reservation-minor-units 25}))]
    (is (:created? (jobs/submit-job! service "july-budget"
                                     (render-request))))
    (reset! now (Instant/parse "2026-08-01T07:30:00Z"))
    (is (= ::jobs/monthly-budget-exhausted
           (exception-type
            #(jobs/submit-job! service "still-july" (render-request)))))
    (reset! now (Instant/parse "2026-08-01T08:00:00Z"))
    (is (:created? (jobs/submit-job! service "august-budget"
                                     (render-request))))))

(deftest explicit-retry-reserves-compute-before-requeue
  (let [system (jobs/in-memory-system {:monthly-budget-minor-units 25
                                       :render-reservation-minor-units 25})
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
                  server (start-api! port {:job-service (:service system)})]
              (try
                (submit! port (str key-prefix "-1"))
                (submit! port (str key-prefix "-2"))
                (finally
                  (.close ^java.lang.AutoCloseable server)))))]
    (let [daily (second-submission {:daily-limit 1
                                    :monthly-budget-minor-units 1000}
                                   "daily-http")
          budget (second-submission {:monthly-budget-minor-units 25
                                     :render-reservation-minor-units 25}
                                    "budget-http")]
      (is (= 429 (.statusCode daily)))
      (is (= {:error "daily_submission_limit_exhausted"}
             (response-json daily)))
      (is (= 429 (.statusCode budget)))
      (is (= {:error "monthly_budget_exhausted"}
             (response-json budget))))))

(deftest transaction-contention-is-a-stable-retryable-http-error
  (let [port (available-port)
        service
        (reify jobs/JobService
          (submit-job! [_ _ _]
            (throw (ex-info "contention"
                            {:type ::jobs/transaction-contention
                             :retryable true})))
          (get-job [_ _] nil)
          (dispatch-job! [_ _] nil)
          (cancel-job! [_ _] nil)
          (retry-job! [_ _] nil)
          (run-job! [_ _] nil))
        server (start-api! port {:job-service service})]
    (try
      (let [response (submit! port "transaction-contention")]
        (is (= 503 (.statusCode response)))
        (is (= "1" (some-> response .headers
                           (.firstValue "Retry-After")
                           (.orElse nil))))
        (is (= {:error "transaction_contention" :retryable true}
               (response-json response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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
        server (start-api! port {:job-service service})]
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
        server (start-api! port {:job-service service
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
        server (start-api! port {:job-service (:service system)})]
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
        server (start-api! port {:job-service (:service system)})]
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

(deftest telemetry-job-rejections-share-the-preview-api-vocabulary
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (start-api! port {:job-service (:service system)})]
    (try
      (let [response (request! port :post "/v1/jobs"
                               (assoc (render-request)
                                      :telemetry
                                      "Date,Duration\n2026-07-17,30\n")
                               {"Content-Type" "application/json"
                                "Idempotency-Key" "invalid-telemetry"})
            body (response-json response)
            request-id (some-> response .headers
                               (.firstValue "x-request-id") (.orElse nil))]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"
                :category "request_contract"
                :failureCode "unsupported_telemetry_columns"
                :requestId request-id
                :retryable false
                :field "telemetry"
                :expectedSchema
                {:timestampColumns ["timestamp" "date/time" "datetime"]
                 :valueColumns ["heart_rate" "heart rate"
                                "heart rate (bpm)" "HR" "HR (bpm)"]}
                :documentationPath
                "/openapi.yaml#/components/schemas/RenderRequest"}
               body))
        (is (empty? @(:enqueued system))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest malformed-job-submission-is-an-invalid-request
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (start-api! port {:job-service (:service system)})]
    (try
      (let [response (raw-post! port "/v1/jobs" "{not-json"
                                {"Content-Type" "application/json"
                                 "Idempotency-Key" "malformed-render"})]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"} (response-json response)))
        (is (empty? @(:enqueued system))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest invalid-job-opacity-identifies-the-public-request-field
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (start-api! port {:job-service (:service system)})]
    (try
      (let [response (request! port :post "/v1/jobs"
                               (assoc (render-request)
                                      :futureTraceOpacityPercent 101)
                               {"Content-Type" "application/json"
                                "Idempotency-Key" "invalid-opacity"})]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"
                :field "futureTraceOpacityPercent"}
               (response-json response)))
        (is (empty? @(:enqueued system))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest job-submission-stores-the-normalized-request
  (let [port (available-port)
        received-request (promise)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id request]
                   (deliver received-request request)
                   {:output-bytes 10
                    :object "jobs/original/output.mov"}))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        server (start-api! port {:job-service service})
        submitted-request (render-request)]
    (try
      (let [submission (request! port :post "/v1/jobs" submitted-request
                                 {"Content-Type" "application/json"
                                  "Idempotency-Key" "original-render"})
            job-id (:id (response-json submission))]
        (request! port :post (str "/internal/v1/jobs/" job-id "/dispatch") {}
                  {"X-CloudTasks-TaskName" "tasks/original-render"})
        (jobs/run-job! service job-id)
        (is (= (assoc submitted-request :futureTraceOpacityPercent 25)
               @received-request)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest authenticated-duplicate-dispatch-holds-at-most-five-leases
  (let [port (available-port)
        system (jobs/in-memory-system)
        server (start-api! port {:job-service (:service system)})]
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
        server (start-api! port {:job-service (:service system)})]
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
        server (start-api! port {:job-service (:service system)})]
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

(deftest cloud-run-cancelled-future-is-a-successful-idempotent-api-cancellation
  (let [port (available-port)
        cancellation-attempts (atom [])
        launcher
        (reify jobs/JobLauncher
          (launch-job! [_ job-id] (str "executions/" job-id))
          (cancel-execution! [_ execution]
            (swap! cancellation-attempts conj execution)
            (throw (CancellationException. "execution cancelled"))))
        system (jobs/in-memory-system {:launcher launcher})
        server (start-api! port {:job-service (:service system)})]
    (try
      (let [job-id (:id (response-json (submit! port "cancelled-future")))
            dispatch
            (request! port :post
                      (str "/internal/v1/jobs/" job-id "/dispatch") {}
                      {"X-CloudTasks-TaskName" "tasks/cancelled-future"})
            first-cancel
            (request! port :post (str "/v1/jobs/" job-id "/cancel") {} {})
            repeated-cancel
            (request! port :post (str "/v1/jobs/" job-id "/cancel") {} {})]
        (is (= 202 (.statusCode dispatch)))
        (is (= 200 (.statusCode first-cancel)))
        (is (= "cancelled" (:state (response-json first-cancel))))
        (is (= 200 (.statusCode repeated-cancel)))
        (is (= (response-json first-cancel)
               (response-json repeated-cancel)))
        (is (= [(str "executions/" job-id)] @cancellation-attempts))
        (is (= 1 (count @(:enqueued system)))
            "cancellation cannot create another attempt")
        (is (nil? (get-in @(:state system) [:jobs job-id :lease]))
            "successful cancellation releases capacity"))
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

(deftest composition-timeout-is-durable-and-releases-the-render-lease
  (let [worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (errors/raise! "bounded render timeout"
                                  {:type ::composition-timeout
                                   :failure-code "composition_timeout"
                                   :stage "composition_encode"
                                   :timeout-ms 50
                                   :retryable true})))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        job-id (get-in (jobs/submit-job! service "timed-render"
                                         (render-request))
                       [:job :id])]
    (jobs/dispatch-job! service job-id)
    (try
      (jobs/run-job! service job-id)
      (catch clojure.lang.ExceptionInfo _))
    (let [failed (jobs/get-job service job-id)]
      (is (= {:state "failed"
              :failureCode "composition_timeout"
              :stage "composition_encode"
              :timeoutMs 50
              :retryable true}
             (select-keys failed [:state :failureCode :stage :timeoutMs
                                  :retryable])))
      (is (str/includes? (ui/job-fragment failed) "Deadline")))
    (is (nil? (get-in @(:state system) [:jobs job-id :lease])))))

(deftest typed-render-failure-has-one-privacy-safe-public-diagnosis
  (let [worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (throw
                    (errors/raise!
                     "private-source.mov secret-token"
                     {:type ::source-download-failed
                      :failure-code "source_download_failed"
                      :stage "source_content"
                      :status 503
                      :retryable true}
                     (ex-info "nested private-file-id" {:token "secret"})))))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        job-id (get-in (jobs/submit-job! service "typed-render-failure"
                                         (render-request))
                       [:job :id])]
    (jobs/dispatch-job! service job-id)
    (try
      (jobs/run-job! service job-id)
      (catch clojure.lang.ExceptionInfo _))
    (let [failed (jobs/get-job service job-id)
          html (ui/job-fragment failed)
          serialized (pr-str failed)]
      (is (= {:failureCode "source_download_failed"
              :stage "source_content"
              :status 503
              :retryable true
              :attempt 1}
             (select-keys failed [:failureCode :stage :status :retryable
                                  :attempt])))
      (is (nat-int? (:elapsedMs failed)))
      (is (str/includes? html "Source content"))
      (is (str/includes? html "Retryable: yes"))
      (is (not (re-find #"private|secret|token|file-id" serialized))))
    (is (= {:state "queued" :attempt 2}
           (select-keys (jobs/retry-job! service job-id) [:state :attempt])))
    (is (empty? (select-keys (jobs/get-job service job-id)
                             [:failureCode :stage :status :retryable
                              :elapsedMs])))))

(deftest nonretryable-failure-rejects-a-stale-direct-retry
  (let [port (available-port)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (errors/raise! "fixture failure"
                                  {:type ::nonretryable-failure
                                   :failure-code "composition_encode_failed"
                                   :stage "composition_encode"
                                   :retryable false})))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        server (start-api! port {:job-service service})]
    (try
      (let [job-id (:id (response-json (submit! port "nonretryable-retry")))]
        (jobs/dispatch-job! service job-id)
        (try
          (jobs/run-job! service job-id)
          (catch clojure.lang.ExceptionInfo _))
        (is (false? (:retryable (jobs/get-job service job-id))))
        (let [enqueued-before (count @(:enqueued system))
              retry-response
              (request! port :post (str "/v1/jobs/" job-id "/retry") {} {})]
          (is (= 409 (.statusCode retry-response)))
          (is (= {:error "invalid_job_transition"}
                 (response-json retry-response)))
          (is (= {:state "failed" :attempt 1 :retryable false}
                 (select-keys (jobs/get-job service job-id)
                              [:state :attempt :retryable])))
          (is (= enqueued-before (count @(:enqueued system))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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
      (is (= "completion_persistence" (:stage failed)))
      (is (false? (:retryable failed)))
      (is (nat-int? (:elapsedMs failed)))
      (is (nil? (:output failed))))))

(deftest renderer-attempt-must-match-the-durable-job-attempt
  (let [rendered (atom 0)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (swap! rendered inc)
                   {:output-bytes 10}))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        job-id (get-in (jobs/submit-job! service "attempt-correlation"
                                         (render-request))
                       [:job :id])]
    (jobs/dispatch-job! service job-id)
    (is (= ::jobs/invalid-render-attempt
           (exception-type #(jobs/run-job-attempt! service job-id 2))))
    (is (zero? @rendered))
    (is (= "running" (:state (jobs/get-job service job-id))))
    (is (= "succeeded"
           (:state (jobs/run-job-attempt! service job-id 1))))
    (is (= 1 @rendered))))

(deftest upload-grant-is-put-only-content-locked-and-short-lived
  (let [port (available-port)
        signer (reify jobs/UploadSigner
                 (signed-upload [_ object content-type expires-seconds]
                   (str "https://upload.test/" object
                        "?content-type=" content-type
                        "&expires=" expires-seconds)))
        server (start-api! port {:upload-signer signer})]
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
        server (start-api! port {:job-service (:service system)})]
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

(deftest revocation-racing-submission-leaves-no-member-job-active
  (let [{:keys [directory]} (admin/in-memory-system
                             {:owner-email "owner@example.com"
                              :initial-emails #{"member@example.com"}})
        job-system (jobs/in-memory-system {:member-directory directory})
        job-service (:service job-system)
        events (atom [])
        admin-service (admin/service {:directory directory
                                      :job-administration job-service
                                      :event-sink #(swap! events conj %)})
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")
        member (admin/authorize-member! directory "member@example.com"
                                        "member-subject")
        member-request (assoc (render-request)
                              :requesterSubject (:subject member)
                              :requesterEmail (:email member)
                              :requesterMembershipVersion
                              (:membership-version member))
        retry-id (get-in (jobs/submit-job! job-service "revoked-retry"
                                           member-request)
                         [:job :id])
        _ (jobs/cancel-job! job-service retry-id)
        start (promise)
        submissions
        (mapv (fn [index]
                (future
                  @start
                  (try
                    (jobs/submit-job! job-service (str "racing-" index)
                                      member-request)
                    (catch clojure.lang.ExceptionInfo error
                      error))))
              (range 30))
        revocation (future
                     @start
                     (admin/revoke-member! admin-service owner
                                           "member@example.com"))]
    (deliver start true)
    @revocation
    (let [results (mapv deref submissions)
          submitted (keep #(when (map? %) (get-in % [:job :id])) results)
          rejected (keep #(when (instance? clojure.lang.ExceptionInfo %) %)
                         results)]
      (is (= 30 (+ (count submitted) (count rejected))))
      (is (every? #(= ::jobs/member-not-allowlisted
                      (:type (ex-data %)))
                  rejected))
      (is (every? #(= "cancelled" (:state (jobs/get-job job-service %)))
                  submitted))
      (is (= (count submitted)
             (:jobsCancelled (last @events))))
      (is (= ::jobs/member-not-allowlisted
             (try
               (jobs/retry-job! job-service retry-id)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))))
