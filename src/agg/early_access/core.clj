(ns agg.early-access.core
  (:require [agg.admin.core :as admin]
            [agg.errors :as errors]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Clock Instant)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private proof-purpose "product-updates-signup")
(def ^:private proof-seconds 600)
(def ^:private random (SecureRandom.))

(defprotocol Notifier
  (send-notification! [notifier notification]))

(defn- random-token [size]
  (let [bytes (byte-array size)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- hmac [key value]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key "HmacSHA256")))]
    (.doFinal mac (.getBytes ^String value StandardCharsets/UTF_8))))

(defn- base64url [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- decode64 [value]
  (.decode (Base64/getUrlDecoder) ^String value))

(defn- sign-json [proof-key payload]
  (let [encoded (base64url
                 (.getBytes (json/write-str payload) StandardCharsets/UTF_8))]
    (str encoded "." (base64url (hmac proof-key encoded)))))

(defn system [{:keys [proof-key clock notifier sender recipient]
               :or {clock (Clock/systemUTC)
                    sender "Alpha Compose <early-access@alphacompose.com>"
                    recipient "me@jamiep.org"}}]
  (when-not (and proof-key (<= 32 (alength ^bytes proof-key)))
    (throw (errors/raise! "Product-updates proof configuration is invalid"
                          {:type ::invalid-configuration})))
  {:proof-key proof-key
   :clock clock
   :notifier notifier
   :sender sender
   :recipient recipient})

(defn issue-proof [{:keys [proof-key clock]}]
  (let [expires-at (.plusSeconds (Instant/now ^Clock clock) proof-seconds)]
    (sign-json proof-key
               {:purpose proof-purpose
                :notificationId (random-token 18)
                :exp (.getEpochSecond expires-at)})))

(defn- invalid-proof! []
  (throw (errors/raise! "Product-updates proof is invalid"
                        {:type ::invalid-proof})))

(defn verify-proof! [{:keys [proof-key clock]} proof]
  (try
    (let [[payload signature extra] (str/split (or proof "") #"\." 3)
          expected (when-not (str/blank? payload) (hmac proof-key payload))]
      (when (or extra
                (str/blank? payload)
                (str/blank? signature)
                (not (MessageDigest/isEqual expected (decode64 signature))))
        (invalid-proof!))
      (let [{:keys [purpose notificationId exp]}
            (json/read-str (String. (decode64 payload)
                                    StandardCharsets/UTF_8)
                           :key-fn keyword)]
        (when-not (and (= proof-purpose purpose)
                       (string? notificationId)
                       (re-matches #"[A-Za-z0-9_-]{20,128}" notificationId)
                       (integer? exp))
          (invalid-proof!))
        (when-not (< (.getEpochSecond (Instant/now ^Clock clock)) exp)
          (throw (errors/raise! "Product-updates proof has expired"
                                {:type ::expired-proof})))
        {:notification-id notificationId
         :expires-at (Instant/ofEpochSecond exp)}))
    (catch clojure.lang.ExceptionInfo error
      (if (contains? #{::invalid-proof ::expired-proof}
                     (:type (ex-data error)))
        (throw error)
        (invalid-proof!)))
    (catch Throwable _
      (invalid-proof!))))

(defn submit!
  [{:keys [notifier sender recipient clock] :as system}
   {:keys [proof email]}]
  (let [{:keys [notification-id]} (verify-proof! system proof)
        email (try
                (admin/require-email email)
                (catch clojure.lang.ExceptionInfo _
                  (throw (errors/raise! "A valid product-updates email is required"
                                        {:type ::invalid-submission
                                         :field "email"}))))]
    (when-not notifier
      (throw (errors/raise! "Product-updates delivery is unavailable"
                            {:type ::delivery-unavailable
                             :retryable true})))
    (send-notification!
     notifier
     {:from sender
      :to recipient
      :reply-to email
      :subject "Alpha Compose product updates signup"
      :text (str "Product updates email: " email "\n"
                 "Submitted at: " (Instant/now ^Clock clock))
      :idempotency-key (str "product-updates/" notification-id)})
    {:status :delivered}))
