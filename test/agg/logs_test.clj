(ns agg.logs-test
  (:require [agg.logs.core :as logs]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant)))

(deftest in-memory-log-store-orders-limits-and-filters
  (let [store (logs/in-memory-store)]
    (logs/append-log! store
                      (assoc (logs/entry {:severity "INFO"
                                          :component "api"
                                          :event "older"})
                             :created-at (Instant/parse "2026-07-18T10:00:00Z")))
    (logs/append-log! store
                      (assoc (logs/entry {:severity "ERROR"
                                          :component "renderer"
                                          :event "newer"})
                             :created-at (Instant/parse "2026-07-18T10:01:00Z")))
    (is (= ["newer" "older"]
           (mapv #(get-in % [:fields :event])
                 (logs/list-logs store {:limit 2}))))
    (is (= ["newer"]
           (mapv #(get-in % [:fields :event])
                 (logs/list-logs store {:severity "ERROR"}))))
    (is (= 1 (count (logs/list-logs store {:limit 1}))))))

(deftest public-log-entry-retains-raw-and-structured-views
  (let [entry (logs/public-entry
               {:id "log-1"
                :created-at "2026-07-18T10:00:00Z"
                :raw "{\"event\":\"job_failed\"}"
                :fields {:event "job_failed"}})]
    (is (= "log-1" (:id entry)))
    (is (= "{\"event\":\"job_failed\"}" (:raw entry)))
    (is (= {:event "job_failed"} (:fields entry)))))
