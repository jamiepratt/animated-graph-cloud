(ns agg.logs.gcp
  (:require [agg.logs.core :as logs]
            [clojure.data.json :as json])
  (:import (com.google.cloud Timestamp)
           (com.google.cloud.firestore Firestore Query Query$Direction)
           (java.util Date)
           (java.util.concurrent ExecutionException Future)))

(def collection-name "observability-logs")

(defn- await! [^Future future]
  (try
    (.get future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- document-data [{:keys [fields raw created-at expires-at]}]
  {"raw" raw
   "fieldsJson" (json/write-str fields)
   "severity" (:severity fields)
   "component" (:component fields)
   "event" (:event fields)
   "createdAt" (Date/from created-at)
   "expireAt" (Date/from expires-at)})

(defn- timestamp->instant [value]
  (cond
    (instance? Timestamp value)
    (.toInstant ^Date (.toDate ^Timestamp value))

    (instance? Date value)
    (.toInstant ^Date value)

    :else nil))

(defn- snapshot-entry [snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      {:id (.getId snapshot)
       :created-at (timestamp->instant (get data "createdAt"))
       :raw (get data "raw")
       :fields (json/read-str (get data "fieldsJson") :key-fn keyword)})))

(defrecord FirestoreLogStore [^Firestore firestore]
  logs/LogStore
  (append-log! [_ entry]
    (let [entry (let [entry (if (:created-at entry)
                              entry
                              (assoc entry :created-at (java.time.Instant/now)))]
                  (if (:expires-at entry)
                    entry
                    (assoc entry :expires-at
                           (.plus (:created-at entry)
                                 (java.time.Duration/ofDays logs/retention-days)))))
          reference (.document (.collection firestore collection-name))]
      (await! (.set reference (document-data entry)))
      (assoc entry :id (.getId reference))))
  (list-logs [_ options]
    (let [{:keys [limit severity component]} (logs/normalize-options options)
          query (cond-> (.orderBy (.collection firestore collection-name)
                                  "createdAt"
                                  Query$Direction/DESCENDING)
                  severity (.whereEqualTo "severity" severity)
                  component (.whereEqualTo "component" component)
                  true (.limit (int limit)))]
      (->> (.getDocuments (await! (.get query)))
           (keep snapshot-entry)
           vec))))

(defn firestore-store [firestore]
  (->FirestoreLogStore firestore))
