(ns agg.derivative-gcp-test
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as admin-gcp]
            [agg.derivative.gcp :as gcp]
            [agg.derivative.lifecycle :as derivative]
            [agg.render.derivative :as render-derivative]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)
           (java.time Clock Instant ZoneOffset)
           (java.util Date)))

(def now (Instant/parse "2026-07-30T10:00:00Z"))

(def request
  {:subject "private-owner"
   :email "owner@example.com"
   :membership-version "membership-v1"
   :file-id "private-drive-id"
   :drive-version "17"
   :source-bytes 4096
   :source-duration-seconds 120.0})

(defn- clean-firestore! [firestore]
  (doseq [collection ["administration"
                      "members"
                      "owner-revocations"
                      "derivative-preparations"
                      "derivative-preparation-idempotency"
                      "derivative-preparation-cache"
                      "derivative-preparation-orchestration"
                      "orchestration"]]
    (.get (.recursiveDelete firestore (.collection firestore collection)))))

(defn- mutable-clock [now]
  (letfn [(clock-for [zone]
            (proxy [Clock] []
              (getZone [] zone)
              (withZone [new-zone] (clock-for new-zone))
              (instant [] @now)
              (millis [] (.toEpochMilli ^Instant @now))))]
    (clock-for ZoneOffset/UTC)))

(deftest cloud-run-worker-and-runtime-configuration-match-infrastructure
  (let [request
        (gcp/derivative-run-request
         "projects/test/locations/europe-central2/jobs/agg-derivative-preview"
         "00000000-0000-0000-0000-000000000193" 3)
        override (-> request .getOverrides (.getContainerOverrides 0))
        config
        (gcp/runtime-config
         {"AGG_DERIVATIVE_BUCKET" "derivative-bucket"
          "AGG_DERIVATIVE_DISPATCHER_URL" "https://dispatcher.test"
          "AGG_DERIVATIVE_TASKS_QUEUE" "derivative-queue"
          "AGG_DERIVATIVE_TASKS_SERVICE_ACCOUNT" "tasks@test"
          "AGG_DERIVATIVE_WORKER_JOB" "agg-derivative-preview"
          "AGG_DERIVATIVE_WORKER_SERVICE_ACCOUNT" "worker@test"
          "AGG_DERIVATIVE_ASSET_TTL_SECONDS" "86400"
          "AGG_DERIVATIVE_JOB_METADATA_TTL_SECONDS" "86400"
          "AGG_DERIVATIVE_PLAYBACK_AUTHORITY_TTL_SECONDS" "3600"
          "AGG_DERIVATIVE_CACHE_MINIMUM_REMAINING_TTL_SECONDS" "3600"
          "AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS" "480"
          "AGG_DERIVATIVE_MAX_SOURCE_BYTES" "2147483648"
          "AGG_DERIVATIVE_MAX_UPSTREAM_BYTES" "2415919104"
          "AGG_DERIVATIVE_MAX_REQUEST_COUNT" "320"
          "AGG_DERIVATIVE_MAX_RANGE_BYTES" "8388608"
          "AGG_DERIVATIVE_MAX_OUTPUT_BYTES" "268435456"
          "AGG_DERIVATIVE_MAX_PROJECT_NONTERMINAL_JOBS" "10"
          "AGG_DERIVATIVE_MAX_USER_NONTERMINAL_JOBS" "1"
          "AGG_DERIVATIVE_ATTEMPT_RESERVATION_MINOR_UNITS" "125"
          "AGG_DERIVATIVE_MAX_USER_ATTEMPTS_PER_DAY" "5"
          "AGG_DERIVATIVE_MAX_USER_MONTHLY_MINOR_UNITS" "2500"
          "AGG_DERIVATIVE_MAX_MONTHLY_MINOR_UNITS" "10000"
          "AGG_MONTHLY_BUDGET_MINOR_UNITS" "40000"})]
    (is (false? (.getClearArgs override)))
    (is (= ["clojure.main" "-m" "agg.derivative.worker"
            "--job-id" "00000000-0000-0000-0000-000000000193"
            "--attempt" "3"]
           (vec (.getArgsList override))))
    (is (= {:bucket "derivative-bucket"
            :dispatcher-url "https://dispatcher.test"
            :queue-name "derivative-queue"
            :tasks-service-account "tasks@test"
            :worker-job "agg-derivative-preview"
            :worker-service-account "worker@test"}
           (select-keys config
                        [:bucket :dispatcher-url :queue-name
                         :tasks-service-account :worker-job
                         :worker-service-account])))
    (is (= {:reservation-minor-units 125
            :max-project-nonterminal 10
            :max-user-nonterminal 1
            :max-user-attempts-per-day 5
            :max-user-monthly-minor-units 2500
            :max-monthly-minor-units 10000
            :max-project-monthly-minor-units 40000}
           (:admission-limits config)))))

