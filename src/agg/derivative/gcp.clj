(ns agg.derivative.gcp
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as admin-gcp]
            [agg.auth.gcp :as auth-gcp]
            [agg.derivative.contract :as contract]
            [agg.derivative.lifecycle :as lifecycle]
            [agg.derivative.storage :as storage]
            [agg.drive.derivative :as drive-derivative]
            [agg.errors :as errors]
            [agg.render.derivative :as render-derivative]
            [clojure.string :as str])
  (:import (com.google.api.gax.rpc ApiException StatusCode$Code)
           (com.google.cloud.firestore DocumentSnapshot Firestore
                                       FirestoreException FirestoreOptions Transaction
                                       Transaction$Function)
           (com.google.cloud.run.v2 Execution ExecutionsClient JobsClient
                                    RunJobRequest RunJobRequest$Overrides
                                    RunJobRequest$Overrides$ContainerOverride)
           (com.google.cloud.tasks.v2 CloudTasksClient HttpRequest OidcToken
                                      QueueName Task TaskName)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Clock Instant LocalDate YearMonth ZoneOffset)
           (java.util Date HexFormat UUID)
           (java.util.concurrent CancellationException ExecutionException)))

(def ^:private billing-zone (ZoneOffset/ofHours -8))

(def ^:private tasks-post
  (com.google.cloud.tasks.v2.HttpMethod/valueOf "POST"))

(defn- await! [future]
  (try
    (.get ^java.util.concurrent.Future future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- duplicate-task? [^ApiException error]
  (= StatusCode$Code/ALREADY_EXISTS
     (some-> error .getStatusCode .getCode)))

(defn- missing-resource? [^ApiException error]
  (= StatusCode$Code/NOT_FOUND
     (some-> error .getStatusCode .getCode)))

(defn- await-cancellation! [future]
  (try
    (await! future)
    (catch CancellationException _ nil)
    (catch ApiException error
      (if (= StatusCode$Code/CANCELLED
             (some-> error .getStatusCode .getCode))
        nil
        (throw error)))))

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

(defn- transaction! [^Firestore firestore action]
  (loop [remaining 20]
    (let [result
          (try
            {:value
             (await!
              (.runTransaction
               firestore
               (reify Transaction$Function
                 (updateCallback [_ transaction]
                   (action transaction)))))}
            (catch Throwable error
              {:error error}))]
      (if-let [error (:error result)]
        (if (and (< 1 remaining) (transaction-contention? error))
          (recur (dec remaining))
          (throw error))
        (:value result)))))

(defn- transaction-snapshot [^Transaction transaction reference]
  (await! (.get transaction reference)))

(defn- sha256 [value]
  (.formatHex
   (HexFormat/of)
   (.digest (MessageDigest/getInstance "SHA-256")
            (.getBytes (pr-str value) StandardCharsets/UTF_8))))

(defn- utc-day [^Clock clock]
  (str (LocalDate/now (.withZone clock ZoneOffset/UTC))))

(defn- billing-month [^Clock clock]
  (str (YearMonth/now (.withZone clock billing-zone))))

(defn- date->instant [value]
  (cond
    (instance? Date value) (.toInstant ^Date value)
    (instance? com.google.cloud.Timestamp value)
    (-> ^com.google.cloud.Timestamp value .toDate .toInstant)
    :else nil))

(defn- job-doc [job]
  (cond-> {"id" (:id job)
           "state" (name (:state job))
           "attempt" (long (:attempt job))
           "profileVersion" (:profile-version job)
           "ownerSubject" (:owner-subject job)
           "ownerEmail" (:owner-email job)
           "membershipVersion" (:membership-version job)
           "sourceFileId" (:file-id job)
           "sourceBytes" (long (:source-bytes job))
           "sourceDurationSeconds" (double (:source-duration-seconds job))
           "reservationMinorUnits" (long (:reservation-minor-units job))
           "createdAt" (Date/from (:created-at job))
           "updatedAt" (Date/from (:updated-at job))
           "expireAt" (Date/from (:metadata-expires-at job))}
    (:drive-version job)
    (assoc "sourceDriveVersion" (:drive-version job))
    (:request-id job) (assoc "requestId" (:request-id job))
    (:trace job) (assoc "trace" (:trace job))
    (:revision job) (assoc "revision" (:revision job))
    (:execution job) (assoc "execution" (:execution job))
    (:dispatch-started-at job)
    (assoc "dispatchStartedAt" (Date/from (:dispatch-started-at job)))
    (:cancellation-requested-at job)
    (assoc "cancellationRequestedAt"
           (Date/from (:cancellation-requested-at job)))
    (:asset-id job) (assoc "assetId" (:asset-id job))
    (:asset-expires-at job)
    (assoc "assetExpiresAt" (Date/from (:asset-expires-at job)))
    (:object-key job) (assoc "objectKey" (:object-key job))
    (:asset-generation job)
    (assoc "assetGeneration" (long (:asset-generation job)))
    (:asset-size job) (assoc "assetSize" (long (:asset-size job)))
    (:asset-content-type job)
    (assoc "assetContentType" (:asset-content-type job))
    (:asset-profile-version job)
    (assoc "assetProfileVersion" (:asset-profile-version job))
    (:completed-at job) (assoc "completedAt" (Date/from (:completed-at job)))
    (:failure-code job) (assoc "failureCode" (:failure-code job))
    (contains? job :retryable)
    (assoc "retryable" (boolean (:retryable job)))
    (:terminal-cause job)
    (assoc "terminalCause" (name (:terminal-cause job)))))

(defn- snapshot-job [^DocumentSnapshot snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      (cond-> {:id (get data "id")
               :state (keyword (get data "state"))
               :attempt (long (get data "attempt"))
               :profile-version (get data "profileVersion")
               :owner-subject (get data "ownerSubject")
               :owner-email (get data "ownerEmail")
               :membership-version (get data "membershipVersion")
               :file-id (get data "sourceFileId")
               :drive-version (get data "sourceDriveVersion")
               :source-bytes (long (get data "sourceBytes"))
               :source-duration-seconds
               (double (get data "sourceDurationSeconds"))
               :reservation-minor-units
               (long (get data "reservationMinorUnits"))
               :created-at (date->instant (get data "createdAt"))
               :updated-at (date->instant (get data "updatedAt"))
               :metadata-expires-at (date->instant (get data "expireAt"))}
        (get data "execution") (assoc :execution (get data "execution"))
        (get data "requestId") (assoc :request-id (get data "requestId"))
        (get data "trace") (assoc :trace (get data "trace"))
        (get data "revision") (assoc :revision (get data "revision"))
        (get data "dispatchStartedAt")
        (assoc :dispatch-started-at
               (date->instant (get data "dispatchStartedAt")))
        (get data "cancellationRequestedAt")
        (assoc :cancellation-requested-at
               (date->instant (get data "cancellationRequestedAt")))
        (get data "assetId") (assoc :asset-id (get data "assetId"))
        (get data "assetExpiresAt")
        (assoc :asset-expires-at (date->instant (get data "assetExpiresAt")))
        (get data "objectKey") (assoc :object-key (get data "objectKey"))
        (get data "assetGeneration")
        (assoc :asset-generation (long (get data "assetGeneration")))
        (get data "assetSize")
        (assoc :asset-size (long (get data "assetSize")))
        (get data "assetContentType")
        (assoc :asset-content-type (get data "assetContentType"))
        (get data "assetProfileVersion")
        (assoc :asset-profile-version (get data "assetProfileVersion"))
        (get data "completedAt")
        (assoc :completed-at (date->instant (get data "completedAt")))
        (get data "failureCode")
        (assoc :failure-code (get data "failureCode"))
        (contains? data "retryable")
        (assoc :retryable (boolean (get data "retryable")))
        (get data "terminalCause")
        (assoc :terminal-cause (keyword (get data "terminalCause")))))))

(defn- preparation-resource [job]
  (let [resource (lifecycle/public-resource job)
        id (:id resource)]
    (assoc resource
           :statusUrl (str "/v1/derivative-preparations/" id)
           :cancelUrl (str "/v1/derivative-preparations/" id "/cancel")
           :retryUrl (str "/v1/derivative-preparations/" id "/retry"))))

(defn- invalid-attempt! []
  (throw
   (errors/raise! "Derivative preparation attempt is invalid"
                  {:type ::lifecycle/invalid-derivative-attempt
                   :failure-code "invalid_derivative_attempt"})))

(defn- exact-attempt [job attempt]
  (when-not (and job (pos-int? attempt)
                 (= (long attempt) (long (:attempt job))))
    (invalid-attempt!))
  job)

(defn- exact-completion? [job result]
  (= [(:asset-id job)
      (:object-key job)
      (:asset-generation job)
      (:asset-size job)
      (:asset-content-type job)
      (:asset-profile-version job)]
     [(:asset-id result)
      (:object-key result)
      (:generation result)
      (:size result)
      (:content-type result)
      (:profile-version result)]))

(declare request-fingerprint)

(defn- attempt-resource [secret job]
  (let [fingerprint
        (:fingerprint
         (request-fingerprint
          secret
          {:subject (:owner-subject job)
           :file-id (:file-id job)
           :drive-version (:drive-version job)
           :source-bytes (:source-bytes job)
           :profile-version (:profile-version job)}
          (:id job)))]
    (cond->
     {:job-id (:id job)
      :attempt (:attempt job)
      :profile render-derivative/profile-v1
      :asset {:id (:id job)
              :object-key (str "derivatives/" fingerprint ".mp4")}
      :source {:file-id (:file-id job)
               :drive-version (:drive-version job)
               :bytes (:source-bytes job)
               :duration-seconds (:source-duration-seconds job)}
      :owner {:subject (:owner-subject job)
              :membership-version (:membership-version job)}}
      (:request-id job)
      (assoc :observability
             {:request-id (:request-id job)
              :trace (:trace job)
              :revision (:revision job)
              :reservation-minor-units (:reservation-minor-units job)}))))

(defn- member-identity [request]
  {:subject (:subject request)
   :email (:email request)
   :membership-version (:membership-version request)})

(defn- require-transaction-member!
  [member-directory transaction identity]
  (when member-directory
    (try
      (if (satisfies? admin/TransactionalMembership member-directory)
        (admin/require-active-transaction!
         member-directory transaction identity)
        (admin/active-member member-directory identity))
      (catch clojure.lang.ExceptionInfo error
        (if (contains? #{::admin/not-allowlisted
                         ::admin/invalid-email
                         ::admin/invalid-subject}
                       (:type (ex-data error)))
          (throw
           (errors/raise! "Member is no longer allowlisted"
                          {:type ::lifecycle/member-not-allowlisted}
                          error))
          (throw error))))))

