(ns agg.derivative.lifecycle
  (:require [agg.admin.core :as admin]
            [agg.derivative.contract :as contract]
            [agg.derivative.keys :as keys]
            [agg.errors :as errors]
            [agg.render.derivative :as render-derivative])
  (:import (java.security MessageDigest)
           (java.time Clock Duration Instant LocalDate YearMonth ZoneOffset)
           (java.util HexFormat UUID)))

(def ^:private profile-version
  (get-in contract/contract-v1 [:profile :version]))

(def ^:private environment :production)

(def ^:private retention-seconds
  (get-in contract/contract-v1 [:limits :ttl :job-metadata-seconds]))

(def ^:private asset-ttl-seconds
  (get-in contract/contract-v1 [:limits :ttl :asset-seconds]))

(defn- opaque-id? [value]
  (try
    (and (string? value) (UUID/fromString value))
    (catch IllegalArgumentException _
      false)))

(defn- invalid-transition! [job event]
  (throw
   (errors/raise! "Invalid derivative preparation transition"
                  {:type ::invalid-transition
                   :failure-code "invalid_derivative_transition"
                   :state (:state job)
                   :reason (str (name (:type event)) "_from_"
                                (some-> (:state job) name))})))

(defn- at-or-after? [^Instant now ^Instant deadline]
  (and now deadline (not (.isBefore now deadline))))

(defn- without-terminal-data [job]
  (dissoc job :asset-id :asset-expires-at :object-key
          :asset-generation :asset-size :asset-content-type
          :asset-profile-version :completed-at
          :failure-code :retryable))

(defn- membership-failure [job state now]
  (-> job
      without-terminal-data
      (assoc :state state
             :updated-at now
             :metadata-expires-at
             (.plusSeconds ^Instant now retention-seconds)
             :failure-code "membership_revoked"
             :retryable false)))

