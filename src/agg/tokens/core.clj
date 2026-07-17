(ns agg.tokens.core
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Clock Instant)
           (java.util Base64 HexFormat UUID)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defprotocol TokenStore
  (save-token! [store token])
  (load-token [store token-id])
  (load-subject-tokens [store subject])
  (mark-token-revoked! [store token-id]))

(defprotocol TokenService
  (create-token! [service subject token-name])
  (list-tokens [service subject])
  (authenticate [service raw-token])
  (revoke-token! [service subject token-id]))

(def ^:private random (SecureRandom.))

(defn- random-secret []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- hmac-hex [pepper value]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. pepper "HmacSHA256")))]
    (.formatHex (HexFormat/of)
                (.doFinal mac (.getBytes ^String value StandardCharsets/UTF_8)))))

(defn- public-token [token]
  (select-keys token [:id :name :createdAt :revoked]))

(defn- require-token-name [value]
  (let [value (some-> value str/trim)]
    (when-not (and (string? value) (<= 1 (count value) 80))
      (throw (ex-info "Token name must contain 1 to 80 characters"
                      {:type ::invalid-token-name})))
    value))

(defn- parse-token [raw-token]
  (when-let [[_ id secret]
             (and (string? raw-token)
                  (re-matches
                   #"agg_pat_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.([A-Za-z0-9_-]{43})"
                   raw-token))]
    {:id id :secret secret}))

(defn- token-identity [subject-or-user]
  (if (map? subject-or-user)
    (select-keys subject-or-user [:subject :email])
    {:subject subject-or-user}))

(defrecord HmacTokenService [store pepper ^Clock clock]
  TokenService
  (create-token! [_ subject-or-user token-name]
    (let [{:keys [subject email]} (token-identity subject-or-user)]
      (when (str/blank? subject)
        (throw (ex-info "Token subject is required" {:type ::invalid-subject})))
      (let [id (str (UUID/randomUUID))
            secret (random-secret)
            token {:id id
                   :subject subject
                   :email email
                   :name (require-token-name token-name)
                   :createdAt (str (Instant/now clock))
                   :revoked false
                   :hash (hmac-hex pepper (str id "." secret))}]
        (save-token! store token)
        (assoc (public-token token) :token (str "agg_pat_" id "." secret)))))
  (list-tokens [_ subject]
    (->> (load-subject-tokens store subject)
         (sort-by :createdAt)
         (mapv public-token)))
  (authenticate [_ raw-token]
    (let [{:keys [id secret]} (parse-token raw-token)
          stored (when id (load-token store id))
          expected (when (and id secret)
                     (hmac-hex pepper (str id "." secret)))]
      (when-not (and stored
                     (not (:revoked stored))
                     (string? (:hash stored))
                     (string? expected)
                     (MessageDigest/isEqual
                      (.getBytes ^String (:hash stored) StandardCharsets/US_ASCII)
                      (.getBytes ^String expected StandardCharsets/US_ASCII)))
        (throw (ex-info "Personal token is invalid or revoked"
                        {:type ::invalid-token})))
      (cond-> {:subject (:subject stored)}
        (not (str/blank? (:email stored))) (assoc :email (:email stored)))))
  (revoke-token! [_ subject token-id]
    (let [stored (load-token store token-id)]
      (when-not (and stored (= subject (:subject stored)))
        (throw (ex-info "Personal token does not exist"
                        {:type ::token-not-found})))
      (mark-token-revoked! store token-id)
      (public-token (assoc stored :revoked true)))))

(defrecord InMemoryTokenStore [records]
  TokenStore
  (save-token! [_ token]
    (swap! records assoc (:id token) token)
    token)
  (load-token [_ token-id]
    (get @records token-id))
  (load-subject-tokens [_ subject]
    (filterv #(= subject (:subject %)) (vals @records)))
  (mark-token-revoked! [_ token-id]
    (swap! records assoc-in [token-id :revoked] true)))

(defn service
  [{:keys [store pepper clock]
    :or {clock (Clock/systemUTC)}}]
  (when-not (and store (bytes? pepper) (<= 32 (alength ^bytes pepper)))
    (throw (ex-info "A token store and 32-byte HMAC pepper are required"
                    {:type ::invalid-configuration})))
  (->HmacTokenService store pepper clock))

(defn in-memory-system
  [{:keys [pepper clock]
    :or {clock (Clock/systemUTC)}}]
  (let [records (atom {})
        store (->InMemoryTokenStore records)]
    {:service (service {:store store :pepper pepper :clock clock})
     :store store
     :records records}))
