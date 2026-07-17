(ns agg.jobs.gcp
  (:require [agg.contracts.render :as contract]
            [agg.auth.gcp :as auth-gcp]
            [agg.jobs.lifecycle :as lifecycle]
            [clojure.data.json :as json])
  (:import (com.google.api.gax.rpc ApiException StatusCode$Code)
           (com.google.cloud.firestore DocumentSnapshot Firestore FirestoreOptions
                                       Transaction Transaction$Function)
           (com.google.cloud.run.v2 ExecutionsClient JobsClient RunJobRequest
                                    RunJobRequest$Overrides
                                    RunJobRequest$Overrides$ContainerOverride)
           (com.google.cloud.storage BlobInfo HttpMethod Storage
                                     Storage$BlobTargetOption
                                     Storage$SignUrlOption StorageOptions)
           (com.google.cloud.tasks.v2 CloudTasksClient HttpRequest
                                      OidcToken QueueName Task TaskName)
           (java.time Clock Instant)
           (java.util Date UUID)
           (java.util.concurrent ExecutionException TimeUnit)))

;; The generated Cloud Tasks HttpMethod enum cannot be imported alongside the
;; Storage enum. Resolve it once without leaking that naming collision.
(def ^:private tasks-post
  (com.google.cloud.tasks.v2.HttpMethod/valueOf "POST"))

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
    (:requester-subject job) (assoc "requesterSubject" (:requester-subject job))))

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
        (assoc :requester-subject (get data "requesterSubject"))))))

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
    (throw (ex-info "A bounded Idempotency-Key header is required"
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
      (throw (ex-info "Signed uploads expire within fifteen minutes"
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

(defn- run-job-request [renderer-job job-id]
  (let [container (-> (RunJobRequest$Overrides$ContainerOverride/newBuilder)
                      (.addAllArgs ["clojure.main" "-m" "agg.renderer.main"
                                    "--job-id" job-id])
                      (.build))
        overrides (-> (RunJobRequest$Overrides/newBuilder)
                      (.addContainerOverrides container)
                      (.setTaskCount 1)
                      (.build))]
    (-> (RunJobRequest/newBuilder)
        (.setName renderer-job)
        (.setOverrides overrides)
        (.build))))

(defrecord CloudRunLauncher [^JobsClient jobs-client
                             ^ExecutionsClient executions-client
                             renderer-job]
  lifecycle/JobLauncher
  (launch-job! [_ job-id]
    (let [request (run-job-request renderer-job job-id)
          operation (.runJobAsync jobs-client request)
          execution (await! (.getMetadata operation))]
      (.getName execution)))
  (cancel-execution! [_ execution]
    (await! (.cancelExecutionAsync executions-client execution))))

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

(defn- complete-cancellation! [^Firestore firestore launcher ^Clock clock
                               job-ref capacity-ref job-id execution]
  (lifecycle/cancel-execution! launcher execution)
  (public-job
   (transaction!
    firestore
    (fn [transaction]
      (let [job (snapshot-job (transaction-snapshot transaction job-ref))
            capacity (transaction-snapshot transaction capacity-ref)
            now (now-ms clock)]
        (if (contains? #{:launching :running :cancellation-requested}
                       (:state job))
          (let [cancelled (terminal-job job :cancelled now)]
            (.set ^Transaction transaction job-ref (job-doc cancelled))
            (.set ^Transaction transaction capacity-ref
                  (released-capacity capacity job-id now))
            cancelled)
          job))))))

(defrecord FirestoreJobService [^Firestore firestore request-store queue launcher
                                worker ^Clock clock]
  lifecycle/JobAccess
  (owns-job? [_ job-id subject]
    (= subject
       (:requester-subject
        (snapshot-job
         (await! (.get (.document (.collection firestore "jobs") job-id)))))))
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
          idempotency-ref (.document idempotency
                                     (lifecycle/request-digest idempotency-key))
          candidate {:id job-id :state :queued :attempt 1
                     :request-object request-object
                     :requester-subject (:requesterSubject request)
                     :created-at now :updated-at now
                     :expires-at (+ now (* 90 24 60 60 1000))}
          result
          (transaction!
           firestore
           (fn [transaction]
             (let [snapshot (transaction-snapshot transaction idempotency-ref)]
               (if (.exists ^DocumentSnapshot snapshot)
                 (let [data (.getData ^DocumentSnapshot snapshot)]
                   (when-not (= digest (get data "requestDigest"))
                     (throw (ex-info "Idempotency key already belongs to another request"
                                     {:type ::lifecycle/idempotency-conflict})))
                   {:created? false
                    :job (snapshot-job
                          (transaction-snapshot
                           transaction (.document jobs (get data "jobId"))))})
                 (do
                   (.set ^Transaction transaction (.document jobs job-id)
                         (job-doc candidate))
                   (.set ^Transaction transaction idempotency-ref
                         {"jobId" job-id "requestDigest" digest})
                   {:created? true :job candidate})))))]
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
                 (throw (ex-info "Job does not exist" {:type ::lifecycle/job-not-found})))
               (if (not= :queued (:state job))
                 {:started? false :job job}
                 (let [capacity (transaction-snapshot transaction capacity-ref)
                       leases (capacity-leases capacity now)]
                   (when (>= (count leases) lifecycle/max-active-leases)
                     (throw (ex-info "All render leases are held"
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
          (let [execution (lifecycle/launch-job! launcher job-id)
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
                                            job-ref capacity-ref job-id execution)
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
            (throw (ex-info "Renderer launch failed"
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
                 (throw (ex-info "Job does not exist" {:type ::lifecycle/job-not-found})))
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
                 (throw (ex-info "Terminal job cannot be cancelled"
                                 {:type ::lifecycle/invalid-transition}))))))]
      (if-let [execution (:execution requested)]
        (complete-cancellation! firestore launcher clock job-ref capacity-ref
                                job-id execution)
        (public-job requested))))
  (retry-job! [_ job-id]
    (let [job-ref (.document (.collection firestore "jobs") job-id)
          retried
          (transaction!
           firestore
           (fn [transaction]
             (let [job (snapshot-job
                        (transaction-snapshot transaction job-ref))]
               (when-not job
                 (throw (ex-info "Job does not exist" {:type ::lifecycle/job-not-found})))
               (when-not (contains? #{:cancelled :failed} (:state job))
                 (throw (ex-info "Only failed or cancelled jobs can be retried"
                                 {:type ::lifecycle/invalid-transition})))
               (let [updated (-> job
                                 (assoc :state :queued
                                        :attempt (inc (:attempt job))
                                        :updated-at (now-ms clock))
                                 (dissoc :failure :output :execution
                                         :lease-token :lease-expires-at))]
                 (.set ^Transaction transaction job-ref (job-doc updated))
                 updated))))]
      (lifecycle/enqueue-job! queue job-id (:attempt retried))
      (public-job retried)))
  (run-job! [_ job-id]
    (let [job-ref (.document (.collection firestore "jobs") job-id)
          capacity-ref (.document (.collection firestore "orchestration") "capacity")
          job (snapshot-job (await! (.get job-ref)))]
      (when-not job
        (throw (ex-info "Job does not exist" {:type ::lifecycle/job-not-found})))
      (when-not (= :running (:state job))
        (throw (ex-info "Only a running job can render"
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
          (throw (ex-info "Render worker failed"
                          {:type ::lifecycle/worker-failed :job-id job-id}
                          cause)))))))

(defn request-store [bucket]
  (->GcsRequestStore (.getService (StorageOptions/getDefaultInstance)) bucket nil))

(defn task-queue [{:keys [project region queue-name dispatcher-url
                          dispatcher-service-account]}]
  (->CloudTaskQueue (CloudTasksClient/create) project region queue-name
                    dispatcher-url dispatcher-service-account))

(defn run-launcher [renderer-job]
  (->CloudRunLauncher (JobsClient/create) (ExecutionsClient/create) renderer-job))

(defn job-service [{:keys [firestore request-store queue launcher worker clock]
                    :or {clock (Clock/systemUTC)}}]
  (->FirestoreJobService
   (or firestore (.getService (FirestoreOptions/getDefaultInstance)))
   request-store queue launcher worker clock))

(defn- env [name default]
  (get (System/getenv) name default))

(defn api-system []
  (let [project (env "GOOGLE_CLOUD_PROJECT" "animated-graph-cloud-jp")
        region (env "AGG_REGION" "europe-central2")
        bucket (env "AGG_TEMPORARY_BUCKET" (str project "-temporary"))
        dispatcher-url (env "AGG_DISPATCHER_URL" nil)
        tasks-service-account
        (env "AGG_TASKS_SERVICE_ACCOUNT"
             (str "agg-tasks@" project ".iam.gserviceaccount.com"))]
    (when-not dispatcher-url
      (throw (ex-info "AGG_DISPATCHER_URL is required"
                      {:type ::missing-dispatcher-url})))
    (let [store (request-store bucket)
          firestore (.getService (FirestoreOptions/getDefaultInstance))
          job-dependencies
          {:upload-signer store
           :job-service
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
                             "/jobs/" (env "AGG_RENDERER_JOB" "agg-renderer")))})}]
      (if (= "true" (env "AGG_AUTH_ENABLED" "false"))
        (merge job-dependencies
               (auth-gcp/api-dependencies
                {:firestore firestore
                 :project project
                 :region region
                 :base-url (env "AGG_PUBLIC_BASE_URL" dispatcher-url)
                 :allowed-emails (env "AGG_ALLOWED_EMAILS" nil)
                 :session-secret (env "AGG_SESSION_KEY" nil)
                 :oauth-client-credentials
                 (env "AGG_OAUTH_CLIENT_CREDENTIALS" nil)
                 :tasks-service-account tasks-service-account
                 :picker-api-key (env "AGG_PICKER_API_KEY" nil)
                 :picker-app-id (env "AGG_PICKER_APP_ID" nil)}))
        job-dependencies))))

(defn api-job-service []
  (:job-service (api-system)))

(defn renderer-job-service [worker]
  (let [project (env "GOOGLE_CLOUD_PROJECT" "animated-graph-cloud-jp")
        bucket (env "AGG_TEMPORARY_BUCKET" (str project "-temporary"))]
    (job-service {:request-store (request-store bucket)
                  :worker worker})))
