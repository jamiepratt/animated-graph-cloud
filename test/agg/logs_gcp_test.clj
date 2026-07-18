(ns agg.logs-gcp-test
  (:require [agg.logs.core :as logs]
            [agg.logs.gcp :as gcp]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud.firestore FirestoreOptions)
           (java.time Instant)))

(deftest firestore-log-store-round-trips-and-filters
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-logs-test")
                        (.setEmulatorHost host)
                        .build
                        .getService)
          store (gcp/firestore-store firestore)]
      (try
        (.get (.recursiveDelete firestore
                                (.collection firestore gcp/collection-name)))
        (logs/append-log! store
                          (assoc (logs/entry {:severity "INFO"
                                              :component "api"
                                              :event "older"})
                                 :created-at
                                 (Instant/parse "2026-07-18T10:00:00Z")))
        (logs/append-log! store
                          (assoc (logs/entry {:severity "ERROR"
                                              :component "renderer"
                                              :event "newer"})
                                 :created-at
                                 (Instant/parse "2026-07-18T10:01:00Z")))
        (is (= ["newer"]
               (mapv #(get-in % [:fields :event])
                     (logs/list-logs store {:severity "ERROR"}))))
        (is (string? (:raw (first (logs/list-logs store {})))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
