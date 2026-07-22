(ns agg.auth-test
  (:require [agg.admin.core :as admin]
            [agg.auth.core :as auth]
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Clock Instant ZoneOffset)))

(def fixed-clock
  (Clock/fixed (Instant/parse "2026-07-17T12:00:00Z") ZoneOffset/UTC))

(defn fake-oauth [exchanges]
  (reify auth/OAuthClient
    (exchange-code! [_ flow code verifier redirect-uri]
      (swap! exchanges conj {:flow flow
                             :code code
                             :verifier verifier
                             :redirect-uri redirect-uri})
      (is (= :login flow))
      {:subject "google-subject-1"
       :email "owner@example.com"
       :email-verified? true
       :access-token "drive-access-token"
       :refresh-token "drive-refresh-token"
       :granted-scopes (set auth/approved-scopes)})))

(defn drive-fixture []
  (let [grants (atom {})
        encrypted (atom [])
        folders (atom [])
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ plaintext]
                   (swap! encrypted conj plaintext)
                   (str "kms:" plaintext))
                 (decrypt-token! [_ ciphertext]
                   (subs ciphertext 4)))
        grant-store (reify auth/GrantStore
                      (load-grant [_ subject] (get @grants subject))
                      (save-grant! [_ subject grant]
                        (swap! grants assoc subject grant)
                        grant)
                      (revoke-grant! [_ subject]
                        (swap! grants update subject assoc :revoked? true)))
        drive (reify auth/DriveClient
                (ensure-output-folder! [_ access-token existing-folder]
                  (swap! folders conj {:access-token access-token
                                       :existing-folder existing-folder})
                  (or existing-folder "drive-folder-1")))
        refreshes (atom [])
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ refresh-token]
                         (swap! refreshes conj refresh-token)
                         {:access-token "refreshed-drive-token"}))
        system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth (fake-oauth (atom []))
                             :cipher cipher
                             :grant-store grant-store
                             :drive drive
                             :drive-token-client token-client
                             :clock fixed-clock})]
    {:system system :grants grants :encrypted encrypted :folders folders
     :refreshes refreshes}))

