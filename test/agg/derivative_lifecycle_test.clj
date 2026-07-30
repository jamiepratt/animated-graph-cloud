(ns agg.derivative-lifecycle-test
  (:require [agg.derivative.lifecycle :as lifecycle]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant)))

(def job-id "00000000-0000-0000-0000-000000000191")
(def asset-id "00000000-0000-0000-0000-000000000192")
(def submitted-at (Instant/parse "2026-07-30T10:00:00Z"))
(def completed-at (Instant/parse "2026-07-30T10:05:00Z"))

(defn successful-completion []
  {:type :complete
   :outcome :succeeded
   :asset-id asset-id
   :object-key "private/object/key.mp4"
   :asset-generation 42
   :asset-size 1024
   :asset-content-type "video/mp4"
   :asset-profile-version "h264-aac-1080p25-v1"
   :now completed-at})

(defn submitted-job []
  (lifecycle/transition
   nil {:type :submit :id job-id :now submitted-at}))

(defn running-job []
  (lifecycle/transition
   (submitted-job)
   {:type :dispatch :now (Instant/parse "2026-07-30T10:00:01Z")}))

(defn succeeded-job []
  (lifecycle/transition
   (running-job)
   (successful-completion)))

(deftest preparation-submit-dispatch-and-completion-produce-a-bounded-resource
  (let [submitted
        (lifecycle/transition
         nil {:type :submit :id job-id :now submitted-at})
        running
        (lifecycle/transition
         submitted {:type :dispatch
                    :now (Instant/parse "2026-07-30T10:00:01Z")})
        succeeded
        (lifecycle/transition
         running (successful-completion))
        public (lifecycle/public-resource
                (assoc succeeded
                       :owner-subject "private-owner"
                       :drive-file-id "private-drive-id"
                       :file-name "private.mov"
                       :oauth-token "private-token"
                       :signed-authority "private-signature"))]
    (is (= {:state :queued :attempt 1}
           (select-keys submitted [:state :attempt])))
    (is (= :running (:state running)))
    (is (= :succeeded (:state succeeded)))
    (is (= {:asset-generation 42
            :asset-size 1024
            :asset-content-type "video/mp4"
            :asset-profile-version "h264-aac-1080p25-v1"
            :completed-at completed-at}
           (select-keys succeeded
                        [:asset-generation :asset-size :asset-content-type
                         :asset-profile-version :completed-at])))
    (is (= {:id job-id
            :state "succeeded"
            :attempt 1
            :profileVersion "h264-aac-1080p25-v1"
            :assetId asset-id
            :expiresAt "2026-07-31T10:05:00Z"}
           (select-keys
            public
            [:id :state :attempt :profileVersion :assetId :expiresAt])))
    (is (not-any? #(str/includes? (pr-str public) %)
                  ["private-owner" "private-drive-id" "private.mov"
                   "private-token" "private-signature"
                   "private/object/key.mp4"]))))

(deftest cancellation-is-immediate-before-dispatch-and-acknowledged-after-dispatch
  (let [queued (submitted-job)
        cancelled
        (lifecycle/transition
         queued {:type :cancel
                 :now (Instant/parse "2026-07-30T10:00:01Z")})
        running (running-job)
        requested
        (lifecycle/transition
         running {:type :cancel
                  :now (Instant/parse "2026-07-30T10:00:02Z")})
        acknowledged
        (lifecycle/transition
         requested {:type :complete
                    :outcome :cancelled
                    :now (Instant/parse "2026-07-30T10:00:03Z")})]
    (is (= {:state "cancelled"
            :cancellationLagMs 0}
           (select-keys
            (lifecycle/public-resource cancelled)
            [:state :cancellationLagMs])))
    (is (= cancelled
           (lifecycle/transition
            cancelled {:type :cancel
                       :now (Instant/parse "2026-07-30T10:00:04Z")})))
    (is (= :cancellation-requested (:state requested)))
    (is (= requested
           (lifecycle/transition
            requested {:type :cancel
                       :now (Instant/parse "2026-07-30T10:00:04Z")})))
    (is (= {:state "cancelled"
            :cancellationLagMs 1000}
           (select-keys
            (lifecycle/public-resource acknowledged)
            [:state :cancellationLagMs])))))

(deftest successful-publication-cannot-win-after-cancellation-was-requested
  (let [requested
        (lifecycle/transition
         (running-job)
         {:type :cancel :now (Instant/parse "2026-07-30T10:00:02Z")})
        attempt
        (fn []
          (try
            (lifecycle/transition
             requested {:type :complete
                        :outcome :succeeded
                        :asset-id asset-id
                        :object-key "private/object/key.mp4"
                        :asset-generation 42
                        :asset-size 1024
                        :asset-content-type "video/mp4"
                        :asset-profile-version "h264-aac-1080p25-v1"
                        :now (Instant/parse "2026-07-30T10:05:00Z")})
            nil
            (catch clojure.lang.ExceptionInfo error
              (select-keys (ex-data error)
                           [:type :failure-code :state :reason]))))]
    (is (= (attempt) (attempt)))
    (is (= {:type ::lifecycle/invalid-transition
            :failure-code "invalid_derivative_transition"
            :state :cancellation-requested
            :reason "complete_from_cancellation-requested"}
           (attempt)))))

