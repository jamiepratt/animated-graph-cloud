(ns agg.logs.core
  (:import (java.time Duration Instant)
           (java.util UUID)))

(def retention-days 30)
(def default-limit 100)
(def max-limit 100)

(defprotocol LogStore
  (append-log! [store entry])
  (list-logs [store options]))

(defn normalize-options [{:keys [limit severity component]}]
  {:limit (if (and (integer? limit) (pos? limit))
            (min limit max-limit)
            default-limit)
   :severity (when (and (string? severity)
                        (<= 1 (count severity) 32)
                        (re-matches #"[A-Za-z0-9_-]+" severity))
              severity)
   :component (when (and (string? component)
                         (<= 1 (count component) 64)
                         (re-matches #"[A-Za-z0-9_.:/-]+" component))
                component)})

(defn entry
  ([fields]
   (entry fields nil))
  ([fields raw]
   {:fields fields
    :raw (or raw (pr-str fields))}))

(defn public-entry [{:keys [id created-at raw fields]}]
  {:id (str id)
   :createdAt (str created-at)
   :raw raw
   :fields fields})

(defn- stored-entry [entry]
  (let [created-at (or (:created-at entry) (Instant/now))]
    (assoc entry
           :id (or (:id entry) (str (UUID/randomUUID)))
           :created-at created-at
           :expires-at (or (:expires-at entry)
                           (.plus created-at (Duration/ofDays retention-days))))))

(defrecord InMemoryLogStore [records]
  LogStore
  (append-log! [_ entry]
    (let [entry (stored-entry entry)]
      (swap! records conj entry)
      entry))
  (list-logs [_ options]
    (let [{:keys [limit severity component]} (normalize-options options)
          now (Instant/now)]
      (->> @records
           (filter #(and (.isAfter ^Instant (:expires-at %) now)
                         (or (nil? severity)
                             (= severity (get-in % [:fields :severity])))
                         (or (nil? component)
                             (= component (get-in % [:fields :component])))))
           (sort-by :created-at #(compare %2 %1))
           (take limit)
           vec))))

(defn in-memory-store []
  (->InMemoryLogStore (atom [])))