(deftest login-uses-one-combined-offline-pkce-flow-and-consent-only-for-recovery
  (let [{:keys [system]} (drive-fixture)
        routine (auth/begin-flow! system :login nil)
        recovery (auth/begin-flow! system :login nil true)]
    (is (= (set auth/approved-scopes) (set (:scopes routine))))
    (is (= "S256" (:codeChallengeMethod routine)))
    (is (re-find #"drive.file" (:authorizationUrl routine)))
    (is (re-find #"access_type=offline" (:authorizationUrl routine)))
    (is (not (re-find #"prompt=consent" (:authorizationUrl routine))))
    (is (re-find #"prompt=consent" (:authorizationUrl recovery)))
    (is (false? (:recovery? routine)))
    (is (true? (:recovery? recovery)))
    (is (= ::auth/invalid-flow
           (try
             (auth/begin-flow! system :drive nil)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest firebase-browser-cookie-bundles-session-and-oauth-state
  (let [system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth (fake-oauth (atom []))
                             :clock fixed-clock})
        session (auth/issue-session system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        flow (auth/begin-flow! system :login nil)
        cookie (auth/issue-browser-cookie
                system {:session session :oauth (:stateCookie flow)})]
    (is (= session (:session (auth/browser-cookie system cookie))))
    (is (= (:stateCookie flow)
           (:oauth (auth/browser-cookie system cookie))))
    (is (nil? (auth/browser-cookie system session)))
    (is (nil? (auth/browser-cookie system (str cookie "tampered"))))))

(deftest login-callback-requires-matching-unexpired-state-and-allowlisted-email
  (let [exchanges (atom [])
        system (assoc (:system (drive-fixture)) :oauth (fake-oauth exchanges))
        flow (auth/begin-flow! system :login nil)]
    (testing "state mismatch fails before exchanging the code"
      (is (= ::auth/invalid-state
             (try
               (auth/finish-login! system {:code "code"
                                           :state "attacker-state"
                                           :state-cookie (:stateCookie flow)})
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error))))))
      (is (empty? @exchanges)))
    (testing "valid state authenticates an allowlisted verified email"
      (let [result (auth/finish-login! system {:code "code"
                                               :state (:state flow)
                                               :state-cookie (:stateCookie flow)})]
        (is (= "owner@example.com" (get-in result [:user :email])))
        (is (= "google-subject-1" (get-in result [:user :subject])))
        (is (string? (:session result)))
        (is (= :login (:flow (first @exchanges))))))))

(deftest verified-nonmember-login-returns-a-private-denial-without-drive-effects
  (let [{:keys [system grants encrypted folders]} (drive-fixture)
        system (assoc system :allowlist #{"another@example.com"})
        flow (auth/begin-flow! system :login nil)
        result (auth/finish-login! system {:code "code"
                                           :state (:state flow)
                                           :state-cookie (:stateCookie flow)})]
    (is (= {:outcome :not-allowlisted
            :verified-email "owner@example.com"}
           result))
    (is (empty? @grants))
    (is (empty? @encrypted))
    (is (empty? @folders))))

(deftest combined-login-accepts-googles-normalized-identity-scope-names
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  {:subject "google-subject-1"
                   :email "owner@example.com"
                   :email-verified? true
                   :access-token "drive-access-token"
                   :refresh-token "drive-refresh-token"
                   :granted-scopes
                   #{"openid"
                     "https://www.googleapis.com/auth/userinfo.email"
                     "https://www.googleapis.com/auth/userinfo.profile"
                     "https://www.googleapis.com/auth/drive.file"}}))
        system (assoc (:system (drive-fixture)) :oauth oauth)
        flow (auth/begin-flow! system :login nil)]
    (is (string? (:session
                  (auth/finish-login! system {:code "code"
                                              :state (:state flow)
                                              :state-cookie
                                              (:stateCookie flow)}))))))

(deftest combined-login-classifies-missing-required-scopes-before-side-effects
  (let [{:keys [system grants encrypted folders]} (drive-fixture)
        oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  {:subject "private-google-subject"
                   :email "private@example.com"
                   :email-verified? true
                   :access-token "private-access-token"
                   :refresh-token "private-refresh-token"
                   :granted-scopes #{"openid" "email" "profile"}}))
        system (assoc system :oauth oauth)
        flow (auth/begin-flow! system :login nil)
        error (try
                (auth/finish-login! system {:code "private-code"
                                            :state (:state flow)
                                            :state-cookie (:stateCookie flow)})
                nil
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= ::auth/missing-required-scopes (:type (ex-data error))))
    (is (empty? @grants))
    (is (empty? @encrypted))
    (is (empty? @folders))))

(deftest combined-login-classifies-unexpected-scopes-before-side-effects
  (let [{:keys [system grants encrypted folders]} (drive-fixture)
        oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  {:subject "private-google-subject"
                   :email "private@example.com"
                   :email-verified? true
                   :access-token "private-access-token"
                   :refresh-token "private-refresh-token"
                   :granted-scopes
                   (conj (set auth/approved-scopes)
                         "https://www.googleapis.com/auth/private.extra")}))
        system (assoc system :oauth oauth)
        flow (auth/begin-flow! system :login nil)
        error (try
                (auth/finish-login! system {:code "private-code"
                                            :state (:state flow)
                                            :state-cookie (:stateCookie flow)})
                nil
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= ::auth/unexpected-scopes (:type (ex-data error))))
    (is (empty? @grants))
    (is (empty? @encrypted))
    (is (empty? @folders))))

(deftest pkce-state-survives-scale-to-zero-and-session-rechecks-allowlist
  (let [{first-instance :system} (drive-fixture)
        callback-instance first-instance
        flow (auth/begin-flow! first-instance :login nil)
        completed (auth/finish-login! callback-instance
                                      {:code "code"
                                       :state (:state flow)
                                       :state-cookie (:stateCookie flow)})
        revoked-instance (assoc callback-instance :allowlist #{})]
    (is (= "owner@example.com" (get-in completed [:user :email])))
    (is (= ::auth/not-allowlisted
           (try
             (auth/session-user revoked-instance (:session completed))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest revoked-members-need-a-new-allowlist-generation-and-oauth-session
  (let [{:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"}})
        oauth (reify auth/OAuthClient
                (exchange-code! [_ flow _ _ _]
                  (is (= :login flow))
                  {:subject "member-subject"
                   :email "member@example.com"
                   :email-verified? true
                   :access-token "drive-access-token"
                   :refresh-token "drive-refresh-token"
                   :granted-scopes (set auth/approved-scopes)}))
        system (assoc (:system (drive-fixture))
                      :allowlist #{}
                      :member-directory directory
                      :oauth oauth)
        login! (fn []
                 (let [flow (auth/begin-flow! system :login nil)]
                   (auth/finish-login! system {:code "code"
                                               :state (:state flow)
                                               :state-cookie (:stateCookie flow)})))
        first-login (login!)
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")]
    (is (= "member-subject"
           (:subject (auth/session-user system (:session first-login)))))
    (admin/revoke-member! service owner "member@example.com")
    (is (= ::auth/not-allowlisted
           (try
             (auth/session-user system (:session first-login))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))
    (admin/add-member! service owner "member@example.com")
    (testing "re-adding does not resurrect the pre-revocation session"
      (is (= ::auth/not-allowlisted
             (try
               (auth/session-user system (:session first-login))
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))
    (is (= "member-subject"
           (:subject (auth/session-user system (:session (login!))))))))

(deftest csrf-tokens-are-signed-expiring-and-bound-to-the-session-subject
  (let [{:keys [system]} (drive-fixture)
        user {:subject "google-subject-1" :email "owner@example.com"}
        csrf (auth/issue-csrf-token system user)
        expired-system
        (assoc system :clock
               (Clock/fixed (Instant/parse "2026-07-18T01:00:01Z")
                            ZoneOffset/UTC))]
    (is (true? (auth/verify-csrf! system user csrf)))
    (doseq [[label candidate-user candidate-token]
            [["another user" (assoc user :subject "member-subject") csrf]
             ["tampered token" user (str csrf "x")]
             ["expired token" user csrf]]]
      (testing label
        (is (= ::auth/invalid-csrf
               (try
                 (auth/verify-csrf! (if (= label "expired token")
                                      expired-system
                                      system)
                                    candidate-user candidate-token)
                 nil
                 (catch clojure.lang.ExceptionInfo error
                   (:type (ex-data error))))))))))

(deftest combined-login-encrypts-the-grant-and-reuses-the-users-output-folder
  (let [{:keys [system grants encrypted folders]} (drive-fixture)]
    (doseq [_ (range 2)]
      (let [flow (auth/begin-flow! system :login nil)]
        (auth/finish-login! system {:code "combined-code"
                                    :state (:state flow)
                                    :state-cookie (:stateCookie flow)})))
    (is (= ["drive-refresh-token" "drive-refresh-token"] @encrypted))
    (is (= "kms:drive-refresh-token"
           (get-in @grants ["google-subject-1" :refresh-token-ciphertext])))
    (is (nil? (get-in @grants ["google-subject-1" :refresh-token])))
    (is (= [nil "drive-folder-1"] (mapv :existing-folder @folders)))
    (is (= "drive-folder-1"
           (get-in @grants ["google-subject-1" :folder-id])))))

(deftest routine-login-validates-and-preserves-an-existing-refresh-token
  (let [{:keys [system grants encrypted]} (drive-fixture)
        first-flow (auth/begin-flow! system :login nil)]
    (auth/finish-login! system {:code "first-combined-code"
                                :state (:state first-flow)
                                :state-cookie (:stateCookie first-flow)})
    (let [reauthorization-system
          (assoc system
                 :oauth
                 (reify auth/OAuthClient
                   (exchange-code! [_ flow _ _ _]
                     (is (= :login flow))
                     {:subject "google-subject-1"
                      :email "owner@example.com"
                      :email-verified? true
                      :access-token "routine-access-token"
                      :refresh-token nil
                      :granted-scopes (set auth/approved-scopes)})))
          second-flow (auth/begin-flow! reauthorization-system :login nil)]
      (is (= {:user {:subject "google-subject-1"
                     :email "owner@example.com"
                     :role :member}}
             (select-keys
              (auth/finish-login!
               reauthorization-system
               {:code "routine-code"
                :state (:state second-flow)
                :state-cookie (:stateCookie second-flow)})
              [:user])))
      (is (= ["drive-refresh-token"] @encrypted))
      (is (= "kms:drive-refresh-token"
             (get-in @grants ["google-subject-1"
                              :refresh-token-ciphertext]))))))

(deftest legacy-session-is-upgraded-only-after-a-successful-drive-refresh
  (let [{:keys [system grants refreshes]} (drive-fixture)
        user {:subject "google-subject-1" :email "owner@example.com"}
        legacy (auth/issue-session system user {:combined-auth? false})]
    (swap! grants assoc "google-subject-1"
           {:refresh-token-ciphertext "kms:refresh"
            :folder-id "drive-folder-1"})
    (let [validated (auth/session-user system legacy)]
      (is (= "google-subject-1" (:subject validated)))
      (is (string? (:session-upgrade validated)))
      (is (= ["refresh"] @refreshes))
      (is (nil? (:session-upgrade
                 (auth/session-user system (:session-upgrade validated)))))
      (is (= ["refresh"] @refreshes)))
    (swap! grants dissoc "google-subject-1")
    (is (= ::auth/drive-grant-required
           (try
             (auth/session-user system legacy)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest drive-access-refreshes-an-encrypted-grant-and-revokes-only-invalid-grants
  (let [{:keys [system grants refreshes]} (drive-fixture)
        subject "google-subject-1"]
    (swap! grants assoc subject {:refresh-token-ciphertext "kms:refresh"
                                 :folder-id "drive-folder-1"})
    (is (= {:access-token "refreshed-drive-token"
            :folder-id "drive-folder-1"}
           (auth/drive-access! system subject)))
    (is (= ["refresh"] @refreshes))
    (let [revoked-token-client
          (reify auth/DriveTokenClient
            (refresh-drive-token! [_ _]
              (throw (ex-info "invalid_grant" {:type ::auth/revoked-grant}))))
          revoked-system (assoc system :drive-token-client revoked-token-client)]
      (is (= ::auth/drive-grant-required
             (try
               (auth/drive-access! revoked-system subject)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error))))))
      (is (true? (get-in @grants [subject :revoked?]))))
    (testing "a token response without access credentials proves the grant unusable"
      (swap! grants assoc-in [subject :revoked?] false)
      (let [empty-token-client
            (reify auth/DriveTokenClient
              (refresh-drive-token! [_ _] {}))]
        (is (= ::auth/drive-grant-required
               (try
                 (auth/drive-access! (assoc system :drive-token-client
                                            empty-token-client)
                                     subject)
                 nil
                 (catch clojure.lang.ExceptionInfo error
                   (:type (ex-data error))))))
        (is (true? (get-in @grants [subject :revoked?])))))
    (testing "transient OAuth and KMS failures preserve the durable grant"
      (doseq [[label transient-system error-type]
              [["OAuth"
                (assoc system :drive-token-client
                       (reify auth/DriveTokenClient
                         (refresh-drive-token! [_ _]
                           (throw (ex-info "temporarily unavailable"
                                           {:type ::oauth-unavailable})))))
                ::oauth-unavailable]
               ["KMS"
                (assoc system :cipher
                       (reify auth/TokenCipher
                         (encrypt-token! [_ value] value)
                         (decrypt-token! [_ _]
                           (throw (ex-info "KMS timeout"
                                           {:type ::kms-unavailable})))))
                ::kms-unavailable]]]
        (testing label
          (swap! grants assoc-in [subject :revoked?] false)
          (is (= error-type
                 (try
                   (auth/drive-access! transient-system subject)
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (:type (ex-data error))))))
          (is (false? (get-in @grants [subject :revoked?]))))))))
