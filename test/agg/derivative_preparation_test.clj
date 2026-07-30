(ns agg.derivative-preparation-test
  (:require [agg.admin.core :as admin]
            [agg.derivative.lifecycle :as derivative]
            [agg.render.derivative :as render-derivative]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.time Clock Instant ZoneOffset)))

(def now (Instant/parse "2026-07-30T10:00:00Z"))

(def owner
  {:subject "private-owner"
   :email "owner@example.com"
   :membership-version "membership-v1"})

(def source
  {:file-id "private-drive-id"
   :drive-version "17"
   :source-bytes 4096
   :source-duration-seconds 120.0})

(defn preparation-request
  ([] (preparation-request {}))
  ([overrides]
   (merge owner source overrides)))

(deftest identical-preparations-admit-one-owner-bound-attempt
  (let [{:keys [service state queued]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        first-result
        (derivative/submit-preparation! service "same-key"
                                        (preparation-request))
        duplicate
        (derivative/submit-preparation! service "same-key"
                                        (preparation-request))]
    (is (:created? first-result))
    (is (false? (:created? duplicate)))
    (is (= (:job first-result) (:job duplicate)))
    (is (= 1 (count @queued)))
    (is (= 125 (get-in @state [:admission :project-month "2026-07"])))
    (is (= 1 (get-in @state
                     [:admission :user-day
                      ["private-owner" "2026-07-30"]])))
    (is (derivative/owns-preparation?
         service (get-in first-result [:job :id]) "private-owner"))
    (is (not (derivative/owns-preparation?
              service (get-in first-result [:job :id]) "other-owner")))
    (is (not-any? #(str/includes? (pr-str (:job first-result)) %)
                  ["private-drive-id" "private-owner" "owner@example.com"
                   "membership-v1" "fixture-secret"]))))

(deftest exact-eligible-cache-hit-does-not-queue-or-reserve
  (let [{:keys [service state queued]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        cached (derivative/put-preparation-cache!
                service
                (preparation-request)
                {:asset-id "00000000-0000-0000-0000-000000000193"
                 :expires-at (.plusSeconds now (* 2 60 60))})
        result (derivative/submit-preparation!
                service "cache-hit" (preparation-request))]
    (is (= (select-keys (:job cached) [:state :assetId :expiresAt])
           (select-keys (:job result) [:state :assetId :expiresAt])))
    (is (:cache-hit? result))
    (is (false? (:created? result)))
    (is (empty? @queued))
    (is (empty? (:admission @state)))))

(deftest membership-cleanup-revokes-succeeded-jobs-and-private-cache
  (let [{:keys [service state queued]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        job-id
        (get-in
         (derivative/submit-preparation!
          service "completed-before-revocation" (preparation-request))
         [:job :id])
        _ (derivative/dispatch-preparation! service job-id)
        _ (derivative/complete-preparation-attempt!
           service job-id 1
           {:asset-id "00000000-0000-0000-0000-000000000193"
            :object-key "private-object"
            :measurements {:output-bytes 1024}})]
    (is (= 1
           (admin/cancel-member-jobs!
            service {:subject "private-owner"
                     :membership-version "membership-v1"})))
    (is (= "revoked" (:state (derivative/get-preparation service job-id))))
    (is (not (derivative/owns-preparation?
              service job-id "private-owner")))
    (is (empty? (:cache @state)))
    (is (:created?
         (derivative/submit-preparation!
          service "after-revocation" (preparation-request))))
    (is (= 2 (count @queued)))))

(deftest cancellation-retry-and-membership-cleanup-preserve-attempt-accounting
  (let [{:keys [service state queued cancelled-executions]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        job-id (get-in (derivative/submit-preparation!
                        service "cancel-retry" (preparation-request))
                       [:job :id])
        queued-cancel (derivative/cancel-preparation! service job-id)
        retry (derivative/retry-preparation! service job-id)
        _ (derivative/dispatch-preparation! service job-id)
        running-cancel (derivative/cancel-preparation! service job-id)
        cleanup-count
        (admin/cancel-member-jobs!
         service {:subject "private-owner"
                  :membership-version "membership-v1"})]
    (is (= "cancelled" (:state queued-cancel)))
    (is (= 2 (:attempt retry)))
    (is (= "cancellation-requested" (:state running-cancel)))
    (is (= 1 cleanup-count))
    (is (= [(str "executions/" job-id "/attempts/2")]
           @cancelled-executions))
    (is (= "cancellation-requested"
           (:state (derivative/get-preparation service job-id))))
    (is (= 2 (count @queued)))
    (is (= 250 (get-in @state [:admission :project-month "2026-07"])))))

(deftest admission-limits-are-owner-scoped-and-nonrefundable
  (let [{:keys [service]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})]
    (dotimes [attempt 5]
      (let [job-id
            (get-in (derivative/submit-preparation!
                     service (str "daily-" attempt)
                     (preparation-request
                      {:drive-version (str attempt)}))
                    [:job :id])]
        (derivative/cancel-preparation! service job-id)))
    (let [error (try
                  (derivative/submit-preparation!
                   service "daily-six"
                   (preparation-request {:drive-version "six"}))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= ::derivative/daily-attempt-limit-exhausted
             (:type (ex-data error)))))))

(deftest cache-reuse-rejects-near-expiry-owner-version-and-profile-mismatches
  (let [{:keys [service queued]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})]
    (doseq [[key candidate cached]
            [["near-expiry" (preparation-request)
              {:request (preparation-request)
               :expires-at (.plusSeconds now 3599)}]
             ["wrong-owner"
              (preparation-request)
              {:request (preparation-request {:subject "other-owner"})
               :expires-at (.plusSeconds now 7200)}]
             ["wrong-version"
              (preparation-request)
              {:request (preparation-request {:drive-version "old"})
               :expires-at (.plusSeconds now 7200)}]
             ["wrong-profile"
              (preparation-request)
              {:request (preparation-request
                         {:profile-version "old-profile"})
               :expires-at (.plusSeconds now 7200)}]]]
      (derivative/put-preparation-cache!
       service (:request cached)
       {:asset-id (str "00000000-0000-0000-0000-"
                       (format "%012d" (count @queued)))
        :expires-at (:expires-at cached)})
      (is (:created?
           (derivative/submit-preparation! service key candidate)))
      (derivative/cancel-preparation!
       service (get-in (derivative/submit-preparation! service key candidate)
                       [:job :id])))))

(deftest worker-seam-loads-and-cancels-only-the-exact-running-attempt
  (let [{:keys [service]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        job-id
        (get-in (derivative/submit-preparation!
                 service "worker-seam" (preparation-request))
                [:job :id])]
    (derivative/dispatch-preparation! service job-id 1)
    (is (= {:job-id job-id
            :attempt 1
            :profile render-derivative/profile-v1
            :source {:file-id "private-drive-id"
                     :drive-version "17"
                     :bytes 4096
                     :duration-seconds 120.0}
            :owner {:subject "private-owner"
                    :membership-version "membership-v1"}}
           (derivative/load-preparation-attempt service job-id 1)))
    (is (false?
         (derivative/preparation-cancellation-requested? service job-id 1)))
    (derivative/cancel-preparation! service job-id)
    (is (derivative/preparation-cancellation-requested? service job-id 1))
    (is (= "cancelled"
           (:state
            (derivative/acknowledge-preparation-cancellation!
             service job-id 1))))
    (is (= ::derivative/invalid-derivative-attempt
           (try
             (derivative/load-preparation-attempt service job-id 2)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))
