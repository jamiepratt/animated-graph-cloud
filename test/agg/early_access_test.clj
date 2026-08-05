(ns agg.early-access-test
  (:require [agg.auth.core :as auth]
            [agg.early-access.core :as product-updates]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Clock Duration Instant ZoneOffset)))

(def ^:private signing-key
  (.getBytes "01234567890123456789012345678901"))

(def ^:private fixed-clock
  (Clock/fixed (Instant/parse "2026-08-05T12:00:00Z") ZoneOffset/UTC))

(defn- error-from [action]
  (try
    (action)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest signup-proof-is-distinct-short-lived-and-purpose-bound
  (let [system (product-updates/system {:proof-key signing-key
                                        :clock fixed-clock})
        proof (product-updates/issue-proof system)
        verified (product-updates/verify-proof! system proof)]
    (is (re-matches #"[A-Za-z0-9_-]{20,}" (:notification-id verified)))
    (is (= (.plusSeconds (Instant/now fixed-clock) 600)
           (:expires-at verified)))
    (is (not (contains? verified :email)))

    (testing "a signed authentication token has the wrong purpose"
      (let [auth-system (auth/system
                         {:client-id "client-id"
                          :client-secret "client-secret"
                          :base-url "https://app.example.com"
                          :allowlist #{"verified@example.com"}
                          :session-key signing-key
                          :oauth (reify auth/OAuthClient
                                   (exchange-code! [_ _ _ _ _] nil))
                          :clock fixed-clock})
            csrf (auth/issue-csrf-token
                  auth-system {:subject "google-subject-1"})]
        (is (= ::product-updates/invalid-proof
               (:type (ex-data
                       (error-from
                        #(product-updates/verify-proof! system csrf))))))))

    (testing "tampering fails without retaining signed or identity data"
      (let [error (error-from
                   #(product-updates/verify-proof! system
                                                   (str proof "tampered")))]
        (is (= ::product-updates/invalid-proof (:type (ex-data error))))
        (is (not-any? #(contains? (ex-data error) %)
                      [:proof :email :notification-id]))))

    (testing "the proof expires no later than ten minutes"
      (let [expired-system
            (product-updates/system
             {:proof-key signing-key
              :clock (Clock/offset fixed-clock (Duration/ofSeconds 601))})]
        (is (= ::product-updates/expired-proof
               (:type (ex-data
                       (error-from
                        #(product-updates/verify-proof! expired-system
                                                        proof))))))))))

(deftest valid-signup-sends-one-idempotent-plain-text-notification
  (let [attempts (atom [])
        delivered (atom {})
        notifier
        (reify product-updates/Notifier
          (send-notification! [_ notification]
            (swap! attempts conj notification)
            (or (get @delivered (:idempotency-key notification))
                (let [result {:delivery-id "resend-email-1"}]
                  (swap! delivered assoc (:idempotency-key notification) result)
                  result))))
        system (product-updates/system {:proof-key signing-key
                                        :clock fixed-clock
                                        :notifier notifier})
        proof (product-updates/issue-proof system)
        submission {:proof proof :email "  RUNNER@Example.com  "}
        first-result (product-updates/submit! system submission)
        replay-result (product-updates/submit! system submission)
        notification (first @attempts)]
    (is (= {:status :delivered} first-result replay-result))
    (is (= 2 (count @attempts)))
    (is (= 1 (count @delivered)))
    (is (= 1 (count (set (map :idempotency-key @attempts)))))
    (is (re-matches #"product-updates/[A-Za-z0-9_-]{20,}"
                    (:idempotency-key notification)))
    (is (= "Alpha Compose <early-access@alphacompose.com>"
           (:from notification)))
    (is (= "me@jamiep.org" (:to notification)))
    (is (= "runner@example.com" (:reply-to notification)))
    (is (= "Alpha Compose product updates signup" (:subject notification)))
    (is (= (str "Product updates email: runner@example.com\n"
                "Submitted at: 2026-08-05T12:00:00Z")
           (:text notification)))
    (is (not (re-find #"proof|resend-email-1" (:text notification))))))

(deftest invalid-proofs-and-emails-send-nothing-and-stay-private
  (let [notifications (atom [])
        notifier (reify product-updates/Notifier
                   (send-notification! [_ notification]
                     (swap! notifications conj notification)))
        system (product-updates/system {:proof-key signing-key
                                        :clock fixed-clock
                                        :notifier notifier})
        proof (product-updates/issue-proof system)
        expired-system
        (product-updates/system
         {:proof-key signing-key
          :clock (Clock/offset fixed-clock (Duration/ofSeconds 601))
          :notifier notifier})]
    (doseq [[label target submission expected-type expected-field]
            [["missing proof" system {:email "private@example.com"}
              ::product-updates/invalid-proof nil]
             ["tampered proof" system
              {:proof (str proof "tampered") :email "private@example.com"}
              ::product-updates/invalid-proof nil]
             ["expired proof" expired-system
              {:proof proof :email "private@example.com"}
              ::product-updates/expired-proof nil]
             ["missing email" system {:proof proof}
              ::product-updates/invalid-submission "email"]
             ["invalid email" system
              {:proof proof :email "private-invalid-email"}
              ::product-updates/invalid-submission "email"]]]
      (testing label
        (let [error (error-from #(product-updates/submit! target submission))]
          (is (= expected-type (:type (ex-data error))))
          (is (= expected-field (:field (ex-data error))))
          (is (not-any? #(contains? (ex-data error) %)
                        [:proof :email :instagram :message])))))
    (is (empty? @notifications))))
