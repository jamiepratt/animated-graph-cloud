(ns agg.drive.derivative
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.util HexFormat)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private cache-fingerprint-domain
  "alpha-compose/derivative-preview/cache-fingerprint/v1")

(defn- present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- canonical-part [name value]
  (let [text (str value)
        length (alength (.getBytes text StandardCharsets/UTF_8))]
    (str name ":" length ":" text "\n")))

(defn- hmac-sha256 [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (.formatHex (HexFormat/of)
                (.doFinal mac (.getBytes ^String message
                                         StandardCharsets/UTF_8)))))

(defn cache-fingerprint
  "Returns only an opaque cache key and its safe reuse scope."
  [secret {:keys [owner-subject drive-file-id drive-version source-bytes
                  profile-version job-id]}]
  (let [immutable-version? (present-string? drive-version)]
    (when-not
     (and (present-string? secret)
          (present-string? owner-subject)
          (present-string? drive-file-id)
          (pos-int? source-bytes)
          (present-string? profile-version)
          (or immutable-version? (present-string? job-id)))
      (throw
       (errors/raise! "Derivative cache evidence is incomplete"
                      {:type ::invalid-cache-evidence})))
    (let [reuse-scope (if immutable-version? :cross-job :current-job-only)
          scope-value (if immutable-version? drive-version job-id)
          message (str (canonical-part "domain" cache-fingerprint-domain)
                       (canonical-part "reuse-scope" (name reuse-scope))
                       (canonical-part "owner-subject" owner-subject)
                       (canonical-part "drive-file-id" drive-file-id)
                       (canonical-part "source-version-or-job" scope-value)
                       (canonical-part "source-bytes" source-bytes)
                       (canonical-part "profile-version" profile-version))]
      {:version "derivative-cache-fingerprint-v1"
       :fingerprint (hmac-sha256 secret message)
       :reuse-scope reuse-scope})))

(defn classify
  "Classifies normalized analysis and browser evidence without echoing it."
  [{:keys [analysis-status browser-support renderable?]}]
  (case analysis-status
    :failed
    {:classification :terminal-failure
     :error-code "playback_analysis_failed"
     :retryable false}

    :unavailable
    {:classification :unavailable
     :error-code "playback_evidence_unavailable"
     :retryable true}

    :available
    (cond
      (= :supported browser-support)
      {:classification :direct-passthrough}

      (and (= :rejected browser-support) renderable?)
      {:classification :derivative-required}

      (= :rejected browser-support)
      {:classification :terminal-failure
       :error-code "derivative_source_not_renderable"
       :retryable false}

      :else
      {:classification :unavailable
       :error-code "playback_evidence_unavailable"
       :retryable true})

    {:classification :unavailable
     :error-code "playback_evidence_unavailable"
     :retryable true}))
