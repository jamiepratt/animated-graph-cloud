(ns agg.derivative.keys
  (:require [agg.derivative.contract :as contract]
            [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.util HexFormat)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- canonical-part [part-name value]
  (let [text (str value)
        length (alength (.getBytes text StandardCharsets/UTF_8))]
    (str part-name ":" length ":" text "\n")))

(defn- hmac-sha256 [secret parts]
  (let [mac (Mac/getInstance "HmacSHA256")
        message (apply str (map (fn [[part-name value]]
                                  (canonical-part part-name value))
                                parts))]
    (.init mac (SecretKeySpec. (.getBytes ^String secret
                                          StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (.formatHex (HexFormat/of)
                (.doFinal mac (.getBytes message StandardCharsets/UTF_8)))))

(defn cache-fingerprint
  "Returns only an opaque cache key and safe reuse scope."
  [secret environment
   {:keys [owner-subject drive-file-id drive-version source-bytes
           profile-version job-id]}]
  (let [environment-name
        (:environment (contract/environment-contract environment))
        immutable-version? (present-string? drive-version)]
    (when-not
     (and (present-string? secret)
          (present-string? owner-subject)
          (present-string? drive-file-id)
          (pos-int? source-bytes)
          (present-string? profile-version)
          (or immutable-version? (present-string? job-id)))
      (throw
       (errors/raise! "Private-preview cache evidence is incomplete"
                      {:type ::invalid-cache-evidence})))
    (let [reuse-scope (if immutable-version? :cross-job :current-job-only)
          scope-value (if immutable-version? drive-version job-id)]
      {:version "derivative-cache-fingerprint-v1"
       :fingerprint
       (hmac-sha256
        secret
        [["domain" "alpha-compose/private-preview/cache/v1"]
         ["environment" environment-name]
         ["reuse-scope" (name reuse-scope)]
         ["owner-subject" owner-subject]
         ["drive-file-id" drive-file-id]
         ["source-version-or-job" scope-value]
         ["source-bytes" source-bytes]
         ["profile-version" profile-version]])
       :reuse-scope reuse-scope})))

(defn idempotency-key
  "Scopes one opaque admission key to an owner and environment."
  [secret environment owner-subject client-key]
  (let [environment-name
        (:environment (contract/environment-contract environment))]
    (when-not (and (present-string? secret)
                   (present-string? owner-subject)
                   (present-string? client-key)
                   (<= (count client-key) 128))
      (throw
       (errors/raise! "Private-preview idempotency evidence is invalid"
                      {:type ::invalid-idempotency-evidence})))
    (hmac-sha256
     secret
     [["domain" "alpha-compose/private-preview/idempotency/v1"]
      ["environment" environment-name]
      ["owner-subject" owner-subject]
      ["client-key" client-key]])))

(defn object-key
  "Builds the environment-scoped opaque immutable object key."
  [environment fingerprint]
  (when-not (and (string? fingerprint)
                 (re-matches #"[0-9a-f]{64}" fingerprint))
    (throw
     (errors/raise! "Private-preview fingerprint is invalid"
                    {:type ::invalid-object-fingerprint})))
  (str (get-in (contract/environment-contract environment)
               [:namespaces :objects])
       "/" fingerprint ".mp4"))