(defn- require-idempotency-key! [value]
  (when-not (and (string? value) (<= 1 (count value) 128))
    (throw
     (errors/raise! "A bounded Idempotency-Key header is required"
                    {:type ::lifecycle/invalid-idempotency-key})))
  value)

(defn- request-fingerprint [secret request job-id]
  (drive-derivative/cache-fingerprint
   secret
   {:owner-subject (:subject request)
    :drive-file-id (:file-id request)
    :drive-version (:drive-version request)
    :source-bytes (:source-bytes request)
    :profile-version
    (or (:profile-version request)
        (get-in contract/contract-v1 [:profile :version]))
    :job-id job-id}))

(defn- request-digest [secret request job-id]
  (sha256 [(request-fingerprint secret request job-id)
           (:source-duration-seconds request)]))

(defn- cache-doc [job]
  {"ownerSubject" (:owner-subject job)
   "membershipVersion" (:membership-version job)
   "sourceFileId" (:file-id job)
   "sourceDriveVersion" (:drive-version job)
   "sourceBytes" (long (:source-bytes job))
   "sourceDurationSeconds" (double (:source-duration-seconds job))
   "profileVersion" (:profile-version job)
   "assetId" (:asset-id job)
   "objectKey" (:object-key job)
   "assetGeneration" (long (:asset-generation job))
   "assetSize" (long (:asset-size job))
   "assetContentType" (:asset-content-type job)
   "assetProfileVersion" (:asset-profile-version job)
   "completedAt" (Date/from (:completed-at job))
   "assetExpiresAt" (Date/from (:asset-expires-at job))
   "expireAt" (Date/from (:asset-expires-at job))})

(defn- eligible-cache-job
  [^DocumentSnapshot snapshot candidate now minimum-remaining-seconds]
  (when (.exists snapshot)
    (let [data (.getData snapshot)
          expires-at (date->instant (get data "assetExpiresAt"))
          generation (get data "assetGeneration")
          size (get data "assetSize")
          content-type (get data "assetContentType")
          asset-profile-version (get data "assetProfileVersion")
          completed-at (date->instant (get data "completedAt"))]
      (when (and expires-at
                 (not (.isBefore
                       expires-at
                       (.plusSeconds ^Instant now minimum-remaining-seconds)))
                 (= (:owner-subject candidate) (get data "ownerSubject"))
                 (= (:membership-version candidate)
                    (get data "membershipVersion"))
                 (= (:drive-version candidate)
                    (get data "sourceDriveVersion"))
                 (= (:profile-version candidate)
                    (get data "profileVersion"))
                 (pos-int? generation)
                 (pos-int? size)
                 (= "video/mp4" content-type)
                 (= (:profile-version candidate) asset-profile-version)
                 completed-at)
        (assoc candidate
               :state :succeeded
               :reservation-minor-units 0
               :asset-id (get data "assetId")
               :object-key (get data "objectKey")
               :asset-generation (long generation)
               :asset-size (long size)
               :asset-content-type content-type
               :asset-profile-version asset-profile-version
               :completed-at completed-at
               :asset-expires-at expires-at)))))

(defn- current-admission [snapshot day month]
  (let [data (if (and snapshot (.exists ^DocumentSnapshot snapshot))
               (.getData ^DocumentSnapshot snapshot)
               {})
        same-day? (= day (get data "day"))
        same-month? (= month (get data "month"))]
    {:active (into {} (or (get data "active") {}))
     :day day
     :user-day-attempts
     (if same-day?
       (into {} (or (get data "userDayAttempts") {}))
       {})
     :month month
     :user-month-reserved
     (if same-month?
       (into {} (or (get data "userMonthReserved") {}))
       {})
     :derivative-reserved-minor-units
     (if same-month?
       (long (or (get data "derivativeReservedMinorUnits") 0))
       0)}))

