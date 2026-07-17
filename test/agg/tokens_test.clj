(ns agg.tokens-test
  (:require [agg.tokens.core :as tokens]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Clock Instant ZoneOffset)))

(def fixed-clock
  (Clock/fixed (Instant/parse "2026-07-17T12:00:00Z") ZoneOffset/UTC))

(deftest personal-token-secret-is-returned-once-and-only-its-hmac-is-stored
  (let [{:keys [service records]}
        (tokens/in-memory-system {:pepper (.getBytes "01234567890123456789012345678901")
                                  :clock fixed-clock})
        created (tokens/create-token! service "owner-subject" "DaVinci workstation")
        listed (tokens/list-tokens service "owner-subject")
        stored (first (vals @records))]
    (is (re-matches #"agg_pat_[^.]+\.[A-Za-z0-9_-]+" (:token created)))
    (is (= (dissoc created :token) (first listed)))
    (is (nil? (:token stored)))
    (is (nil? (:secret stored)))
    (is (re-matches #"[0-9a-f]{64}" (:hash stored)))
    (is (= {:subject "owner-subject"}
           (tokens/authenticate service (:token created))))))

(deftest personal-tokens-are-owner-scoped-and-revocable
  (let [{:keys [service]}
        (tokens/in-memory-system {:pepper (.getBytes "01234567890123456789012345678901")
                                  :clock fixed-clock})
        created (tokens/create-token! service "owner-subject" "Automation")]
    (testing "another member cannot list or revoke the owner's token"
      (is (empty? (tokens/list-tokens service "member-subject")))
      (is (= ::tokens/token-not-found
             (try
               (tokens/revoke-token! service "member-subject" (:id created))
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))
    (tokens/revoke-token! service "owner-subject" (:id created))
    (is (true? (:revoked (first (tokens/list-tokens service "owner-subject")))))
    (is (= ::tokens/invalid-token
           (try
             (tokens/authenticate service (:token created))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))
