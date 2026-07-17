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
        (doseq [collection ["members" "administration"
                            "owner-revocations"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [token-administration
              (reify admin/TokenAdministration
                (revoke-member-tokens! [_ _] 0))
              credential-administration
              (reify admin/CredentialAdministration
                (delete-member-credentials! [_ _] false))
              job-administration
              (reify admin/JobAdministration
                (cancel-member-jobs! [_ _] 0))
              directory-a (gcp/member-directory firestore "owner-a@example.com")
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
              service-b (admin/service
                         {:directory directory-b
                          :token-administration token-administration
                          :credential-administration credential-administration
                          :job-administration job-administration})
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

(deftest owner-rotation-cleanup-fails-closed-and-is-concurrent-startup-safe
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId
                         "animated-graph-cloud-owner-cleanup-retry-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)]
      (try
        (doseq [collection ["members" "administration"
                            "owner-revocations"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [directory-a (gcp/member-directory firestore "owner-a@example.com")
              owner-a (admin/authorize-member! directory-a
                                               "owner-a@example.com"
                                               "owner-a-subject")
              directory-b (gcp/member-directory firestore "owner-b@example.com")
              events (atom [])
              failed-calls (atom [])
              error-type
              (fn [action]
                (try
                  (action)
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (:type (ex-data error)))))
              failed-startup
              (error-type
               (fn []
                 (admin/service
                  {:directory directory-b
                   :token-administration
                   (reify admin/TokenAdministration
                     (revoke-member-tokens! [_ _]
                       (swap! failed-calls conj :tokens)
                       (throw (ex-info "unavailable" {}))))
                   :credential-administration
                   (reify admin/CredentialAdministration
                     (delete-member-credentials! [_ _]
                       (swap! failed-calls conj :credentials)
                       false))
                   :job-administration
                   (reify admin/JobAdministration
                     (cancel-member-jobs! [_ _]
                       (swap! failed-calls conj :jobs)
                       0))
                   :event-sink #(swap! events conj %)})))]
          (is (= ::admin/revocation-incomplete failed-startup))
          (is (= #{:tokens :credentials :jobs} (set @failed-calls))
              "A failed startup still attempts every cleanup component")
          (is (= 1 (count (admin/pending-owner-rotation-cleanups directory-b))))
          (is (= (:subject owner-a)
                 (:subject
                  (first (filter #(= (:email owner-a) (:email %))
                                 (admin/list-member-records directory-b)))))
              "Cleanup identity remains durable after a failed startup")
          (is (= ::admin/revocation-incomplete
                 (error-type #(admin/add-member-record! directory-b
                                                        (:email owner-a))))
              "A pending generation cannot be reactivated")
          (let [calls (atom {:tokens 0 :credentials 0 :jobs 0})
                both-entered (promise)
                release (promise)
                options
                {:directory directory-b
                 :token-administration
                 (reify admin/TokenAdministration
                   (revoke-member-tokens! [_ _]
                     (swap! calls update :tokens inc)
                     0))
                 :credential-administration
                 (reify admin/CredentialAdministration
                   (delete-member-credentials! [_ _]
                     (swap! calls update :credentials inc)
                     false))
                 :job-administration
                 (reify admin/JobAdministration
                   (cancel-member-jobs! [_ _]
                     (let [updated (swap! calls update :jobs inc)]
                       (when (= 2 (:jobs updated))
                         (deliver both-entered true)))
                     @release
                     0))
                 :event-sink #(swap! events conj %)}
                startups (mapv (fn [_] (future (admin/service options)))
                               (range 2))]
            (is (= true (deref both-entered 10000 ::timeout))
                "Both startup reconcilers read the pending generation")
            (is (= ::admin/revocation-incomplete
                   (error-type #(admin/add-member-record! directory-b
                                                          (:email owner-a))))
                "A racing re-add cannot clobber cleanup identity")
            (deliver release true)
            (is (every? #(not= ::timeout (deref % 10000 ::timeout)) startups))
            (is (empty? (admin/pending-owner-rotation-cleanups directory-b)))
            (let [former-owner
                  (first (filter #(= (:email owner-a) (:email %))
                                 (admin/list-member-records directory-b)))
                  calls-after-cleanup @calls
                  success-count
                  (fn []
                    (count (filter #(= "owner_rotation_cleanup_complete"
                                       (:event %))
                                   @events)))]
              (is (nil? (:subject former-owner)))
              (is (= 1 (success-count))
                  "Only the generation CAS winner emits success")
              (admin/service options)
              (is (= calls-after-cleanup @calls)
                  "Repeated startup does not repeat completed cleanup")
              (is (= 1 (success-count)))
              (let [new-member (admin/add-member-record! directory-b
                                                         (:email owner-a))]
                (is (not= (:membership-version owner-a)
                          (:membership-version new-member)))
                (is (= (:subject owner-a)
                       (:subject
                        (admin/authorize-member! directory-b
                                                 (:email owner-a)
                                                 (:subject owner-a))))))))
          (is (every? #(not-any? (fn [key] (contains? % key))
                                 [:email :subject :token :credential :job-id])
                      @events)))
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
