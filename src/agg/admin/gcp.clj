(ns agg.admin.gcp
  (:require [agg.admin.core :as admin])
  (:import (com.google.cloud.firestore DocumentSnapshot Firestore Transaction
                                       Transaction$Function TransactionOptions)
           (java.time Instant)
           (java.util UUID)
           (java.util.concurrent ExecutionException Future)))

(defn- await! [^Future future]
  (try
    (.get future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- transaction!
  ([firestore action]
   (transaction! firestore action nil))
  ([^Firestore firestore action attempts]
   (let [callback (reify Transaction$Function
                    (updateCallback [_ transaction]
                      (action transaction)))]
     (await!
      (if attempts
        (.runTransaction firestore callback (TransactionOptions/create attempts))
        (.runTransaction firestore callback))))))

(defn- snapshot-member [^DocumentSnapshot snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      (cond-> {:email (get data "email")
               :role (keyword (get data "role"))
               :status (keyword (get data "status"))
               :membership-version (get data "membershipVersion")}
        (get data "subject") (assoc :subject (get data "subject"))))))

(defn- member-document [member]
  (cond-> {"email" (:email member)
           "role" (name (:role member))
           "status" (name (:status member))
           "membershipVersion" (:membership-version member)
           "updatedAt" (str (Instant/now))}
    (:subject member) (assoc "subject" (:subject member))))

(defn- snapshot-owner-revocation [^DocumentSnapshot snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)
          revocation {:id (.getId snapshot)
                      :member-id (get data "memberId")
                      :membership-version (get data "membershipVersion")
                      :subject (get data "subject")}]
      (when-not (and (= "pending" (get data "state"))
                     (string? (:membership-version revocation))
                     (= (:id revocation) (:membership-version revocation))
                     (string? (:member-id revocation))
                     (re-matches #"[0-9a-f]{64}" (:member-id revocation))
                     (string? (:subject revocation))
                     (not-empty (:subject revocation)))
        (throw (ex-info "Owner rotation cleanup record is invalid"
                        {:type ::admin/revocation-incomplete})))
      revocation)))

(defn- owner-revocation-document [member]
  {"memberId" (admin/member-id (:email member))
   "membershipVersion" (:membership-version member)
   "subject" (:subject member)
   "state" "pending"
   "createdAt" (str (Instant/now))})

(defn- owner-revocation-reference [firestore membership-version]
  (.document (.collection firestore "owner-revocations") membership-version))

(defn- active? [member {:keys [email subject membership-version]}]
  (and member
       (= :active (:status member))
       (= (admin/normalize-email email) (:email member))
       (= subject (:subject member))
       (= membership-version (:membership-version member))))

