(ns agg.gcp-jobs-test
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as admin-gcp]
            [agg.auth.core :as auth]
            [agg.auth.gcp :as auth-gcp]
            [agg.errors :as errors]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.jobs-test :as fixture]
            [agg.tokens.core :as tokens]
            [agg.tokens.gcp :as tokens-gcp]
            [clojure.test :refer [deftest is]])
  (:import (com.google.api.gax.rpc AbortedException AlreadyExistsException
                                   CancelledException StatusCode StatusCode$Code)
           (com.google.cloud.firestore FirestoreException FirestoreOptions)
           (com.google.cloud.run.v2 Container Execution TaskTemplate)
           (com.google.cloud.storage StorageOptions)
           (com.google.protobuf Timestamp)
           (io.grpc Status)
           (java.time Clock Instant ZoneOffset)
           (java.util Date)
           (java.util.concurrent CancellationException CompletableFuture
                                 ExecutionException)))

(deftest duplicate-task-delivery-is-idempotent
  (let [status (reify StatusCode
                 (getCode [_] StatusCode$Code/ALREADY_EXISTS)
                 (getTransportCode [_] nil))
        error (AlreadyExistsException. "duplicate task" nil status false)]
    (is (#'gcp/duplicate-task? error))))

(defn- nested-transaction-failure [cause]
  (let [firestore-error
        (FirestoreException/forServerRejection
         Status/UNKNOWN cause "transaction failed" (object-array 0))]
    (ExecutionException. firestore-error)))

(defn- gax-aborted-failure []
  (let [status-code (reify StatusCode
                      (getCode [_] StatusCode$Code/ABORTED)
                      (getTransportCode [_] Status/ABORTED))]
    (nested-transaction-failure
     (AbortedException. "transaction contention"
                        (.asRuntimeException Status/ABORTED)
                        status-code false))))

(deftest nested-transaction-contention-is-classified-through-job-submission
  (let [{:keys [directory]} (admin/in-memory-system
                             {:owner-email "owner@example.com"
                              :initial-emails #{"member@example.com"}})
        member (admin/authorize-member! directory "member@example.com"
                                        "member-subject")
        firestore (-> (FirestoreOptions/newBuilder)
                      (.setProjectId "nested-contention-test")
                      .build
                      .getService)
        request-store (reify jobs/RequestStore
                        (save-request! [_ job-id _request]
                          (str "jobs/" job-id "/request.json"))
                        (load-request [_ _object] nil))
        queue (reify jobs/JobQueue
                (enqueue-job! [_ _job-id _attempt]))
        service (gcp/job-service {:firestore firestore
                                  :request-store request-store
                                  :queue queue
                                  :member-directory directory})
        request (assoc (fixture/render-request)
                       :requesterSubject (:subject member)
                       :requesterEmail (:email member)
                       :requesterMembershipVersion
                       (:membership-version member))
        gax-contention (gax-aborted-failure)
        grpc-contention
        (nested-transaction-failure (.asRuntimeException Status/ABORTED))
        submit-error
        (fn [idempotency-key failure]
          (with-redefs-fn
            {#'gcp/transaction! (fn [_firestore _callback]
                                  (throw failure))}
            #(try
               (jobs/submit-job! service idempotency-key request)
               nil
               (catch Throwable error
                 error))))]
    (try
      (is (= ::jobs/transaction-contention
             (some-> (submit-error "nested-gax-contention" gax-contention)
                     ex-data
                     :type)))
      (is (= ::jobs/transaction-contention
             (some-> (submit-error "nested-grpc-contention" grpc-contention)
                     ex-data
                     :type)))
      (admin/revoke-member-record! directory "member@example.com")
      (is (= ::jobs/member-not-allowlisted
             (some-> (submit-error "nested-revoked-contention" gax-contention)
                     ex-data
                     :type)))
      (let [unrelated
            (nested-transaction-failure
             (.asRuntimeException Status/UNAVAILABLE))]
        (is (identical? unrelated
                        (submit-error "nested-unrelated-failure" unrelated))
            "An unrelated nested transaction failure passes through"))
      (finally
        (.close firestore)))))

(defn- retry-transaction-contention [action]
  (loop [remaining 20]
    (let [result (try
                   {:value (action)}
                   (catch clojure.lang.ExceptionInfo error
                     {:error error}))]
      (if-let [error (:error result)]
        (if (and (< 1 remaining)
                 (= ::jobs/transaction-contention (:type (ex-data error))))
          (recur (dec remaining))
          (throw error))
        (:value result)))))

(defn- mutable-clock [now]
  (letfn [(clock-for [zone]
            (proxy [Clock] []
              (getZone [] zone)
              (withZone [new-zone] (clock-for new-zone))
              (instant [] @now)
              (millis [] (.toEpochMilli ^Instant @now))))]
    (clock-for ZoneOffset/UTC)))

(deftest firestore-preview-evidence-is-private-owner-bound-and-expiring
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-preview-evidence-test")
                        (.setEmulatorHost host)
                        .build .getService)
          requests (atom {})
          now (atom (Instant/parse "2026-07-20T10:00:00Z"))
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _ _]))
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :clock (mutable-clock now)})
          identity {:subject "preview-owner"
                    :membership-version "membership-1"}
          request (assoc (fixture/render-request)
                         :previewOperation jobs/preview-operation-version
                         :requesterSubject (:subject identity)
                         :requesterEmail "owner@example.com"
                         :requesterMembershipVersion
                         (:membership-version identity))]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [operation-id
              (get-in (jobs/submit-job! service "preview-evidence" request)
                      [:job :id])
              reference (.document (.collection firestore "jobs") operation-id)]
          (.get (.update reference
                         {"state" "succeeded"
                          "updatedAt" (.toEpochMilli ^Instant @now)}))
          (let [snapshot (.get (.get reference))
                public (jobs/get-job service operation-id)]
            (is (re-matches #"[0-9a-f]{64}"
                            (.getString snapshot "requestDigest")))
            (is (not (contains? public :requestDigest)))
            (is (true? (jobs/require-preview-evidence!
                        service operation-id (fixture/render-request)
                        identity))))
          (is (= ::jobs/preview-stale
                 (try
                   (jobs/require-preview-evidence!
                    service operation-id (fixture/render-request)
                    (assoc identity :membership-version "membership-2"))
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (swap! now #(.plusSeconds ^Instant %
                                    (inc jobs/preview-evidence-seconds)))
          (is (= ::jobs/preview-expired
                 (try
                   (jobs/require-preview-evidence!
                    service operation-id (fixture/render-request) identity)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error)))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest cloud-run-override-replaces-args-without-an-invalid-clear-flag
  (let [request (#'gcp/run-job-request
                 "projects/test/locations/europe-central2/jobs/renderer"
                 "job-id" 3)
        override (-> request .getOverrides (.getContainerOverrides 0))]
    (is (false? (.getClearArgs override)))
    (is (= ["clojure.main" "-m" "agg.renderer.main"
            "--job-id" "job-id" "--attempt" "3"]
           (vec (.getArgsList override))))))

(defn- cloud-run-execution [name job-id attempt completed?]
  (let [container (-> (Container/newBuilder)
                      (.addAllArgs ["clojure.main" "-m" "agg.renderer.main"
                                    "--job-id" job-id
                                    "--attempt" (str attempt)])
                      .build)
        template (-> (TaskTemplate/newBuilder)
                     (.addContainers container)
                     .build)
        builder (doto (Execution/newBuilder)
                  (.setName name)
                  (.setTemplate template))]
    (when completed?
      (.setCompletionTime builder (-> (Timestamp/newBuilder)
                                      (.setSeconds 1)
                                      .build)))
    (.build builder)))

(deftest cloud-run-execution-correlation-is-active-and-attempt-exact
  (let [unrelated (cloud-run-execution "executions/unrelated" "other-job" 2
                                       false)
        completed (cloud-run-execution "executions/completed" "job-id" 2 true)
        active (cloud-run-execution "executions/active" "job-id" 3 false)]
    (is (= "executions/active"
           (#'gcp/active-execution-for-attempt
            [unrelated completed active] "job-id" 3)))
    (is (nil? (#'gcp/active-execution-for-attempt
               [unrelated completed active] "job-id" 2))
        "a completed execution cannot be adopted by a later retry")))

(deftest cloud-run-cancelled-operation-result-is-successful
  (let [status (reify StatusCode
                 (getCode [_] StatusCode$Code/CANCELLED)
                 (getTransportCode [_] Status/CANCELLED))
        operation (CompletableFuture.)
        failed-status (reify StatusCode
                        (getCode [_] StatusCode$Code/ABORTED)
                        (getTransportCode [_] Status/ABORTED))
        failed-operation (CompletableFuture.)]
    (.completeExceptionally
     operation
     (CancelledException. "execution cancelled" nil status false))
    (.completeExceptionally
     failed-operation
     (AbortedException. "remote cancellation failed" nil failed-status true))
    (is (nil? (#'gcp/await-cancellation! operation)))
    (is (thrown-with-msg? AbortedException #"remote cancellation failed"
                          (#'gcp/await-cancellation! failed-operation)))))

(deftest signed-upload-is-a-bounded-content-type-locked-v4-put
  (let [signer (reify com.google.auth.ServiceAccountSigner
                 (getAccount [_] "uploader@example.test")
                 (sign [_ _] (byte-array 256)))
        store (gcp/->GcsRequestStore
               (.getService (StorageOptions/getDefaultInstance))
               "temporary-test-bucket"
               signer)
        url (jobs/signed-upload store "uploads/request.json"
                                "application/json" 900)]
    (is (re-find #"X-Goog-Algorithm=GOOG4-RSA-SHA256" url))
    (is (re-find #"X-Goog-Expires=900" url))
    (is (re-find #"X-Goog-SignedHeaders=content-type%3Bhost" url))
    (is (= ::gcp/invalid-upload-expiry
           (try
             (jobs/signed-upload store "uploads/request.json"
                                 "application/json" 901)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest firestore-transactions-deduplicate-launch-and-capacity
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          enqueued (atom #{})
          launched (atom [])
          cancelled (atom [])
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ job-id attempt]
                    (swap! enqueued conj [job-id attempt])))
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id]
                       (let [execution (str "executions/" job-id)]
                         (swap! launched conj execution)
                         execution))
                     (cancel-execution! [_ execution]
                       (swap! cancelled conj execution)))
          worker (reify jobs/RenderWorker
                   (perform-render! [_ job-id _]
                     {:output-bytes 10
                      :object (str "jobs/" job-id "/output.mov")}))
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :launcher launcher
                                    :worker worker
                                    :clock (Clock/systemUTC)})]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [submissions (->> (range 10)
                               (mapv (fn [_]
                                       (future
                                         (retry-transaction-contention
                                          #(jobs/submit-job!
                                            service "emulator-idempotency"
                                            (fixture/render-request))))))
                               (mapv deref))
              job-id (get-in (first submissions) [:job :id])]
          (is (= 1 (count (set (map #(get-in % [:job :id]) submissions)))))
          (is (= #{[job-id 1]} @enqueued))
          (let [dispatches (->> (range 10)
                                (mapv (fn [_]
                                        (future (jobs/dispatch-job! service job-id))))
                                (mapv deref))]
            (is (= 1 (count (filter :started? dispatches))))
            (is (= 1 (count @launched))))
          (let [more-job-ids
                (mapv (fn [index]
                        (get-in (jobs/submit-job!
                                 service (str "emulator-capacity-" index)
                                 (fixture/render-request))
                                [:job :id]))
                      (range 5))]
            (doseq [id (take 4 more-job-ids)]
              (jobs/dispatch-job! service id))
            (is (= ::jobs/capacity-exhausted
                   (try
                     (jobs/dispatch-job! service (last more-job-ids))
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))
            (is (= ::jobs/capacity-exhausted
                   (try
                     (jobs/submit-job! service "capacity-submit-overflow"
                                       (fixture/render-request))
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-admission-load-enforces-daily-and-budget-boundaries
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-admission-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          enqueued (atom [])
          fixed-now (Instant/parse "2026-07-17T10:00:00Z")
          clock (Clock/fixed fixed-now ZoneOffset/UTC)
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ job-id attempt]
                    (swap! enqueued conj [job-id attempt])))
          service (fn [monthly-budget-minor-units]
                    (gcp/job-service {:firestore firestore
                                      :request-store request-store
                                      :queue queue
                                      :clock clock
                                      :monthly-budget-minor-units monthly-budget-minor-units
                                      :render-reservation-minor-units 25}))
          exception-type (fn [f]
                           (try
                             (f)
                             nil
                             (catch clojure.lang.ExceptionInfo error
                               (:type (ex-data error)))))]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [daily-service (service 100000)
              first-job (jobs/submit-job! daily-service "daily-load-0"
                                          (fixture/render-request))
              first-snapshot (.get
                              (.get (.document (.collection firestore "jobs")
                                               (get-in first-job [:job :id]))))]
          (is (= (Date/from (.plusSeconds fixed-now (* 90 24 60 60)))
                 (.getDate first-snapshot "expireAt")))
          (doseq [index (range 1 100)]
            (is (:created?
                 (jobs/submit-job! daily-service (str "daily-load-" index)
                                   (fixture/render-request)))))
          (is (false? (:created?
                       (jobs/submit-job! daily-service "daily-load-99"
                                         (fixture/render-request)))))
          (is (= ::jobs/daily-submission-limit-exhausted
                 (exception-type
                  #(jobs/submit-job! daily-service "daily-load-100"
                                     (fixture/render-request))))))
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (reset! enqueued [])
        (let [budget-service (service 50)]
          (let [first-job (jobs/submit-job! budget-service "budget-load-1"
                                            (fixture/render-request))
                second-job (jobs/submit-job! budget-service "budget-load-2"
                                             (fixture/render-request))
                budget-snapshot
                (.get (.get (.document (.collection firestore "orchestration")
                                       "budget-2026-07")))]
            (is (:created? first-job))
            (is (:created? second-job))
            (is (= 50 (.getLong budget-snapshot "reservedMinorUnits")))
            (is (= 50 (.getLong budget-snapshot "limitMinorUnits")))
            (is (= 25 (.getLong budget-snapshot "reservationMinorUnits")))
            (is (= "PLN" (.getString budget-snapshot "currency")))
            (jobs/cancel-job! budget-service (get-in first-job [:job :id]))
            (is (= ::jobs/monthly-budget-exhausted
                   (exception-type
                    #(jobs/retry-job! budget-service
                                      (get-in first-job [:job :id]))))))
          (is (= ::jobs/monthly-budget-exhausted
                 (exception-type
                  #(jobs/submit-job! budget-service "budget-load-3"
                                     (fixture/render-request)))))
          (is (= 2 (count @enqueued))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-billing-month-resets-at-fixed-utc-minus-eight
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-billing-month-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          now (atom (Instant/parse "2026-07-31T23:00:00Z"))
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          service (gcp/job-service
                   {:firestore firestore
                    :request-store request-store
                    :queue queue
                    :clock (mutable-clock now)
                    :monthly-budget-minor-units 25
                    :render-reservation-minor-units 25})
          exception-type
          (fn [f]
            (try
              (f)
              nil
              (catch clojure.lang.ExceptionInfo error
                (:type (ex-data error)))))]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (is (:created? (jobs/submit-job! service "firestore-july-budget"
                                         (fixture/render-request))))
        (reset! now (Instant/parse "2026-08-01T07:30:00Z"))
        (is (= ::jobs/monthly-budget-exhausted
               (exception-type
                #(jobs/submit-job! service "firestore-still-july"
                                   (fixture/render-request)))))
        (reset! now (Instant/parse "2026-08-01T08:00:00Z"))
        (is (:created? (jobs/submit-job! service "firestore-august-budget"
                                         (fixture/render-request))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-reconciliation-repairs-stale-jobs-and-orphaned-leases
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-reconcile-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id] (str "executions/" job-id))
                     (cancel-execution! [_ _execution]))
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :launcher launcher})]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [job-id (get-in (jobs/submit-job! service "stale-emulator-job"
                                               (fixture/render-request))
                             [:job :id])
              job-ref (.document (.collection firestore "jobs") job-id)
              capacity-ref (.document (.collection firestore "orchestration")
                                      "capacity")]
          (jobs/dispatch-job! service job-id)
          (.get (.update job-ref {"leaseExpiresAt" 0}))
          (.get (.set capacity-ref
                      {"leases" {job-id 0
                                 "missing-job" 9999999999999}}))
          (is (= {:repaired-jobs 1 :released-leases 2}
                 (jobs/reconcile-jobs! service)))
          (is (= "failed" (:state (jobs/get-job service job-id))))
          (is (= "stale_lease" (:failureCode (jobs/get-job service job-id))))
          (is (= {}
                 (get (.getData (.get (.get capacity-ref))) "leases")))
          (is (= {:repaired-jobs 0 :released-leases 0}
                 (jobs/reconcile-jobs! service))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-reconciliation-adopts-an-accepted-unrecorded-execution
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-execution-recovery-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          launched (atom [])
          cancelled (atom [])
          fail-next-cancellation? (atom false)
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          launcher
          (reify
            jobs/JobLauncher
            (launch-job! [_ job-id]
              (let [execution (str "executions/" job-id "-attempt-1")]
                (swap! launched conj {:job-id job-id
                                      :attempt 1
                                      :execution execution})
                execution))
            (cancel-execution! [_ execution]
              (swap! cancelled conj execution)
              (when (compare-and-set! fail-next-cancellation? true false)
                (throw (ex-info "simulated cancellation failure" {})))
              (throw (CancellationException. "execution cancelled")))
            jobs/RecoverableJobLauncher
            (launch-job-attempt! [_ job-id attempt]
              (let [execution (str "executions/" job-id "-attempt-" attempt)]
                (swap! launched conj {:job-id job-id
                                      :attempt attempt
                                      :execution execution})
                execution))
            (find-active-execution [_ job-id attempt]
              (some (fn [accepted]
                      (when (= [job-id attempt]
                               ((juxt :job-id :attempt) accepted))
                        (:execution accepted)))
                    @launched)))
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :launcher launcher})]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [job-id (get-in (jobs/submit-job! service "accepted-unrecorded"
                                               (fixture/render-request))
                             [:job :id])
              job-ref (.document (.collection firestore "jobs") job-id)
              capacity-ref (.document (.collection firestore "orchestration")
                                      "capacity")]
          (jobs/dispatch-job! service job-id)
          (let [future-lease 4102444800000]
            (.get (.update job-ref
                           {"state" "launching"
                            "execution" (com.google.cloud.firestore.FieldValue/delete)
                            "leaseExpiresAt" future-lease}))
            (.get (.set capacity-ref {"leases" {job-id future-lease}})))

          (is (= {:repaired-jobs 1 :released-leases 0}
                 (jobs/reconcile-jobs! service)))
          (is (= "running" (:state (jobs/get-job service job-id))))
          (is (= 1 (count @launched)))
          (is (false? (:started? (jobs/dispatch-job! service job-id))))
          (is (= 1 (count @launched))
              "redelivery does not launch a second renderer attempt")
          (is (pos? (long (get-in (.getData (.get (.get capacity-ref)))
                                  ["leases" job-id])))))
        (let [job-id (get-in (jobs/submit-job! service "cancel-unrecorded"
                                               (fixture/render-request))
                             [:job :id])
              job-ref (.document (.collection firestore "jobs") job-id)
              capacity-ref (.document (.collection firestore "orchestration")
                                      "capacity")
              execution (str "executions/" job-id "-attempt-1")
              future-lease 4102444800000]
          (jobs/dispatch-job! service job-id)
          (.get (.update job-ref
                         {"state" "launching"
                          "execution" (com.google.cloud.firestore.FieldValue/delete)
                          "leaseExpiresAt" future-lease}))
          (jobs/cancel-job! service job-id)

          (reset! fail-next-cancellation? true)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"simulated cancellation failure"
                                (jobs/reconcile-jobs! service)))
          (is (= "cancellation-requested"
                 (:state (jobs/get-job service job-id))))
          (is (pos? (long (get-in (.getData (.get (.get capacity-ref)))
                                  ["leases" job-id])))
              "failed cancellation keeps capacity reserved")
          (is (= {:repaired-jobs 1 :released-leases 1}
                 (jobs/reconcile-jobs! service)))
          (is (= "cancelled" (:state (jobs/get-job service job-id))))
          (is (= [execution execution] @cancelled)
              "reconciliation accepts the cancelled operation future for the exact orphan execution")
          (is (= 2 (count @launched))
              "cancellation retry does not launch another renderer")
          (is (nil? (get-in (.getData (.get (.get capacity-ref)))
                            ["leases" job-id]))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-terminal-transitions-release-capacity-atomically
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-terminal-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          failing-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ _job-id]
              (throw (ex-info "forced launch failure" {})))
            (cancel-execution! [_ _execution]))
          successful-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ job-id] (str "executions/" job-id))
            (cancel-execution! [_ _execution]))
          launch-started (promise)
          allow-launch (promise)
          cancelled-executions (atom [])
          blocking-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ job-id]
              (deliver launch-started true)
              @allow-launch
              (str "executions/" job-id))
            (cancel-execution! [_ execution]
              (swap! cancelled-executions conj execution)))
          worker (reify jobs/RenderWorker
                   (perform-render! [_ job-id _request]
                     {:output-bytes 10
                      :object (str "jobs/" job-id "/output.mov")}))
          worker-started (promise)
          allow-worker (promise)
          blocking-worker
          (reify jobs/RenderWorker
            (perform-render! [_ job-id _request]
              (deliver worker-started true)
              @allow-worker
              {:output-bytes 10
               :object (str "jobs/" job-id "/output.mov")}))
          service (fn [launcher]
                    (gcp/job-service {:firestore firestore
                                      :request-store request-store
                                      :queue queue
                                      :launcher launcher
                                      :worker worker
                                      :clock (Clock/systemUTC)}))]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [job-id (get-in (jobs/submit-job!
                              (service failing-launcher)
                              "terminal-launch-failure"
                              (fixture/render-request))
                             [:job :id])]
          (try
            (jobs/dispatch-job! (service failing-launcher) job-id)
            (catch clojure.lang.ExceptionInfo _))
          (is (= "launch_failed"
                 (:failureCode
                  (jobs/get-job (service failing-launcher) job-id)))))
        (let [render-service (service successful-launcher)
              job-id (get-in (jobs/submit-job!
                              render-service
                              "terminal-worker-success"
                              (fixture/render-request))
                             [:job :id])]
          (jobs/dispatch-job! render-service job-id)
          (is (= ::jobs/invalid-render-attempt
                 (try
                   (jobs/run-job-attempt! render-service job-id 2)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (is (= "running" (:state (jobs/get-job render-service job-id))))
          (is (= "succeeded"
                 (:state (jobs/run-job-attempt! render-service job-id 1)))))
        (let [diagnostic-worker
              (reify jobs/RenderWorker
                (perform-render! [_ _job-id _request]
                  (errors/raise! "private failure"
                                 {:type ::typed-render-failure
                                  :failure-code "composition_encode_failed"
                                  :stage "composition_encode"
                                  :status 422
                                  :retryable false})))
              diagnostic-service
              (gcp/job-service {:firestore firestore
                                :request-store request-store
                                :queue queue
                                :launcher successful-launcher
                                :worker diagnostic-worker
                                :clock (Clock/systemUTC)})
              job-id (get-in (jobs/submit-job!
                              diagnostic-service
                              "terminal-worker-diagnostics"
                              (fixture/render-request))
                             [:job :id])]
          (jobs/dispatch-job! diagnostic-service job-id)
          (let [cause (try
                        (jobs/run-job-attempt! diagnostic-service job-id 1)
                        (catch clojure.lang.ExceptionInfo error error))
                failed (jobs/get-job diagnostic-service job-id)]
            (is (= 1 (:attempt (ex-data cause))))
            (is (nil? (:job-id (ex-data cause))))
            (is (= {:failureCode "composition_encode_failed"
                    :stage "composition_encode"
                    :status 422
                    :retryable false
                    :attempt 1}
                   (select-keys failed [:failureCode :stage :status :retryable
                                        :attempt])))
            (is (nat-int? (:elapsedMs failed)))))
        (let [race-service (service blocking-launcher)
              job-id (get-in (jobs/submit-job!
                              race-service
                              "cancel-during-launch"
                              (fixture/render-request))
                             [:job :id])
              dispatch (future (jobs/dispatch-job! race-service job-id))]
          @launch-started
          (is (= "cancellation-requested"
                 (:state (jobs/cancel-job! race-service job-id))))
          (deliver allow-launch true)
          @dispatch
          (is (= "cancelled" (:state (jobs/get-job race-service job-id))))
          (is (= [(str "executions/" job-id)] @cancelled-executions)))
        (let [worker-race-service
              (gcp/job-service {:firestore firestore
                                :request-store request-store
                                :queue queue
                                :launcher successful-launcher
                                :worker blocking-worker
                                :clock (Clock/systemUTC)})
              job-id (get-in (jobs/submit-job!
                              worker-race-service
                              "cancel-during-worker-completion"
                              (fixture/render-request))
                             [:job :id])]
          (jobs/dispatch-job! worker-race-service job-id)
          (let [render-result (future
                                (jobs/run-job! worker-race-service job-id))]
            @worker-started
            (is (= "cancelled"
                   (:state (jobs/cancel-job! worker-race-service job-id))))
            (deliver allow-worker true)
            (is (= "cancelled" (:state @render-result)))
            (is (= "cancelled"
                   (:state (jobs/get-job worker-race-service job-id))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-member-revocation-races-submission-and-cancels-active-jobs
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-member-job-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          cancelled (atom [])
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id] (str "executions/" job-id))
                     (cancel-execution! [_ execution]
                       (swap! cancelled conj execution)))
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          directory (admin-gcp/member-directory firestore "owner@example.com")
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :launcher launcher
                                    :member-directory directory})
          administration (admin/service {:directory directory
                                         :job-administration service})]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [owner (admin/authorize-member! directory "owner@example.com"
                                             "owner-subject")
              _ (admin/add-member! administration owner "member@example.com")
              member (admin/authorize-member! directory "member@example.com"
                                              "member-subject")
              request (assoc (fixture/render-request)
                             :requesterSubject (:subject member)
                             :requesterEmail (:email member)
                             :requesterMembershipVersion
                             (:membership-version member))
              retry-id (get-in (jobs/submit-job! service "revoked-member-retry"
                                                 request)
                               [:job :id])
              _ (jobs/cancel-job! service retry-id)
              running-id (get-in (jobs/submit-job! service "running-member-job"
                                                   request)
                                 [:job :id])
              _ (jobs/dispatch-job! service running-id)
              start (promise)
              submissions (mapv (fn [index]
                                  (future
                                    @start
                                    (try
                                      (jobs/submit-job! service
                                                        (str "member-race-" index)
                                                        request)
                                      (catch Throwable error
                                        error))))
                                (range 20))
              revocation (future
                           @start
                           (admin/revoke-member! administration owner
                                                 "member@example.com"))]
          (deliver start true)
          @revocation
          (let [results (mapv deref submissions)
                successful (filterv map? results)
                submitted (cons running-id
                                (map #(get-in % [:job :id]) successful))
                rejected (remove map? results)
                job-count (-> (.collection firestore "jobs")
                              .get .get .getDocuments count)
                idempotency-count (-> (.collection firestore "job-idempotency")
                                      .get .get .getDocuments count)]
            (is (every? #(and (instance? clojure.lang.ExceptionInfo %)
                              (contains? #{::jobs/member-not-allowlisted
                                           ::jobs/transaction-contention}
                                         (:type (ex-data %))))
                        rejected))
            (is (= (+ 2 (count successful)) job-count)
                "A rejected submit never writes a job document")
            (is (= (+ 2 (count successful)) idempotency-count)
                "A rejected submit never writes an idempotency document")
            (is (every? #(= "cancelled" (:state (jobs/get-job service %)))
                        submitted))
            (is (= [(str "executions/" running-id)] @cancelled))
            (is (= ::jobs/member-not-allowlisted
                   (try
                     (jobs/submit-job! service "after-member-revocation" request)
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))
            (is (= ::jobs/member-not-allowlisted
                   (try
                     (jobs/retry-job! service retry-id)
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest owner-rotation-startup-revokes-access-grants-and-active-work
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId
                         "animated-graph-cloud-owner-cleanup-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          requests (atom {})
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          cancelled (atom [])
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id] (str "executions/" job-id))
                     (cancel-execution! [_ execution]
                       (swap! cancelled conj execution)))
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))]
      (try
        (doseq [collection ["members" "administration" "owner-revocations"
                            "personal-tokens" "drive-grants" "jobs"
                            "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory-a (admin-gcp/member-directory
                           firestore "owner-a@example.com")
              token-service (tokens/service
                             {:store (tokens-gcp/token-store firestore)
                              :pepper (byte-array 32)})
              grant-store (auth-gcp/grant-store firestore directory-a)
              job-service (gcp/job-service {:firestore firestore
                                            :request-store request-store
                                            :queue queue
                                            :launcher launcher
                                            :member-directory directory-a})
              owner-a (admin/authorize-member! directory-a
                                               "owner-a@example.com"
                                               "owner-a-subject")
              personal-token (tokens/create-token! token-service owner-a
                                                   "Owner automation")
              _ (auth/save-member-grant!
                 grant-store owner-a
                 {:refresh-token-ciphertext "kms-ciphertext"
                  :folder-id "drive-folder"
                  :revoked? false})
              request (assoc (fixture/render-request)
                             :requesterSubject (:subject owner-a)
                             :requesterEmail (:email owner-a)
                             :requesterMembershipVersion
                             (:membership-version owner-a))
              queued-id (get-in (jobs/submit-job! job-service
                                                  "rotated-owner-queued" request)
                                [:job :id])
              running-id (get-in (jobs/submit-job! job-service
                                                   "rotated-owner-running" request)
                                 [:job :id])
              _ (jobs/dispatch-job! job-service running-id)
              directory-b (admin-gcp/member-directory
                           firestore "owner-b@example.com")
              owner-b (admin/authorize-member! directory-b
                                               "owner-b@example.com"
                                               "owner-b-subject")
              events (atom [])
              startup-admin (admin/service
                             {:directory directory-b
                              :token-administration token-service
                              :credential-administration grant-store
                              :job-administration job-service
                              :event-sink #(swap! events conj %)})
              token-error (try
                            (tokens/authenticate token-service
                                                 (:token personal-token))
                            nil
                            (catch clojure.lang.ExceptionInfo error
                              (:type (ex-data error))))]
          (is (= ::tokens/invalid-token token-error))
          (is (nil? (auth/load-grant grant-store (:subject owner-a))))
          (is (= "cancelled" (:state (jobs/get-job job-service queued-id))))
          (is (= "cancelled" (:state (jobs/get-job job-service running-id))))
          (is (= [(str "executions/" running-id)] @cancelled))
          (is (= 1 (count (filter #(= "owner_rotation_cleanup_complete"
                                      (:event %))
                                  @events))))
          (admin/add-member! startup-admin owner-b (:email owner-a))
          (let [new-member-a (admin/authorize-member! directory-b
                                                      (:email owner-a)
                                                      (:subject owner-a))]
            (is (not= (:membership-version owner-a)
                      (:membership-version new-member-a)))
            (is (nil? (auth/load-grant grant-store (:subject new-member-a))))
            (is (= "cancelled" (:state (jobs/get-job job-service queued-id))))
            (is (= "cancelled" (:state (jobs/get-job job-service running-id)))))
          (is (every? #(not-any? (fn [key] (contains? % key))
                                 [:email :subject :token :credential :job-id])
                      @events)))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest delayed-owner-cleanup-cannot-destroy-a-new-membership-generation
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId
                         "animated-graph-cloud-stale-owner-cleanup-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          requests (atom {})
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id] (str "executions/" job-id))
                     (cancel-execution! [_ _execution]))
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))]
      (try
        (doseq [collection ["members" "administration" "owner-revocations"
                            "personal-tokens" "drive-grants" "jobs"
                            "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory-a (admin-gcp/member-directory
                           firestore "owner-a@example.com")
              token-service (tokens/service
                             {:store (tokens-gcp/token-store firestore)
                              :pepper (byte-array 32)})
              grant-store (auth-gcp/grant-store firestore directory-a)
              job-service (gcp/job-service {:firestore firestore
                                            :request-store request-store
                                            :queue queue
                                            :launcher launcher
                                            :member-directory directory-a})
              owner-a (admin/authorize-member! directory-a
                                               "owner-a@example.com"
                                               "owner-a-subject")
              old-token (tokens/create-token! token-service owner-a
                                              "Old generation")
              old-grant {:refresh-token-ciphertext "old-ciphertext"
                         :folder-id "old-folder"
                         :revoked? false}
              _ (auth/save-member-grant! grant-store owner-a old-grant)
              request-for
              (fn [identity]
                (assoc (fixture/render-request)
                       :requesterSubject (:subject identity)
                       :requesterEmail (:email identity)
                       :requesterMembershipVersion
                       (:membership-version identity)))
              old-job-id
              (get-in (jobs/submit-job! job-service "old-generation-job"
                                        (request-for owner-a))
                      [:job :id])
              directory-b (admin-gcp/member-directory
                           firestore "owner-b@example.com")
              owner-b (admin/authorize-member! directory-b
                                               "owner-b@example.com"
                                               "owner-b-subject")
              delayed-entered (promise)
              release-delayed (promise)
              delayed-token-administration
              (reify admin/TokenAdministration
                (revoke-member-tokens! [_ cleanup-identity]
                  (deliver delayed-entered true)
                  @release-delayed
                  (admin/revoke-member-tokens! token-service
                                               cleanup-identity)))
              events (atom [])
              delayed-startup
              (future
                (admin/service
                 {:directory directory-b
                  :token-administration delayed-token-administration
                  :credential-administration grant-store
                  :job-administration job-service
                  :event-sink #(swap! events conj %)}))]
          (is (= true (deref delayed-entered 10000 ::timeout)))
          (let [winning-service
                (admin/service
                 {:directory directory-b
                  :token-administration token-service
                  :credential-administration grant-store
                  :job-administration job-service
                  :event-sink #(swap! events conj %)})]
            (admin/add-member! winning-service owner-b (:email owner-a))
            (let [new-owner-a
                  (admin/authorize-member! directory-b
                                           (:email owner-a)
                                           (:subject owner-a))
                  new-token (tokens/create-token! token-service new-owner-a
                                                  "New generation")
                  new-grant {:refresh-token-ciphertext "new-ciphertext"
                             :folder-id "new-folder"
                             :revoked? false}
                  _ (auth/save-member-grant! grant-store new-owner-a new-grant)
                  new-job-id
                  (get-in (jobs/submit-job! job-service "new-generation-job"
                                            (request-for new-owner-a))
                          [:job :id])]
              (deliver release-delayed true)
              (is (not= ::timeout (deref delayed-startup 10000 ::timeout)))
              (is (= ::tokens/invalid-token
                     (try
                       (tokens/authenticate token-service (:token old-token))
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         (:type (ex-data error))))))
              (is (= {:subject (:subject new-owner-a)
                      :email (:email new-owner-a)
                      :membership-version (:membership-version new-owner-a)}
                     (tokens/authenticate token-service (:token new-token))))
              (is (= new-grant
                     (auth/load-grant grant-store (:subject new-owner-a))))
              (is (= "cancelled"
                     (:state (jobs/get-job job-service old-job-id))))
              (is (= "queued"
                     (:state (jobs/get-job job-service new-job-id))))
              (is (= 1 (count (filter #(= "owner_rotation_cleanup_complete"
                                          (:event %))
                                      @events)))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
