(ns agg.early-access-test
  (:require [agg.auth.core :as auth]
            [agg.early-access.core :as early-access]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Clock Duration Instant ZoneOffset)))

(def ^:private signing-key
  (.getBytes "01234567890123456789012345678901"))

(def ^:private fixed-clock
  (Clock/fixed (Instant/parse "2026-07-22T12:00:00Z") ZoneOffset/UTC))

(defn- error-from [action]
  (try
    (action)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest denial-proof-is-distinct-short-lived-and-bound-to-the-verified-email
  (let [system (early-access/system {:proof-key signing-key
                                     :clock fixed-clock})
        proof (early-access/issue-proof system "verified@example.com")
        verified (early-access/verify-proof! system proof)]
    (is (= "verified@example.com" (:email verified)))
    (is (re-matches #"[A-Za-z0-9_-]{20,}" (:notification-id verified)))
    (is (= (.plusSeconds (Instant/now fixed-clock) 600)
           (:expires-at verified)))

    (testing "a signed auth token has the wrong purpose"
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
        (is (= ::early-access/invalid-proof
               (:type (ex-data
                       (error-from
                        #(early-access/verify-proof! system csrf))))))))

    (testing "tampering fails without retaining proof or identity in error data"
      (let [error (error-from
                   #(early-access/verify-proof! system (str proof "tampered")))]
        (is (= ::early-access/invalid-proof (:type (ex-data error))))
        (is (not-any? #(contains? (ex-data error) %)
                      [:proof :email :notification-id]))))

    (testing "the proof expires no later than ten minutes"
      (let [expired-system
            (early-access/system
             {:proof-key signing-key
              :clock (Clock/offset fixed-clock (Duration/ofSeconds 601))})]
        (is (= ::early-access/expired-proof
               (:type (ex-data
                       (error-from
                        #(early-access/verify-proof! expired-system proof))))))))))

(deftest valid-request-sends-one-plain-text-notification-bound-to-the-proof
  (let [attempts (atom [])
        delivered (atom {})
        notifier
        (reify early-access/Notifier
          (send-notification! [_ notification]
            (swap! attempts conj notification)
            (or (get @delivered (:idempotency-key notification))
                (let [result {:delivery-id "resend-email-1"}]
                  (swap! delivered assoc (:idempotency-key notification) result)
                  result))))
        system (early-access/system {:proof-key signing-key
                                     :clock fixed-clock
                                     :notifier notifier})
        proof (early-access/issue-proof system "verified@example.com")
        submission {:proof proof
                    :email "attacker@example.com"
                    :instagram "  @verified_runner  "
                    :message "  I would like to test telemetry overlays.  "}
        first-result (early-access/submit! system submission)
        replay-result (early-access/submit! system submission)
        first-notification (first @attempts)]
    (is (= {:status :delivered} first-result replay-result))
    (is (= 2 (count @attempts)))
    (is (= 1 (count @delivered)))
    (is (= 1 (count (set (map :idempotency-key @attempts)))))
    (is (re-matches #"early-access/[A-Za-z0-9_-]{20,}"
                    (:idempotency-key first-notification)))
    (is (= "Alpha Compose <early-access@alphacompose.com>"
           (:from first-notification)))
    (is (= "me@jamiep.org" (:to first-notification)))
    (is (= "verified@example.com" (:reply-to first-notification)))
    (is (= "Alpha Compose early access request"
           (:subject first-notification)))
    (is (= (str "Verified email: verified@example.com\n"
                "Instagram handle: @verified_runner\n"
                "Message: I would like to test telemetry overlays.\n"
                "Submitted at: 2026-07-22T12:00:00Z")
           (:text first-notification)))
    (is (not (re-find #"attacker@example\.com|proof|resend-email-1"
                      (:text first-notification))))))

(deftest invalid-proofs-and-overlong-fields-send-nothing
  (let [notifications (atom [])
        notifier (reify early-access/Notifier
                   (send-notification! [_ notification]
                     (swap! notifications conj notification)))
        system (early-access/system {:proof-key signing-key
                                     :clock fixed-clock
                                     :notifier notifier})
        proof (early-access/issue-proof system "verified@example.com")
        expired-system
        (early-access/system
         {:proof-key signing-key
          :clock (Clock/offset fixed-clock (Duration/ofSeconds 601))
          :notifier notifier})]
    (doseq [[label target submission expected-type expected-field]
            [["missing proof" system {} ::early-access/invalid-proof nil]
             ["tampered proof" system {:proof (str proof "tampered")}
              ::early-access/invalid-proof nil]
             ["expired proof" expired-system {:proof proof}
              ::early-access/expired-proof nil]
             ["long Instagram" system
              {:proof proof :instagram (apply str (repeat 65 "x"))}
              ::early-access/invalid-submission "instagram"]
             ["long message" system
              {:proof proof :message (apply str (repeat 2001 "x"))}
              ::early-access/invalid-submission "message"]]]
      (testing label
        (let [error (error-from #(early-access/submit! target submission))]
          (is (= expected-type (:type (ex-data error))))
          (is (= expected-field (:field (ex-data error))))
          (is (not-any? #(contains? (ex-data error) %)
                        [:proof :email :instagram :message])))))
    (is (empty? @notifications))))

(deftest valid-request-without-optional-fields-still-sends
  (let [notifications (atom [])
        notifier (reify early-access/Notifier
                   (send-notification! [_ notification]
                     (swap! notifications conj notification)))
        system (early-access/system {:proof-key signing-key
                                     :clock fixed-clock
                                     :notifier notifier})
        proof (early-access/issue-proof system "verified@example.com")]
    (is (= {:status :delivered}
           (early-access/submit! system {:proof proof})))
    (is (re-find #"Instagram handle: Not provided\nMessage: Not provided"
                 (:text (first @notifications))))))