(defrecord FirestoreMemberDirectory [^Firestore firestore]
  admin/MemberDirectory
  (authorize-member! [_ email subject]
    (let [email (admin/require-email email)
          subject (admin/require-subject subject)
          reference (.document (.collection firestore "members")
                               (admin/member-id email))]
      (transaction!
       firestore
       (fn [^Transaction transaction]
         (let [member (snapshot-member (await! (.get transaction reference)))]
           (when-not (and member
                          (= :active (:status member))
                          (or (nil? (:subject member))
                              (= subject (:subject member))))
             (throw (ex-info "Member is not allowlisted"
                             {:type ::admin/not-allowlisted})))
           (let [authorized (assoc member :subject subject)]
             (.set transaction reference (member-document authorized))
             authorized))))))
  (active-member [_ identity]
    (let [email (admin/require-email (:email identity))
          member (some-> (.document (.collection firestore "members")
                                    (admin/member-id email))
                         .get await! snapshot-member)]
      (when-not (active? member identity)
        (throw (ex-info "Member is not allowlisted"
                        {:type ::admin/not-allowlisted})))
      member))
  (with-active-member! [this identity action]
    (admin/active-member this identity)
    (action))
  (list-member-records [_]
    (->> (await! (.get (.collection firestore "members")))
         .getDocuments
         (keep snapshot-member)
         (sort-by :email)
         vec))
  (add-member-record! [_ email]
    (let [email (admin/require-email email)
          reference (.document (.collection firestore "members")
                               (admin/member-id email))]
      (transaction!
       firestore
       (fn [^Transaction transaction]
         (let [existing (snapshot-member (await! (.get transaction reference)))
               pending
               (when (and existing
                          (= :revoked (:status existing))
                          (:membership-version existing))
                 (some-> (owner-revocation-reference
                          firestore (:membership-version existing))
                         (#(.get transaction %))
                         await!
                         snapshot-owner-revocation))
               _ (when pending
                   (throw (ex-info "Owner rotation cleanup is incomplete"
                                   {:type ::admin/revocation-incomplete})))
               member (if (= :active (:status existing))
                        existing
                        {:email email
                         :role :member
                         :status :active
                         :membership-version (str (UUID/randomUUID))})]
           (.set transaction reference (member-document member))
           member)))))
  (revoke-member-record! [_ email]
    (let [email (admin/require-email email)
          reference (.document (.collection firestore "members")
                               (admin/member-id email))]
      (transaction!
       firestore
       (fn [^Transaction transaction]
         (let [member (snapshot-member (await! (.get transaction reference)))]
           (when-not member
             (throw (ex-info "Member does not exist"
                             {:type ::admin/member-not-found})))
           (when (= :owner (:role member))
             (throw (ex-info "The owner cannot be revoked"
                             {:type ::admin/owner-cannot-be-revoked})))
           (let [revoked (assoc member :status :revoked)]
             (.set transaction reference (member-document revoked))
             revoked)))
       20)))
  admin/TransactionalMembership
  (require-active-transaction! [_ transaction identity]
    (let [email (admin/require-email (:email identity))
          reference (.document (.collection firestore "members")
                               (admin/member-id email))
          member (snapshot-member (await! (.get ^Transaction transaction
                                                reference)))]
      (when-not (active? member identity)
        (throw (ex-info "Member is not allowlisted"
                        {:type ::admin/not-allowlisted})))
      member))
  admin/OwnerRotationCleanup
  (pending-owner-rotation-cleanups [_]
    (->> (await! (.get (.collection firestore "owner-revocations")))
         .getDocuments
         (keep snapshot-owner-revocation)
         vec))
  (complete-owner-rotation-cleanup! [_ cleanup]
    (let [pending-reference
          (owner-revocation-reference firestore (:id cleanup))
          member-reference
          (.document (.collection firestore "members") (:member-id cleanup))]
      (transaction!
       firestore
       (fn [^Transaction transaction]
         (let [pending (snapshot-owner-revocation
                        (await! (.get transaction pending-reference)))]
           (if-not pending
             false
             (let [member (snapshot-member
                           (await! (.get transaction member-reference)))]
               (when-not (and (= (select-keys cleanup
                                              [:id :member-id
                                               :membership-version :subject])
                                 (select-keys pending
                                              [:id :member-id
                                               :membership-version :subject]))
                              (= :revoked (:status member))
                              (= (:membership-version cleanup)
                                 (:membership-version member))
                              (= (:subject cleanup) (:subject member)))
                 (throw (ex-info "Revoked owner generation changed during cleanup"
                                 {:type ::admin/revocation-incomplete})))
               (.set transaction member-reference
                     (member-document (dissoc member :subject)))
               (.delete transaction pending-reference)
               true))))
       20))))

(defn member-directory [firestore owner-email]
  (let [owner-email (admin/require-email owner-email)
        directory (->FirestoreMemberDirectory firestore)
        members (.collection firestore "members")
        reference (.document members (admin/member-id owner-email))
        owner-reference (.document (.collection firestore "administration")
                                   "current-owner")]
    (transaction!
     firestore
     (fn [^Transaction transaction]
       (await! (.get transaction owner-reference))
       (let [previous-owners
             (->> (await! (.get transaction (.whereEqualTo members
                                                           "role" "owner")))
                  .getDocuments
                  (keep snapshot-member))
             existing (snapshot-member (await! (.get transaction reference)))
             pending
             (when (and existing
                        (= :revoked (:status existing))
                        (:membership-version existing))
               (some-> (owner-revocation-reference
                        firestore (:membership-version existing))
                       (#(.get transaction %))
                       await!
                       snapshot-owner-revocation))
             _ (when pending
                 (throw (ex-info "Owner rotation cleanup is incomplete"
                                 {:type ::admin/revocation-incomplete})))
             owner (if (= :active (:status existing))
                     (assoc existing :role :owner)
                     {:email owner-email
                      :role :owner
                      :status :active
                      :membership-version (str (UUID/randomUUID))})]
         (doseq [previous previous-owners
                 :when (not= owner-email (:email previous))]
           (.set transaction
                 (.document members (admin/member-id (:email previous)))
                 (member-document (assoc previous
                                         :role :member :status :revoked)))
           (when (:subject previous)
             (.set transaction
                   (owner-revocation-reference
                    firestore (:membership-version previous))
                   (owner-revocation-document previous))))
         (.set transaction reference (member-document owner))
         (.set transaction owner-reference
               {"memberId" (admin/member-id owner-email)
                "updatedAt" (str (Instant/now))})
         owner))
     20)
    directory))