(defn- admission-doc [admission]
  {"active" (:active admission)
   "day" (:day admission)
   "userDayAttempts" (:user-day-attempts admission)
   "month" (:month admission)
   "userMonthReserved" (:user-month-reserved admission)
   "derivativeReservedMinorUnits"
   (long (:derivative-reserved-minor-units admission))})

(defn- remove-active [admission job-id]
  (assoc admission :active (dissoc (:active admission) job-id)))

(defn- admit!
  [admission shared-reserved owner-key job-id
   {:keys [reservation-minor-units max-project-nonterminal
           max-user-nonterminal max-user-attempts-per-day
           max-user-monthly-minor-units max-monthly-minor-units
           max-project-monthly-minor-units]}]
  (let [active (:active admission)
        user-active (count (filter #(= owner-key %) (vals active)))
        daily (long (get (:user-day-attempts admission) owner-key 0))
        user-monthly
        (long (get (:user-month-reserved admission) owner-key 0))
        derivative-monthly (:derivative-reserved-minor-units admission)]
    (when (>= user-active max-user-nonterminal)
      (throw
       (errors/raise! "A derivative preparation is already active"
                      {:type ::lifecycle/user-job-active})))
    (when (>= (count active) max-project-nonterminal)
      (throw
       (errors/raise! "The derivative preparation backlog is full"
                      {:type ::lifecycle/project-backlog-exhausted})))
    (when (>= daily max-user-attempts-per-day)
      (throw
       (errors/raise! "The daily derivative attempt limit is exhausted"
                      {:type ::lifecycle/daily-attempt-limit-exhausted})))
    (when (> (+ user-monthly reservation-minor-units)
             max-user-monthly-minor-units)
      (throw
       (errors/raise! "The member derivative budget is exhausted"
                      {:type ::lifecycle/user-budget-exhausted})))
    (when (> (+ derivative-monthly reservation-minor-units)
             max-monthly-minor-units)
      (throw
       (errors/raise! "The derivative project pool is exhausted"
                      {:type ::lifecycle/derivative-budget-exhausted})))
    (when (> (+ shared-reserved reservation-minor-units)
             max-project-monthly-minor-units)
      (throw
       (errors/raise! "The shared project budget is exhausted"
                      {:type ::lifecycle/project-budget-exhausted})))
    {:admission
     (-> admission
         (assoc-in [:active job-id] owner-key)
         (assoc-in [:user-day-attempts owner-key] (inc daily))
         (assoc-in [:user-month-reserved owner-key]
                   (+ user-monthly reservation-minor-units))
         (assoc :derivative-reserved-minor-units
                (+ derivative-monthly reservation-minor-units)))
     :shared-reserved (+ shared-reserved reservation-minor-units)}))

(defn- shared-budget-doc [snapshot month reserved limit]
  (assoc (if (and snapshot (.exists ^DocumentSnapshot snapshot))
           (into {} (.getData ^DocumentSnapshot snapshot))
           {})
         "month" month
         "reservedMinorUnits" (long reserved)
         "limitMinorUnits" (long limit)
         "currency" "PLN"))

(defn- default-limits []
  {:reservation-minor-units
   (get-in contract/contract-v1
           [:limits :cost :attempt-reservation-minor-units])
   :max-project-nonterminal
   (get-in contract/contract-v1
           [:limits :concurrency :max-project-nonterminal-jobs])
   :max-user-nonterminal
   (get-in contract/contract-v1
           [:limits :concurrency :max-user-nonterminal-jobs])
   :max-user-attempts-per-day
   (get-in contract/contract-v1
           [:limits :cost :max-user-attempts-per-utc-day])
   :max-user-monthly-minor-units
   (get-in contract/contract-v1
           [:limits :cost :max-user-monthly-minor-units])
   :max-monthly-minor-units
   (get-in contract/contract-v1
           [:limits :cost :max-derivative-monthly-minor-units])
   :max-project-monthly-minor-units
   (get-in contract/contract-v1
           [:limits :cost :max-project-monthly-minor-units])})

(defn- config-long [environment name default]
  (let [value (get environment name)]
    (if (nil? value)
      default
      (or (parse-long value)
          (throw
           (errors/raise! "Derivative runtime limit must be an integer"
                          {:type ::invalid-environment
                           :name name}))))))

(defn runtime-config
  "Reads the #192 derivative runtime contract with #191 as default authority."
  ([]
   (runtime-config (System/getenv)))
  ([environment]
   (let [limits (:limits contract/contract-v1)]
     {:bucket (get environment "AGG_DERIVATIVE_BUCKET")
      :dispatcher-url
      (get environment "AGG_DERIVATIVE_DISPATCHER_URL")
      :queue-name (get environment "AGG_DERIVATIVE_TASKS_QUEUE")
      :tasks-service-account
      (get environment "AGG_DERIVATIVE_TASKS_SERVICE_ACCOUNT")
      :worker-job (get environment "AGG_DERIVATIVE_WORKER_JOB")
      :worker-service-account
      (get environment "AGG_DERIVATIVE_WORKER_SERVICE_ACCOUNT")
      :ttl
      {:asset-seconds
       (config-long
        environment "AGG_DERIVATIVE_ASSET_TTL_SECONDS"
        (get-in limits [:ttl :asset-seconds]))
       :job-metadata-seconds
       (config-long
        environment "AGG_DERIVATIVE_JOB_METADATA_TTL_SECONDS"
        (get-in limits [:ttl :job-metadata-seconds]))
       :playback-authority-seconds
       (config-long
        environment "AGG_DERIVATIVE_PLAYBACK_AUTHORITY_TTL_SECONDS"
        (get-in limits [:ttl :playback-authority-seconds]))
       :cache-minimum-remaining-seconds
       (config-long
        environment "AGG_DERIVATIVE_CACHE_MINIMUM_REMAINING_TTL_SECONDS"
        (get-in limits [:ttl :cache-minimum-remaining-seconds]))}
      :work-limits
      {:max-source-duration-seconds
       (config-long
        environment "AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS"
        (get-in limits [:source :max-duration-seconds]))
       :max-source-bytes
       (config-long
        environment "AGG_DERIVATIVE_MAX_SOURCE_BYTES"
        (get-in limits [:source :max-bytes]))
       :max-upstream-bytes
       (config-long
        environment "AGG_DERIVATIVE_MAX_UPSTREAM_BYTES"
        (get-in limits [:transfer :max-upstream-bytes]))
       :max-request-count
       (config-long
        environment "AGG_DERIVATIVE_MAX_REQUEST_COUNT"
        (get-in limits [:transfer :max-request-count]))
       :max-range-bytes
       (config-long
        environment "AGG_DERIVATIVE_MAX_RANGE_BYTES"
        (get-in limits [:transfer :max-range-bytes]))
       :max-output-bytes
       (config-long
        environment "AGG_DERIVATIVE_MAX_OUTPUT_BYTES"
        (get-in limits [:output :max-bytes]))}
      :admission-limits
      {:reservation-minor-units
       (config-long
        environment "AGG_DERIVATIVE_ATTEMPT_RESERVATION_MINOR_UNITS"
        (get-in limits [:cost :attempt-reservation-minor-units]))
       :max-project-nonterminal
       (config-long
        environment "AGG_DERIVATIVE_MAX_PROJECT_NONTERMINAL_JOBS"
        (get-in limits [:concurrency :max-project-nonterminal-jobs]))
       :max-user-nonterminal
       (config-long
        environment "AGG_DERIVATIVE_MAX_USER_NONTERMINAL_JOBS"
        (get-in limits [:concurrency :max-user-nonterminal-jobs]))
       :max-user-attempts-per-day
       (config-long
        environment "AGG_DERIVATIVE_MAX_USER_ATTEMPTS_PER_DAY"
        (get-in limits [:cost :max-user-attempts-per-utc-day]))
       :max-user-monthly-minor-units
       (config-long
        environment "AGG_DERIVATIVE_MAX_USER_MONTHLY_MINOR_UNITS"
        (get-in limits [:cost :max-user-monthly-minor-units]))
       :max-monthly-minor-units
       (config-long
        environment "AGG_DERIVATIVE_MAX_MONTHLY_MINOR_UNITS"
        (get-in limits [:cost :max-derivative-monthly-minor-units]))
       :max-project-monthly-minor-units
       (config-long
        environment "AGG_MONTHLY_BUDGET_MINOR_UNITS"
        (get-in limits [:cost :max-project-monthly-minor-units]))}})))

(defn- task-name [project region queue-name job-id attempt]
  (str
   (TaskName/of project region queue-name
                (str "derivative-" job-id "-attempt-" attempt))))

(defrecord CloudTaskPreparationQueue
           [^CloudTasksClient client project region queue-name
            dispatcher-url tasks-service-account]
  lifecycle/PreparationQueue
  (enqueue-preparation! [_ job-id attempt]
    (let [parent (str (QueueName/of project region queue-name))
          name (task-name project region queue-name job-id attempt)
          oidc (-> (OidcToken/newBuilder)
                   (.setServiceAccountEmail tasks-service-account)
                   (.setAudience dispatcher-url)
                   .build)
          request
          (-> (HttpRequest/newBuilder)
              (.setUrl
               (str dispatcher-url
                    "/internal/v1/derivative-preparations/"
                    job-id "/attempts/" attempt "/dispatch"))
              (.setHttpMethod tasks-post)
              (.setOidcToken oidc)
              (.putHeaders "Content-Type" "application/json")
              .build)
          task (-> (Task/newBuilder)
                   (.setName name)
                   (.setHttpRequest request)
                   .build)]
      (try
        (.createTask client parent task)
        (catch ApiException error
          (when-not (duplicate-task? error)
            (throw error))))))
  (delete-preparation-task! [_ job-id attempt]
    (try
      (.deleteTask client
                   (task-name project region queue-name job-id attempt))
      (catch ApiException error
        (when-not (missing-resource? error)
          (throw error))))))

(defn derivative-run-request [worker-job job-id attempt]
  (let [container
        (-> (RunJobRequest$Overrides$ContainerOverride/newBuilder)
            (.addAllArgs
             ["clojure.main" "-m" "agg.derivative.worker"
              "--job-id" job-id "--attempt" (str attempt)])
            .build)
        overrides
        (-> (RunJobRequest$Overrides/newBuilder)
            (.addContainerOverrides container)
            (.setTaskCount 1)
            .build)]
    (-> (RunJobRequest/newBuilder)
        (.setName worker-job)
        (.setOverrides overrides)
        .build)))

(defn- execution-option [^Execution execution flag]
  (->> (.getContainersList (.getTemplate execution))
       (mapcat #(.getArgsList %))
       (partition 2 1)
       (some (fn [[candidate value]]
               (when (= flag candidate) value)))))

(defn- active-execution-for-attempt [executions job-id attempt]
  (some
   (fn [^Execution execution]
     (when (and (not (.hasCompletionTime execution))
                (= job-id (execution-option execution "--job-id"))
                (= (str attempt)
                   (execution-option execution "--attempt")))
       (.getName execution)))
   executions))

(defn- execution-state [^Execution execution]
  (let [task-count (.getTaskCount execution)]
    (cond
      (and (pos? task-count)
           (= task-count (.getCancelledCount execution))) :cancelled
      (pos? (.getFailedCount execution)) :failed
      (and (pos? task-count)
           (= task-count (.getSucceededCount execution))) :succeeded
      :else :running)))

(defrecord CloudRunPreparationLauncher
           [^JobsClient jobs-client ^ExecutionsClient executions-client
            worker-job]
  lifecycle/PreparationLauncher
  (launch-preparation! [_ job-id attempt]
    (let [operation
          (.runJobAsync
           jobs-client
           (derivative-run-request worker-job job-id attempt))
          execution (await! (.getMetadata operation))]
      (.getName ^Execution execution)))
  (cancel-preparation-execution! [this execution]
    (try
      (await-cancellation!
       (.cancelExecutionAsync executions-client execution))
      (catch ApiException error
        (if (and
             (= StatusCode$Code/FAILED_PRECONDITION
                (some-> error .getStatusCode .getCode))
             (contains? #{:cancelled :failed :succeeded}
                        (lifecycle/preparation-execution-state
                         this execution)))
          nil
          (throw error)))))
  (preparation-execution-state [_ execution]
    (try
      (execution-state (.getExecution executions-client execution))
      (catch ApiException error
        (if (missing-resource? error)
          :missing
          (throw error)))))
  lifecycle/RecoverablePreparationLauncher
  (find-active-preparation-execution [_ job-id attempt]
    (active-execution-for-attempt
     (.. executions-client (listExecutions worker-job) iterateAll)
     job-id attempt)))

(defn task-queue
  [{:keys [project region queue-name dispatcher-url
           tasks-service-account]}]
  (->CloudTaskPreparationQueue
   (CloudTasksClient/create) project region queue-name
   dispatcher-url tasks-service-account))

(defn run-launcher [worker-job]
  (->CloudRunPreparationLauncher
   (JobsClient/create) (ExecutionsClient/create) worker-job))

(defrecord FirestorePreparationService
           [^Firestore firestore queue launcher ^Clock clock
            fingerprint-secret member-directory limits source-gateway
            access-provider asset-store]
  lifecycle/PreparationAccess
  (owns-preparation? [_ job-id subject]
    (let [job
          (snapshot-job
           (await! (.get (.document
                          (.collection firestore "derivative-preparations")
                          job-id))))]
      (and (= subject (:owner-subject job))
           (not (contains? #{:expired :revoked} (:state job)))
           (.isBefore (Instant/now clock) (:metadata-expires-at job)))))
  lifecycle/PreparationPlaybackAccess
  (preparation-playback-asset [_ job-id identity]
    (transaction!
     firestore
     (fn [transaction]
       (let [job
             (snapshot-job
              (transaction-snapshot
               transaction
               (.document
                (.collection firestore "derivative-preparations")
                job-id)))
             now (Instant/now clock)]
         (require-transaction-member! member-directory transaction identity)
         (when (and (= :succeeded (:state job))
                    (= (:subject identity) (:owner-subject job))
                    (= (:membership-version identity)
                       (:membership-version job))
                    (instance? Instant (:completed-at job))
                    (instance? Instant (:asset-expires-at job))
                    (.isBefore now (:asset-expires-at job))
                    (pos-int? (:asset-generation job))
                    (pos-int? (:asset-size job))
                    (= "video/mp4" (:asset-content-type job))
                    (= (:profile-version job)
                       (:asset-profile-version job))
                    (string? (:object-key job))
                    (not-empty (:object-key job)))
           (cond-> {:object-key (:object-key job)
                    :generation (:asset-generation job)
                    :size (:asset-size job)
                    :content-type (:asset-content-type job)
                    :profile-version (:asset-profile-version job)
                    :completed-at (:completed-at job)
                    :expires-at (:asset-expires-at job)}
             (:request-id job) (assoc :request-id (:request-id job))
             (:trace job) (assoc :trace (:trace job))
             (:revision job) (assoc :revision (:revision job))))))))
  lifecycle/PreparationService
  (submit-preparation! [_ idempotency-key raw-request]
    (require-idempotency-key! idempotency-key)
    (let [profile-version (get-in contract/contract-v1 [:profile :version])
          request (assoc raw-request :profile-version profile-version)
          job-id (str (UUID/randomUUID))
          digest (request-digest fingerprint-secret request job-id)
          now (Instant/now clock)
          retention
          (get-in contract/contract-v1 [:limits :ttl :job-metadata-seconds])
          candidate
          (assoc
           (lifecycle/transition nil {:type :submit :id job-id :now now})
           :owner-subject (:subject request)
           :owner-email (:email request)
           :membership-version (:membership-version request)
           :file-id (:file-id request)
           :drive-version (:drive-version request)
           :source-bytes (:source-bytes request)
           :source-duration-seconds (:source-duration-seconds request)
           :request-id (:request-id request)
           :trace (:trace request)
           :revision (:revision request)
           :reservation-minor-units (:reservation-minor-units limits)
           :metadata-expires-at (.plusSeconds now retention))
          jobs (.collection firestore "derivative-preparations")
          idempotency
          (.collection firestore "derivative-preparation-idempotency")
          cache (.collection firestore "derivative-preparation-cache")
          orchestration
          (.collection firestore "derivative-preparation-orchestration")
          owner-key (sha256 (:subject request))
          idempotency-ref
          (.document idempotency
                     (sha256 [(:subject request) idempotency-key]))
          admission-ref (.document orchestration "admission")
          day (utc-day clock)
          month (billing-month clock)
          shared-ref
          (.document (.collection firestore "orchestration")
                     (str "budget-" month))
          fingerprint (request-fingerprint fingerprint-secret request job-id)
          cache-ref (.document cache (:fingerprint fingerprint))
          result
          (transaction!
           firestore
           (fn [transaction]
             (let [stored-idempotency
                   (transaction-snapshot transaction idempotency-ref)]
               (if (.exists ^DocumentSnapshot stored-idempotency)
                 (let [data (.getData ^DocumentSnapshot stored-idempotency)
                       stored-job
                       (snapshot-job
                        (transaction-snapshot
                         transaction (.document jobs (get data "jobId"))))]
                   (require-transaction-member!
                    member-directory transaction (member-identity request))
                   (when-not
                    (= (get data "requestDigest")
                       (request-digest fingerprint-secret request
                                       (:id stored-job)))
                     (throw
                      (errors/raise!
                       "Idempotency key belongs to another preparation"
                       {:type ::lifecycle/idempotency-conflict})))
                   {:created? false :job stored-job})
                 (let [cache-snapshot
                       (transaction-snapshot transaction cache-ref)
                       cached
                       (when (= :cross-job (:reuse-scope fingerprint))
                         (eligible-cache-job
                          cache-snapshot candidate now
                          (get-in contract/contract-v1
                                  [:limits :ttl
                                   :cache-minimum-remaining-seconds])))]
                   (require-transaction-member!
                    member-directory transaction (member-identity request))
                   (if cached
                     (do
                       (.set ^Transaction transaction
                             (.document jobs job-id) (job-doc cached))
                       (.set ^Transaction transaction idempotency-ref
                             {"jobId" job-id
                              "requestDigest" digest
                              "expireAt"
                              (Date/from (:metadata-expires-at cached))})
                       {:created? false :cache-hit? true :job cached})
                     (let [admission-snapshot
                           (transaction-snapshot transaction admission-ref)
                           shared-snapshot
                           (transaction-snapshot transaction shared-ref)
                           admission
                           (current-admission admission-snapshot day month)
                           shared-reserved
                           (long
                            (or
                             (some-> ^DocumentSnapshot shared-snapshot
                                     .getData
                                     (get "reservedMinorUnits"))
                             0))
                           admitted
                           (admit! admission shared-reserved owner-key job-id
                                   limits)]
                       (.set ^Transaction transaction
                             (.document jobs job-id) (job-doc candidate))
                       (.set ^Transaction transaction idempotency-ref
                             {"jobId" job-id
                              "requestDigest" digest
                              "expireAt"
                              (Date/from (:metadata-expires-at candidate))})
                       (.set ^Transaction transaction admission-ref
                             (admission-doc (:admission admitted)))
                       (.set ^Transaction transaction shared-ref
                             (shared-budget-doc
                              shared-snapshot month
                              (:shared-reserved admitted)
                              (:max-project-monthly-minor-units limits)))
                       {:created? true :job candidate})))))))]
      (when (:created? result)
        (lifecycle/enqueue-preparation!
         queue (get-in result [:job :id]) (get-in result [:job :attempt])))
      (update result :job preparation-resource)))
  (get-preparation [_ job-id]
    (some-> (await! (.get (.document
                           (.collection firestore "derivative-preparations")
                           job-id)))
            snapshot-job
            preparation-resource))
  (dispatch-preparation! [this job-id]
    (let [job (snapshot-job
               (await! (.get (.document
                              (.collection firestore
                                           "derivative-preparations")
                              job-id))))]
      (lifecycle/dispatch-preparation! this job-id (:attempt job))))
  (dispatch-preparation! [_ job-id attempt]
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admitted
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (snapshot-job
                    (transaction-snapshot transaction job-ref))]
               (when-not job
                 (throw
                  (errors/raise! "Derivative preparation does not exist"
                                 {:type
                                  ::lifecycle/preparation-not-found})))
               (if (or (not (pos-int? attempt))
                       (not= (long attempt) (long (:attempt job)))
                       (not= :queued (:state job)))
                 {:started? false :job job}
                 (let [running
                       (assoc
                        (lifecycle/transition
                         job {:type :dispatch :now (Instant/now clock)})
                        :dispatch-started-at (Instant/now clock))]
                   (.set ^Transaction transaction job-ref (job-doc running))
                   {:started? true :job running})))))]
      (if-not (:started? admitted)
        (update admitted :job preparation-resource)
        (let [launch
              (try
                {:execution
                 (lifecycle/launch-preparation!
                  launcher job-id attempt)}
                (catch Throwable error
                  {:error error}))]
          (if-let [error (:error launch)]
            (let [admission-ref
                  (.document
                   (.collection
                    firestore "derivative-preparation-orchestration")
                   "admission")]
              (transaction!
               firestore
               (fn [transaction]
                 (let [job
                       (exact-attempt
                        (snapshot-job
                         (transaction-snapshot transaction job-ref))
                        attempt)
                       admission
                       (current-admission
                        (transaction-snapshot transaction admission-ref)
                        (utc-day clock) (billing-month clock))
                       terminal
                       (if (= :cancellation-requested (:state job))
                         (lifecycle/transition
                          job {:type :complete
                               :outcome :cancelled
                               :now (Instant/now clock)})
                         (lifecycle/transition
                          job {:type :complete
                               :outcome :failed
                               :failure-code "derivative_failed"
                               :retryable true
                               :now (Instant/now clock)}))]
                   (.set ^Transaction transaction job-ref
                         (job-doc terminal))
                   (.set ^Transaction transaction admission-ref
                         (admission-doc
                          (remove-active admission job-id))))))
              (throw
               (errors/raise! "Derivative worker launch failed"
                              {:type ::launch-failed
                               :retryable true}
                              error)))
            (let [execution (:execution launch)
                  running
                  (transaction!
                   firestore
                   (fn [transaction]
                     (let [job
                           (exact-attempt
                            (snapshot-job
                             (transaction-snapshot transaction job-ref))
                            attempt)]
                       (if (and (contains? #{:running
                                             :cancellation-requested}
                                           (:state job))
                                (nil? (:execution job)))
                         (let [updated
                               (assoc job :execution execution
                                      :updated-at (Instant/now clock))]
                           (.set ^Transaction transaction job-ref
                                 (job-doc updated))
                           updated)
                         job))))]
              (when (= :cancellation-requested (:state running))
                (lifecycle/cancel-preparation-execution!
                 launcher execution))
              {:started? true :job (preparation-resource running)}))))))
  (cancel-preparation! [_ job-id]
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admission-ref
          (.document
           (.collection firestore "derivative-preparation-orchestration")
           "admission")
          requested
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (snapshot-job
                    (transaction-snapshot transaction job-ref))]
               (when-not job
                 (throw
                  (errors/raise! "Derivative preparation does not exist"
                                 {:type
                                  ::lifecycle/preparation-not-found})))
               (let [updated
                     (lifecycle/transition
                      job {:type :cancel :now (Instant/now clock)})
                     admission
                     (when (= :queued (:state job))
                       (current-admission
                        (transaction-snapshot transaction admission-ref)
                        (utc-day clock)
                        (billing-month clock)))]
                 (.set ^Transaction transaction job-ref (job-doc updated))
                 (when admission
                   (.set ^Transaction transaction admission-ref
                         (admission-doc
                          (remove-active admission job-id))))
                 {:before job :after updated}))))]
      (when (= :queued (get-in requested [:before :state]))
        (try
          (lifecycle/delete-preparation-task!
           queue job-id (get-in requested [:before :attempt]))
          (catch Throwable _ nil)))
      (when-let [execution (get-in requested [:after :execution])]
        (lifecycle/cancel-preparation-execution! launcher execution))
      (cond-> (preparation-resource (:after requested))
        (and (not (contains? #{:succeeded :failed :cancelled
                               :expired :revoked}
                             (get-in requested [:before :state])))
             (contains? #{:succeeded :failed :cancelled :expired :revoked}
                        (get-in requested [:after :state])))
        lifecycle/with-terminal-transition)))
  (retry-preparation! [_ job-id]
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admission-ref
          (.document
           (.collection firestore "derivative-preparation-orchestration")
           "admission")
          month (billing-month clock)
          shared-ref
          (.document (.collection firestore "orchestration")
                     (str "budget-" month))
          retried
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (snapshot-job
                    (transaction-snapshot transaction job-ref))]
               (when-not job
                 (throw
                  (errors/raise! "Derivative preparation does not exist"
                                 {:type
                                  ::lifecycle/preparation-not-found})))
               (let [admission-snapshot
                     (transaction-snapshot transaction admission-ref)
                     shared-snapshot
                     (transaction-snapshot transaction shared-ref)
                     identity {:subject (:owner-subject job)
                               :email (:owner-email job)
                               :membership-version
                               (:membership-version job)}
                     admission
                     (current-admission admission-snapshot
                                        (utc-day clock) month)
                     shared-reserved
                     (long
                      (or
                       (some-> ^DocumentSnapshot shared-snapshot
                               .getData
                               (get "reservedMinorUnits"))
                       0))
                     _ (require-transaction-member!
                        member-directory transaction identity)
                     admitted
                     (admit! admission shared-reserved
                             (sha256 (:owner-subject job)) job-id limits)
                     updated
                     (assoc
                      (lifecycle/transition
                       job {:type :retry :now (Instant/now clock)})
                      :reservation-minor-units
                      (:reservation-minor-units limits))]
                 (.set ^Transaction transaction job-ref (job-doc updated))
                 (.set ^Transaction transaction admission-ref
                       (admission-doc (:admission admitted)))
                 (.set ^Transaction transaction shared-ref
                       (shared-budget-doc
                        shared-snapshot month (:shared-reserved admitted)
                        (:max-project-monthly-minor-units limits)))
                 updated))))]
      (lifecycle/enqueue-preparation! queue job-id (:attempt retried))
      (preparation-resource retried)))
  (reconcile-preparations! [this]
    (let [jobs
          (->> (await! (.get (.collection
                              firestore "derivative-preparations")))
               .getDocuments
               (keep snapshot-job)
               vec)
          repaired (atom 0)
          terminal-jobs (atom [])
          now (Instant/now clock)]
      (doseq [job jobs]
        (cond
          (and (:metadata-expires-at job)
               (not (.isBefore now (:metadata-expires-at job)))
               (not (contains? #{:expired :revoked} (:state job))))
          (let [job-id (:id job)
                job-ref (.document
                         (.collection firestore "derivative-preparations")
                         job-id)
                admission-ref
                (.document
                 (.collection
                  firestore "derivative-preparation-orchestration")
                 "admission")
                expiration
                (transaction!
                 firestore
                 (fn [transaction]
                   (let [current
                         (snapshot-job
                          (transaction-snapshot transaction job-ref))
                         updated
                         (lifecycle/transition
                          current {:type :expire :now now})
                         release?
                         (not (contains? #{:running
                                           :cancellation-requested}
                                         (:state updated)))
                         admission
                         (when release?
                           (current-admission
                            (transaction-snapshot
                             transaction admission-ref)
                            (utc-day clock) (billing-month clock)))]
                     (.set ^Transaction transaction job-ref
                           (job-doc updated))
                     (when admission
                       (.set ^Transaction transaction admission-ref
                             (admission-doc
                              (remove-active admission job-id))))
                     {:before current :after updated})))]
            (when (= :queued (:state job))
              (try
                (lifecycle/delete-preparation-task!
                 queue (:id job) (:attempt job))
                (catch Throwable _ nil)))
            (when (and (= :cancellation-requested
                          (get-in expiration [:after :state]))
                       (get-in expiration [:after :execution]))
              (lifecycle/cancel-preparation-execution!
               launcher (get-in expiration [:after :execution])))
            (when (and
                   (not (contains? #{:succeeded :failed :cancelled
                                     :expired :revoked}
                                   (get-in expiration [:before :state])))
                   (contains? #{:succeeded :failed :cancelled
                                :expired :revoked}
                              (get-in expiration [:after :state])))
              (swap! terminal-jobs conj
                     (preparation-resource (:after expiration))))
            (swap! repaired inc))

          (= :queued (:state job))
          (try
            (lifecycle/enqueue-preparation!
             queue (:id job) (:attempt job))
            (catch Throwable _ nil))

          (and (contains? #{:running :cancellation-requested}
                          (:state job))
               (nil? (:execution job)))
          (let [execution
                (when
                 (satisfies?
                  lifecycle/RecoverablePreparationLauncher launcher)
                  (lifecycle/find-active-preparation-execution
                   launcher (:id job) (:attempt job)))]
            (if execution
              (let [job-ref
                    (.document
                     (.collection firestore "derivative-preparations")
                     (:id job))
                    recovered
                    (transaction!
                     firestore
                     (fn [transaction]
                       (let [current
                             (exact-attempt
                              (snapshot-job
                               (transaction-snapshot transaction job-ref))
                              (:attempt job))]
                         (if (and
                              (contains? #{:running
                                           :cancellation-requested}
                                         (:state current))
                              (nil? (:execution current)))
                           (let [updated
                                 (assoc current :execution execution
                                        :updated-at now)]
                             (.set ^Transaction transaction job-ref
                                   (job-doc updated))
                             updated)
                           current))))]
                (when (= :cancellation-requested (:state recovered))
                  (lifecycle/cancel-preparation-execution!
                   launcher execution))
                (swap! repaired inc))
              (when (and (:dispatch-started-at job)
                         (not
                          (.isBefore
                           now
                           (.plusSeconds
                            ^Instant (:dispatch-started-at job) 60))))
                (if (= :cancellation-requested (:state job))
                  (let [terminal
                        (lifecycle/acknowledge-preparation-cancellation!
                         this (:id job) (:attempt job))]
                    (when (lifecycle/terminal-transition? terminal)
                      (swap! terminal-jobs conj terminal)))
                  (lifecycle/fail-preparation-attempt!
                   this (:id job) (:attempt job)
                   {:failure-code "derivative_failed"
                    :retryable true}))
                (swap! repaired inc))))

          (= :cancellation-requested (:state job))
          (when (:execution job)
            (let [state
                  (lifecycle/preparation-execution-state
                   launcher (:execution job))]
              (if (= :cancelled state)
                (do
                  (let [terminal
                        (lifecycle/acknowledge-preparation-cancellation!
                         this (:id job) (:attempt job))]
                    (when (lifecycle/terminal-transition? terminal)
                      (swap! terminal-jobs conj terminal)))
                  (swap! repaired inc))
                (try
                  (lifecycle/cancel-preparation-execution!
                   launcher (:execution job))
                  (catch Throwable _ nil)))))

          (and (= :running (:state job)) (:execution job))
          (let [state
                (lifecycle/preparation-execution-state
                 launcher (:execution job))]
            (when (contains? #{:failed :cancelled :succeeded :missing}
                             state)
              (lifecycle/fail-preparation-attempt!
               this (:id job) (:attempt job)
               {:failure-code
                (if (= :cancelled state)
                  "derivative_cancel_failed"
                  "derivative_failed")
                :retryable true})
              (swap! repaired inc)))))
      (cond-> {:repairedJobs @repaired}
        (seq @terminal-jobs) (assoc :terminalJobs @terminal-jobs))))
  lifecycle/PreparationAttemptService
  (load-preparation-attempt [_ job-id attempt]
    (let [job
          (exact-attempt
           (snapshot-job
            (await! (.get (.document
                           (.collection firestore
                                        "derivative-preparations")
                           job-id))))
           attempt)]
      (when-not (= :running (:state job))
        (invalid-attempt!))
      (attempt-resource fingerprint-secret job)))
  (complete-preparation-attempt! [_ job-id attempt result]
    (contract/validate-work! (:measurements result))
    (when-not (= (:size result)
                 (get-in result [:measurements :output-bytes]))
      (invalid-attempt!))
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admission-ref
          (.document
           (.collection firestore "derivative-preparation-orchestration")
           "admission")
          completed
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (exact-attempt
                    (snapshot-job
                     (transaction-snapshot transaction job-ref))
                    attempt)
                   updated
                   (if (= :succeeded (:state job))
                     (if (exact-completion? job result)
                       job
                       (invalid-attempt!))
                     (lifecycle/transition
                      job {:type :complete
                           :outcome :succeeded
                           :asset-id (:asset-id result)
                           :object-key (:object-key result)
                           :asset-generation (:generation result)
                           :asset-size (:size result)
                           :asset-content-type (:content-type result)
                           :asset-profile-version (:profile-version result)
                           :now (Instant/now clock)}))
                   fingerprint
                   (request-fingerprint
                    fingerprint-secret
                    {:subject (:owner-subject job)
                     :file-id (:file-id job)
                     :drive-version (:drive-version job)
                     :source-bytes (:source-bytes job)
                     :profile-version (:profile-version job)}
                    job-id)
                   admission
                   (current-admission
                    (transaction-snapshot transaction admission-ref)
                    (utc-day clock) (billing-month clock))]
               (.set ^Transaction transaction job-ref (job-doc updated))
               (.set ^Transaction transaction admission-ref
                     (admission-doc (remove-active admission job-id)))
               (when (= :cross-job (:reuse-scope fingerprint))
                 (.set ^Transaction transaction
                       (.document
                        (.collection firestore
                                     "derivative-preparation-cache")
                        (:fingerprint fingerprint))
                       (cache-doc updated)))
               updated)))]
      (preparation-resource completed)))
  (fail-preparation-attempt! [_ job-id attempt failure]
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admission-ref
          (.document
           (.collection firestore "derivative-preparation-orchestration")
           "admission")
          failed
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (exact-attempt
                    (snapshot-job
                     (transaction-snapshot transaction job-ref))
                    attempt)
                   updated
                   (lifecycle/transition
                    job {:type :complete
                         :outcome :failed
                         :failure-code (:failure-code failure)
                         :retryable (boolean (:retryable failure))
                         :now (Instant/now clock)})
                   admission
                   (current-admission
                    (transaction-snapshot transaction admission-ref)
                    (utc-day clock) (billing-month clock))]
               (.set ^Transaction transaction job-ref (job-doc updated))
               (.set ^Transaction transaction admission-ref
                     (admission-doc (remove-active admission job-id)))
               updated)))]
      (preparation-resource failed)))
  (acknowledge-preparation-cancellation! [_ job-id attempt]
    (let [job-ref (.document
                   (.collection firestore "derivative-preparations") job-id)
          admission-ref
          (.document
           (.collection firestore "derivative-preparation-orchestration")
           "admission")
          transition-result
          (transaction!
           firestore
           (fn [transaction]
             (let [job
                   (exact-attempt
                    (snapshot-job
                     (transaction-snapshot transaction job-ref))
                    attempt)]
               (cond
                 (contains? #{:cancelled :expired} (:state job))
                 {:before job :after job}

                 (= :cancellation-requested (:state job))
                 (let [updated
                       (lifecycle/transition
                        job {:type :complete
                             :outcome :cancelled
                             :now (Instant/now clock)})
                       admission
                       (current-admission
                        (transaction-snapshot transaction admission-ref)
                        (utc-day clock) (billing-month clock))]
                   (.set ^Transaction transaction job-ref (job-doc updated))
                   (.set ^Transaction transaction admission-ref
                         (admission-doc
                          (remove-active admission job-id)))
                   {:before job :after updated})
                 :else (invalid-attempt!)))))]
      (cond-> (preparation-resource (:after transition-result))
        (and (not (contains? #{:succeeded :failed :cancelled
                               :expired :revoked}
                             (get-in transition-result [:before :state])))
             (contains? #{:succeeded :failed :cancelled :expired :revoked}
                        (get-in transition-result [:after :state])))
        lifecycle/with-terminal-transition)))
  (preparation-cancellation-requested? [_ job-id attempt]
    (let [job
          (exact-attempt
           (snapshot-job
            (await! (.get (.document
                           (.collection firestore
                                        "derivative-preparations")
                           job-id))))
           attempt)]
      (cond
        (contains? #{:running :succeeded} (:state job)) false
        (contains? #{:cancellation-requested :cancelled :expired :revoked}
                   (:state job)) true
        :else (invalid-attempt!))))
  admin/JobAdministration
  (cancel-member-jobs! [this {:keys [subject] :as cleanup-identity}]
    (let [jobs (.collection firestore "derivative-preparations")
          candidates
          (->> (await! (.get (.whereEqualTo jobs "ownerSubject" subject)))
               .getDocuments
               (keep snapshot-job)
               (filter
                #(and
                  (admin/cleanup-generation?
                   cleanup-identity (:membership-version %))
                  (contains? #{:queued :running :cancellation-requested
                               :succeeded}
                             (:state %))))
               vec)]
      (doseq [job candidates]
        (if (= :succeeded (:state job))
          (let [job-ref (.document jobs (:id job))
                fingerprint
                (request-fingerprint
                 fingerprint-secret
                 {:subject (:owner-subject job)
                  :file-id (:file-id job)
                  :drive-version (:drive-version job)
                  :source-bytes (:source-bytes job)
                  :profile-version (:profile-version job)}
                 (:id job))]
            (transaction!
             firestore
             (fn [transaction]
               (let [current
                     (snapshot-job
                      (transaction-snapshot transaction job-ref))]
                 (when (and
                        (= :succeeded (:state current))
                        (admin/cleanup-generation?
                         cleanup-identity (:membership-version current)))
                   (.set ^Transaction transaction job-ref
                         (job-doc
                          (lifecycle/transition
                           current {:type :membership-revoked
                                    :now (Instant/now clock)})))
                   (when (= :cross-job (:reuse-scope fingerprint))
                     (.delete
                      ^Transaction transaction
                      (.document
                       (.collection firestore
                                    "derivative-preparation-cache")
                       (:fingerprint fingerprint)))))))))
          (try
            (lifecycle/cancel-preparation! this (:id job))
            (catch clojure.lang.ExceptionInfo error
              (when-not
               (= ::lifecycle/invalid-transition (:type (ex-data error)))
                (throw error))))))
      (count candidates))))

