(ns agg.jobs.gcp
  (:require [agg.errors :as errors]
            [agg.admin.core :as admin]
            [agg.contracts.render :as contract]
            [agg.auth.gcp :as auth-gcp]
            [agg.jobs.lifecycle :as lifecycle]
            [agg.observability :as observability]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.google.api.gax.rpc ApiException StatusCode$Code)
           (com.google.cloud.firestore DocumentSnapshot Firestore
                                       FirestoreException FirestoreOptions
                                       Transaction Transaction$Function)
           (com.google.cloud.run.v2 Execution ExecutionsClient JobsClient RunJobRequest
                                    RunJobRequest$Overrides
                                    RunJobRequest$Overrides$ContainerOverride)
           (com.google.cloud.storage BlobInfo HttpMethod Storage
                                     Storage$BlobTargetOption
                                     Storage$SignUrlOption StorageOptions)
           (com.google.cloud.tasks.v2 CloudTasksClient HttpRequest
                                      OidcToken QueueName Task TaskName)
           (java.time Clock Instant LocalDate YearMonth ZoneOffset)
           (java.util Date UUID)
           (java.util.concurrent ExecutionException TimeUnit)))

;; The generated Cloud Tasks HttpMethod enum cannot be imported alongside the
;; Storage enum. Resolve it once without leaking that naming collision.
(def ^:private tasks-post
  (com.google.cloud.tasks.v2.HttpMethod/valueOf "POST"))

(def ^:private billing-zone (ZoneOffset/ofHours -8))

(defn- duplicate-task? [^ApiException error]
  (= StatusCode$Code/ALREADY_EXISTS
     (some-> error .getStatusCode .getCode)))