(defn transition
  "Applies one pure derivative preparation lifecycle event."
  [job {:keys [type id now outcome asset-id object-key
               asset-generation asset-size asset-content-type
               asset-profile-version failure-code retryable]
        :as event}]
  (case type
    :submit
    (if (and (nil? job) (opaque-id? id) (instance? Instant now))
      {:id id
       :environment "production"
       :state :queued
       :attempt 1
       :profile-version profile-version
       :created-at now
       :updated-at now
       :metadata-expires-at (.plusSeconds ^Instant now retention-seconds)}
      (invalid-transition! job event))

    :dispatch
    (if (and (= :queued (:state job)) (instance? Instant now))
      (assoc job :state :running :updated-at now)
      (invalid-transition! job event))

    :cancel
    (if-not (instance? Instant now)
      (invalid-transition! job event)
      (case (:state job)
        :queued
        (assoc job
               :state :cancelled
               :cancellation-requested-at now
               :updated-at now
               :metadata-expires-at
               (.plusSeconds ^Instant now retention-seconds))
        :running
        (assoc job
               :state :cancellation-requested
               :cancellation-requested-at now
               :updated-at now)
        :cancellation-requested job
        :cancelled job
        (invalid-transition! job event)))

    :complete
    (cond
      (and (= :running (:state job))
           (= :succeeded outcome)
           (opaque-id? asset-id)
           (and (string? object-key) (not-empty object-key))
           (pos-int? asset-generation)
           (pos-int? asset-size)
           (= "video/mp4" asset-content-type)
           (= (:profile-version job) asset-profile-version)
           (instance? Instant now))
      (assoc job
             :state :succeeded
             :asset-id asset-id
             :object-key object-key
             :asset-generation asset-generation
             :asset-size asset-size
             :asset-content-type asset-content-type
             :asset-profile-version asset-profile-version
             :completed-at now
             :updated-at now
             :metadata-expires-at
             (.plusSeconds ^Instant now retention-seconds)
             :asset-expires-at (.plusSeconds ^Instant now asset-ttl-seconds))

      (and (= :running (:state job))
           (= :failed outcome)
           (instance? Instant now)
           (boolean? retryable))
      (assoc job
             :state :failed
             :failure-code
             (if (contains? contract/public-error-codes-v1 failure-code)
               failure-code
               "derivative_failed")
             :retryable retryable
             :updated-at now
             :metadata-expires-at
             (.plusSeconds ^Instant now retention-seconds))

      (and (= :cancellation-requested (:state job))
           (= :cancelled outcome)
           (instance? Instant now))
      (assoc job
             :state (if (= :expired (:terminal-cause job))
                      :expired
                      :cancelled)
             :updated-at now
             :metadata-expires-at
             (.plusSeconds ^Instant now retention-seconds))

      :else
      (invalid-transition! job event))

    :retry
    (if (and (instance? Instant now)
             (or (= :cancelled (:state job))
                 (and (= :failed (:state job)) (true? (:retryable job)))))
      (-> job
          (assoc :state :queued
                 :attempt (inc (:attempt job))
                 :updated-at now
                 :metadata-expires-at
                 (.plusSeconds ^Instant now retention-seconds))
          (dissoc :failure-code :retryable :asset-id :asset-expires-at
                  :object-key :asset-generation :asset-size
                  :asset-content-type :asset-profile-version :completed-at
                  :cancellation-requested-at :terminal-cause))
      (invalid-transition! job event))

    :expire
    (let [deadline (if (= :succeeded (:state job))
                     (:asset-expires-at job)
                     (:metadata-expires-at job))]
      (if-not (and (instance? Instant now) (at-or-after? now deadline))
        (invalid-transition! job event)
        (case (:state job)
          :running
          (assoc job
                 :state :cancellation-requested
                 :terminal-cause :expired
                 :cancellation-requested-at now
                 :updated-at now)

          :cancellation-requested
          (cond-> job
            (nil? (:terminal-cause job))
            (assoc :terminal-cause :expired :updated-at now)
            (nil? (:cancellation-requested-at job))
            (assoc :cancellation-requested-at now))

          (:queued :succeeded :failed :cancelled)
          (-> job
              without-terminal-data
              (assoc :state :expired :updated-at now))

          (:expired :revoked) job
          (invalid-transition! job event))))

    :membership-revoked
    (if-not (instance? Instant now)
      (invalid-transition! job event)
      (case (:state job)
        :queued
        (membership-failure job :cancelled now)

        :running
        (assoc job
               :state :cancellation-requested
               :terminal-cause :membership-revoked
               :cancellation-requested-at now
               :updated-at now
               :failure-code "membership_revoked"
               :retryable false)

        :cancellation-requested
        (assoc job
               :terminal-cause :membership-revoked
               :updated-at now
               :failure-code "membership_revoked"
               :retryable false)

        :succeeded
        (membership-failure job :revoked now)

        (:failed :cancelled :expired :revoked) job
        (invalid-transition! job event)))

    (invalid-transition! job event)))

(defn public-resource
  "Projects a preparation job onto its bounded browser-visible contract."
  [job]
  (let [id (:id job)]
    (cond-> {:id id
             :state (name (:state job))
             :attempt (:attempt job)
             :profileVersion (:profile-version job)
             :statusUrl (str "/v1/derivative-preparations/" id)
             :cancelUrl (str "/v1/derivative-preparations/" id "/cancel")
             :retryUrl (str "/v1/derivative-preparations/" id "/retry")}
      (:request-id job) (assoc :requestId (:request-id job))
      (:asset-id job) (assoc :assetId (:asset-id job))
      (:asset-expires-at job) (assoc :expiresAt (str (:asset-expires-at job)))
      (:failure-code job) (assoc :failureCode (:failure-code job))
      (contains? job :retryable)
      (assoc :retryable (boolean (:retryable job))))))

(defn with-terminal-transition
  "Marks an internal service result as the process that won a terminal transition."
  [resource]
  (vary-meta resource assoc ::terminal-transition true))