(deftest failed-and-cancelled-work-retries-as-a-new-bounded-attempt
  (let [failed
        (lifecycle/transition
         (running-job)
         {:type :complete
          :outcome :failed
          :failure-code "derivative_encode_failed"
          :retryable true
          :now (Instant/parse "2026-07-30T10:05:00Z")})
        retried
        (lifecycle/transition
         failed {:type :retry
                 :now (Instant/parse "2026-07-30T10:06:00Z")})
        cancelled
        (lifecycle/transition
         (submitted-job)
         {:type :cancel :now (Instant/parse "2026-07-30T10:01:00Z")})
        retried-cancel
        (lifecycle/transition
         cancelled {:type :retry
                    :now (Instant/parse "2026-07-30T10:02:00Z")})]
    (is (= {:id job-id
            :state "failed"
            :attempt 1
            :profileVersion "h264-aac-1080p25-v1"
            :failureCode "derivative_encode_failed"
            :retryable true}
           (select-keys
            (lifecycle/public-resource failed)
            [:id :state :attempt :profileVersion :failureCode :retryable])))
    (is (= {:state :queued :attempt 2}
           (select-keys retried [:state :attempt])))
    (is (empty? (select-keys retried
                             [:failure-code :retryable :asset-id
                              :asset-expires-at :object-key])))
    (is (= {:state :queued :attempt 2}
           (select-keys retried-cancel [:state :attempt])))))

(deftest nonretryable-and-unknown-failures-stay-bounded
  (let [failed
        (lifecycle/transition
         (running-job)
         {:type :complete
          :outcome :failed
          :failure-code "private-file-id-secret"
          :retryable false
          :now (Instant/parse "2026-07-30T10:05:00Z")})
        retry-error
        (try
          (lifecycle/transition
           failed {:type :retry
                   :now (Instant/parse "2026-07-30T10:06:00Z")})
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= "derivative_failed"
           (:failureCode (lifecycle/public-resource failed))))
    (is (= ::lifecycle/invalid-transition
           (:type (ex-data retry-error))))
    (is (not (str/includes? (pr-str (lifecycle/public-resource failed))
                            "private-file-id-secret")))))

(deftest expiry-fails-closed-at-the-exact-deadline
  (let [early-error
        (try
          (lifecycle/transition
           (submitted-job)
           {:type :expire :now (Instant/parse "2026-07-31T09:59:59Z")})
          nil
          (catch clojure.lang.ExceptionInfo error error))
        queued-expired
        (lifecycle/transition
         (submitted-job)
         {:type :expire :now (Instant/parse "2026-07-31T10:00:00Z")})
        running-expired
        (lifecycle/transition
         (running-job)
         {:type :expire :now (Instant/parse "2026-07-31T10:00:00Z")})
        running-expiry-acknowledged
        (lifecycle/transition
         running-expired
         {:type :complete
          :outcome :cancelled
          :now (Instant/parse "2026-07-31T10:00:02Z")})
        asset-expired
        (lifecycle/transition
         (succeeded-job)
         {:type :expire :now (Instant/parse "2026-07-31T10:05:00Z")})]
    (is (= ::lifecycle/invalid-transition
           (:type (ex-data early-error))))
    (is (= :expired (:state queued-expired)))
    (is (= {:state :cancellation-requested
            :terminal-cause :expired
            :cancellation-requested-at
            (Instant/parse "2026-07-31T10:00:00Z")}
           (select-keys
            running-expired
            [:state :terminal-cause :cancellation-requested-at])))
    (is (= {:state "expired"
            :cancellationLagMs 2000}
           (select-keys
            (lifecycle/public-resource running-expiry-acknowledged)
            [:state :cancellationLagMs])))
    (is (= {:id job-id
            :state "expired"
            :attempt 1
            :profileVersion "h264-aac-1080p25-v1"}
           (select-keys
            (lifecycle/public-resource asset-expired)
            [:id :state :attempt :profileVersion])))
    (is (empty? (select-keys asset-expired
                             [:asset-id :asset-expires-at :object-key])))))

(deftest membership-revocation-cancels-work-and-invalidates-completed-assets
  (let [now (Instant/parse "2026-07-30T10:06:00Z")
        queued (lifecycle/transition
                (submitted-job) {:type :membership-revoked :now now})
        running (lifecycle/transition
                 (running-job) {:type :membership-revoked :now now})
        succeeded (lifecycle/transition
                   (succeeded-job) {:type :membership-revoked :now now})]
    (is (= {:state :cancelled
            :failure-code "membership_revoked"
            :retryable false}
           (select-keys queued [:state :failure-code :retryable])))
    (is (= {:state :cancellation-requested
            :terminal-cause :membership-revoked
            :failure-code "membership_revoked"
            :retryable false}
           (select-keys running
                        [:state :terminal-cause :failure-code :retryable])))
    (is (= {:id job-id
            :state "revoked"
            :attempt 1
            :profileVersion "h264-aac-1080p25-v1"
            :failureCode "membership_revoked"
            :retryable false}
           (select-keys
            (lifecycle/public-resource succeeded)
            [:id :state :attempt :profileVersion :failureCode :retryable])))
    (is (empty? (select-keys succeeded
                             [:asset-id :asset-expires-at :object-key])))))
