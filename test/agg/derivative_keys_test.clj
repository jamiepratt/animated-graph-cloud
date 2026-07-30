(ns agg.derivative-keys-test
  (:require [agg.derivative.keys :as keys]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest cache-idempotency-and-object-keys-are-opaque-and-environment-scoped
  (let [secret "fixture-hmac-secret"
        source {:owner-subject "private-owner"
                :drive-file-id "private-drive-file"
                :drive-version "17"
                :source-bytes 4096
                :profile-version "h264-aac-1080p25-v1"
                :job-id "00000000-0000-0000-0000-000000000211"}
        production (keys/cache-fingerprint secret :production source)
        proto (keys/cache-fingerprint secret :proto source)
        production-idempotency
        (keys/idempotency-key secret :production
                              "private-owner" "ui-preview-211")
        proto-idempotency
        (keys/idempotency-key secret :proto
                              "private-owner" "ui-preview-211")
        object-key (keys/object-key :production (:fingerprint production))]
    (is (= :cross-job (:reuse-scope production)))
    (is (re-matches #"[0-9a-f]{64}" (:fingerprint production)))
    (is (re-matches #"[0-9a-f]{64}" production-idempotency))
    (is (not= production proto))
    (is (not= production-idempotency proto-idempotency))
    (is (= (str "production/derivative-previews/v1/"
                (:fingerprint production) ".mp4")
           object-key))
    (is (not-any? #(str/includes? (pr-str [production
                                           production-idempotency
                                           object-key])
                                  %)
                  ["private-owner" "private-drive-file"
                   "fixture-hmac-secret" "ui-preview-211"]))))