(defn terminal-transition?
  "Returns true only for the service call that durably made a job terminal."
  [resource]
  (true? (::terminal-transition (meta resource))))

(defprotocol PreparationService
  (submit-preparation! [service idempotency-key request])
  (get-preparation [service job-id])
  (dispatch-preparation! [service job-id] [service job-id attempt])
  (cancel-preparation! [service job-id])
  (retry-preparation! [service job-id])
  (reconcile-preparations! [service]))

(defprotocol PreparationAttemptService
  (load-preparation-attempt [service job-id attempt])
  (complete-preparation-attempt! [service job-id attempt result])
  (fail-preparation-attempt! [service job-id attempt failure])
  (acknowledge-preparation-cancellation! [service job-id attempt])
  (preparation-cancellation-requested? [service job-id attempt]))

(defprotocol PreparationAccess
  (owns-preparation? [service job-id subject]))

(defprotocol PreparationPlaybackAccess
  (preparation-playback-asset [service job-id identity]))

(defprotocol PreparationCache
  (put-preparation-cache! [service request asset]))

(defprotocol PreparationQueue
  (enqueue-preparation! [queue job-id attempt])
  (delete-preparation-task! [queue job-id attempt]))

(defprotocol PreparationLauncher
  (launch-preparation! [launcher job-id attempt])
  (cancel-preparation-execution! [launcher execution])
  (preparation-execution-state [launcher execution]))

(defprotocol RecoverablePreparationLauncher
  (find-active-preparation-execution [launcher job-id attempt]))

