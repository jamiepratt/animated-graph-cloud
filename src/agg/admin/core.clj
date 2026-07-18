(ns agg.admin.core
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(defprotocol MemberDirectory
  (authorize-member! [directory email subject])
  (active-member [directory identity])
  (with-active-member! [directory identity action])
  (list-member-records [directory])
  (add-member-record! [directory email])
  (revoke-member-record! [directory email]))

(defprotocol TransactionalMembership
  (require-active-transaction! [directory transaction identity]))

(defprotocol Administration
  (list-members [service actor])
  (add-member! [service actor email])
  (revoke-member! [service actor email]))

(defprotocol TokenAdministration
  (revoke-member-tokens! [administration cleanup-identity]))

(defprotocol CredentialAdministration
  (delete-member-credentials! [administration cleanup-identity]))

(defprotocol JobAdministration
  (cancel-member-jobs! [administration cleanup-identity]))

(defprotocol OwnerRotationCleanup
  (pending-owner-rotation-cleanups [directory])
  (complete-owner-rotation-cleanup! [directory cleanup]))

(defn normalize-email [email]
  (some-> email str/trim str/lower-case))

(defn member-id [email]
  (.formatHex
   (HexFormat/of)
   (.digest (MessageDigest/getInstance "SHA-256")
            (.getBytes ^String (or (normalize-email email) "")
                       StandardCharsets/UTF_8))))

(defn require-email [email]
  (let [normalized (normalize-email email)]
    (when-not (and (string? normalized)
                   (<= 3 (count normalized) 254)
                   (re-matches #"[^\s@]+@[^\s@]+" normalized))
      (throw (errors/raise! "A valid member email is required"
                      {:type ::invalid-email})))
    normalized))

(defn require-subject [subject]
  (when (str/blank? subject)
    (throw (errors/raise! "A Google subject is required"
                    {:type ::invalid-subject})))
  subject)

(defn cleanup-generation?
  [{:keys [membership-version]} resource-membership-version]
  (or (nil? resource-membership-version)
      (= membership-version resource-membership-version)))

(defn- cleanup-identity [member]
  (select-keys member [:subject :membership-version]))

(defn- membership-version []
  (str (UUID/randomUUID)))

(defn- public-member [member]
  (-> (select-keys member [:email :role :status])
      (update :role name)
      (update :status name)))

(defn administrator? [role]
  (contains? #{:owner :admin} role))

(defn- require-administrator! [actor]
  (when-not (administrator? (:role actor))
    (throw (errors/raise! "Administrator access is required"
                    {:type ::admin-required})))
  actor)

(defn- require-active-administrator! [directory actor]
  (let [member (active-member directory actor)]
    (require-administrator! member)))

(defn- require-active [records {:keys [email subject membership-version]}]
  (let [member (get records (normalize-email email))]
    (when-not (and member
                   (= :active (:status member))
                   (= subject (:subject member))
                   (= membership-version (:membership-version member)))
      (throw (errors/raise! "Member is not allowlisted" {:type ::not-allowlisted})))
    member))

(defrecord InMemoryMemberDirectory [records lock]
  MemberDirectory
  (authorize-member! [_ email subject]
    (let [email (require-email email)
          subject (require-subject subject)]
      (locking lock
        (let [member (get @records email)]
          (when-not (and member
                         (= :active (:status member))
                         (or (nil? (:subject member))
                             (= subject (:subject member))))
            (throw (errors/raise! "Member is not allowlisted"
                            {:type ::not-allowlisted})))
          (let [authorized (assoc member :subject subject)]
            (swap! records assoc email authorized)
            authorized)))))
  (active-member [_ identity]
    (locking lock
      (require-active @records identity)))
  (with-active-member! [_ identity action]
    (locking lock
      (require-active @records identity)
      (action)))
  (list-member-records [_]
    (locking lock
      (->> (vals @records) (sort-by :email) vec)))
  (add-member-record! [_ email]
    (let [email (require-email email)]
      (locking lock
        (let [existing (get @records email)
              member (if (= :active (:status existing))
                       existing
                       {:email email
                        :role :member
                        :status :active
                        :membership-version (membership-version)})]
          (swap! records assoc email member)
          member))))
  (revoke-member-record! [_ email]
    (let [email (require-email email)]
      (locking lock
        (let [member (get @records email)]
          (when-not member
            (throw (errors/raise! "Member does not exist" {:type ::member-not-found})))
          (when (= :owner (:role member))
            (throw (errors/raise! "The owner cannot be revoked"
                            {:type ::owner-cannot-be-revoked})))
          (let [revoked (assoc member :status :revoked)]
            (swap! records assoc email revoked)
            revoked))))))

(defn- emit! [event-sink event]
  (when event-sink
    (event-sink event)))

(defn- cleanup-result [component fallback action]
  (if-not action
    {:value fallback}
    (try
      {:value (action)}
      (catch Throwable _
        {:value fallback :error component}))))

(defn- required-cleanup-result [component fallback administration action]
  (cleanup-result
   component fallback
   #(if administration
      (action administration)
      (throw (errors/raise! "Owner rotation cleanup dependency is unavailable"
                      {:type ::invalid-configuration
                       :component component})))))

(defn- reconcile-owner-rotation!
  [directory token-administration credential-administration
   job-administration event-sink cleanup]
  (let [identity (cleanup-identity cleanup)
        token-result
        (required-cleanup-result
         "tokens" 0 token-administration
         #(revoke-member-tokens! % identity))
        credential-result
        (required-cleanup-result
         "credentials" false credential-administration
         #(boolean (delete-member-credentials! % identity)))
        job-result
        (required-cleanup-result
         "jobs" 0 job-administration
         #(cancel-member-jobs! % identity))
        errors (->> [token-result credential-result job-result]
                    (keep :error)
                    vec)
        base-event {:component "security"
                    :targetMemberId (:member-id cleanup)}]
    (if (seq errors)
      (do
        (emit! event-sink
               (assoc base-event
                      :severity "ERROR"
                      :event "owner_rotation_cleanup_failed"
                      :cleanupErrors errors))
        {:errors errors})
      (try
        (when (complete-owner-rotation-cleanup! directory cleanup)
          (emit! event-sink
                 (assoc base-event
                        :severity "NOTICE"
                        :event "owner_rotation_cleanup_complete"
                        :tokensRevoked (:value token-result)
                        :credentialsDeleted (:value credential-result)
                        :jobsCancelled (:value job-result))))
        {}
        (catch Throwable _
          (emit! event-sink
                 (assoc base-event
                        :severity "ERROR"
                        :event "owner_rotation_cleanup_failed"
                        :cleanupErrors ["membership"]))
          {:errors ["membership"]})))))

(defn- reconcile-owner-rotations!
  [directory token-administration credential-administration
   job-administration event-sink]
  (when (satisfies? OwnerRotationCleanup directory)
    (let [failures
          (->> (pending-owner-rotation-cleanups directory)
               (mapv #(reconcile-owner-rotation!
                       directory token-administration
                       credential-administration job-administration
                       event-sink %))
               (keep :errors)
               (apply concat)
               distinct
               vec)]
      (when (seq failures)
        (throw (errors/raise! "Owner rotation cleanup is incomplete"
                        {:type ::revocation-incomplete
                         :components failures}))))))

(defrecord AdminService [directory token-administration
                         credential-administration job-administration
                         event-sink]
  Administration
  (list-members [_ actor]
    (require-active-administrator! directory actor)
    (mapv public-member (list-member-records directory)))
  (add-member! [_ actor email]
    (require-active-administrator! directory actor)
    (let [member (add-member-record! directory email)]
      (emit! event-sink
             {:severity "NOTICE"
              :component "security"
              :event "member_added"
              :targetMemberId (member-id (:email member))})
      (public-member member)))
  (revoke-member! [_ actor email]
    (require-active-administrator! directory actor)
    (let [member (revoke-member-record! directory email)
          subject (:subject member)
          identity (cleanup-identity member)
          token-result
          (cleanup-result
           "tokens" 0
           (when (and subject token-administration)
             #(revoke-member-tokens! token-administration identity)))
          credential-result
          (cleanup-result
           "credentials" false
           (when (and subject credential-administration)
             #(boolean (delete-member-credentials!
                        credential-administration identity))))
          job-result
          (cleanup-result
           "jobs" 0
           (when (and subject job-administration)
             #(cancel-member-jobs! job-administration identity)))
          errors (->> [token-result credential-result job-result]
                      (keep :error)
                      vec)
          event (cond->
                 {:severity "NOTICE"
                  :component "security"
                  :event "member_revoked"
                  :targetMemberId (member-id (:email member))
                  :tokensRevoked (:value token-result)
                  :credentialsDeleted (:value credential-result)
                  :jobsCancelled (:value job-result)}
                  (seq errors) (assoc :cleanupErrors errors))]
      (emit! event-sink
             event)
      (when (seq errors)
        (throw (errors/raise! "Member was revoked but cleanup is incomplete"
                        {:type ::revocation-incomplete
                         :components errors})))
      (public-member member))))

(defn service [{:keys [directory token-administration
                       credential-administration job-administration
                       event-sink]}]
  (reconcile-owner-rotations! directory token-administration
                              credential-administration job-administration
                              event-sink)
  (->AdminService directory token-administration credential-administration
                  job-administration event-sink))

(defn in-memory-system
  [{:keys [owner-email initial-emails token-administration
           credential-administration job-administration event-sink
           admin-emails]}]
  (let [owner-email (require-email owner-email)
        admin-emails (into #{} (map require-email) admin-emails)
        initial-emails (into (conj (set (map require-email initial-emails))
                                   owner-email)
                             admin-emails)
        records
        (atom
         (into {}
               (map (fn [email]
                      [email {:email email
                              :role (cond
                                      (= email owner-email) :owner
                                      (contains? admin-emails email) :admin
                                      :else :member)
                              :status :active
                              :membership-version (membership-version)}]))
               initial-emails))
        directory (->InMemoryMemberDirectory records (Object.))]
    {:directory directory
     :service (service {:directory directory
                        :token-administration token-administration
                        :credential-administration credential-administration
                        :job-administration job-administration
                        :event-sink event-sink})
     :records records}))