(defn- await! [future]
  (try
    (.get ^java.util.concurrent.Future future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- now-ms [^Clock clock]
  (.toEpochMilli (Instant/now clock)))

(defn- iso [epoch-ms]
  (str (Instant/ofEpochMilli (long epoch-ms))))

(defn- utc-day [^Clock clock]
  (str (LocalDate/now (.withZone clock ZoneOffset/UTC))))

(defn- billing-month [^Clock clock]
  (str (YearMonth/now (.withZone clock billing-zone))))

(defn- job-doc [job]
  (cond-> {"id" (:id job)
           "state" (name (:state job))
           "attempt" (long (:attempt job))
           "requestObject" (:request-object job)
           "createdAt" (long (:created-at job))
           "updatedAt" (long (:updated-at job))
           "expireAt" (Date/from
                       (Instant/ofEpochMilli (long (:expires-at job))))}
    (:lease-token job) (assoc "leaseToken" (:lease-token job))
    (:lease-expires-at job) (assoc "leaseExpiresAt" (long (:lease-expires-at job)))
    (:execution job) (assoc "execution" (:execution job))
    (:failure job) (assoc "failure" (:failure job))
    (:output job) (assoc "outputJson" (json/write-str (:output job)))
    (:requester-subject job) (assoc "requesterSubject" (:requester-subject job))
    (:requester-email job) (assoc "requesterEmail" (:requester-email job))
    (:requester-membership-version job)
    (assoc "requesterMembershipVersion" (:requester-membership-version job))))

(defn- snapshot-job [^DocumentSnapshot snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      (cond-> {:id (get data "id")
               :state (keyword (get data "state"))
               :attempt (long (get data "attempt"))
               :request-object (get data "requestObject")
               :created-at (long (get data "createdAt"))
               :updated-at (long (get data "updatedAt"))
               :expires-at (.getTime (.getDate snapshot "expireAt"))}
        (get data "leaseToken") (assoc :lease-token (get data "leaseToken"))
        (get data "leaseExpiresAt")
        (assoc :lease-expires-at (long (get data "leaseExpiresAt")))
        (get data "execution") (assoc :execution (get data "execution"))
        (get data "failure") (assoc :failure (get data "failure"))
        (get data "outputJson")
        (assoc :output (json/read-str (get data "outputJson") :key-fn keyword))
        (get data "requesterSubject")
        (assoc :requester-subject (get data "requesterSubject"))
        (get data "requesterEmail")
        (assoc :requester-email (get data "requesterEmail"))
        (get data "requesterMembershipVersion")
        (assoc :requester-membership-version
               (get data "requesterMembershipVersion"))))))

(defn- public-job [job]
  (lifecycle/job-resource
   (-> job
       (update :created-at iso)
       (update :updated-at iso))))

(defn- transaction! [^Firestore firestore f]
  (await!
   (.runTransaction
    firestore
    (reify Transaction$Function
      (updateCallback [_ transaction]
        (f transaction))))))

(defn- transaction-snapshot [^Transaction transaction reference]
  (await! (.get transaction reference)))

(defn- require-idempotency-key! [value]
  (when-not (and (string? value) (<= 1 (count value) 128))
    (throw (errors/raise! "A bounded Idempotency-Key header is required"
                    {:type ::lifecycle/invalid-idempotency-key}))))

(defrecord GcsRequestStore [^Storage storage bucket signer]
  lifecycle/RequestStore
  (save-request! [_ job-id request]
    (let [object-name (str "jobs/" job-id "/request.json")
          blob (-> (BlobInfo/newBuilder bucket object-name)
                   (.setContentType "application/json")
                   (.build))]
      (.create storage blob
               (.getBytes (json/write-str request) "UTF-8")
               (make-array Storage$BlobTargetOption 0))
      object-name))
  (load-request [_ object-name]
    (json/read-str
     (String. (.readAllBytes storage bucket object-name
                             (make-array
                              com.google.cloud.storage.Storage$BlobSourceOption
                              0))
              "UTF-8")
     :key-fn keyword))
  lifecycle/UploadSigner
  (signed-upload [_ object-name content-type expires-seconds]
    (when-not (<= 1 expires-seconds 900)
      (throw (errors/raise! "Signed uploads expire within fifteen minutes"
                      {:type ::invalid-upload-expiry})))
    (let [blob (-> (BlobInfo/newBuilder bucket object-name)
                   (.setContentType content-type)
                   (.build))
          options (cond-> [(Storage$SignUrlOption/withV4Signature)
                           (Storage$SignUrlOption/httpMethod HttpMethod/PUT)
                           (Storage$SignUrlOption/withContentType)
                           (Storage$SignUrlOption/withExtHeaders
                            {"content-type" content-type})]
                    signer (conj (Storage$SignUrlOption/signWith signer)))]
      (str (.signUrl storage blob expires-seconds TimeUnit/SECONDS
                     (into-array Storage$SignUrlOption options))))))

(defrecord CloudTaskQueue [^CloudTasksClient client project region queue-name
                           dispatcher-url dispatcher-service-account]
  lifecycle/JobQueue
  (enqueue-job! [_ job-id attempt]
    (let [parent (str (QueueName/of project region queue-name))
          task-name (str (TaskName/of project region queue-name
                                      (str "job-" job-id "-attempt-" attempt)))
          oidc (-> (OidcToken/newBuilder)
                   (.setServiceAccountEmail dispatcher-service-account)
                   (.setAudience dispatcher-url)
                   (.build))
          request (-> (HttpRequest/newBuilder)
                      (.setUrl (str dispatcher-url
                                    "/internal/v1/jobs/" job-id "/dispatch"))
                      (.setHttpMethod tasks-post)
                      (.setOidcToken oidc)
                      (.putHeaders "Content-Type" "application/json")
                      (.build))
          task (-> (Task/newBuilder)
                   (.setName task-name)
                   (.setHttpRequest request)
                   (.build))]
      (try
        (.createTask client parent task)
        (catch ApiException error
          (when-not (duplicate-task? error)
            (throw error)))))))

(defn- run-job-request [renderer-job job-id attempt]
  (let [container (-> (RunJobRequest$Overrides$ContainerOverride/newBuilder)
                      (.addAllArgs ["clojure.main" "-m" "agg.renderer.main"
                                    "--job-id" job-id
                                    "--attempt" (str attempt)])
                      (.build))
        overrides (-> (RunJobRequest$Overrides/newBuilder)
                      (.addContainerOverrides container)
                      (.setTaskCount 1)
                      (.build))]
    (-> (RunJobRequest/newBuilder)
        (.setName renderer-job)
        (.setOverrides overrides)
        (.build))))

(defn- execution-option [^Execution execution flag]
  (->> (.getContainersList (.getTemplate execution))
       (mapcat #(.getArgsList %))
       (partition 2 1)
       (some (fn [[candidate value]]
               (when (= flag candidate) value)))))

(defn- active-execution-for-attempt [executions job-id attempt]
  (some (fn [^Execution execution]
          (when (and (not (.hasCompletionTime execution))
                     (= job-id (execution-option execution "--job-id"))
                     (= (str attempt)
                        (execution-option execution "--attempt")))
            (.getName execution)))
        executions))

(defn- launch-cloud-run! [^JobsClient jobs-client renderer-job job-id attempt]
  (let [request (run-job-request renderer-job job-id attempt)
        operation (.runJobAsync jobs-client request)
        execution (await! (.getMetadata operation))]
    (.getName execution)))

(defrecord CloudRunLauncher [^JobsClient jobs-client
                             ^ExecutionsClient executions-client
                             renderer-job]
  lifecycle/JobLauncher
  (launch-job! [_ job-id]
    (launch-cloud-run! jobs-client renderer-job job-id 1))
  (cancel-execution! [_ execution]
    (await! (.cancelExecutionAsync executions-client execution)))
  lifecycle/RecoverableJobLauncher
  (launch-job-attempt! [_ job-id attempt]
    (launch-cloud-run! jobs-client renderer-job job-id attempt))
  (find-active-execution [_ job-id attempt]
    (active-execution-for-attempt
     (.. executions-client (listExecutions renderer-job) iterateAll)
     job-id attempt)))

(defn- capacity-data [leases]
  {"leases" leases})

(defn- capacity-leases [snapshot now]
  (into {}
        (filter (fn [[_ expires-at]] (> (long expires-at) now)))
        (or (some-> ^DocumentSnapshot snapshot .getData (get "leases")) {})))

(defn- released-capacity [snapshot job-id now]
  (capacity-data (dissoc (capacity-leases snapshot now) job-id)))

(defn- terminal-job [job state now]
  (-> job
      (assoc :state state :updated-at now)
      (dissoc :lease-token :lease-expires-at :execution)))

(defn- cancellation-state? [job]
  (contains? #{:cancelled :cancellation-requested} (:state job)))

(defn- stale-job? [now job]
  (and (contains? #{:launching :running :cancellation-requested} (:state job))
       (some? (:lease-expires-at job))
       (<= (:lease-expires-at job) now)))

(defn- complete-cancellation! [^Firestore firestore launcher ^Clock clock
                               job-ref capacity-ref job-id attempt execution]
  (lifecycle/cancel-execution! launcher execution)
  (public-job
   (transaction!
    firestore
    (fn [transaction]
      (let [job (snapshot-job (transaction-snapshot transaction job-ref))
            capacity (transaction-snapshot transaction capacity-ref)
            now (now-ms clock)]
        (if (and (= :cancellation-requested (:state job))
                 (= attempt (:attempt job))
                 (= execution (:execution job)))
          (let [cancelled (terminal-job job :cancelled now)]
            (.set ^Transaction transaction job-ref (job-doc cancelled))
            (.set ^Transaction transaction capacity-ref
                  (released-capacity capacity job-id now))
            cancelled)
          job))))))

(defn- member-identity [request]
  {:subject (:requesterSubject request)
   :email (:requesterEmail request)
   :membership-version (:requesterMembershipVersion request)})

(defn- job-member-identity [job]
  {:subject (:requester-subject job)
   :email (:requester-email job)
   :membership-version (:requester-membership-version job)})

(defn- require-transaction-member!
  [member-directory transaction identity]
  (when member-directory
    (try
      (admin/require-active-transaction! member-directory transaction identity)
      (catch clojure.lang.ExceptionInfo error
        (if (contains? #{::admin/not-allowlisted
                         ::admin/invalid-email
                         ::admin/invalid-subject}
                       (:type (ex-data error)))
          (throw (errors/raise! "Member is no longer allowlisted"
                          {:type ::lifecycle/member-not-allowlisted}
                          error))
          (throw error))))))

(defn- transaction-contention? [error]
  (loop [cause error]
    (cond
      (nil? cause) false
      (and (instance? FirestoreException cause)
           (= io.grpc.Status$Code/ABORTED
              (some-> ^FirestoreException cause .getStatus .getCode))) true
      (and (instance? ApiException cause)
           (= StatusCode$Code/ABORTED
              (some-> ^ApiException cause .getStatusCode .getCode))) true
      (and (instance? io.grpc.StatusRuntimeException cause)
           (= io.grpc.Status$Code/ABORTED
              (some-> ^io.grpc.StatusRuntimeException cause
                      .getStatus
                      .getCode))) true
      :else (recur (.getCause ^Throwable cause)))))

(defn- membership-denied? [error]
  (and (instance? clojure.lang.ExceptionInfo error)
       (contains? #{::admin/not-allowlisted
                    ::admin/invalid-email
                    ::admin/invalid-subject}
                  (:type (ex-data error)))))

(defn- rethrow-transaction-contention!
  [member-directory identity error]
  (when-not (transaction-contention? error)
    (throw error))
  (let [membership-error
        (when (and member-directory identity)
          (try
            (admin/active-member member-directory identity)
            nil
            (catch Throwable recheck-error
              recheck-error)))]
    (if (membership-denied? membership-error)
      (throw (errors/raise! "Member is no longer allowlisted"
                      {:type ::lifecycle/member-not-allowlisted}
                      error))
      (throw (errors/raise! "Firestore transaction contention"
                      {:type ::lifecycle/transaction-contention
                       :retryable true}
                      error)))))

(defrecord FirestoreJobService [^Firestore firestore request-store queue launcher
                                worker ^Clock clock daily-limit
                                monthly-budget-minor-units
                                render-reservation-minor-units
                                member-directory]
  lifecycle/JobAccess
  (owns-job? [_ job-id subject]
    (= subject
       (:requester-subject
        (snapshot-job
         (await! (.get (.document (.collection firestore "jobs") job-id)))))))
  lifecycle/JobReconciler
  (reconcile-jobs! [_]
    (let [jobs (.collection firestore "jobs")
          capacity-ref (.document (.collection firestore "orchestration") "capacity")
          now (now-ms clock)
          stale-job-candidates
          (->> (await! (.get (.whereLessThanOrEqualTo
                              jobs "leaseExpiresAt" now)))
               .getDocuments
               (keep snapshot-job)
               (filter #(stale-job? now %))
               vec)
          recoverable-job-candidates
          (->> ["launching" "cancellation-requested"]
               (mapcat (fn [state]
                         (->> (await! (.get (.whereEqualTo jobs "state" state)))
                              .getDocuments
                              (keep snapshot-job))))
               vec)
          recovery-candidates
          (->> (concat stale-job-candidates recoverable-job-candidates)
               (map (juxt :id identity))
               (into {})
               vals)
          recoverable? (satisfies? lifecycle/RecoverableJobLauncher launcher)
          recovered-executions
          (if recoverable?
            (->> recovery-candidates
                 (keep (fn [{:keys [id attempt execution]}]
                         (when-not execution
                           (when-let [accepted
                                      (lifecycle/find-active-execution
                                       launcher id attempt)]
                             [id {:attempt attempt
                                  :execution accepted}]))))
                 (into {}))
            {})
          result
          (transaction!
           firestore
           (fn [transaction]
             (let [capacity (transaction-snapshot transaction capacity-ref)
                   leases (or (some-> ^DocumentSnapshot capacity .getData
                                      (get "leases"))
                              {})
                   candidate-job-ids
                   (distinct (concat (map :id stale-job-candidates)
                                     (map :id recovery-candidates)
                                     (keys leases)))
                   job-snapshots
                   (mapv #(transaction-snapshot transaction (.document jobs %))
                         candidate-job-ids)
                   jobs-by-id (->> job-snapshots
                                   (keep snapshot-job)
                                   (map (juxt :id identity))
                                   (into {}))
                   renewed-until (+ now (* 1000 lifecycle/lease-seconds))
                   updates
                   (into {}
                         (keep
                          (fn [job]
                            (let [{recovered-attempt :attempt
                                   execution :execution}
                                  (get recovered-executions (:id job))
                                  recoverable-job?
                                  (and execution
                                       (= recovered-attempt (:attempt job))
                                       (nil? (:execution job))
                                       (contains? #{:launching
                                                    :cancellation-requested}
                                                  (:state job)))
                                  retry-cancellation?
                                  (and (= :cancellation-requested (:state job))
                                       (:execution job))
                                  stale? (stale-job? now job)]
                              (when (or recoverable-job?
                                        retry-cancellation?
                                        stale?)
                                [(:id job)
                                 (cond
                                   recoverable-job?
                                   (assoc job
                                          :state
                                          (if (= :cancellation-requested
                                                 (:state job))
                                            :cancellation-requested
                                            :running)
                                          :execution execution
                                          :lease-expires-at renewed-until
                                          :updated-at now)

                                   retry-cancellation?
                                   (assoc job
                                          :lease-expires-at renewed-until
                                          :updated-at now)

                                   (= :cancellation-requested (:state job))
                                   (terminal-job job :cancelled now)

                                   :else
                                   (assoc (terminal-job job :failed now)
                                          :failure "stale_lease"))]))))
                         (vals jobs-by-id))
                   jobs-after-updates (merge jobs-by-id updates)
                   recovered-leases
                   (into {}
                         (keep (fn [[job-id job]]
                                 (when (and (:execution job)
                                            (= renewed-until
                                               (:lease-expires-at job)))
                                   [job-id renewed-until])))
                         updates)
                   retained-leases
                   (merge
                    (into {}
                          (filter
                           (fn [[job-id expires-at]]
                             (let [job (get jobs-after-updates job-id)]
                               (and (> (long expires-at) now)
                                    (contains? #{:launching :running
                                                 :cancellation-requested}
                                               (:state job))
                                    (= (long expires-at)
                                       (:lease-expires-at job))))))
                          leases)
                    recovered-leases)
                   cancellations
                   (->> updates
                        (keep (fn [[job-id job]]
                                (when (and (= :cancellation-requested
                                              (:state job))
                                           (:execution job))
                                  {:job-id job-id
                                   :attempt (:attempt job)
                                   :execution (:execution job)})))
                        vec)]
               (doseq [[job-id updated] updates]
                 (.set ^Transaction transaction
                       (.document jobs job-id)
                       (job-doc updated)))
               (when (not= leases retained-leases)
                 (.set ^Transaction transaction capacity-ref
                       (capacity-data retained-leases)))
               {:repaired-jobs (count updates)
                :released-leases
                (count (remove #(contains? retained-leases %)
                               (keys leases)))
                :cancellations cancellations})))]
      (doseq [{:keys [job-id attempt execution]} (:cancellations result)]
        (complete-cancellation! firestore launcher clock
                                (.document jobs job-id) capacity-ref
                                job-id attempt execution))
      (-> result
          (update :released-leases + (count (:cancellations result)))
          (dissoc :cancellations))))
  lifecycle/JobService
  (submit-job! [_ idempotency-key request]
    (require-idempotency-key! idempotency-key)
    (contract/prepare request)
    (let [job-id (str (UUID/randomUUID))
          digest (lifecycle/request-digest request)
          request-object (lifecycle/save-request! request-store job-id request)
          now (now-ms clock)
          jobs (.collection firestore "jobs")
          idempotency (.collection firestore "job-idempotency")
          orchestration (.collection firestore "orchestration")
          idempotency-ref (.document idempotency
                                     (lifecycle/request-digest idempotency-key))
          capacity-ref (.document orchestration "capacity")
          day (utc-day clock)
          month (billing-month clock)
          day-ref (.document orchestration (str "submissions-" day))
          budget-ref (.document orchestration (str "budget-" month))
          candidate {:id job-id :state :queued :attempt 1
                     :request-object request-object
                     :requester-subject (:requesterSubject request)
                     :requester-email (:requesterEmail request)
                     :requester-membership-version
                     (:requesterMembershipVersion request)
                     :created-at now :updated-at now
                     :expires-at (+ now (* 90 24 60 60 1000))}
          result
          (try
            (transaction!
             firestore
             (fn [transaction]
               (let [snapshot (transaction-snapshot transaction idempotency-ref)]
                 (if (.exists ^DocumentSnapshot snapshot)
                   (let [data (.getData ^DocumentSnapshot snapshot)
                         stored-job
                         (snapshot-job
                          (transaction-snapshot
                           transaction (.document jobs (get data "jobId"))))]
                     (require-transaction-member!
                      member-directory transaction (member-identity request))
                     (when-not (= digest (get data "requestDigest"))
                       (throw (errors/raise!
                               "Idempotency key already belongs to another request"
                               {:type ::lifecycle/idempotency-conflict})))
                     {:created? false :job stored-job})
                   (let [capacity (transaction-snapshot transaction capacity-ref)
                         day-snapshot (transaction-snapshot transaction day-ref)
                         budget-snapshot
                         (transaction-snapshot transaction budget-ref)
                         submitted
                         (long (or (some-> ^DocumentSnapshot day-snapshot
                                           .getData
                                           (get "submissionCount"))
                                   0))
                         reserved
                         (long (or (some-> ^DocumentSnapshot budget-snapshot
                                           .getData
                                           (get "reservedMinorUnits"))
                                   0))]
                     (require-transaction-member!
                      member-directory transaction (member-identity request))
                     (when (>= (count (capacity-leases capacity now))
                               lifecycle/max-active-leases)
                       (throw (errors/raise! "All render leases are held"
                                       {:type ::lifecycle/capacity-exhausted})))
                     (when (>= submitted daily-limit)
                       (throw (errors/raise!
                               "Daily submission limit is exhausted"
                               {:type
                                ::lifecycle/daily-submission-limit-exhausted})))
                     (when (> (+ reserved render-reservation-minor-units)
                              monthly-budget-minor-units)
                       (throw (errors/raise! "Monthly compute budget is exhausted"
                                       {:type
                                        ::lifecycle/monthly-budget-exhausted})))
                     (.set ^Transaction transaction (.document jobs job-id)
                           (job-doc candidate))
                     (.set ^Transaction transaction idempotency-ref
                           {"jobId" job-id "requestDigest" digest})
                     (.set ^Transaction transaction day-ref
                           {"day" day
                            "submissionCount" (inc submitted)
                            "updatedAt" now})
                     (.set ^Transaction transaction budget-ref
                           {"month" month
                            "reservedMinorUnits"
                            (+ reserved render-reservation-minor-units)
                            "limitMinorUnits" monthly-budget-minor-units
                            "reservationMinorUnits"
                            render-reservation-minor-units
                            "currency" "PLN"
                            "updatedAt" now})
                     {:created? true :job candidate})))))
            (catch Throwable error
              (rethrow-transaction-contention!
               member-directory (member-identity request) error)))]
      (when (= :queued (get-in result [:job :state]))
        (lifecycle/enqueue-job! queue
                                (get-in result [:job :id])
                                (get-in result [:job :attempt])))
      (update result :job public-job)))
  (get-job [_ job-id]
    (some-> (await! (.get (.document (.collection firestore "jobs") job-id)))
            snapshot-job
            public-job))
  (dispatch-job! [_ job-id]
    (let [jobs (.collection firestore "jobs")
          job-ref (.document jobs job-id)
          capacity-ref (.document (.collection firestore "orchestration") "capacity")
          now (now-ms clock)
          lease-token (str (UUID/randomUUID))
          admission
          (transaction!
           firestore
           (fn [transaction]
             (let [job (snapshot-job
                        (transaction-snapshot transaction job-ref))]
               (when-not job
                 (throw (errors/raise! "Job does not exist" {:type ::lifecycle/job-not-found})))
               (if (not= :queued (:state job))
                 {:started? false :job job}
                 (let [capacity (transaction-snapshot transaction capacity-ref)
                       leases (capacity-leases capacity now)]
                   (when (>= (count leases) lifecycle/max-active-leases)
                     (throw (errors/raise! "All render leases are held"
                                     {:type ::lifecycle/capacity-exhausted})))
                   (let [expires-at (+ now (* 1000 lifecycle/lease-seconds))
                         admitted (assoc job
                                         :state :launching
                                         :updated-at now
                                         :lease-token lease-token
                                         :lease-expires-at expires-at)]
                     (.set ^Transaction transaction job-ref (job-doc admitted))
                     (.set ^Transaction transaction capacity-ref
                           (capacity-data (assoc leases job-id expires-at)))
                     {:started? true :job admitted}))))))]
      (if-not (:started? admission)
        (update admission :job public-job)
        (try
          (let [execution (if (satisfies? lifecycle/RecoverableJobLauncher
                                          launcher)
                            (lifecycle/launch-job-attempt!
                             launcher job-id (get-in admission [:job :attempt]))
                            (lifecycle/launch-job! launcher job-id))
                running
                (transaction!
                 firestore
                 (fn [transaction]
                   (let [job (snapshot-job
                              (transaction-snapshot transaction job-ref))]
                     (if (= lease-token (:lease-token job))
                       (let [updated (assoc job
                                            :state (if (= :cancellation-requested
                                                          (:state job))
                                                     :cancellation-requested
                                                     :running)
                                            :execution execution
                                            :updated-at (now-ms clock))]
                         (.set ^Transaction transaction job-ref (job-doc updated))
                         updated)
                       job))))]
            {:started? true
             :job (if (= :cancellation-requested (:state running))
                    (complete-cancellation! firestore launcher clock
                                            job-ref capacity-ref job-id
                                            (:attempt running) execution)
                    (public-job running))})
          (catch Throwable cause
            (transaction!
             firestore
             (fn [transaction]
               (let [job (snapshot-job
                          (transaction-snapshot transaction job-ref))
                     capacity (transaction-snapshot transaction capacity-ref)
                     now (now-ms clock)
                     failed (if (cancellation-state? job)
                              (terminal-job job :cancelled now)
                              (-> (terminal-job job :failed now)
                                  (assoc :failure "launch_failed")))]
                 (.set ^Transaction transaction job-ref (job-doc failed))
                 (.set ^Transaction transaction capacity-ref
                       (released-capacity capacity job-id now)))))
            (throw (errors/raise! "Renderer launch failed"
                            {:type ::lifecycle/launch-failed}
                            cause)))))))
  (cancel-job! [_ job-id]
    (let [job-ref (.document (.collection firestore "jobs") job-id)
          capacity-ref (.document (.collection firestore "orchestration") "capacity")
          requested
          (transaction!
           firestore
           (fn [transaction]
             (let [job (snapshot-job
                        (transaction-snapshot transaction job-ref))
                   now (now-ms clock)]
               (when-not job
                 (throw (errors/raise! "Job does not exist" {:type ::lifecycle/job-not-found})))
               (case (:state job)
                 :cancelled job
                 :queued (let [updated (terminal-job job :cancelled now)]
                           (.set ^Transaction transaction job-ref (job-doc updated))
                           updated)
                 (:launching :running :cancellation-requested)
                 (let [updated (assoc job :state :cancellation-requested
                                      :updated-at now)]
                   (.set ^Transaction transaction job-ref (job-doc updated))
                   updated)
                 (throw (errors/raise! "Terminal job cannot be cancelled"
                                 {:type ::lifecycle/invalid-transition}))))))]
      (if-let [execution (:execution requested)]
        (complete-cancellation! firestore launcher clock job-ref capacity-ref
                                job-id (:attempt requested) execution)
        (public-job requested))))
  (retry-job! [_ job-id]
    (let [job-ref (.document (.collection firestore "jobs") job-id)
          orchestration (.collection firestore "orchestration")
          capacity-ref (.document orchestration "capacity")
          month (billing-month clock)
          budget-ref (.document orchestration (str "budget-" month))
          now (now-ms clock)
          retry-identity (atom nil)
          retried
          (try
            (transaction!
             firestore
             (fn [transaction]
               (let [job (snapshot-job
                          (transaction-snapshot transaction job-ref))]
                 (when-not job
                   (throw (errors/raise! "Job does not exist"
                                   {:type ::lifecycle/job-not-found})))
                 (let [capacity (transaction-snapshot transaction capacity-ref)
                       budget (transaction-snapshot transaction budget-ref)
                       identity (job-member-identity job)
                       reserved (long (or (some-> ^DocumentSnapshot budget
                                                  .getData
                                                  (get "reservedMinorUnits"))
                                          0))]
                   (reset! retry-identity identity)
                   (require-transaction-member! member-directory transaction
                                                identity)
                   (when-not (contains? #{:cancelled :failed} (:state job))
                     (throw (errors/raise!
                             "Only failed or cancelled jobs can be retried"
                             {:type ::lifecycle/invalid-transition})))
                   (when (>= (count (capacity-leases capacity now))
                             lifecycle/max-active-leases)
                     (throw (errors/raise! "All render leases are held"
                                     {:type ::lifecycle/capacity-exhausted})))
                   (when (> (+ reserved render-reservation-minor-units)
                            monthly-budget-minor-units)
                     (throw (errors/raise! "Monthly compute budget is exhausted"
                                     {:type
                                      ::lifecycle/monthly-budget-exhausted})))
                   (let [updated (-> job
                                     (assoc :state :queued
                                            :attempt (inc (:attempt job))
                                            :updated-at now)
                                     (dissoc :failure :output :execution
                                             :lease-token :lease-expires-at))]
                     (.set ^Transaction transaction job-ref (job-doc updated))
                     (.set ^Transaction transaction budget-ref
                           {"month" month
                            "reservedMinorUnits"
                            (+ reserved render-reservation-minor-units)
                            "limitMinorUnits" monthly-budget-minor-units
                            "reservationMinorUnits"
                            render-reservation-minor-units
                            "currency" "PLN"
                            "updatedAt" now})
                     updated)))))
            (catch Throwable error
              (rethrow-transaction-contention!
               member-directory @retry-identity error)))]
      (lifecycle/enqueue-job! queue job-id (:attempt retried))
      (public-job retried)))
  (run-job! [_ job-id]
    (let [job-ref (.document (.collection firestore "jobs") job-id)
          capacity-ref (.document (.collection firestore "orchestration") "capacity")
          job (snapshot-job (await! (.get job-ref)))]
      (when-not job
        (throw (errors/raise! "Job does not exist" {:type ::lifecycle/job-not-found})))
      (when-not (= :running (:state job))
        (throw (errors/raise! "Only a running job can render"
                        {:type ::lifecycle/invalid-transition})))
      (try
        (let [request (lifecycle/load-request request-store (:request-object job))
              result (lifecycle/perform-render! worker job-id request)
              completed
              (transaction!
               firestore
               (fn [transaction]
                 (let [current (snapshot-job
                                (transaction-snapshot transaction job-ref))
                       capacity (transaction-snapshot transaction capacity-ref)
                       now (now-ms clock)
                       updated
                       (cond
                         (cancellation-state? current)
                         (terminal-job current :cancelled now)

                         (> (long (or (:output-bytes result) 0))
                            lifecycle/max-output-bytes)
                         (assoc (terminal-job current :failed now)
                                :failure "output_too_large")

                         :else
                         (assoc (terminal-job current :succeeded now)
                                :output (dissoc result :output-bytes)))]
                   (.set ^Transaction transaction job-ref (job-doc updated))
                   (.set ^Transaction transaction capacity-ref
                         (released-capacity capacity job-id now))
                   updated)))]
          (public-job completed))
        (catch Throwable cause
          (transaction!
           firestore
           (fn [transaction]
             (let [current (snapshot-job
                            (transaction-snapshot transaction job-ref))
                   capacity (transaction-snapshot transaction capacity-ref)
                   now (now-ms clock)
                   failed (if (cancellation-state? current)
                            (terminal-job current :cancelled now)
                            (assoc (terminal-job current :failed now)
                                   :failure "worker_failed"))]
               (.set ^Transaction transaction job-ref (job-doc failed))
               (.set ^Transaction transaction capacity-ref
                     (released-capacity capacity job-id now)))))
          (throw (errors/raise! "Render worker failed"
                          {:type ::lifecycle/worker-failed :job-id job-id}
                          cause))))))
  admin/JobAdministration
  (cancel-member-jobs! [this {:keys [subject] :as cleanup-identity}]
    (let [job-ids
          (->> (await! (.get (.whereEqualTo (.collection firestore "jobs")
                                            "requesterSubject" subject)))
               .getDocuments
               (keep (fn [snapshot]
                       (let [job (snapshot-job snapshot)]
                         (when (and job
                                    (admin/cleanup-generation?
                                     cleanup-identity
                                     (:requester-membership-version job))
                                    (contains? #{:queued :launching :running
                                                 :cancellation-requested}
                                               (:state job)))
                           (:id job)))))
               vec)]
      (doseq [job-id job-ids]
        (try
          (lifecycle/cancel-job! this job-id)
          (catch clojure.lang.ExceptionInfo error
            (when-not (= ::lifecycle/invalid-transition (:type (ex-data error)))
              (throw error)))))
      (count job-ids))))

(defn request-store [bucket]
  (->GcsRequestStore (.getService (StorageOptions/getDefaultInstance)) bucket nil))

(defn task-queue [{:keys [project region queue-name dispatcher-url
                          dispatcher-service-account]}]
  (->CloudTaskQueue (CloudTasksClient/create) project region queue-name
                    dispatcher-url dispatcher-service-account))

(defn run-launcher [renderer-job]
  (->CloudRunLauncher (JobsClient/create) (ExecutionsClient/create) renderer-job))

(defn job-service [{:keys [firestore request-store queue launcher worker clock
                           daily-limit monthly-budget-minor-units
                           render-reservation-minor-units member-directory]
                    :or {clock (Clock/systemUTC)
                         daily-limit lifecycle/max-daily-submissions
                         monthly-budget-minor-units
                         lifecycle/default-monthly-budget-minor-units
                         render-reservation-minor-units
                         lifecycle/default-render-reservation-minor-units}}]
  (lifecycle/validate-admission-limits! daily-limit monthly-budget-minor-units
                                        render-reservation-minor-units)
  (->FirestoreJobService
   (or firestore (.getService (FirestoreOptions/getDefaultInstance)))
   request-store queue launcher worker clock daily-limit
   monthly-budget-minor-units
   render-reservation-minor-units member-directory))

(defn- env [name default]
  (get (System/getenv) name default))

(defn- env-emails [name]
  (->> (str/split (env name "") #"[;,]")
       (map str/trim)
       (remove str/blank?)
       (map admin/require-email)
       set))

(defn- env-long [name default]
  (or (parse-long (env name (str default)))
      (throw (errors/raise! (str name " must be an integer")
                      {:type ::invalid-environment :name name}))))

(defn api-system []
  (let [project (env "GOOGLE_CLOUD_PROJECT" "animated-graph-cloud-jp")
        region (env "AGG_REGION" "europe-central2")
        bucket (env "AGG_TEMPORARY_BUCKET" (str project "-temporary"))
        dispatcher-url (env "AGG_DISPATCHER_URL" nil)
        tasks-service-account
        (env "AGG_TASKS_SERVICE_ACCOUNT"
             (str "agg-tasks@" project ".iam.gserviceaccount.com"))
        scheduler-service-account
        (env "AGG_SCHEDULER_SERVICE_ACCOUNT"
             (str "agg-scheduler@" project ".iam.gserviceaccount.com"))]
    (when-not dispatcher-url
      (throw (errors/raise! "AGG_DISPATCHER_URL is required"
                      {:type ::missing-dispatcher-url})))
    (let [store (request-store bucket)
          firestore (.getService (FirestoreOptions/getDefaultInstance))
          admin-emails (env-emails "AGG_ADMIN_EMAILS")
          auth-enabled? (= "true" (env "AGG_AUTH_ENABLED" "false"))
          auth-dependencies
          (when auth-enabled?
            (auth-gcp/api-dependencies
             {:firestore firestore
              :project project
              :region region
              :base-url (env "AGG_PUBLIC_BASE_URL" dispatcher-url)
              :internal-audience dispatcher-url
              :owner-email (env "AGG_OWNER_EMAIL" nil)
              :admin-emails admin-emails
              :session-secret (env "AGG_SESSION_KEY" nil)
              :oauth-client-credentials
              (env "AGG_OAUTH_CLIENT_CREDENTIALS" nil)
              :tasks-service-account tasks-service-account
              :scheduler-service-account scheduler-service-account
              :picker-api-key (env "AGG_PICKER_API_KEY" nil)
              :picker-app-id (env "AGG_PICKER_APP_ID" nil)
              :token-hash-secret (env "AGG_TOKEN_HASH_PEPPER" nil)}))
          service
          (job-service
           {:firestore firestore
            :request-store store
            :queue (task-queue {:project project
                                :region region
                                :queue-name (env "AGG_TASKS_QUEUE" "agg-render")
                                :dispatcher-url dispatcher-url
                                :dispatcher-service-account tasks-service-account})
            :launcher (run-launcher
                       (str "projects/" project "/locations/" region
                            "/jobs/" (env "AGG_RENDERER_JOB" "agg-renderer")))
            :daily-limit
            (env-long "AGG_DAILY_SUBMISSION_LIMIT"
                      lifecycle/max-daily-submissions)
            :monthly-budget-minor-units
            (env-long "AGG_MONTHLY_BUDGET_MINOR_UNITS"
                      lifecycle/default-monthly-budget-minor-units)
            :render-reservation-minor-units
            (env-long "AGG_RENDER_RESERVATION_MINOR_UNITS"
                      lifecycle/default-render-reservation-minor-units)
            :member-directory (:member-directory auth-dependencies)})
          job-dependencies {:upload-signer store :job-service service}]
      (if auth-enabled?
        (assoc (merge job-dependencies auth-dependencies)
               :admin-service
               (admin/service
                {:directory (:member-directory auth-dependencies)
                 :token-administration (:token-service auth-dependencies)
                 :credential-administration
                 (:credential-administration auth-dependencies)
                 :job-administration service
                 :event-sink observability/emit-event!}))
        job-dependencies))))

(defn api-job-service []
  (:job-service (api-system)))

(defn renderer-job-service [worker]
  (let [project (env "GOOGLE_CLOUD_PROJECT" "animated-graph-cloud-jp")
        bucket (env "AGG_TEMPORARY_BUCKET" (str project "-temporary"))]
    (job-service {:request-store (request-store bucket)
                  :worker worker})))
