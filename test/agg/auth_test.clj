(ns agg.auth-test
  (:require [agg.auth.core :as auth]
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
      (if (= :login flow)
        {:subject "google-subject-1"
         :email "owner@example.com"
         :email-verified? true}
        {:access-token "drive-access-token"
         :refresh-token "drive-refresh-token"
         :granted-scopes #{"https://www.googleapis.com/auth/drive.file"}}))))

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

(deftest login-and-drive-use-separate-minimal-pkce-flows
  (let [system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth (fake-oauth (atom []))
                             :clock fixed-clock})
        login (auth/begin-flow! system :login nil)
        session (auth/issue-session system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        drive (auth/begin-flow! system :drive session)]
    (is (= #{"openid" "email" "profile"}
           (set (:scopes login))))
    (is (= #{"https://www.googleapis.com/auth/drive.file"}
           (set (:scopes drive))))
    (is (= "S256" (:codeChallengeMethod login)))
    (is (= "S256" (:codeChallengeMethod drive)))
    (is (not= (:state login) (:state drive)))
    (is (not= (:codeChallenge login) (:codeChallenge drive)))
    (is (not (re-find #"drive" (:authorizationUrl login))))
    (is (re-find #"drive.file" (:authorizationUrl drive)))))

(deftest login-callback-requires-matching-unexpired-state-and-allowlisted-email
  (let [exchanges (atom [])
        system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth (fake-oauth exchanges)
                             :clock fixed-clock})
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

(deftest login-rejects-a-valid-google-user-outside-the-allowlist
  (let [system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"another@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth (fake-oauth (atom []))
                             :clock fixed-clock})
        flow (auth/begin-flow! system :login nil)]
    (is (= ::auth/not-allowlisted
           (try
             (auth/finish-login! system {:code "code"
                                         :state (:state flow)
                                         :state-cookie (:stateCookie flow)})
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest pkce-state-survives-scale-to-zero-and-session-rechecks-allowlist
  (let [configuration {:client-id "client-id"
                       :client-secret "client-secret"
                       :base-url "https://app.example.com"
                       :allowlist #{"owner@example.com"}
                       :session-key (.getBytes "01234567890123456789012345678901")
                       :oauth (fake-oauth (atom []))
                       :clock fixed-clock}
        first-instance (auth/system configuration)
        callback-instance (auth/system configuration)
        flow (auth/begin-flow! first-instance :login nil)
        completed (auth/finish-login! callback-instance
                                      {:code "code"
                                       :state (:state flow)
                                       :state-cookie (:stateCookie flow)})
        revoked-instance (auth/system (assoc configuration :allowlist #{}))]
    (is (= "owner@example.com" (get-in completed [:user :email])))
    (is (= ::auth/not-allowlisted
           (try
             (auth/session-user revoked-instance (:session completed))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

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

(deftest drive-grant-is-encrypted-and-reuses-the-users-output-folder
  (let [{:keys [system grants encrypted folders]} (drive-fixture)
        session (auth/issue-session system {:subject "google-subject-1"
                                            :email "owner@example.com"})]
    (doseq [_ (range 2)]
      (let [flow (auth/begin-flow! system :drive session)]
        (auth/finish-drive! system {:code "drive-code"
                                    :state (:state flow)
                                    :state-cookie (:stateCookie flow)})))
    (is (= ["drive-refresh-token" "drive-refresh-token"] @encrypted))
    (is (= "kms:drive-refresh-token"
           (get-in @grants ["google-subject-1" :refresh-token-ciphertext])))
    (is (nil? (get-in @grants ["google-subject-1" :refresh-token])))
    (is (= [nil "drive-folder-1"] (mapv :existing-folder @folders)))
    (is (= "drive-folder-1"
           (get-in @grants ["google-subject-1" :folder-id])))))

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
