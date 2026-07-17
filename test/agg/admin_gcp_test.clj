(ns agg.admin-gcp-test
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as gcp]
            [agg.auth.core :as auth]
            [agg.auth.gcp :as auth-gcp]
            [agg.tokens.core :as tokens]
            [agg.tokens.gcp :as tokens-gcp]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)))

(deftest configured-owner-rotation-invalidates-the-former-owner
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-owner-rotation-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)]
      (try
        (doseq [collection ["members" "administration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory-a (gcp/member-directory firestore "owner-a@example.com")
              service-a (admin/service {:directory directory-a})
              owner-a (admin/authorize-member! directory-a
                                               "owner-a@example.com"
                                               "owner-a-subject")
              _ (admin/add-member! service-a owner-a "member@example.com")
              member (admin/authorize-member! directory-a
                                              "member@example.com"
                                              "member-subject")
              same-owner-directory
              (gcp/member-directory firestore "OWNER-A@example.com")
              same-owner (admin/active-member same-owner-directory owner-a)
              directory-b (gcp/member-directory firestore "owner-b@example.com")
              owner-b (admin/authorize-member! directory-b
                                               "owner-b@example.com"
                                               "owner-b-subject")
              service-b (admin/service {:directory directory-b})
              error-type (fn [action]
                           (try
                             (action)
                             nil
                             (catch clojure.lang.ExceptionInfo error
                               (:type (ex-data error)))))]
          (is (= owner-a same-owner)
              "Restarting with the same owner preserves its generation")
          (is (= member (admin/active-member directory-b member))
              "Owner rotation preserves unrelated active members")
          (is (= ::admin/not-allowlisted
                 (error-type #(admin/active-member directory-b owner-a))))
          (is (= ::admin/not-allowlisted
                 (error-type #(admin/list-members service-b owner-a)))
              "A stale owner role cannot bypass live membership")
          (is (= ::admin/not-allowlisted
                 (error-type
                  #(admin/with-active-member!
                     directory-b owner-a
                     (fn [] (admin/list-members service-b owner-a))))))
          (is (= [{:email "member@example.com"
                   :role "member"
                   :status "active"}
                  {:email "owner-a@example.com"
                   :role "member"
                   :status "revoked"}
                  {:email "owner-b@example.com"
                   :role "owner"
                   :status "active"}]
                 (admin/list-members service-b owner-b)))
          (let [same-owner-directory
                (gcp/member-directory firestore "owner-b@example.com")
                same-owner (admin/active-member same-owner-directory owner-b)
                _ (admin/add-member! service-b owner-b "owner-a@example.com")
                new-member-a
                (admin/authorize-member! directory-b "owner-a@example.com"
                                         "owner-a-new-subject")]
            (is (= owner-b same-owner)
                "Restarting after rotation preserves the new owner generation")
            (is (= :member (:role new-member-a)))
            (is (not= (:membership-version owner-a)
                      (:membership-version new-member-a))
                "Re-adding a former owner requires a fresh identity generation")
            (is (= ::admin/not-allowlisted
                   (error-type #(admin/active-member directory-b owner-a))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest concurrent-configured-owner-bootstraps-leave-one-active-owner
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId
                         "animated-graph-cloud-concurrent-owner-bootstrap-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)]
      (try
        (doseq [collection ["members" "administration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [start (promise)
              bootstraps
              (mapv (fn [email]
                      (future
                        @start
                        (gcp/member-directory firestore email)))
                    ["owner-a@example.com" "owner-b@example.com"])]
          (deliver start true)
          (let [directories (mapv deref bootstraps)
                records (admin/list-member-records (first directories))
                active-owners (filter #(and (= :owner (:role %))
                                            (= :active (:status %)))
                                      records)
                retired-owners (filter #(and (= :member (:role %))
                                             (= :revoked (:status %)))
                                       records)]
            (is (= 1 (count active-owners)))
            (is (= 1 (count retired-owners)))
            (is (= #{"owner-a@example.com" "owner-b@example.com"}
                   (set (map :email records))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

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
