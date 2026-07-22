(ns agg.early-access-resend-test
  (:require [agg.early-access.core :as early-access]
            [agg.early-access.resend :as resend]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]])
  (:import (java.net.http HttpTimeoutException)
           (java.time Duration)))

(def ^:private notification
  {:from "Alpha Compose <early-access@alphacompose.com>"
   :to "me@jamiep.org"
   :reply-to "verified@example.com"
   :subject "Alpha Compose early access request"
   :text "Verified email: verified@example.com\nInstagram handle: Not provided\nMessage: Not provided\nSubmitted at: 2026-07-22T12:00:00Z"
   :idempotency-key "early-access/opaque-notification-id"})

(defn- delivery-error [notifier]
  (try
    (early-access/send-notification! notifier notification)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest resend-request-is-plain-text-idempotent-and-time-bounded
  (let [requests (atom [])
        notifier (resend/notifier
                  {:api-key "private-resend-key"
                   :send! (fn [request]
                            (swap! requests conj request)
                            {:status 200
                             :body "{\"id\":\"resend-email-1\"}"})})]
    (is (= {:delivery-id "resend-email-1"}
           (early-access/send-notification! notifier notification)))
    (let [{:keys [method url headers body request-timeout]} (first @requests)
          parsed (json/read-str body)]
      (is (= :post method))
      (is (= "https://api.resend.com/emails" url))
      (is (= (Duration/ofSeconds 10) request-timeout))
      (is (= "Bearer private-resend-key" (get headers "Authorization")))
      (is (= "application/json" (get headers "Content-Type")))
      (is (= "early-access/opaque-notification-id"
             (get headers "Idempotency-Key")))
      (is (= {"from" "Alpha Compose <early-access@alphacompose.com>"
              "to" "me@jamiep.org"
              "reply_to" "verified@example.com"
              "subject" "Alpha Compose early access request"
              "text" (:text notification)}
             parsed))
      (is (not (re-find #"proof|private-resend-key" body))))))

(deftest resend-failures-expose-only-bounded-operational-context
  (doseq [[label response expected-type expected-status retryable]
          [["provider 4xx" {:status 422 :body "private@example.com"}
            ::resend/provider-rejected 422 false]
           ["provider 5xx" {:status 503 :body "private@example.com"}
            ::resend/provider-unavailable 503 true]
           ["malformed success" {:status 200 :body "not-json private@example.com"}
            ::resend/malformed-response nil true]
           ["undocumented success" {:status 201 :body "{\"id\":\"private-id\"}"}
            ::resend/provider-rejected 201 false]]]
    (testing label
      (let [notifier (resend/notifier {:api-key "private-resend-key"
                                       :send! (constantly response)})
            error (delivery-error notifier)
            data (ex-data error)]
        (is (= expected-type (:type data)))
        (is (= expected-status (:status data)))
        (is (= retryable (:retryable data)))
        (is (map? (:source data)))
        (is (not (re-find #"private@example\.com|private-resend-key|private-id|not-json"
                          (pr-str data)))))))
  (testing "timeout"
    (let [notifier (resend/notifier
                    {:api-key "private-resend-key"
                     :send! (fn [_]
                              (throw (HttpTimeoutException. "private body")))})
          data (ex-data (delivery-error notifier))]
      (is (= ::resend/provider-timeout (:type data)))
      (is (true? (:retryable data)))
      (is (nil? (:status data)))
      (is (not (re-find #"private" (pr-str data))))))
  (testing "missing secret"
    (let [data (ex-data (delivery-error (resend/notifier {:api-key nil})))]
      (is (= ::resend/delivery-unavailable (:type data)))
      (is (true? (:retryable data)))
      (is (nil? (:status data))))))

(deftest resend-rejects-unlocked-or-header-unsafe-mail-fields-before-network-io
  (doseq [[label field value]
          [["sender" :from "Attacker <attacker@example.com>"]
           ["recipient" :to "attacker@example.com"]
           ["subject" :subject "Different subject"]
           ["Reply-To injection" :reply-to
            "verified@example.com\r\nBcc: attacker@example.com"]
           ["idempotency injection" :idempotency-key
            "early-access/opaque-id\r\nX-Private: value"]]]
    (testing label
      (let [requests (atom [])
            notifier (resend/notifier
                      {:api-key "private-resend-key"
                       :send! #(swap! requests conj %)})
            error (try
                    (early-access/send-notification!
                     notifier (assoc notification field value))
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (= ::resend/invalid-notification (:type (ex-data error))))
        (is (empty? @requests))
        (is (not (re-find #"attacker|private|X-Private"
                          (pr-str (ex-data error)))))))))
