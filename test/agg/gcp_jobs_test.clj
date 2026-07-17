(ns agg.gcp-jobs-test
  (:require [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.jobs-test :as fixture]
            [clojure.test :refer [deftest is]])
  (:import (com.google.api.gax.rpc AlreadyExistsException StatusCode StatusCode$Code)
           (com.google.cloud.firestore FirestoreOptions)
           (com.google.cloud.storage StorageOptions)
           (java.time Clock)))

(deftest duplicate-task-delivery-is-idempotent
  (let [status (reify StatusCode
                 (getCode [_] StatusCode$Code/ALREADY_EXISTS)
                 (getTransportCode [_] nil))
        error (AlreadyExistsException. "duplicate task" nil status false)]
    (is (#'gcp/duplicate-task? error))))

(deftest cloud-run-override-replaces-args-without-an-invalid-clear-flag
  (let [request (#'gcp/run-job-request
                 "projects/test/locations/europe-central2/jobs/renderer"
                 "job-id")
        override (-> request .getOverrides (.getContainerOverrides 0))]
    (is (false? (.getClearArgs override)))
    (is (= ["clojure.main" "-m" "agg.renderer.main" "--job-id" "job-id"]
           (vec (.getArgsList override))))))

(deftest signed-upload-is-a-bounded-content-type-locked-v4-put
  (let [signer (reify com.google.auth.ServiceAccountSigner
                 (getAccount [_] "uploader@example.test")
                 (sign [_ _] (byte-array 256)))
        store (gcp/->GcsRequestStore
               (.getService (StorageOptions/getDefaultInstance))
               "temporary-test-bucket"
               signer)
        url (jobs/signed-upload store "uploads/request.json"
                                "application/json" 900)]
    (is (re-find #"X-Goog-Algorithm=GOOG4-RSA-SHA256" url))
    (is (re-find #"X-Goog-Expires=900" url))
    (is (re-find #"X-Goog-SignedHeaders=content-type%3Bhost" url))
    (is (= ::gcp/invalid-upload-expiry
           (try
             (jobs/signed-upload store "uploads/request.json"
                                 "application/json" 901)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest firestore-transactions-deduplicate-launch-and-capacity
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          enqueued (atom #{})
          launched (atom [])
          cancelled (atom [])
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ job-id attempt]
                    (swap! enqueued conj [job-id attempt])))
          launcher (reify jobs/JobLauncher
                     (launch-job! [_ job-id]
                       (let [execution (str "executions/" job-id)]
                         (swap! launched conj execution)
                         execution))
                     (cancel-execution! [_ execution]
                       (swap! cancelled conj execution)))
          worker (reify jobs/RenderWorker
                   (perform-render! [_ job-id _]
                     {:output-bytes 10
                      :object (str "jobs/" job-id "/output.mov")}))
          service (gcp/job-service {:firestore firestore
                                    :request-store request-store
                                    :queue queue
                                    :launcher launcher
                                    :worker worker
                                    :clock (Clock/systemUTC)})]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [submissions (->> (range 10)
                               (mapv (fn [_]
                                       (future
                                         (jobs/submit-job!
                                          service "emulator-idempotency"
                                          (fixture/render-request)))))
                               (mapv deref))
              job-id (get-in (first submissions) [:job :id])]
          (is (= 1 (count (set (map #(get-in % [:job :id]) submissions)))))
          (is (= #{[job-id 1]} @enqueued))
          (let [dispatches (->> (range 10)
                                (mapv (fn [_]
                                        (future (jobs/dispatch-job! service job-id))))
                                (mapv deref))]
            (is (= 1 (count (filter :started? dispatches))))
            (is (= 1 (count @launched))))
          (let [more-job-ids
                (mapv (fn [index]
                        (get-in (jobs/submit-job!
                                 service (str "emulator-capacity-" index)
                                 (fixture/render-request))
                                [:job :id]))
                      (range 5))]
            (doseq [id (take 4 more-job-ids)]
              (jobs/dispatch-job! service id))
            (is (= ::jobs/capacity-exhausted
                   (try
                     (jobs/dispatch-job! service (last more-job-ids))
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))

(deftest firestore-terminal-transitions-release-capacity-atomically
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-terminal-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          requests (atom {})
          request-store
          (reify jobs/RequestStore
            (save-request! [_ job-id request]
              (let [object (str "jobs/" job-id "/request.json")]
                (swap! requests assoc object request)
                object))
            (load-request [_ object] (get @requests object)))
          queue (reify jobs/JobQueue
                  (enqueue-job! [_ _job-id _attempt]))
          failing-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ _job-id]
              (throw (ex-info "forced launch failure" {})))
            (cancel-execution! [_ _execution]))
          successful-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ job-id] (str "executions/" job-id))
            (cancel-execution! [_ _execution]))
          launch-started (promise)
          allow-launch (promise)
          cancelled-executions (atom [])
          blocking-launcher
          (reify jobs/JobLauncher
            (launch-job! [_ job-id]
              (deliver launch-started true)
              @allow-launch
              (str "executions/" job-id))
            (cancel-execution! [_ execution]
              (swap! cancelled-executions conj execution)))
          worker (reify jobs/RenderWorker
                   (perform-render! [_ job-id _request]
                     {:output-bytes 10
                      :object (str "jobs/" job-id "/output.mov")}))
          worker-started (promise)
          allow-worker (promise)
          blocking-worker
          (reify jobs/RenderWorker
            (perform-render! [_ job-id _request]
              (deliver worker-started true)
              @allow-worker
              {:output-bytes 10
               :object (str "jobs/" job-id "/output.mov")}))
          service (fn [launcher]
                    (gcp/job-service {:firestore firestore
                                      :request-store request-store
                                      :queue queue
                                      :launcher launcher
                                      :worker worker
                                      :clock (Clock/systemUTC)}))]
      (try
        (doseq [collection ["jobs" "job-idempotency" "orchestration"]]
          (.get (.recursiveDelete firestore (.collection firestore collection))))
        (let [job-id (get-in (jobs/submit-job!
                              (service failing-launcher)
                              "terminal-launch-failure"
                              (fixture/render-request))
                             [:job :id])]
          (try
            (jobs/dispatch-job! (service failing-launcher) job-id)
            (catch clojure.lang.ExceptionInfo _))
          (is (= "launch_failed"
                 (:failureCode
                  (jobs/get-job (service failing-launcher) job-id)))))
        (let [render-service (service successful-launcher)
              job-id (get-in (jobs/submit-job!
                              render-service
                              "terminal-worker-success"
                              (fixture/render-request))
                             [:job :id])]
          (jobs/dispatch-job! render-service job-id)
          (is (= "succeeded" (:state (jobs/run-job! render-service job-id)))))
        (let [race-service (service blocking-launcher)
              job-id (get-in (jobs/submit-job!
                              race-service
                              "cancel-during-launch"
                              (fixture/render-request))
                             [:job :id])
              dispatch (future (jobs/dispatch-job! race-service job-id))]
          @launch-started
          (is (= "cancellation-requested"
                 (:state (jobs/cancel-job! race-service job-id))))
          (deliver allow-launch true)
          @dispatch
          (is (= "cancelled" (:state (jobs/get-job race-service job-id))))
          (is (= [(str "executions/" job-id)] @cancelled-executions)))
        (let [worker-race-service
              (gcp/job-service {:firestore firestore
                                :request-store request-store
                                :queue queue
                                :launcher successful-launcher
                                :worker blocking-worker
                                :clock (Clock/systemUTC)})
              job-id (get-in (jobs/submit-job!
                              worker-race-service
                              "cancel-during-worker-completion"
                              (fixture/render-request))
                             [:job :id])]
          (jobs/dispatch-job! worker-race-service job-id)
          (let [render-result (future
                                (jobs/run-job! worker-race-service job-id))]
            @worker-started
            (is (= "cancelled"
                   (:state (jobs/cancel-job! worker-race-service job-id))))
            (deliver allow-worker true)
            (is (= "cancelled" (:state @render-result)))
            (is (= "cancelled"
                   (:state (jobs/get-job worker-race-service job-id))))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
