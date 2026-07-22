(ns agg.auth.core
  (:require [agg.errors :as errors]
            [agg.admin.core :as admin]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Clock Instant)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defprotocol OAuthClient
  (exchange-code! [client flow code verifier redirect-uri]))

(defprotocol DriveTokenClient
  (refresh-drive-token! [client refresh-token]))

(defprotocol TokenCipher
  (encrypt-token! [cipher plaintext])
  (decrypt-token! [cipher ciphertext]))

(defprotocol GrantStore
  (load-grant [store subject])
  (save-grant! [store subject grant])
  (revoke-grant! [store subject]))

(defprotocol MemberGrantStore
  (save-member-grant! [store identity grant]))

(defprotocol DriveClient
  (ensure-output-folder! [client access-token existing-folder]))

(defprotocol TaskTokenVerifier
  (verify-task-token! [verifier token]))

(def approved-scopes
  ["openid" "email" "profile"
   "https://www.googleapis.com/auth/drive.file"])

(def ^:private drive-file-scope
  "https://www.googleapis.com/auth/drive.file")

(def ^:private accepted-returned-scopes
  (into (set approved-scopes)
        ["https://www.googleapis.com/auth/userinfo.email"
         "https://www.googleapis.com/auth/userinfo.profile"]))

(defn- required-scopes-granted? [granted-scopes]
  (let [granted (set granted-scopes)]
    (and (contains? granted "openid")
         (or (contains? granted "email")
             (contains? granted
                        "https://www.googleapis.com/auth/userinfo.email"))
         (or (contains? granted "profile")
             (contains? granted
                        "https://www.googleapis.com/auth/userinfo.profile"))
         (contains? granted drive-file-scope))))

(defn- only-accepted-scopes-granted? [granted-scopes]
  (every? accepted-returned-scopes granted-scopes))

(def ^:private flow-seconds (* 10 60))
(def ^:private session-seconds (* 12 60 60))
(def ^:private csrf-seconds session-seconds)
(def ^:private random (SecureRandom.))

(defn- random-token [size]
  (let [bytes (byte-array size)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- sha256 [^String value]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes value StandardCharsets/US_ASCII)))

(defn- challenge [verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (sha256 verifier)))

(defn- urlencode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- query-string [params]
  (->> params
       (map (fn [[key value]]
              (str (urlencode (name key)) "=" (urlencode value))))
       (str/join "&")))

(defn- redirect-uri [{:keys [base-url]} flow]
  (str base-url "/v1/auth/" (name flow) "/callback"))

(defn- scopes [flow]
  (case flow
    :login approved-scopes
    (throw (errors/raise! "Unknown OAuth flow" {:type ::invalid-flow}))))

(defn- authorization-url [{:keys [client-id authorization-endpoint] :as system}
                          flow state verifier recovery?]
  (str authorization-endpoint "?"
       (query-string
        (cond-> (array-map
                 :client_id client-id
                 :redirect_uri (redirect-uri system flow)
                 :response_type "code"
                 :scope (str/join " " (scopes flow))
                 :state state
                 :code_challenge (challenge verifier)
                 :code_challenge_method "S256"
                 :include_granted_scopes "false"
                 :access_type "offline")
          recovery? (assoc :prompt "consent")))))

(defn- hmac [key value]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key "HmacSHA256")))]
    (.doFinal mac (.getBytes ^String value StandardCharsets/UTF_8))))

