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
      member)))

(defn member-directory [firestore owner-email]
  (let [owner-email (admin/require-email owner-email)
        directory (->FirestoreMemberDirectory firestore)
        reference (.document (.collection firestore "members")
                             (admin/member-id owner-email))]
    (transaction!
     firestore
     (fn [^Transaction transaction]
       (let [existing (snapshot-member (await! (.get transaction reference)))
             owner (or existing
                       {:email owner-email
                        :role :owner
                        :status :active
                        :membership-version (str (UUID/randomUUID))})]
         (when-not (and (= :owner (:role owner))
                        (= :active (:status owner)))
           (throw (ex-info "Configured owner membership is invalid"
                           {:type ::invalid-owner})))
         (.set transaction reference (member-document owner))
         owner)))
    directory))
