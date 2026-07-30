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

(defn mutable-clock [current]
  (proxy [Clock] []
    (getZone [] ZoneOffset/UTC)
    (withZone [zone] (Clock/fixed @current zone))
    (instant [] @current)))

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
                (preparation-request
                 {:request-id
                  "00000000-0000-0000-0000-000000000196"
                  :trace "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  :revision "old-revision"})
                {:asset-id "00000000-0000-0000-0000-000000000193"
                 :expires-at (.plusSeconds now (* 2 60 60))})
        result (derivative/submit-preparation!
                service "cache-hit"
                (preparation-request
                 {:request-id
                  "00000000-0000-0000-0000-000000000197"
                  :trace "0123456789abcdef0123456789abcdef"
                  :revision "agg-proto-00001-test"}))]
    (is (= (select-keys (:job cached) [:state :assetId :expiresAt])
           (select-keys (:job result) [:state :assetId :expiresAt])))
    (is (:cache-hit? result))
    (is (false? (:created? result)))
    (is (= {:requestId "00000000-0000-0000-0000-000000000197"}
           (select-keys (:job result) [:requestId :trace :revision])))
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
            :generation 42
            :size 1024
            :content-type "video/mp4"
            :profile-version "h264-aac-1080p25-v1"
            :measurements {:output-bytes 1024}})]
    (is (= {:object-key "private-object"
            :generation 42
            :size 1024
            :content-type "video/mp4"
            :profile-version "h264-aac-1080p25-v1"}
           (dissoc
            (derivative/preparation-playback-asset service job-id owner)
            :completed-at :expires-at)))
    (is (nil?
         (derivative/preparation-playback-asset
          service job-id (assoc owner :subject "another-owner"))))
    (is (nil?
         (derivative/preparation-playback-asset
          service job-id (assoc owner :membership-version "membership-v2"))))
    (is (= 1
           (admin/cancel-member-jobs!
            service {:subject "private-owner"
                     :membership-version "membership-v1"})))
    (is (nil?
         (derivative/preparation-playback-asset service job-id owner)))
    (is (= "revoked" (:state (derivative/get-preparation service job-id))))
    (is (not (derivative/owns-preparation?
              service job-id "private-owner")))
    (is (empty? (:cache @state)))
    (is (:created?
         (derivative/submit-preparation!
          service "after-revocation" (preparation-request))))
    (is (= 2 (count @queued)))))

(deftest exact-duplicate-completion-keeps-the-published-generation
  (let [{:keys [service]}
        (derivative/in-memory-preparation-system
         {:clock (Clock/fixed now ZoneOffset/UTC)
          :fingerprint-secret "fixture-secret"})
        job-id
        (get-in
         (derivative/submit-preparation!
          service "duplicate-completion" (preparation-request))
         [:job :id])
        _ (derivative/dispatch-preparation! service job-id)
        completion
        {:asset-id job-id
         :object-key "private-object"
         :generation 42
         :size 1024
         :content-type "video/mp4"
         :profile-version "h264-aac-1080p25-v1"
         :measurements {:output-bytes 1024}}
        first-completion
        (derivative/complete-preparation-attempt!
         service job-id 1 completion)]
    (is (= first-completion
           (derivative/complete-preparation-attempt!
            service job-id 1 completion)))
    (is (= ::derivative/invalid-derivative-attempt
           (try
             (derivative/complete-preparation-attempt!
              service job-id 1 (assoc completion :generation 43))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

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
    (let [attempt (derivative/load-preparation-attempt service job-id 1)]
      (is (= {:job-id job-id
              :environment "production"
              :attempt 1
              :profile render-derivative/profile-v1
              :asset {:id job-id}
              :source {:file-id "private-drive-id"
                       :drive-version "17"
                       :bytes 4096
                       :duration-seconds 120.0}
              :owner {:subject "private-owner"
                      :membership-version "membership-v1"}}
             (update-in attempt [:asset] dissoc :object-key)))
      (is (re-matches
           #"production/derivative-previews/v1/[0-9a-f]{64}\.mp4"
           (get-in attempt [:asset :object-key]))))
    (is (false?
         (derivative/preparation-cancellation-requested? service job-id 1)))
    (derivative/cancel-preparation! service job-id)
    (is (derivative/preparation-cancellation-requested? service job-id 1))
    (let [acknowledged
          (derivative/acknowledge-preparation-cancellation!
           service job-id 1)
          duplicate
          (derivative/acknowledge-preparation-cancellation!
           service job-id 1)]
      (is (= "cancelled" (:state acknowledged)))
      (is (derivative/terminal-transition? acknowledged))
      (is (false? (derivative/terminal-transition? duplicate))))
    (is (= ::derivative/invalid-derivative-attempt
           (try
             (derivative/load-preparation-attempt service job-id 2)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest reconciliation-reports-a-correlated-expiry-terminal-once
  (let [current-time (atom now)
        {:keys [service]}
        (derivative/in-memory-preparation-system
         {:clock (mutable-clock current-time)
          :fingerprint-secret "fixture-secret"})
        request-id "00000000-0000-0000-0000-000000000197"
        job-id
        (get-in
         (derivative/submit-preparation!
          service "expiry"
          (preparation-request
           {:request-id request-id
            :trace "0123456789abcdef0123456789abcdef"
            :revision "agg-proto-00001-test"}))
         [:job :id])]
    (reset! current-time (.plusSeconds now 86400))
    (let [first-reconciliation
          (derivative/reconcile-preparations! service)
          second-reconciliation
          (derivative/reconcile-preparations! service)]
      (is (= 1 (:repairedJobs first-reconciliation)))
      (is (= [{:id job-id
               :state "expired"
               :attempt 1
               :requestId request-id}]
             (mapv
              #(select-keys
                %
                [:id :state :attempt :requestId :trace :revision])
              (:terminalJobs first-reconciliation))))
      (is (= {:repairedJobs 0} second-reconciliation)))))
