(ns agg.derivative.lifecycle
  (:require [agg.derivative.contract :as contract]
            [agg.errors :as errors])
  (:import (java.time Instant)
           (java.util UUID)))

(def ^:private profile-version
  (get-in contract/contract-v1 [:profile :version]))

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
          :failure-code :retryable))

(defn- membership-failure [job state now]
  (-> job
      without-terminal-data
      (assoc :state state
             :updated-at now
             :failure-code "membership_revoked"
             :retryable false)))

(defn transition
  "Applies one pure derivative preparation lifecycle event."
  [job {:keys [type id now outcome asset-id object-key failure-code retryable]
        :as event}]
  (case type
    :submit
    (if (and (nil? job) (opaque-id? id) (instance? Instant now))
      {:id id
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
        :queued (assoc job :state :cancelled :updated-at now)
        :running (assoc job :state :cancellation-requested :updated-at now)
        :cancellation-requested job
        :cancelled job
        (invalid-transition! job event)))

    :complete
    (cond
      (and (= :running (:state job))
           (= :succeeded outcome)
           (opaque-id? asset-id)
           (string? object-key)
           (instance? Instant now))
      (assoc job
             :state :succeeded
             :asset-id asset-id
             :object-key object-key
             :updated-at now
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
             :updated-at now)

      (and (= :cancellation-requested (:state job))
           (= :cancelled outcome)
           (instance? Instant now))
      (assoc job :state :cancelled :updated-at now)

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
                  :object-key))
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
                 :updated-at now)

          :cancellation-requested
          (cond-> job
            (nil? (:terminal-cause job))
            (assoc :terminal-cause :expired :updated-at now))

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
  (cond-> {:id (:id job)
           :state (name (:state job))
           :attempt (:attempt job)
           :profileVersion (:profile-version job)}
    (:asset-id job) (assoc :assetId (:asset-id job))
    (:asset-expires-at job) (assoc :expiresAt (str (:asset-expires-at job)))
    (:failure-code job) (assoc :failureCode (:failure-code job))
    (contains? job :retryable) (assoc :retryable (boolean (:retryable job)))))