(defn preparation-service
  [{:keys [firestore queue launcher clock fingerprint-secret member-directory
           limits source-gateway access-provider asset-store]
    :or {clock (Clock/systemUTC)}}]
  (when-not (and (string? fingerprint-secret)
                 (not-empty fingerprint-secret))
    (throw
     (errors/raise! "Derivative fingerprint secret is required"
                    {:type ::lifecycle/invalid-configuration})))
  (->FirestorePreparationService
   (or firestore (.getService (FirestoreOptions/getDefaultInstance)))
   queue launcher clock fingerprint-secret member-directory
   (merge (default-limits) limits) source-gateway access-provider asset-store))

(defn publish-derivative!
  [service publication]
  (let [asset-store (:asset-store service)]
    (when-not (satisfies? storage/AssetStore asset-store)
      (throw
       (errors/raise! "Derivative asset storage is unavailable"
                      {:type ::storage/invalid-configuration})))
    (storage/publish-verified! asset-store publication)))

(defn delete-derivative!
  [service asset]
  (let [asset-store (:asset-store service)]
    (when-not (satisfies? storage/AssetStore asset-store)
      (throw
       (errors/raise! "Derivative asset storage is unavailable"
                      {:type ::storage/invalid-configuration})))
    (storage/delete-generation! asset-store asset)))