(defn- base64url [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- decode64 [value]
  (.decode (Base64/getUrlDecoder) ^String value))

(defn- sign-json [session-key payload]
  (let [encoded (base64url
                 (.getBytes (json/write-str payload) StandardCharsets/UTF_8))]
    (str encoded "." (base64url (hmac session-key encoded)))))

(defn- verify-json [session-key token error-type]
  (try
    (let [[payload signature extra] (str/split (or token "") #"\." 3)
          expected (hmac session-key payload)]
      (when (or extra
                (str/blank? payload)
                (str/blank? signature)
                (not (MessageDigest/isEqual expected (decode64 signature))))
        (throw (errors/raise! "Signed value is invalid" {:type error-type})))
      (json/read-str (String. (decode64 payload) StandardCharsets/UTF_8)
                     :key-fn keyword))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable error
      (throw (errors/raise! "Signed value is invalid" {:type error-type} error)))))

(defn system
  [{:keys [client-id client-secret base-url allowlist session-key oauth clock
           authorization-endpoint cipher grant-store drive drive-token-client
           member-directory owner-email admin-emails]
    :or {clock (Clock/systemUTC)
         authorization-endpoint "https://accounts.google.com/o/oauth2/v2/auth"}}]
  (when-not (and (not-empty client-id)
                 (not-empty client-secret)
                 (not-empty base-url)
                 (seq session-key)
                 oauth)
    (throw (errors/raise! "OAuth configuration is incomplete"
                          {:type ::invalid-configuration})))
  {:client-id client-id
   :client-secret client-secret
   :base-url (str/replace base-url #"/$" "")
   :allowlist (into #{} (map str/lower-case) allowlist)
   :member-directory member-directory
   :owner-email (some-> owner-email str/lower-case)
   :admin-emails (into #{} (map str/lower-case) admin-emails)
   :session-key session-key
   :oauth oauth
   :cipher cipher
   :grant-store grant-store
   :drive drive
   :drive-token-client drive-token-client
   :clock clock
   :authorization-endpoint authorization-endpoint
   :flows (atom {})})

(defn- translate-membership-error [action]
  (try
    (action)
    (catch clojure.lang.ExceptionInfo error
      (if (contains? #{::admin/not-allowlisted
                       ::admin/invalid-email
                       ::admin/invalid-subject}
                     (:type (ex-data error)))
        (throw (errors/raise! "User is no longer allowlisted"
                              {:type ::not-allowlisted}
                              error))
        (throw error)))))

(defn- dynamic-member
  [{:keys [member-directory]} {:keys [subject email membership-version] :as user}
   authorize?]
  (if-not member-directory
    user
    (translate-membership-error
     #(if authorize?
        (admin/authorize-member! member-directory email subject)
        (admin/active-member member-directory
                             {:subject subject
                              :email email
                              :membership-version membership-version})))))

(defn issue-session
  ([system user]
   (issue-session system user {:combined-auth? true}))
  ([{:keys [session-key clock member-directory] :as system}
    {:keys [subject email] :as user}
    {:keys [combined-auth?]}]
   (let [member (dynamic-member system user
                                (and member-directory
                                     (nil? (:membership-version user))))]
     (sign-json session-key
                (cond-> {:sub subject
                         :email (str/lower-case email)
                         :exp (.getEpochSecond
                               (.plusSeconds (Instant/now clock)
                                             session-seconds))}
                  (:membership-version member)
                  (assoc :membershipVersion (:membership-version member))
                  combined-auth?
                  (assoc :combinedAuth true))))))

;; Firebase Hosting forwards only the specially named __session cookie.
(defn issue-browser-cookie
  [{:keys [session-key]} {:keys [session oauth]}]
  (sign-json session-key
             (cond-> {}
               (not (str/blank? session)) (assoc :session session)
               (not (str/blank? oauth)) (assoc :oauth oauth))))

(defn browser-cookie
  [{:keys [session-key]} token]
  (when-not (str/blank? token)
    (try
      (let [cookie (verify-json session-key token ::invalid-browser-cookie)]
        ;; A raw session is also a valid signed JSON value. Only accept the
        ;; wrapper shape here so raw __session cookies continue to work after
        ;; the OAuth callback replaces the temporary browser cookie.
        (when (and (map? cookie)
                   (or (contains? cookie :session)
                       (contains? cookie :oauth)))
          cookie))
      (catch Throwable _ nil))))

(defn issue-drive-recovery-token [{:keys [session-key clock]}]
  (sign-json session-key
             {:purpose "drive-recovery"
              :exp (.getEpochSecond
                    (.plusSeconds (Instant/now clock) flow-seconds))}))

(defn drive-recovery-token? [{:keys [session-key clock]} token]
  (try
    (let [{:keys [purpose exp]}
          (verify-json session-key token ::invalid-recovery)]
      (and (= "drive-recovery" purpose)
           (number? exp)
           (> (long exp) (.getEpochSecond (Instant/now clock)))))
    (catch Throwable _ false)))

(declare drive-access!)

(defn session-user [{:keys [session-key clock allowlist member-directory
                            owner-email admin-emails]
                     :as system}
                    token]
  (try
    (let [{:keys [sub email exp membershipVersion combinedAuth]}
          (verify-json session-key token ::invalid-session)]
      (when (or (str/blank? sub)
                (str/blank? email)
                (not (number? exp))
                (<= (long exp) (.getEpochSecond (Instant/now clock))))
        (throw (errors/raise! "Session is expired" {:type ::invalid-session})))
      (let [user
            (if member-directory
              (select-keys
               (dynamic-member system {:subject sub
                                       :email email
                                       :membership-version membershipVersion}
                               false)
               [:subject :email :role :membership-version])
              (do
                (when-not (contains? allowlist (str/lower-case email))
                  (throw (errors/raise! "Session user is no longer allowlisted"
                                        {:type ::not-allowlisted})))
                (assoc {:subject sub :email email}
                       :role (cond
                               (= (str/lower-case email) owner-email) :owner
                               (contains? admin-emails (str/lower-case email)) :admin
                               :else :member))))]
        (if (true? combinedAuth)
          user
          (do
            (drive-access! system sub)
            (assoc user :session-upgrade (issue-session system user))))))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable error
      (throw (errors/raise! "Session is invalid" {:type ::invalid-session} error)))))

(defn require-allowlisted!
  [{:keys [allowlist member-directory owner-email admin-emails] :as system}
   {:keys [subject email] :as user}]
  (if member-directory
    (dynamic-member system user false)
    (do
      (when (or (str/blank? subject)
                (str/blank? email)
                (not (contains? allowlist (str/lower-case email))))
        (throw (errors/raise! "User is no longer allowlisted"
                              {:type ::not-allowlisted})))
      (assoc user
             :role (cond
                     (= (str/lower-case email) owner-email) :owner
                     (contains? admin-emails (str/lower-case email)) :admin
                     :else :member)))))

(defn issue-csrf-token [{:keys [session-key clock]} {:keys [subject]}]
  (sign-json session-key
             {:purpose "csrf"
              :sub subject
              :exp (.getEpochSecond
                    (.plusSeconds (Instant/now clock) csrf-seconds))}))

(defn verify-csrf!
  [{:keys [session-key clock]} {:keys [subject]} token]
  (try
    (let [{:keys [purpose sub exp]}
          (verify-json session-key token ::invalid-csrf)]
      (when-not (and (= "csrf" purpose)
                     (= subject sub)
                     (number? exp)
                     (> (long exp) (.getEpochSecond (Instant/now clock))))
        (throw (errors/raise! "CSRF token is invalid or expired"
                              {:type ::invalid-csrf})))
      true)
    (catch clojure.lang.ExceptionInfo error
      (if (= ::invalid-csrf (:type (ex-data error)))
        (throw error)
        (throw (errors/raise! "CSRF token is invalid"
                              {:type ::invalid-csrf}
                              error))))
    (catch Throwable error
      (throw (errors/raise! "CSRF token is invalid"
                            {:type ::invalid-csrf}
                            error)))))

(defn begin-flow!
  ([system flow session]
   (begin-flow! system flow session false))
  ([{:keys [session-key clock] :as system} flow _session recovery?]
   (let [state (random-token 32)
         verifier (random-token 48)
         expires-at (.plusSeconds (Instant/now clock) flow-seconds)]
     {:authorizationUrl (authorization-url system flow state verifier recovery?)
      :state state
      :stateCookie (sign-json session-key
                              {:flow (name flow)
                               :state state
                               :verifier verifier
                               :recovery (boolean recovery?)
                               :exp (.getEpochSecond expires-at)})
      :scopes (scopes flow)
      :recovery? (boolean recovery?)
      :codeChallenge (challenge verifier)
      :codeChallengeMethod "S256"})))

(defn- consume-flow! [{:keys [session-key clock]} expected-flow state state-cookie]
  (let [{:keys [flow exp verifier recovery] stored-state :state}
        (verify-json session-key state-cookie ::invalid-state)
        flow (some-> flow keyword)]
    (when-not (and (not-empty state)
                   (= state stored-state)
                   (= expected-flow flow)
                   (not (str/blank? verifier))
                   (number? exp)
                   (> (long exp) (.getEpochSecond (Instant/now clock))))
      (throw (errors/raise! "OAuth state is invalid or expired"
                            {:type ::invalid-state})))
    {:flow flow :verifier verifier :recovery? (true? recovery)}))

(defn- external-failure [type message error]
  (let [error-data (ex-data error)
        status (:status error-data)
        reason (:reason error-data)
        data (cond-> {:type type}
               (and (integer? status) (<= 100 status 599))
               (assoc :status status)
               (string? reason)
               (assoc :reason reason))]
    (throw (errors/raise! message data error))))

(defn- load-existing-grant! [grant-store subject]
  (try
    (load-grant grant-store subject)
    (catch Throwable error
      (external-failure
       ::grant-persistence-failed
       "Drive grant could not be loaded"
       error))))

(defn- revoke-existing-grant! [grant-store subject]
  (try
    (revoke-grant! grant-store subject)
    (catch Throwable error
      (external-failure
       ::grant-persistence-failed
       "Drive grant could not be revoked"
       error))))

(defn- refresh-existing-grant!
  [{:keys [cipher drive-token-client grant-store]} subject ciphertext]
  (let [refresh-token
        (try
          (decrypt-token! cipher ciphertext)
          (catch Throwable error
            (external-failure
             ::kms-unavailable
             "Drive grant could not be decrypted"
             error)))]
    (try
      (let [{:keys [access-token]}
            (refresh-drive-token! drive-token-client refresh-token)]
        (when (str/blank? access-token)
          (throw (errors/raise! "Drive token refresh returned no access token"
                                {:type ::revoked-grant})))
        access-token)
      (catch clojure.lang.ExceptionInfo error
        (if (= ::revoked-grant (:type (ex-data error)))
          (do
            (revoke-existing-grant! grant-store subject)
            (throw (errors/raise! "Drive authorization is expired or revoked"
                                  {:type ::drive-grant-required}
                                  error)))
          (external-failure
           ::oauth-exchange-failed
           "Drive grant could not be refreshed"
           error)))
      (catch Throwable error
        (external-failure
         ::oauth-exchange-failed
         "Drive grant could not be refreshed"
         error)))))

(defn finish-login!
  [{:keys [oauth member-directory cipher grant-store drive]
    :as system}
   {:keys [code state state-cookie]}]
  (when-not (and cipher grant-store drive (:drive-token-client system))
    (throw (errors/raise! "Combined Google authorization is not configured"
                          {:type ::drive-not-configured})))
  (when (str/blank? code)
    (throw (errors/raise! "OAuth code is required" {:type ::invalid-code})))
  (let [{:keys [verifier]} (consume-flow! system :login state state-cookie)
        {:keys [subject email email-verified? access-token refresh-token
                granted-scopes]
         :as identity}
        (exchange-code! oauth :login code verifier (redirect-uri system :login))
        email (some-> email str/lower-case)]
    (when-not (and email-verified?
                   (not (str/blank? subject))
                   (not (str/blank? email)))
      (throw (errors/raise! "Google identity is not allowlisted"
                            {:type ::not-allowlisted})))
    (when-not (required-scopes-granted? granted-scopes)
      (throw (errors/raise! "Google grant is missing required scopes"
                            {:type ::missing-required-scopes})))
    (when-not (only-accepted-scopes-granted? granted-scopes)
      (throw (errors/raise! "Google grant has unexpected scopes"
                            {:type ::unexpected-scopes})))
    (when (str/blank? access-token)
      (throw (errors/raise! "Drive grant did not return an access token"
                            {:type ::missing-access-token})))
    (let [user (try
                 (if member-directory
                   (dynamic-member system {:subject subject :email email} true)
                   (require-allowlisted! system
                                         {:subject subject :email email}))
                 (catch clojure.lang.ExceptionInfo error
                   (if (= ::not-allowlisted (:type (ex-data error)))
                     nil
                     (throw error))))]
      (if-not user
        {:outcome :not-allowlisted
         :verified-email email}
        (let [subject (:subject user)
              existing (load-existing-grant! grant-store subject)
              {:keys [refresh-token-ciphertext folder-access-token]}
              (if (str/blank? refresh-token)
                (let [ciphertext (:refresh-token-ciphertext existing)]
                  (when (or (:revoked? existing) (str/blank? ciphertext))
                    (throw (errors/raise! "Drive grant did not return offline access"
                                          {:type ::missing-refresh-token})))
                  {:refresh-token-ciphertext ciphertext
                   :folder-access-token
                   (refresh-existing-grant! system subject ciphertext)})
                {:refresh-token-ciphertext
                 (try
                   (encrypt-token! cipher refresh-token)
                   (catch Throwable error
                     (external-failure
                      ::kms-unavailable
                      "Drive grant could not be encrypted"
                      error)))
                 :folder-access-token access-token})]
          (when (str/blank? refresh-token-ciphertext)
            (throw (errors/raise! "Drive grant did not return offline access"
                                  {:type ::missing-refresh-token})))
          (let [folder-id (try
                            (ensure-output-folder! drive folder-access-token
                                                   (:folder-id existing))
                            (catch Throwable error
                              (external-failure
                               ::drive-unavailable
                               "Drive output folder is unavailable"
                               error)))
                grant {:refresh-token-ciphertext refresh-token-ciphertext
                       :folder-id folder-id
                       :revoked? false}]
            (try
              (if (satisfies? MemberGrantStore grant-store)
                (save-member-grant! grant-store user grant)
                (save-grant! grant-store subject grant))
              (catch clojure.lang.ExceptionInfo error
                (if (= ::not-allowlisted (:type (ex-data error)))
                  (throw error)
                  (external-failure
                   ::grant-persistence-failed
                   "Drive grant could not be saved"
                   error)))
              (catch Throwable error
                (external-failure
                 ::grant-persistence-failed
                 "Drive grant could not be saved"
                 error)))
            {:user (select-keys user [:subject :email :role :membership-version])
             :identity (select-keys identity
                                    [:subject :email :email-verified?])
             :folderId folder-id
             :session (issue-session system user)}))))))

(defn drive-access!
  [{:keys [cipher grant-store drive-token-client]} subject]
  (let [{:keys [refresh-token-ciphertext folder-id revoked?] :as grant}
        (when grant-store (load-grant grant-store subject))]
    (when (or (nil? grant) revoked? (str/blank? refresh-token-ciphertext))
      (throw (errors/raise! "Drive authorization is required"
                            {:type ::drive-grant-required})))
    (try
      (let [{:keys [access-token]}
            (refresh-drive-token!
             drive-token-client
             (decrypt-token! cipher refresh-token-ciphertext))]
        (when (str/blank? access-token)
          (throw (errors/raise! "Drive token refresh returned no access token"
                                {:type ::revoked-grant})))
        {:access-token access-token :folder-id folder-id})
      (catch clojure.lang.ExceptionInfo cause
        (if (= ::revoked-grant (:type (ex-data cause)))
          (do
            (revoke-grant! grant-store subject)
            (throw (errors/raise! "Drive authorization is expired or revoked"
                                  {:type ::drive-grant-required}
                                  cause)))
          (throw cause))))))
