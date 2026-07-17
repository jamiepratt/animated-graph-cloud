(ns agg.tokens-gcp-test
  (:require [agg.tokens.core :as tokens]
            [agg.tokens.gcp :as gcp]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)))

(deftest firestore-token-store-persists-only-hmacs-and-supports-revocation
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-token-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          store (gcp/token-store firestore)
          service (tokens/service
                   {:store store
                    :pepper (.getBytes "01234567890123456789012345678901")})]
      (try
        (.get (.recursiveDelete firestore (.collection firestore "personal-tokens")))
        (let [created (tokens/create-token! service "owner-subject" "CI")
              snapshot (.get (.get (.document (.collection firestore "personal-tokens")
                                              (:id created))))
              data (.getData snapshot)]
          (is (re-matches #"[0-9a-f]{64}" (get data "hash")))
          (is (nil? (get data "token")))
          (is (nil? (get data "secret")))
          (is (= [{:id (:id created)
                   :name "CI"
                   :createdAt (:createdAt created)
                   :revoked false}]
                 (tokens/list-tokens service "owner-subject")))
          (is (= {:subject "owner-subject"}
                 (tokens/authenticate service (:token created))))
          (tokens/revoke-token! service "owner-subject" (:id created))
          (is (true? (:revoked
                      (first (tokens/list-tokens service "owner-subject"))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