(defn source-access!
  "Returns exact-attempt Drive authority only inside the worker boundary."
  [service job-id attempt]
  (let [firestore (:firestore service)
        member-directory (:member-directory service)
        job-ref (.document
                 (.collection firestore "derivative-preparations") job-id)
        job
        (transaction!
         firestore
         (fn [transaction]
           (let [job
                 (exact-attempt
                  (snapshot-job
                   (transaction-snapshot transaction job-ref))
                  attempt)]
             (when-not (= :running (:state job))
               (invalid-attempt!))
             (require-transaction-member!
              member-directory transaction
              {:subject (:owner-subject job)
               :email (:owner-email job)
               :membership-version (:membership-version job)})
             job)))
        access ((:access-provider service) (:owner-subject job))]
    (when-not (and (:source-gateway service)
                   (string? (:access-token access))
                   (not-empty (:access-token access)))
      (throw
       (errors/raise! "Derivative source authority is unavailable"
                      {:type ::source-unavailable})))
    {:gateway (:source-gateway service)
     :access-token (:access-token access)
     :file-id (:file-id job)}))

(defn- environment-emails [environment name]
  (->> (str/split (get environment name "") #"[;,]")
       (map str/trim)
       (remove str/blank?)
       set))

(defn worker-service
  "Builds the worker-side exact-attempt and Drive-authority service."
  ([]
   (let [environment (System/getenv)
         config (runtime-config environment)
         project
         (get environment "GOOGLE_CLOUD_PROJECT"
              "animated-graph-cloud-jp")
         region (get environment "AGG_REGION" "europe-central2")
         firestore (.getService (FirestoreOptions/getDefaultInstance))
         member-directory
         (admin-gcp/member-directory
          firestore
          (get environment "AGG_OWNER_EMAIL")
          (environment-emails environment "AGG_ADMIN_EMAILS"))
         source
         (auth-gcp/renderer-source
          {:firestore firestore
           :project project
           :region region
           :oauth-client-credentials
           (get environment "AGG_OAUTH_CLIENT_CREDENTIALS")})]
     (preparation-service
      {:firestore firestore
       :member-directory member-directory
       :fingerprint-secret
       (get environment "AGG_TOKEN_HASH_PEPPER")
       :asset-store (storage/gcs-asset-store (:bucket config))
       :source-gateway (:gateway source)
       :access-provider (:access-provider source)
       :limits (:admission-limits config)})))
  ([options]
   (preparation-service options)))
