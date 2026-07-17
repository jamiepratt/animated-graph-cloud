(ns agg.admin-test
  (:require [agg.admin.core :as admin]
            [clojure.test :refer [deftest is testing]]))

(deftest only-the-owner-can-list-add-or-revoke-members
  (let [{:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"}})
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")
        member (admin/authorize-member! directory "member@example.com"
                                        "member-subject")]
    (is (= [{:email "member@example.com" :role "member" :status "active"}
            {:email "owner@example.com" :role "owner" :status "active"}]
           (admin/list-members service owner)))
    (is (= {:email "new@example.com" :role "member" :status "active"}
           (admin/add-member! service owner " New@Example.com ")))
    (is (= {:email "member@example.com" :role "member" :status "revoked"}
           (admin/revoke-member! service owner "MEMBER@example.com")))
    (testing "a member cannot administer the allowlist"
      (doseq [action [#(admin/list-members service member)
                      #(admin/add-member! service member "blocked@example.com")
                      #(admin/revoke-member! service member "owner@example.com")]]
        (is (= ::admin/owner-required
               (try
                 (action)
                 nil
                 (catch clojure.lang.ExceptionInfo error
                   (:type (ex-data error))))))))))

(deftest revocation-invalidates-every-access-path-and-emits-a-safe-event
  (let [revoked-tokens (atom [])
        deleted-credentials (atom [])
        cancelled-jobs (atom [])
        events (atom [])
        token-admin (reify admin/TokenAdministration
                      (revoke-member-tokens! [_ subject]
                        (swap! revoked-tokens conj subject)
                        2))
        credential-admin (reify admin/CredentialAdministration
                           (delete-member-credentials! [_ subject]
                             (swap! deleted-credentials conj subject)
                             true))
        job-admin (reify admin/JobAdministration
                    (cancel-member-jobs! [_ subject]
                      (swap! cancelled-jobs conj subject)
                      2))
        {:keys [directory service]}
        (admin/in-memory-system
         {:owner-email "owner@example.com"
          :initial-emails #{"member@example.com"}
          :token-administration token-admin
          :credential-administration credential-admin
          :job-administration job-admin
          :event-sink #(swap! events conj %)})
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")
        _ (admin/authorize-member! directory "member@example.com"
                                   "member-subject")]
    (admin/revoke-member! service owner "member@example.com")
    (is (= ["member-subject"] @revoked-tokens))
    (is (= ["member-subject"] @deleted-credentials))
    (is (= ["member-subject"] @cancelled-jobs))
    (is (= [{:severity "NOTICE"
             :component "security"
             :event "member_revoked"
             :targetMemberId (admin/member-id "member@example.com")
             :tokensRevoked 2
             :credentialsDeleted true
             :jobsCancelled 2}]
           @events))
    (is (not-any? #(contains? (first @events) %)
                  [:email :subject :token :credential :content]))))

(deftest revocation-attempts-every-cleanup-when-one-dependency-fails
  (let [credentials-deleted? (atom false)
        jobs-cancelled? (atom false)
        events (atom [])
        {:keys [directory]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"}})
        service
        (admin/service
         {:directory directory
          :token-administration
          (reify admin/TokenAdministration
            (revoke-member-tokens! [_ _]
              (throw (ex-info "token store unavailable" {}))))
          :credential-administration
          (reify admin/CredentialAdministration
            (delete-member-credentials! [_ _]
              (reset! credentials-deleted? true)))
          :job-administration
          (reify admin/JobAdministration
            (cancel-member-jobs! [_ _]
              (reset! jobs-cancelled? true)
              1))
          :event-sink #(swap! events conj %)})
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")
        member (admin/authorize-member! directory "member@example.com"
                                        "member-subject")]
    (is (= ::admin/revocation-incomplete
           (try
             (admin/revoke-member! service owner "member@example.com")
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))
    (is @credentials-deleted?)
    (is @jobs-cancelled?)
    (is (= :revoked
           (:status (first (filter #(= "member@example.com" (:email %))
                                   (admin/list-member-records directory))))))
    (is (= ["tokens"] (:cleanupErrors (last @events))))
    (is (= ::admin/not-allowlisted
           (try
             (admin/active-member directory member)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))
