(ns agg.early-access.resend
  (:require [agg.early-access.core :as early-access]
            [agg.errors :as errors]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io IOException)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers HttpTimeoutException)
           (java.time Duration)))

(def ^:private connect-timeout (Duration/ofSeconds 5))
(def ^:private request-timeout (Duration/ofSeconds 10))
(def ^:private locked-sender
  "Alpha Compose <early-access@alphacompose.com>")
(def ^:private locked-recipient "me@jamiep.org")
(def ^:private locked-subject "Alpha Compose early access request")

(defn- jdk-sender []
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout connect-timeout)
                   (.build))]
    (fn [{:keys [url headers body request-timeout]}]
      (let [builder (HttpRequest/newBuilder (URI/create url))]
        (doseq [[name value] headers]
          (.header builder name value))
        (.timeout builder ^Duration request-timeout)
        (.POST builder (HttpRequest$BodyPublishers/ofString body))
        (let [response (.send client (.build builder)
                              (HttpResponse$BodyHandlers/ofString))]
          {:status (.statusCode response)
           :body (.body response)})))))

(defn- retryable-status? [status]
  (or (<= 500 status 599)
      (contains? #{408 409 429} status)))

(defn- provider-error! [status]
  (throw (errors/raise!
          "Resend rejected the early-access notification"
          {:type (if (<= 500 status 599)
                   ::provider-unavailable
                   ::provider-rejected)
           :status status
           :retryable (retryable-status? status)})))

(defn- invalid-notification! []
  (throw (errors/raise! "Early-access notification is invalid"
                        {:type ::invalid-notification
                         :retryable false})))

(defn- valid-notification?
  [{:keys [from to reply-to subject text idempotency-key]}]
  (and (= locked-sender from)
       (= locked-recipient to)
       (= locked-subject subject)
       (string? reply-to)
       (<= 3 (count reply-to) 254)
       (re-matches #"[^\s@]+@[^\s@]+" reply-to)
       (string? text)
       (<= 1 (count text) 4096)
       (string? idempotency-key)
       (<= (count idempotency-key) 256)
       (re-matches #"early-access/[A-Za-z0-9_-]{20,128}"
                   idempotency-key)))

(defrecord ResendNotifier [api-key endpoint send!]
  early-access/Notifier
  (send-notification! [_ {:keys [from to reply-to subject text
                                 idempotency-key]
                          :as notification}]
    (when-not (valid-notification? notification)
      (invalid-notification!))
    (when (or (str/blank? api-key)
              (re-find #"[\r\n]" api-key))
      (throw (errors/raise! "Resend delivery secret is unavailable"
                            {:type ::delivery-unavailable
                             :retryable true})))
    (let [request {:method :post
                   :url endpoint
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"
                             "Idempotency-Key" idempotency-key}
                   :body (json/write-str {:from from
                                          :to to
                                          :reply_to reply-to
                                          :subject subject
                                          :text text})
                   :request-timeout request-timeout}
          {:keys [status body]}
          (try
            (send! request)
            (catch HttpTimeoutException _
              (throw (errors/raise! "Resend request timed out"
                                    {:type ::provider-timeout
                                     :retryable true})))
            (catch InterruptedException _
              (.interrupt (Thread/currentThread))
              (throw (errors/raise! "Resend request was interrupted"
                                    {:type ::provider-unavailable
                                     :retryable true})))
            (catch IOException _
              (throw (errors/raise! "Resend request failed"
                                    {:type ::provider-unavailable
                                     :retryable true}))))]
      (when-not (= 200 status)
        (if (and (integer? status) (<= 100 status 599))
          (provider-error! status)
          (throw (errors/raise! "Resend returned an invalid status"
                                {:type ::malformed-response
                                 :retryable true}))))
      (let [delivery-id
            (try
              (some-> (json/read-str (or body "") :key-fn keyword)
                      :id
                      not-empty)
              (catch Throwable _ nil))]
        (when-not (string? delivery-id)
          (throw (errors/raise! "Resend returned a malformed success response"
                                {:type ::malformed-response
                                 :retryable true})))
        {:delivery-id delivery-id}))))

(defn notifier [{:keys [api-key endpoint send!]
                 :or {endpoint "https://api.resend.com/emails"}}]
  (->ResendNotifier api-key endpoint (or send! (jdk-sender))))
