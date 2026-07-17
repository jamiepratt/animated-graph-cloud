(ns agg.admin-gcp-test
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as gcp]
            [agg.auth.core :as auth]
            [agg.auth.gcp :as auth-gcp]
            [agg.tokens.core :as tokens]
            [agg.tokens.gcp :as tokens-gcp]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)))

(deftest firestore-membership-revocation-deletes-drive-credentials-and-tokens
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-admin-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))]
      (try
        (doseq [collection ["members" "personal-tokens" "drive-grants"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory (gcp/member-directory firestore "owner@example.com")
              token-service (tokens/service
                             {:store (tokens-gcp/token-store firestore)
                              :pepper (.getBytes "01234567890123456789012345678901")})
              grant-store (auth-gcp/grant-store firestore)
              service (admin/service
                       {:directory directory
                        :token-administration token-service
                        :credential-administration grant-store})
              owner (admin/authorize-member! directory "owner@example.com"
                                             "owner-subject")
              _ (admin/add-member! service owner "member@example.com")
              member (admin/authorize-member! directory "member@example.com"
                                              "member-subject")
              token (tokens/create-token! token-service member "Automation")]
          (auth/save-grant! grant-store "member-subject"
                            {:refresh-token-ciphertext "kms-ciphertext"
                             :folder-id "drive-folder"
                             :revoked? false})
          (is (= :member (:role (admin/active-member directory member))))
          (admin/revoke-member! service owner "member@example.com")
          (is (nil? (auth/load-grant grant-store "member-subject")))
          (is (= ::tokens/invalid-token
                 (try
                   (tokens/authenticate token-service (:token token))
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (is (= ::admin/not-allowlisted
                 (try
                   (admin/active-member directory member)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (admin/add-member! service owner "member@example.com")
          (is (= ::admin/not-allowlisted
                 (try
                   (admin/active-member directory member)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error)))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-revocation-fences-a-concurrent-drive-grant-save
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-drive-revoke-race-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))]
      (try
        (doseq [collection ["members" "drive-grants"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory (gcp/member-directory firestore "owner@example.com")
              grant-store (auth-gcp/grant-store firestore directory)
              service (admin/service {:directory directory
                                      :credential-administration grant-store})
              owner (admin/authorize-member! directory "owner@example.com"
                                             "owner-subject")
              _ (admin/add-member! service owner "member@example.com")
              member (admin/authorize-member! directory "member@example.com"
                                              "member-subject")
              start (promise)
              save (future
                     @start
                     (try
                       (auth/save-member-grant!
                        grant-store member
                        {:refresh-token-ciphertext "kms-new-ciphertext"
                         :folder-id "drive-folder"
                         :revoked? false})
                       (catch clojure.lang.ExceptionInfo error
                         error)))
              revoke (future
                       @start
                       (admin/revoke-member! service owner "member@example.com"))]
          (deliver start true)
          @save
          @revoke
          (is (nil? (auth/load-grant grant-store "member-subject"))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
