(ns agg.auth-gcp-test
  (:require [agg.admin.core :as admin]
            [agg.auth.core :as auth]
            [agg.auth.gcp :as gcp]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)))

(deftest google-oauth-exchanges-login-drive-and-refresh-without-scope-mixing
  (let [requests (atom [])
        responses (atom [{:status 200 :body (json/write-str {:id_token "login-jwt"})}
                         {:status 200
                          :body (json/write-str
                                 {:access_token "drive-access"
                                  :refresh_token "drive-refresh"
                                  :scope "https://www.googleapis.com/auth/drive.file"})}
                         {:status 200
                          :body (json/write-str {:access_token "fresh-access"})}])
        send! (fn [request]
                (swap! requests conj request)
                (let [response (first @responses)]
                  (swap! responses subvec 1)
                  response))
        client (gcp/->GoogleOAuthClient
                send! "client-id" "client-secret"
                (fn [token]
                  (is (= "login-jwt" token))
                  {:subject "subject-1"
                   :email "owner@example.com"
                   :email-verified? true})
                "https://oauth2.googleapis.test/token")]
    (is (= "owner@example.com"
           (:email (auth/exchange-code! client :login "login-code" "verifier-1"
                                        "https://app/login/callback"))))
    (is (= #{"https://www.googleapis.com/auth/drive.file"}
           (:granted-scopes
            (auth/exchange-code! client :drive "drive-code" "verifier-2"
                                 "https://app/drive/callback"))))
    (is (= {:access-token "fresh-access"}
           (auth/refresh-drive-token! client "drive-refresh")))
    (is (= ["authorization_code" "authorization_code" "refresh_token"]
           (mapv #(get (:form %) "grant_type") @requests)))
    (is (every? #(= "client-secret" (get (:form %) "client_secret"))
                @requests))
    (is (every? #(nil? (get (:form %) "scope")) @requests))))

(deftest revoked-refresh-token-becomes-a-bounded-domain-error
  (let [client (gcp/->GoogleOAuthClient
                (fn [_]
                  {:status 400 :body (json/write-str {:error "invalid_grant"})})
                "client-id" "client-secret" identity
                "https://oauth2.googleapis.test/token")]
    (is (= ::auth/revoked-grant
           (try
             (auth/refresh-drive-token! client "revoked-token")
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest kms-cipher-persists-only-ciphertext-and-round-trips
  (let [requests (atom [])
        send! (fn [request]
                (swap! requests conj request)
                (if (.endsWith ^String (:url request) ":encrypt")
                  {:status 200 :body (json/write-str {:ciphertext "kms-ciphertext"})}
                  {:status 200
                   :body (json/write-str
                          {:plaintext (.encodeToString
                                       (java.util.Base64/getEncoder)
                                       (.getBytes "refresh-secret"))})}))
        cipher (gcp/->KmsTokenCipher
                send!
                "projects/project/locations/europe-central2/keyRings/application/cryptoKeys/drive-refresh-tokens")]
    (is (= "kms-ciphertext" (auth/encrypt-token! cipher "refresh-secret")))
    (is (= "refresh-secret" (auth/decrypt-token! cipher "kms-ciphertext")))
    (is (= [":encrypt" ":decrypt"]
           (mapv #(subs (:url %) (- (count (:url %)) 8)) @requests)))))

(deftest task-token-verifier-exposes-only-cryptographically-verified-claims
  (let [verifier (gcp/->GoogleTaskTokenVerifier
                  (fn [token]
                    (is (= "signed-jwt" token))
                    {:iss "https://accounts.google.com"
                     :aud "https://app.example.com"
                     :email "tasks@example.com"
                     :email_verified true}))]
    (is (= {:issuer "https://accounts.google.com"
            :audience "https://app.example.com"
            :email "tasks@example.com"
            :email-verified? true}
           (auth/verify-task-token! verifier "signed-jwt")))))

(deftest oauth-client-credentials-are-read-from-one-secret-json-value
  (is (= {:client-id "secret-client-id" :client-secret "secret-client-secret"}
         (gcp/parse-client-credentials
          (json/write-str {:web {:client_id "secret-client-id"
                                 :client_secret "secret-client-secret"}})))))

(deftest firestore-revokes-a-real-encrypted-grant
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-auth-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          store (gcp/grant-store firestore)
          subject "firestore-revoked-subject"]
      (try
        (.get (.recursiveDelete firestore (.collection firestore "drive-grants")))
        (auth/save-grant! store subject
                          {:refresh-token-ciphertext "kms-ciphertext"
                           :folder-id "folder-1"
                           :revoked? false})
        (auth/revoke-grant! store subject)
        (is (= {:refresh-token-ciphertext "kms-ciphertext"
                :folder-id "folder-1"
                :revoked? true}
               (auth/load-grant store subject)))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-grant-cleanup-is-generation-scoped-and-includes-legacy
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId
                         "animated-graph-cloud-grant-generation-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          member-directory
          (reify admin/TransactionalMembership
            (require-active-transaction! [_ _ identity] identity))
          store (gcp/grant-store firestore member-directory)
          old-identity {:subject "member-subject"
                        :membership-version "old-generation"}
          new-identity {:subject "member-subject"
                        :membership-version "new-generation"}
          grant {:refresh-token-ciphertext "kms-ciphertext"
                 :folder-id "folder-1"
                 :revoked? false}]
      (try
        (.get (.recursiveDelete firestore (.collection firestore "drive-grants")))
        (auth/save-member-grant! store new-identity grant)
        (is (false? (admin/delete-member-credentials! store old-identity)))
        (is (= grant (auth/load-grant store (:subject new-identity))))
        (is (true? (admin/delete-member-credentials! store new-identity)))
        (is (nil? (auth/load-grant store (:subject new-identity))))
        (auth/save-grant! store (:subject old-identity) grant)
        (is (true? (admin/delete-member-credentials! store old-identity)))
        (is (nil? (auth/load-grant store (:subject old-identity))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