(deftest firestore-admission-dispatch-and-cancellation-are-attempt-exact
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "derivative-preparation-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          _clean (clean-firestore! firestore)
          directory (admin-gcp/member-directory firestore "admin@example.com")
          _ (admin/add-member-record! directory "owner@example.com")
          member (admin/authorize-member! directory "owner@example.com"
                                          "private-owner")
          request (assoc request
                         :membership-version (:membership-version member))
          enqueued (atom [])
          deleted (atom [])
          launched (atom [])
          cancelled (atom [])
          queue
          (reify derivative/PreparationQueue
            (enqueue-preparation! [_ job-id attempt]
              (swap! enqueued conj [job-id attempt]))
            (delete-preparation-task! [_ job-id attempt]
              (swap! deleted conj [job-id attempt])))
          launcher
          (reify derivative/PreparationLauncher
            (launch-preparation! [_ job-id attempt]
              (let [execution (str "executions/" job-id "/attempts/" attempt)]
                (swap! launched conj execution)
                execution))
            (cancel-preparation-execution! [_ execution]
              (swap! cancelled conj execution))
            (preparation-execution-state [_ _] :running))
          service
          (gcp/preparation-service
           {:firestore firestore
            :queue queue
            :launcher launcher
            :member-directory directory
            :fingerprint-secret "fixture-secret"
            :clock (Clock/fixed now ZoneOffset/UTC)})]
      (try
        (doseq [collection ["derivative-preparations"
                            "derivative-preparation-idempotency"
                            "derivative-preparation-cache"
                            "derivative-preparation-orchestration"]]
          (.get (.recursiveDelete firestore
                                  (.collection firestore collection))))
        (let [results
              (->> (range 8)
                   (mapv (fn [_]
                           (future
                             (derivative/submit-preparation!
                              service "same-key" request))))
                   (mapv deref))
              job-id (get-in (first results) [:job :id])]
          (is (= #{job-id} (set (map #(get-in % [:job :id]) results))))
          (is (= 1 (count (filter :created? results))))
          (is (= [[job-id 1]] @enqueued))
          (let [admission
                (.getData
                 (.get
                  (.get
                   (.document
                    (.collection firestore
                                 "derivative-preparation-orchestration")
                    "admission"))))]
            (is (= 125 (get admission "derivativeReservedMinorUnits")))
            (is (= 1 (count (get admission "active")))))
          (let [dispatches
                (->> (range 8)
                     (mapv (fn [_]
                             (future
                               (derivative/dispatch-preparation!
                                service job-id 1))))
                     (mapv deref))]
            (is (= 1 (count (filter :started? dispatches))))
            (is (= 1 (count @launched))))
          (is (= {:job-id job-id
                  :attempt 1
                  :profile render-derivative/profile-v1
                  :source {:file-id "private-drive-id"
                           :drive-version "17"
                           :bytes 4096
                           :duration-seconds 120.0}
                  :owner {:subject "private-owner"
                          :membership-version
                          (:membership-version member)}}
                 (derivative/load-preparation-attempt service job-id 1)))
          (is (= "cancellation-requested"
                 (:state (derivative/cancel-preparation! service job-id))))
          (is (= [(str "executions/" job-id "/attempts/1")] @cancelled))
          (is (derivative/preparation-cancellation-requested?
               service job-id 1))
          (is (= "cancelled"
                 (:state
                  (derivative/acknowledge-preparation-cancellation!
                   service job-id 1))))
          (let [queued-id
                (get-in
                 (derivative/submit-preparation!
                  service "queued-cancel"
                  (assoc request :drive-version "18"))
                 [:job :id])]
            (is (= "cancelled"
                   (:state
                    (derivative/cancel-preparation!
                     service queued-id))))
            (is (false?
                 (:started?
                  (derivative/dispatch-preparation!
                   service queued-id 1))))
            (is (= [[queued-id 1]] @deleted))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by the targeted emulator command")))

(deftest firestore-completion-publishes-an-exact-private-cache-hit
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "derivative-cache-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          _clean (clean-firestore! firestore)
          directory (admin-gcp/member-directory firestore "owner@example.com")
          member (admin/authorize-member! directory "owner@example.com"
                                          "private-owner")
          request (assoc request
                         :membership-version (:membership-version member))
          enqueued (atom [])
          queue
          (reify derivative/PreparationQueue
            (enqueue-preparation! [_ job-id attempt]
              (swap! enqueued conj [job-id attempt]))
            (delete-preparation-task! [_ _ _]))
          launcher
          (reify derivative/PreparationLauncher
            (launch-preparation! [_ job-id attempt]
              (str "executions/" job-id "/attempts/" attempt))
            (cancel-preparation-execution! [_ _])
            (preparation-execution-state [_ _] :running))
          service
          (gcp/preparation-service
           {:firestore firestore
            :queue queue
            :launcher launcher
            :member-directory directory
            :fingerprint-secret "fixture-secret"
            :clock (Clock/fixed now ZoneOffset/UTC)})]
      (try
        (doseq [collection ["derivative-preparations"
                            "derivative-preparation-idempotency"
                            "derivative-preparation-cache"
                            "derivative-preparation-orchestration"
                            "orchestration"]]
          (.get (.recursiveDelete firestore
                                  (.collection firestore collection))))
        (let [job-id
              (get-in (derivative/submit-preparation!
                       service "cache-source" request)
                      [:job :id])
              _ (derivative/dispatch-preparation! service job-id 1)
              completed
              (derivative/complete-preparation-attempt!
               service job-id 1
               {:asset-id "00000000-0000-0000-0000-000000000193"
                :object-key
                "derivatives/00000000-0000-0000-0000-000000000193.mp4"
                :measurements {:output-bytes 1024}})
              cached
              (derivative/submit-preparation! service "cache-reuse" request)
              public-text (pr-str [completed cached])]
          (is (= "succeeded" (:state completed)))
          (is (:cache-hit? cached))
          (is (false? (:created? cached)))
          (is (= 1 (count @enqueued)))
          (is (= 125
                 (.getLong
                  (.get
                   (.get
                    (.document
                     (.collection
                      firestore "derivative-preparation-orchestration")
                     "admission")))
                  "derivativeReservedMinorUnits")))
          (is (not (str/includes?
                    public-text "private-drive-id")))
          (is (not (str/includes?
                    public-text "derivatives/"))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by the targeted emulator command")))

(deftest source-authority-is-exact-attempt-and-membership-generation-bound
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "derivative-source-authority-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          _clean (clean-firestore! firestore)
          directory (admin-gcp/member-directory firestore "admin@example.com")
          _ (admin/add-member-record! directory "owner@example.com")
          member (admin/authorize-member! directory "owner@example.com"
                                          "private-owner")
          request (assoc request
                         :membership-version (:membership-version member))
          cancelled (atom [])
          queue
          (reify derivative/PreparationQueue
            (enqueue-preparation! [_ _ _])
            (delete-preparation-task! [_ _ _]))
          launcher
          (reify derivative/PreparationLauncher
            (launch-preparation! [_ job-id attempt]
              (str "executions/" job-id "/attempts/" attempt))
            (cancel-preparation-execution! [_ execution]
              (swap! cancelled conj execution))
            (preparation-execution-state [_ _] :running))
          service
          (gcp/preparation-service
           {:firestore firestore
            :queue queue
            :launcher launcher
            :member-directory directory
            :fingerprint-secret "fixture-secret"
            :source-gateway ::source-gateway
            :access-provider
            (fn [subject] {:access-token (str "token-for-" subject)})
            :clock (Clock/fixed now ZoneOffset/UTC)})]
      (try
        (doseq [collection ["derivative-preparations"
                            "derivative-preparation-idempotency"
                            "derivative-preparation-cache"
                            "derivative-preparation-orchestration"
                            "orchestration"]]
          (.get (.recursiveDelete firestore
                                  (.collection firestore collection))))
        (let [job-id
              (get-in (derivative/submit-preparation!
                       service "source-authority" request)
                      [:job :id])]
          (derivative/dispatch-preparation! service job-id 1)
          (is (= {:gateway ::source-gateway
                  :access-token "token-for-private-owner"
                  :file-id "private-drive-id"}
                 (gcp/source-access! service job-id 1)))
          (is (= ::derivative/invalid-derivative-attempt
                 (try
                   (gcp/source-access! service job-id 2)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (let [revoked
                (admin/revoke-member-record! directory "owner@example.com")
                cleanup (select-keys revoked
                                     [:subject :membership-version])]
            (is (= ::derivative/member-not-allowlisted
                   (try
                     (gcp/source-access! service job-id 1)
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))
            (is (= 1 (admin/cancel-member-jobs! service cleanup)))
            (is (= "cancellation-requested"
                   (:state (derivative/get-preparation service job-id))))
            (is (= [(str "executions/" job-id "/attempts/1")]
                   @cancelled))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by the targeted emulator command")))

(deftest retry-reserves-a-new-attempt-and-reconciliation-acknowledges-cancel
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "derivative-retry-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          _clean (clean-firestore! firestore)
          directory (admin-gcp/member-directory firestore "owner@example.com")
          member (admin/authorize-member! directory "owner@example.com"
                                          "private-owner")
          request (assoc request
                         :membership-version (:membership-version member))
          current-time (atom now)
          enqueued (atom [])
          execution-states (atom {})
          queue
          (reify derivative/PreparationQueue
            (enqueue-preparation! [_ job-id attempt]
              (swap! enqueued conj [job-id attempt]))
            (delete-preparation-task! [_ _ _]))
          launcher
          (reify derivative/PreparationLauncher
            (launch-preparation! [_ job-id attempt]
              (let [execution (str "executions/" job-id
                                   "/attempts/" attempt)]
                (swap! execution-states assoc execution :running)
                execution))
            (cancel-preparation-execution! [_ execution]
              (swap! execution-states assoc execution :cancelled))
            (preparation-execution-state [_ execution]
              (get @execution-states execution :missing)))
          service
          (gcp/preparation-service
           {:firestore firestore
            :queue queue
            :launcher launcher
            :member-directory directory
            :fingerprint-secret "fixture-secret"
            :clock (mutable-clock current-time)})]
      (try
        (let [job-id
              (get-in (derivative/submit-preparation!
                       service "retry-source" request)
                      [:job :id])]
          (derivative/dispatch-preparation! service job-id 1)
          (is (= "failed"
                 (:state
                  (derivative/fail-preparation-attempt!
                   service job-id 1
                   {:failure-code "derivative_encode_failed"
                    :retryable true}))))
          (is (= 2
                 (:attempt
                  (derivative/retry-preparation! service job-id))))
          (is (= [[job-id 1] [job-id 2]] @enqueued))
          (is (false?
               (:started?
                (derivative/dispatch-preparation! service job-id 1))))
          (derivative/dispatch-preparation! service job-id 2)
          (is (= "cancellation-requested"
                 (:state (derivative/cancel-preparation! service job-id))))
          (is (= {:repairedJobs 1}
                 (derivative/reconcile-preparations! service)))
          (is (= "cancelled"
                 (:state (derivative/get-preparation service job-id))))
          (is (= 250
                 (.getLong
                  (.get
                   (.get
                    (.document
                     (.collection
                      firestore "derivative-preparation-orchestration")
                     "admission")))
                  "derivativeReservedMinorUnits"))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by the targeted emulator command")))

(deftest reconciliation-adopts-an-accepted-unrecorded-exact-execution
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "derivative-recovery-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          _clean (clean-firestore! firestore)
          directory (admin-gcp/member-directory firestore "owner@example.com")
          member (admin/authorize-member! directory "owner@example.com"
                                          "private-owner")
          request (assoc request
                         :membership-version (:membership-version member))
          queue
          (reify derivative/PreparationQueue
            (enqueue-preparation! [_ _ _])
            (delete-preparation-task! [_ _ _]))
          launcher
          (reify
            derivative/PreparationLauncher
            (launch-preparation! [_ _ _])
            (cancel-preparation-execution! [_ _])
            (preparation-execution-state [_ _] :running)
            derivative/RecoverablePreparationLauncher
            (find-active-preparation-execution [_ job-id attempt]
              (str "executions/" job-id "/attempts/" attempt)))
          service
          (gcp/preparation-service
           {:firestore firestore
            :queue queue
            :launcher launcher
            :member-directory directory
            :fingerprint-secret "fixture-secret"
            :clock (Clock/fixed now ZoneOffset/UTC)})]
      (try
        (let [job-id
              (get-in (derivative/submit-preparation!
                       service "recovery" request)
                      [:job :id])
              job-ref
              (.document
               (.collection firestore "derivative-preparations") job-id)]
          (.get
           (.update job-ref
                    {"state" "running"
                     "updatedAt" (Date/from now)
                     "dispatchStartedAt" (Date/from now)}))
          (is (= {:repairedJobs 1}
                 (derivative/reconcile-preparations! service)))
          (is (= (str "executions/" job-id "/attempts/1")
                 (.getString (.get (.get job-ref)) "execution")))
          (is (= job-id
                 (:job-id
                  (derivative/load-preparation-attempt
                   service job-id 1)))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by the targeted emulator command")))