(def ^:private active-states
  #{:queued :running :cancellation-requested})

(def ^:private terminal-states
  #{:succeeded :failed :cancelled :expired :revoked})

(def ^:private reservation-minor-units
  (get-in contract/contract-v1
          [:limits :cost :attempt-reservation-minor-units]))

(def ^:private minimum-cache-seconds
  (get-in contract/contract-v1
          [:limits :ttl :cache-minimum-remaining-seconds]))

(def ^:private max-project-nonterminal
  (get-in contract/contract-v1
          [:limits :concurrency :max-project-nonterminal-jobs]))

(def ^:private max-user-nonterminal
  (get-in contract/contract-v1
          [:limits :concurrency :max-user-nonterminal-jobs]))

(def ^:private max-user-daily-attempts
  (get-in contract/contract-v1
          [:limits :cost :max-user-attempts-per-utc-day]))

(def ^:private max-user-monthly
  (get-in contract/contract-v1
          [:limits :cost :max-user-monthly-minor-units]))

(def ^:private max-derivative-monthly
  (get-in contract/contract-v1
          [:limits :cost :max-derivative-monthly-minor-units]))

(def ^:private max-project-monthly
  (get-in contract/contract-v1
          [:limits :cost :max-project-monthly-minor-units]))

(defn- bounded-idempotency-key! [value]
  (when-not (and (string? value) (<= 1 (count value) 128))
    (throw
     (errors/raise! "A bounded Idempotency-Key header is required"
                    {:type ::invalid-idempotency-key})))
  value)

(defn- sha256 [value]
  (.formatHex
   (HexFormat/of)
   (.digest (MessageDigest/getInstance "SHA-256")
            (.getBytes (pr-str value) "UTF-8"))))

(defn- normalized-request [request]
  (assoc request :profile-version
         (or (:profile-version request) profile-version)))

(defn- fingerprint [secret request]
  (keys/cache-fingerprint
   secret
   environment
   {:owner-subject (:subject request)
    :drive-file-id (:file-id request)
    :drive-version (:drive-version request)
    :source-bytes (:source-bytes request)
    :profile-version (:profile-version request)
    :job-id (:job-id request)}))

(defn- request-digest [secret request]
  (sha256 [(fingerprint secret request)
           (:source-duration-seconds request)]))

(defn- utc-day [^Clock clock]
  (str (LocalDate/now (.withZone clock ZoneOffset/UTC))))

(defn- utc-month [^Clock clock]
  (str (YearMonth/now (.withZone clock ZoneOffset/UTC))))

(defn- member-identity [request]
  {:subject (:subject request)
   :email (:email request)
   :membership-version (:membership-version request)})

(defn- with-active-member [directory request action]
  (if directory
    (try
      (admin/with-active-member! directory (member-identity request) action)
      (catch clojure.lang.ExceptionInfo error
        (if (= ::admin/not-allowlisted (:type (ex-data error)))
          (throw
           (errors/raise! "Member is no longer allowlisted"
                          {:type ::member-not-allowlisted}
                          error))
          (throw error))))
    (action)))

(defn- preparation-resource [job]
  (let [resource (public-resource job)
        id (:id resource)]
    (assoc resource
           :statusUrl (str "/v1/derivative-preparations/" id)
           :cancelUrl (str "/v1/derivative-preparations/" id "/cancel")
           :retryUrl (str "/v1/derivative-preparations/" id "/retry"))))

(defn- active-jobs [jobs]
  (filter #(contains? active-states (:state %)) (vals jobs)))

(defn- admission-error! [type message]
  (throw (errors/raise! message {:type type})))

(defn- invalid-derivative-attempt! []
  (throw
   (errors/raise! "Derivative preparation attempt is invalid"
                  {:type ::invalid-derivative-attempt
                   :failure-code "invalid_derivative_attempt"})))

(defn- exact-attempt-job [job attempt]
  (when-not (and job
                 (pos-int? attempt)
                 (= (long attempt) (long (:attempt job))))
    (invalid-derivative-attempt!))
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

(defn- attempt-resource [secret job]
  (let [fingerprint
        (:fingerprint
         (fingerprint
          secret
          {:subject (:owner-subject job)
           :file-id (:file-id job)
           :drive-version (:drive-version job)
           :source-bytes (:source-bytes job)
           :profile-version (:profile-version job)
           :job-id (:id job)}))]
    (cond->
     {:job-id (:id job)
      :environment "production"
      :attempt (:attempt job)
      :profile render-derivative/profile-v1
      :asset {:id (:id job)
              :object-key (keys/object-key environment fingerprint)}
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

(defn- admit-attempt [state request clock]
  (let [subject (:subject request)
        day (utc-day clock)
        month (utc-month clock)
        jobs (active-jobs (:jobs state))
        user-active (count (filter #(= subject (:owner-subject %)) jobs))
        project-active (count jobs)
        daily (get-in state [:admission :user-day [subject day]] 0)
        user-monthly
        (get-in state [:admission :user-month [subject month]] 0)
        derivative-monthly
        (get-in state [:admission :derivative-month month] 0)
        project-monthly
        (get-in state [:admission :project-month month] 0)]
    (when (>= user-active max-user-nonterminal)
      (admission-error! ::user-job-active
                        "A derivative preparation is already active"))
    (when (>= project-active max-project-nonterminal)
      (admission-error! ::project-backlog-exhausted
                        "The derivative preparation backlog is full"))
    (when (>= daily max-user-daily-attempts)
      (admission-error! ::daily-attempt-limit-exhausted
                        "The daily derivative attempt limit is exhausted"))
    (when (> (+ user-monthly reservation-minor-units) max-user-monthly)
      (admission-error! ::user-budget-exhausted
                        "The member derivative budget is exhausted"))
    (when (> (+ derivative-monthly reservation-minor-units)
             max-derivative-monthly)
      (admission-error! ::derivative-budget-exhausted
                        "The derivative project pool is exhausted"))
    (when (> (+ project-monthly reservation-minor-units)
             max-project-monthly)
      (admission-error! ::project-budget-exhausted
                        "The shared project budget is exhausted"))
    (-> state
        (assoc-in [:admission :user-day [subject day]] (inc daily))
        (assoc-in [:admission :user-month [subject month]]
                  (+ user-monthly reservation-minor-units))
        (assoc-in [:admission :derivative-month month]
                  (+ derivative-monthly reservation-minor-units))
        (assoc-in [:admission :project-month month]
                  (+ project-monthly reservation-minor-units)))))

(defn- eligible-cache-job [state secret request now]
  (let [key (:fingerprint (fingerprint secret request))
        job (get-in state [:cache key :job])]
    (when (and (= :succeeded (:state job))
               (= (:subject request) (:owner-subject job))
               (= (:drive-version request) (:drive-version job))
               (= (:profile-version request) (:profile-version job))
               (:asset-expires-at job)
               (not (.isBefore ^Instant (:asset-expires-at job)
                               (.plusSeconds ^Instant now
                                             minimum-cache-seconds))))
      job)))

(defn- internal-job [request id now]
  (assoc (transition nil {:type :submit :id id :now now})
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
         :reservation-minor-units reservation-minor-units))

(defn- try-delete-task! [queue job]
  (when queue
    (try
      (delete-preparation-task! queue (:id job) (:attempt job))
      (catch Throwable _ nil))))

(defn- try-cancel-execution! [launcher job]
  (when (and launcher (:execution job))
    (try
      (cancel-preparation-execution! launcher (:execution job))
      (catch Throwable _ nil))))

(defrecord InMemoryPreparationService
           [state queue launcher ^Clock clock fingerprint-secret member-directory]
  PreparationAccess
  (owns-preparation? [_ job-id subject]
    (let [job (get-in @state [:jobs job-id])
          now (Instant/now clock)]
      (and (= subject (:owner-subject job))
           (not (contains? #{:expired :revoked} (:state job)))
           (or (nil? (:metadata-expires-at job))
               (.isBefore now (:metadata-expires-at job))))))
  PreparationPlaybackAccess
  (preparation-playback-asset [_ job-id identity]
    (with-active-member
      member-directory identity
      (fn []
        (let [job (get-in @state [:jobs job-id])
              now (Instant/now clock)]
          (when (and (= :succeeded (:state job))
                     (= "production" (:environment job))
                     (= (:subject identity) (:owner-subject job))
                     (= (:membership-version identity)
                        (:membership-version job))
                     (instance? Instant (:completed-at job))
                     (instance? Instant (:asset-expires-at job))
                     (.isBefore now ^Instant (:asset-expires-at job))
                     (pos-int? (:asset-generation job))
                     (pos-int? (:asset-size job))
                     (= "video/mp4" (:asset-content-type job))
                     (= (:profile-version job)
                        (:asset-profile-version job))
                     (string? (:object-key job))
                     (not-empty (:object-key job)))
            (cond-> {:asset-id (:asset-id job)
                     :environment (:environment job)
                     :object-key (:object-key job)
                     :generation (:asset-generation job)
                     :size (:asset-size job)
                     :content-type (:asset-content-type job)
                     :profile-version (:asset-profile-version job)
                     :completed-at (:completed-at job)
                     :expires-at (:asset-expires-at job)}
              (:request-id job) (assoc :request-id (:request-id job))
              (:trace job) (assoc :trace (:trace job))
              (:revision job) (assoc :revision (:revision job))))))))
  PreparationCache
  (put-preparation-cache! [_ request asset]
    (let [request (normalized-request request)
          id (or (:job-id asset) (str (UUID/randomUUID)))
          job (assoc (internal-job request id (Instant/now clock))
                     :state :succeeded
                     :asset-id (:asset-id asset)
                     :asset-expires-at (:expires-at asset)
                     :updated-at (Instant/now clock))
          cache-key (:fingerprint (fingerprint fingerprint-secret request))]
      (swap! state assoc-in [:cache cache-key] {:job job})
      {:job (preparation-resource job)}))
  PreparationService
  (submit-preparation! [_ idempotency-key raw-request]
    (bounded-idempotency-key! idempotency-key)
    (let [request (normalized-request raw-request)
          digest (request-digest fingerprint-secret request)
          result
          (with-active-member
            member-directory request
            (fn []
              (locking state
                (if-let [{:keys [job-id request-digest]}
                         (get-in @state [:idempotency idempotency-key])]
                  (do
                    (when-not (= digest request-digest)
                      (throw
                       (errors/raise!
                        "Idempotency key belongs to another preparation"
                        {:type ::idempotency-conflict})))
                    {:created? false
                     :job (get-in @state [:jobs job-id])})
                  (let [now (Instant/now clock)]
                    (if-let [cached
                             (eligible-cache-job @state fingerprint-secret
                                                 request now)]
                      (let [job-id (str (UUID/randomUUID))
                            cached-job
                            (assoc cached
                                   :id job-id
                                   :created-at now
                                   :updated-at now
                                   :request-id (:request-id request)
                                   :trace (:trace request)
                                   :revision (:revision request)
                                   :metadata-expires-at
                                   (.plusSeconds now retention-seconds))]
                        (swap! state
                               (fn [current]
                                 (-> current
                                     (assoc-in [:jobs job-id] cached-job)
                                     (assoc-in [:idempotency idempotency-key]
                                               {:job-id job-id
                                                :request-digest digest}))))
                        {:created? false :cache-hit? true :job cached-job})
                      (let [job-id (str (UUID/randomUUID))
                            job (internal-job request job-id now)]
                        (swap! state
                               (fn [current]
                                 (-> (admit-attempt current request clock)
                                     (assoc-in [:jobs job-id] job)
                                     (assoc-in [:idempotency idempotency-key]
                                               {:job-id job-id
                                                :request-digest digest}))))
                        {:created? true :job job})))))))]
      (when (:created? result)
        (enqueue-preparation! queue (get-in result [:job :id])
                              (get-in result [:job :attempt])))
      (update result :job preparation-resource)))
  (get-preparation [_ job-id]
    (some-> (get-in @state [:jobs job-id]) preparation-resource))
  (dispatch-preparation! [this job-id]
    (let [attempt (get-in @state [:jobs job-id :attempt])]
      (dispatch-preparation! this job-id attempt)))
  (dispatch-preparation! [_ job-id attempt]
    (let [admitted
          (locking state
            (let [job (get-in @state [:jobs job-id])]
              (when-not job
                (admission-error! ::preparation-not-found
                                  "Derivative preparation does not exist"))
              (if (or (not (pos-int? attempt))
                      (not= (long attempt) (long (:attempt job)))
                      (not= :queued (:state job)))
                {:started? false :job job}
                (let [now (Instant/now clock)
                      execution
                      (launch-preparation! launcher job-id (:attempt job))
                      running
                      (assoc (transition
                              job {:type :dispatch :now now})
                             :execution execution)]
                  (swap! state assoc-in [:jobs job-id] running)
                  {:started? true
                   :queueAgeMs
                   (max 0
                        (.toMillis
                         (Duration/between
                          ^Instant (:created-at job) now)))
                   :job running}))))]
      (update admitted :job preparation-resource)))
  (cancel-preparation! [_ job-id]
    (let [{:keys [before after]}
          (locking state
            (let [job (get-in @state [:jobs job-id])]
              (when-not job
                (admission-error! ::preparation-not-found
                                  "Derivative preparation does not exist"))
              (let [updated (transition
                             job {:type :cancel :now (Instant/now clock)})]
                (swap! state assoc-in [:jobs job-id] updated)
                {:before job :after updated})))]
      (when (= :queued (:state before))
        (try-delete-task! queue before))
      (when (contains? #{:running :cancellation-requested} (:state before))
        (try-cancel-execution! launcher before))
      (cond-> (preparation-resource after)
        (and (not (contains? terminal-states (:state before)))
             (contains? terminal-states (:state after)))
        with-terminal-transition)))
  (retry-preparation! [_ job-id]
    (let [raw-job (get-in @state [:jobs job-id])
          request
          {:subject (:owner-subject raw-job)
           :email (:owner-email raw-job)
           :membership-version (:membership-version raw-job)}
          retried
          (with-active-member
            member-directory request
            (fn []
              (locking state
                (let [job (get-in @state [:jobs job-id])]
                  (when-not job
                    (admission-error! ::preparation-not-found
                                      "Derivative preparation does not exist"))
                  (let [admitted (admit-attempt @state request clock)
                        updated
                        (assoc (transition
                                job {:type :retry :now (Instant/now clock)})
                               :reservation-minor-units
                               reservation-minor-units)]
                    (reset! state
                            (assoc-in admitted [:jobs job-id] updated))
                    updated)))))]
      (enqueue-preparation! queue job-id (:attempt retried))
      (preparation-resource retried)))
  (reconcile-preparations! [_]
    (let [now (Instant/now clock)
          repaired (atom 0)
          terminal-jobs (atom [])]
      (locking state
        (swap! state update :jobs
               (fn [jobs]
                 (into {}
                       (map
                        (fn [[job-id job]]
                          (if (and (:metadata-expires-at job)
                                   (not (.isBefore
                                         now (:metadata-expires-at job)))
                                   (not= :expired (:state job)))
                            (do
                              (swap! repaired inc)
                              (let [updated
                                    (transition
                                     job {:type :expire :now now})]
                                (when (and
                                       (not (contains?
                                             terminal-states (:state job)))
                                       (contains?
                                        terminal-states (:state updated)))
                                  (swap! terminal-jobs conj
                                         (preparation-resource updated)))
                                [job-id updated]))
                            [job-id job])))
                       jobs))))
      (cond-> {:repairedJobs @repaired}
        (seq @terminal-jobs) (assoc :terminalJobs @terminal-jobs))))
  PreparationAttemptService
  (load-preparation-attempt [_ job-id attempt]
    (let [job (exact-attempt-job (get-in @state [:jobs job-id]) attempt)]
      (when-not (= :running (:state job))
        (invalid-derivative-attempt!))
      (attempt-resource fingerprint-secret job)))
  (complete-preparation-attempt! [_ job-id attempt result]
    (contract/validate-work! (:measurements result))
    (when-not (= (:size result)
                 (get-in result [:measurements :output-bytes]))
      (invalid-derivative-attempt!))
    (let [completed
          (locking state
            (let [job (exact-attempt-job (get-in @state [:jobs job-id])
                                         attempt)
                  updated
                  (if (= :succeeded (:state job))
                    (if (exact-completion? job result)
                      job
                      (invalid-derivative-attempt!))
                    (transition job
                                {:type :complete
                                 :outcome :succeeded
                                 :asset-id (:asset-id result)
                                 :object-key (:object-key result)
                                 :asset-generation (:generation result)
                                 :asset-size (:size result)
                                 :asset-content-type (:content-type result)
                                 :asset-profile-version
                                 (:profile-version result)
                                 :now (Instant/now clock)}))
                  cache-key
                  (:fingerprint
                   (fingerprint fingerprint-secret
                                {:subject (:owner-subject job)
                                 :file-id (:file-id job)
                                 :drive-version (:drive-version job)
                                 :source-bytes (:source-bytes job)
                                 :profile-version (:profile-version job)
                                 :job-id job-id}))]
              (swap! state
                     (fn [current]
                       (-> current
                           (assoc-in [:jobs job-id] updated)
                           (assoc-in [:cache cache-key] {:job updated}))))
              updated))]
      (preparation-resource completed)))
  (fail-preparation-attempt! [_ job-id attempt failure]
    (let [failed
          (locking state
            (let [job (exact-attempt-job (get-in @state [:jobs job-id])
                                         attempt)
                  updated
                  (transition job
                              {:type :complete
                               :outcome :failed
                               :failure-code (:failure-code failure)
                               :retryable (boolean (:retryable failure))
                               :now (Instant/now clock)})]
              (swap! state assoc-in [:jobs job-id] updated)
              updated))]
      (preparation-resource failed)))
  (acknowledge-preparation-cancellation! [_ job-id attempt]
    (let [{:keys [before after]}
          (locking state
            (let [job (exact-attempt-job (get-in @state [:jobs job-id])
                                         attempt)]
              (cond
                (contains? #{:cancelled :expired} (:state job))
                {:before job :after job}

                (= :cancellation-requested (:state job))
                (let [updated
                      (transition job {:type :complete
                                       :outcome :cancelled
                                       :now (Instant/now clock)})]
                  (swap! state assoc-in [:jobs job-id] updated)
                  {:before job :after updated})
                :else (invalid-derivative-attempt!))))]
      (cond-> (preparation-resource after)
        (and (not (contains? terminal-states (:state before)))
             (contains? terminal-states (:state after)))
        with-terminal-transition)))
  (preparation-cancellation-requested? [_ job-id attempt]
    (let [job (exact-attempt-job (get-in @state [:jobs job-id]) attempt)]
      (cond
        (contains? #{:running :succeeded} (:state job)) false
        (contains? #{:cancellation-requested :cancelled :expired :revoked}
                   (:state job)) true
        :else (invalid-derivative-attempt!))))
  admin/JobAdministration
  (cancel-member-jobs! [_ cleanup-identity]
    (let [affected (atom [])]
      (locking state
        (swap! state update :jobs
               (fn [jobs]
                 (into {}
                       (map
                        (fn [[job-id job]]
                          (if (and (= (:subject cleanup-identity)
                                      (:owner-subject job))
                                   (admin/cleanup-generation?
                                    cleanup-identity
                                    (:membership-version job))
                                   (contains? (conj active-states :succeeded)
                                              (:state job)))
                            (let [updated
                                  (transition
                                   job {:type :membership-revoked
                                        :now (Instant/now clock)})]
                              (swap! affected conj
                                     {:job job
                                      :cancel-execution?
                                      (= :running (:state job))})
                              [job-id updated])
                            [job-id job])))
                       jobs)))
        (swap! state update :cache
               (fn [cache]
                 (into {}
                       (remove
                        (fn [[_ {:keys [job]}]]
                          (and (= (:subject cleanup-identity)
                                  (:owner-subject job))
                               (admin/cleanup-generation?
                                cleanup-identity
                                (:membership-version job)))))
                       cache))))
      (doseq [{:keys [job cancel-execution?]} @affected]
        (if (= :queued (:state job))
          (try-delete-task! queue job)
          (when cancel-execution?
            (try-cancel-execution! launcher job))))
      (count @affected))))

(defn in-memory-preparation-system
  [{:keys [clock fingerprint-secret member-directory queue launcher]
    :or {clock (Clock/systemUTC)}}]
  (when-not (and (string? fingerprint-secret)
                 (not-empty fingerprint-secret))
    (throw
     (errors/raise! "Derivative fingerprint secret is required"
                    {:type ::invalid-configuration})))
  (let [state (atom {:jobs {} :idempotency {} :cache {}})
        queued (atom [])
        deleted-tasks (atom [])
        cancelled-executions (atom [])
        queue
        (or queue
            (reify PreparationQueue
              (enqueue-preparation! [_ job-id attempt]
                (swap! queued conj [job-id attempt]))
              (delete-preparation-task! [_ job-id attempt]
                (swap! deleted-tasks conj [job-id attempt]))))
        launcher
        (or launcher
            (reify PreparationLauncher
              (launch-preparation! [_ job-id attempt]
                (str "executions/" job-id "/attempts/" attempt))
              (cancel-preparation-execution! [_ execution]
                (swap! cancelled-executions conj execution))
              (preparation-execution-state [_ _] :running)))
        service
        (->InMemoryPreparationService state queue launcher clock
                                      fingerprint-secret member-directory)]
    {:service service
     :state state
     :queued queued
     :deleted-tasks deleted-tasks
     :cancelled-executions cancelled-executions}))
