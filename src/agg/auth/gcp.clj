(ns agg.auth.gcp
  (:require [agg.admin.core :as admin]
            [agg.admin.gcp :as admin-gcp]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.drive.gcp :as drive-gcp]
            [agg.tokens.core :as tokens]
            [agg.tokens.gcp :as tokens-gcp]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.google.api.client.googleapis.auth.oauth2 GoogleIdTokenVerifier$Builder)
           (com.google.api.client.http.javanet NetHttpTransport)
           (com.google.api.client.json.gson GsonFactory)
           (com.google.auth.oauth2 GoogleCredentials)
           (com.google.cloud.firestore Firestore Transaction Transaction$Function)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.util Base64 Collections)
           (java.util.concurrent ExecutionException Future)))

(defn- urlencode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- form-body [form]
  (->> form
       (map (fn [[key value]]
              (str (urlencode key) "=" (urlencode value))))
       (str/join "&")))

(defn http-send!
  [{:keys [method url headers form body]}]
  (let [builder (HttpRequest/newBuilder (URI/create url))
        _ (doseq [[name value] headers]
            (.header builder name value))
        publisher (cond
                    form (HttpRequest$BodyPublishers/ofString (form-body form))
                    body (HttpRequest$BodyPublishers/ofString body)
                    :else (HttpRequest$BodyPublishers/noBody))
        _ (case method
            :get (.GET builder)
            :post (.POST builder publisher)
            :put (.PUT builder publisher))
        response (.send (HttpClient/newHttpClient)
                        (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :headers (into {}
                    (map (fn [[key values]]
                           [(str/lower-case key) (first values)]))
                    (.map (.headers response)))
     :body (.body response)}))

(defn- token-response! [send! token-endpoint form]
  (let [{:keys [status body]}
        (send! {:method :post
                :url token-endpoint
                :headers {"Content-Type"
                          "application/x-www-form-urlencoded"}
                :form form})
        parsed (try
                 (json/read-str (or body "{}") :key-fn keyword)
                 (catch Throwable _ {}))]
    (when-not (<= 200 status 299)
      (throw (ex-info "Google OAuth token exchange failed"
                      {:type (if (= "invalid_grant" (:error parsed))
                               ::auth/revoked-grant
                               ::oauth-exchange-failed)
                       :status status})))
    parsed))

(defrecord GoogleOAuthClient [send! client-id client-secret verify-identity
                              token-endpoint]
  auth/OAuthClient
  (exchange-code! [_ flow code verifier redirect-uri]
    (let [{:keys [id_token access_token refresh_token scope]}
          (token-response!
           send! token-endpoint
           {"client_id" client-id
            "client_secret" client-secret
            "code" code
            "code_verifier" verifier
            "redirect_uri" redirect-uri
            "grant_type" "authorization_code"})]
      (case flow
        :login (verify-identity id_token)
        :drive {:access-token access_token
                :refresh-token refresh_token
                :granted-scopes (into #{}
                                      (remove str/blank?)
                                      (str/split (or scope "") #"\s+"))}
        (throw (ex-info "Unknown OAuth flow" {:type ::auth/invalid-flow})))))
  auth/DriveTokenClient
  (refresh-drive-token! [_ refresh-token]
    (let [{:keys [access_token]}
          (token-response!
           send! token-endpoint
           {"client_id" client-id
            "client_secret" client-secret
            "refresh_token" refresh-token
            "grant_type" "refresh_token"})]
      {:access-token access_token})))

(defn- google-id-token-verifier [audience]
  (-> (GoogleIdTokenVerifier$Builder.
       (NetHttpTransport.) (GsonFactory/getDefaultInstance))
      (.setAudience (Collections/singletonList audience))
      (.build)))

(defn- verified-token-payload [verifier token]
  (when (str/blank? token)
    (throw (ex-info "Google ID token is missing"
                    {:type ::invalid-id-token})))
  (if-let [verified (.verify verifier token)]
    (.getPayload verified)
    (throw (ex-info "Google ID token signature or claims are invalid"
                    {:type ::invalid-id-token}))))

(defn identity-verifier [client-id]
  (let [verifier (google-id-token-verifier client-id)]
    (fn [token]
      (let [payload (verified-token-payload verifier token)]
        {:subject (.getSubject payload)
         :email (.getEmail payload)
         :email-verified? (true? (.getEmailVerified payload))}))))

(defrecord GoogleTaskTokenVerifier [verify-token]
  auth/TaskTokenVerifier
  (verify-task-token! [_ token]
    (let [{:keys [iss aud email email_verified]} (verify-token token)]
      {:issuer iss
       :audience (if (sequential? aud) (first aud) aud)
       :email email
       :email-verified? (or (true? email_verified)
                            (= "true" email_verified))})))

(defn task-token-verifier [audience]
  (let [verifier (google-id-token-verifier audience)]
    (->GoogleTaskTokenVerifier
     (fn [token]
       (let [payload (verified-token-payload verifier token)]
         {:iss (.getIssuer payload)
          :aud (.getAudience payload)
          :email (.getEmail payload)
          :email_verified (.getEmailVerified payload)})))))

(defn parse-client-credentials [secret-json]
  (let [parsed (json/read-str secret-json :key-fn keyword)
        credentials (or (:web parsed) (:installed parsed) parsed)
        client-id (:client_id credentials)
        client-secret (:client_secret credentials)]
    (when-not (and (not-empty client-id) (not-empty client-secret))
      (throw (ex-info "OAuth client secret JSON is invalid"
                      {:type ::invalid-client-secret})))
    {:client-id client-id :client-secret client-secret}))

(defn oauth-client [{:keys [client-id client-secret]}]
  (->GoogleOAuthClient http-send! client-id client-secret
                       (identity-verifier client-id)
                       "https://oauth2.googleapis.com/token"))

(defn- await! [^Future future]
  (try
    (.get future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- transaction! [^Firestore firestore action]
  (await!
   (.runTransaction
    firestore
    (reify Transaction$Function
      (updateCallback [_ transaction]
        (action transaction))))))

(defrecord FirestoreGrantStore [^Firestore firestore member-directory]
  auth/GrantStore
  (load-grant [_ subject]
    (let [snapshot (await! (.get (.document (.collection firestore "drive-grants")
                                            subject)))]
      (when (.exists snapshot)
        (let [data (.getData snapshot)]
          {:refresh-token-ciphertext (get data "refreshTokenCiphertext")
           :folder-id (get data "folderId")
           :revoked? (true? (get data "revoked"))}))))
  (save-grant! [_ subject grant]
    (await! (.set (.document (.collection firestore "drive-grants") subject)
                  {"refreshTokenCiphertext" (:refresh-token-ciphertext grant)
                   "folderId" (:folder-id grant)
                   "revoked" (boolean (:revoked? grant))}))
    grant)
  (revoke-grant! [_ subject]
    (await! (.update (.document (.collection firestore "drive-grants") subject)
                     {"revoked" true})))
  auth/MemberGrantStore
  (save-member-grant! [_ identity grant]
    (let [reference (.document (.collection firestore "drive-grants")
                               (:subject identity))]
      (try
        (transaction!
         firestore
         (fn [^Transaction transaction]
           (admin/require-active-transaction! member-directory transaction
                                              identity)
           (.set transaction reference
                 {"refreshTokenCiphertext" (:refresh-token-ciphertext grant)
                  "folderId" (:folder-id grant)
                  "revoked" (boolean (:revoked? grant))})
           grant))
        (catch clojure.lang.ExceptionInfo error
          (if (= ::admin/not-allowlisted (:type (ex-data error)))
            (throw (ex-info "User is no longer allowlisted"
                            {:type ::auth/not-allowlisted}
                            error))
            (throw error))))))
  admin/CredentialAdministration
  (delete-member-credentials! [this subject]
    (let [reference (.document (.collection firestore "drive-grants") subject)
          existed? (some? (auth/load-grant this subject))]
      (await! (.delete reference))
      existed?)))

(defn grant-store
  ([firestore]
   (->FirestoreGrantStore firestore nil))
  ([firestore member-directory]
   (->FirestoreGrantStore firestore member-directory)))

(defn- cloud-credentials []
  (-> (GoogleCredentials/getApplicationDefault)
      (.createScoped (Collections/singletonList
                      "https://www.googleapis.com/auth/cloud-platform"))))

(defn authorized-send [credentials request]
  (.refreshIfExpired ^GoogleCredentials credentials)
  (http-send! (update request :headers assoc
                      "Authorization"
                      (str "Bearer " (some-> credentials .getAccessToken
                                             .getTokenValue)))))

(defrecord KmsTokenCipher [send! crypto-key]
  auth/TokenCipher
  (encrypt-token! [_ plaintext]
    (let [encoded (.encodeToString (Base64/getEncoder)
                                   (.getBytes ^String plaintext
                                              StandardCharsets/UTF_8))
          {:keys [status body]}
          (send! {:method :post
                  :url (str "https://cloudkms.googleapis.com/v1/"
                            crypto-key ":encrypt")
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:plaintext encoded})})
          ciphertext (:ciphertext (json/read-str body :key-fn keyword))]
      (when-not (and (<= 200 status 299) (not-empty ciphertext))
        (throw (ex-info "KMS token encryption failed"
                        {:type ::kms-encryption-failed :status status})))
      ciphertext))
  (decrypt-token! [_ ciphertext]
    (let [{:keys [status body]}
          (send! {:method :post
                  :url (str "https://cloudkms.googleapis.com/v1/"
                            crypto-key ":decrypt")
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:ciphertext ciphertext})})
          plaintext (:plaintext (json/read-str body :key-fn keyword))]
      (when-not (and (<= 200 status 299) (not-empty plaintext))
        (throw (ex-info "KMS token decryption failed"
                        {:type ::kms-decryption-failed :status status})))
      (String. (.decode (Base64/getDecoder) ^String plaintext)
               StandardCharsets/UTF_8))))

(defn kms-cipher [crypto-key]
  (let [credentials (cloud-credentials)]
    (->KmsTokenCipher #(authorized-send credentials %) crypto-key)))

(defn- required [value name]
  (when (str/blank? value)
    (throw (ex-info (str name " is required")
                    {:type ::missing-configuration :name name})))
  value)

(defn- session-key [value]
  (let [bytes (.getBytes ^String (required value "AGG_SESSION_KEY")
                         StandardCharsets/UTF_8)]
    (when (< (alength bytes) 32)
      (throw (ex-info "AGG_SESSION_KEY must contain at least 32 bytes"
                      {:type ::weak-session-key})))
    bytes))

(defn- token-hash-pepper [value]
  (let [bytes (.getBytes ^String (required value "AGG_TOKEN_HASH_PEPPER")
                         StandardCharsets/UTF_8)]
    (when (< (alength bytes) 32)
      (throw (ex-info "AGG_TOKEN_HASH_PEPPER must contain at least 32 bytes"
                      {:type ::weak-token-hash-pepper})))
    bytes))

(defn- crypto-key-name [project region]
  (str "projects/" project "/locations/" region
       "/keyRings/application/cryptoKeys/drive-refresh-tokens"))

(defn- drive-components
  [^Firestore firestore project region credentials-json member-directory]
  (let [credentials (parse-client-credentials
                     (required credentials-json
                               "AGG_OAUTH_CLIENT_CREDENTIALS"))
        oauth (oauth-client credentials)
        cipher (kms-cipher (crypto-key-name project region))
        grants (grant-store firestore member-directory)
        gateway (drive-gcp/gateway)]
    {:credentials credentials
     :oauth oauth
     :cipher cipher
     :grant-store grants
     :gateway gateway}))

(defn api-dependencies
  [{:keys [firestore project region base-url owner-email session-secret
           oauth-client-credentials tasks-service-account
           scheduler-service-account picker-api-key picker-app-id
           token-hash-secret]}]
  (let [owner-email (required owner-email "AGG_OWNER_EMAIL")
        member-directory (admin-gcp/member-directory firestore owner-email)
        {:keys [credentials oauth cipher grant-store gateway]}
        (drive-components firestore project region oauth-client-credentials
                          member-directory)
        auth-system (auth/system
                     {:client-id (:client-id credentials)
                      :client-secret (:client-secret credentials)
                      :base-url (required base-url "AGG_PUBLIC_BASE_URL")
                      :member-directory member-directory
                      :owner-email owner-email
                      :session-key (session-key session-secret)
                      :oauth oauth
                      :cipher cipher
                      :grant-store grant-store
                      :drive gateway
                      :drive-token-client oauth})
        token-service
        (tokens/service {:store (tokens-gcp/token-store firestore)
                         :pepper (token-hash-pepper token-hash-secret)})]
    {:auth-system auth-system
     :member-directory member-directory
     :credential-administration grant-store
     :task-token-verifier (task-token-verifier base-url)
     :task-audience base-url
     :tasks-service-account tasks-service-account
     :scheduler-service-account scheduler-service-account
     :picker-api-key picker-api-key
     :picker-app-id picker-app-id
     :token-service token-service}))

(defn renderer-delivery
  [{:keys [firestore project region oauth-client-credentials]}]
  (let [{:keys [oauth cipher grant-store gateway]}
        (drive-components firestore project region oauth-client-credentials nil)
        access-system {:cipher cipher
                       :grant-store grant-store
                       :drive-token-client oauth}]
    (drive/delivery
     {:store (drive-gcp/delivery-store firestore)
      :gateway gateway
      :access-provider #(auth/drive-access! access-system %)})))
