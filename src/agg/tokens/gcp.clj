(ns agg.tokens.gcp
  (:require [agg.tokens.core :as tokens])
  (:import (com.google.cloud.firestore Firestore)
           (java.util.concurrent Future)))

(defn- await! [^Future future]
  (.get future))

(defn- document->token [snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      {:id (.getId snapshot)
       :subject (get data "subject")
       :email (get data "email")
       :membership-version (get data "membershipVersion")
       :name (get data "name")
       :createdAt (get data "createdAt")
       :revoked (true? (get data "revoked"))
       :hash (get data "hash")})))

(defrecord FirestoreTokenStore [^Firestore firestore]
  tokens/TokenStore
  (save-token! [_ token]
    (await! (.set (.document (.collection firestore "personal-tokens")
                             (:id token))
                  {"subject" (:subject token)
                   "email" (:email token)
                   "membershipVersion" (:membership-version token)
                   "name" (:name token)
                   "createdAt" (:createdAt token)
                   "revoked" (boolean (:revoked token))
                   "hash" (:hash token)}))
    token)
  (load-token [_ token-id]
    (some-> (.document (.collection firestore "personal-tokens") token-id)
            .get
            await!
            document->token))
  (load-subject-tokens [_ subject]
    (->> (.getDocuments
          (await! (.get (.whereEqualTo (.collection firestore "personal-tokens")
                                       "subject" subject))))
         (keep document->token)
         vec))
  (mark-token-revoked! [_ token-id]
    (await! (.update (.document (.collection firestore "personal-tokens")
                                token-id)
                     {"revoked" true}))))

(defn token-store [firestore]
  (->FirestoreTokenStore firestore))
