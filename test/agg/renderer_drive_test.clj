(ns agg.renderer-drive-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.errors :as errors]
            [agg.jobs.lifecycle :as jobs]
            [agg.jobs-test :as fixture]
            [agg.observability :as observability]
            [agg.preview.core :as preview]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files OpenOption)))

(deftest cloud-render-delivers-the-local-mov-before-cleanup
  (let [delivered (atom [])
        delivery (reify drive/OutputDelivery
                   (deliver-output! [_ job-id subject path]
                     (swap! delivered conj {:job-id job-id
                                            :subject subject
                                            :bytes (Files/readString path)})
                     {:fileId "drive-output-1"
                      :webViewLink "https://drive.google.com/file/d/drive-output-1/view"}))
        render-cloud!
        (fn [{:keys [output-path]} _dependencies]
          (Files/writeString output-path "movie" (make-array OpenOption 0))
          {:output-bytes 5
           :sha256 (apply str (repeat 64 "a"))
           :objects {:media {:object "jobs/job-1/output.mov"}}})
        worker (renderer/->CloudRenderWorker "temporary-bucket" delivery
                                             render-cloud!)
        result (jobs/perform-render! worker "job-1"
                                     (assoc (fixture/render-request)
                                            :requesterSubject
                                            "google-subject-1"))]
    (is (= [{:job-id "job-1"
             :subject "google-subject-1"
             :bytes "movie"}]
           @delivered))
    (is (= "drive-output-1" (:driveFileId result)))
    (is (= "https://drive.google.com/file/d/drive-output-1/view"
           (:driveWebViewLink result)))
    (is (= "jobs/job-1/output.mov" (:object result)))))

(deftest durable-cloud-render-emits-only-bounded-stage-progress
  (let [events (atom [])
        delivery (reify drive/OutputDelivery
                   (deliver-output! [_ _job-id _subject _path]
                     {:fileId "drive-output-1"}))
        render-cloud!
        (fn [{:keys [output-path]} {:keys [progress!]}]
          (progress! "source_content")
          (progress! "overlay_render")
          (progress! "composition_encode")
          (Files/writeString output-path "movie" (make-array OpenOption 0))
          (progress! "artifact_upload")
          {:output-bytes 5
           :sha256 (apply str (repeat 64 "a"))
           :objects {:media {:object "jobs/job-1/output.mov"}}})
        worker (renderer/->CloudRenderWorker "temporary-bucket" delivery
                                             render-cloud!)]
    (with-redefs [observability/emit-event! #(swap! events conj %)]
      (jobs/perform-render! worker "private-job-id"
                            (assoc (fixture/render-request)
                                   :requesterSubject "private-subject")))
    (is (= ["request_prepare" "source_content" "overlay_render"
            "composition_encode" "artifact_upload" "drive_delivery"]
           (mapv :stage @events)))
    (is (every? #(= {:severity "INFO"
                     :component "renderer"
                     :event "cloud_render_progress"
                     :message "Cloud renderer job is active"}
                    (dissoc % :stage :elapsedMs))
                @events))
    (is (every? #(and (integer? (:elapsedMs %))
                      (not (neg? (:elapsedMs %))))
                @events))
    (is (not (re-find #"private|subject|job-id" (pr-str @events))))))

(deftest cloud-preview-worker-persists-a-gallery-without-durable-delivery
  (let [delivered (atom 0)
        delivery (reify drive/OutputDelivery
                   (deliver-output! [_ _ _ _]
                     (swap! delivered inc)))
        calls (atom [])
        render-cloud!
        (fn [render-spec dependencies]
          (swap! calls conj {:duration (:duration-seconds render-spec)
                             :spo2-count (count (:spo2 render-spec))
                             :operation-id (:preview-operation-id dependencies)
                             :request-kind
                             (get-in dependencies
                                     [:original-request :previewOperation])})
          {:output-bytes 42
           :version 1
           :mode "overlay"
           :sections []
           :assets []})
        worker (renderer/->CloudRenderWorker "temporary-bucket" delivery
                                             render-cloud!)
        result (jobs/perform-render!
                worker "preview-operation-1"
                (assoc (fixture/render-request)
                       :previewOperation jobs/preview-operation-version
                       :spo2 {:format "oxiwear-spo2-csv"
                              :telemetry
                              (str "reading_time,spo2\n"
                                   "2026-07-17T10:00:00Z,96\n"
                                   "2026-07-17T10:00:01Z,92\n"
                                   "2026-07-17T10:00:02Z,97\n")}
                       :requesterSubject "google-subject-1"))]
    (is (= 0 @delivered))
    (is (= [{:duration 2
             :spo2-count 3
             :operation-id "preview-operation-1"
             :request-kind jobs/preview-operation-version}]
           @calls))
    (is (= "overlay" (:mode result)))
    (is (= 42 (:output-bytes result)))))

(deftest partial-preview-emits-a-bounded-correlated-warning-event
  (let [operation-id "00000000-0000-0000-0000-000000000084"
        events (atom [])
        render-cloud!
        (fn [_render-spec _dependencies]
          {:output-bytes 42
           :version 1
           :mode "source-final"
           :sections []
           :assets []
           :warnings [{:reason "source_duration_too_short"
                       :requestId operation-id
                       :requestedMomentCount 4
                       :generatedMomentCount 3
                       :omittedMomentCount 1
                       :requestedDurationSeconds 20
                       :retryable false}]})
        worker (renderer/->CloudRenderWorker "temporary-bucket" nil
                                             render-cloud!)]
    (with-redefs [observability/emit-event! #(swap! events conj %)]
      (jobs/perform-render!
       worker operation-id
       (assoc (fixture/render-request)
              :previewOperation jobs/preview-operation-version)))
    (is (= [{:severity "WARNING"
             :component "renderer"
             :event "preview_partial_gallery"
             :message "Preview gallery has unavailable source frames"
             :requestId operation-id
             :reason "source_duration_too_short"
             :requestedMomentCount 4
             :generatedMomentCount 3
             :omittedMomentCount 1
             :requestedDurationSeconds 20
             :retryable false}]
           (filterv #(= "preview_partial_gallery" (:event %)) @events)))
    (is (not (re-find #"source-video|telemetry|filename|token"
                      (pr-str @events))))))

(deftest drive-reauthorization-has-a-data-free-operational-event
  (let [revoked (ex-info "secret-token filename.mov"
                         {:type ::auth/drive-grant-required
                          :access-token "secret-token"})
        wrapped (ex-info "worker failed" {:type ::jobs/worker-failed} revoked)
        event (renderer/cloud-failure-event wrapped)]
    (is (= {:severity "ERROR"
            :component "renderer"
            :event "drive_reauthorization_required"
            :message "Google Drive reauthorization is required"}
           event))
    (is (not (re-find #"secret-token|filename" (pr-str event))))
    (is (= "cloud_render_failed"
           (:event (renderer/cloud-failure-event
                    (ex-info "other failure" {})))))))

(deftest a-durable-failure-cannot-be-logged-as-a-completed-cloud-render
  (is (= {:state "succeeded"}
         (renderer/require-cloud-success! {:state "succeeded"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"durable failure"
                        (renderer/require-cloud-success!
                         {:state "failed"
                          :failureCode "output_too_large"}))))

(deftest durable-failure-event-matches-the-public-job-diagnosis
  (let [failed {:state "failed"
                :attempt 2
                :failureCode "composition_encode_failed"
                :stage "composition_encode"
                :status 422
                :retryable false
                :elapsedMs 9123
                :timeoutMs 2700000}
        cause (try
                (renderer/require-cloud-success! failed)
                (catch Throwable error error))]
    (is (= {:severity "ERROR"
            :component "renderer"
            :event "cloud_render_failed"
            :message "Cloud renderer job failed"
            :failureCode "composition_encode_failed"
            :stage "composition_encode"
            :status 422
            :retryable false
            :attempt 2
            :elapsedMs 9123
            :timeoutMs 2700000}
           (renderer/cloud-failure-event cause)))))

(deftest actual-worker-failure-event-keeps-the-validated-attempt
  (let [worker (reify jobs/RenderWorker
                 (perform-render! [_ _job-id _request]
                   (errors/raise! "private.mov secret-token"
                                  {:type ::composition-failed
                                   :failure-code
                                   "composition_encode_failed"
                                   :stage "composition_encode"
                                   :retryable false})))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        job-id (get-in (jobs/submit-job! service "actual-worker-failure"
                                         (fixture/render-request))
                       [:job :id])
        _ (jobs/dispatch-job! service job-id)
        cause (try
                (jobs/run-job-attempt! service job-id 1)
                (catch Throwable error error))
        event (renderer/cloud-failure-event cause)]
    (is (= 1 (:attempt event)))
    (is (= "composition_encode_failed" (:failureCode event)))
    (is (= "composition_encode" (:stage event)))
    (is (not (re-find #"private|secret|token"
                      (pr-str event))))
    (is (not (re-find (re-pattern job-id) (pr-str event))))))

(deftest zero-frame-preview-retains-bounded-api-and-operational-diagnostics
  (let [worker
        (reify jobs/RenderWorker
          (perform-render! [_ _job-id _request]
            (jobs/with-durable-stage
              "composition_encode"
              #(errors/raise!
                "private-source.mov ended before a requested preview frame"
                {:type ::source-duration-too-short
                 :reason "source_duration_too_short"
                 :limits {:requested-moment-count 4
                          :generated-moment-count 0
                          :omitted-moment-count 4
                          :requested-duration-seconds 20}
                 :retryable false}))))
        system (jobs/in-memory-system {:worker worker})
        service (:service system)
        job-id (get-in (jobs/submit-job!
                        service "zero-frame-preview"
                        (assoc (fixture/render-request)
                               :previewOperation jobs/preview-operation-version))
                       [:job :id])
        _ (jobs/dispatch-job! service job-id)
        cause (try
                (jobs/run-job-attempt! service job-id 1)
                (catch Throwable error error))
        failed (jobs/get-job service job-id)
        operation (preview/operation-resource failed)
        event (renderer/cloud-failure-event cause)]
    (is (= {:failureCode "composition_encode_failed"
            :stage "composition_encode"
            :reason "source_duration_too_short"
            :requestedMomentCount 4
            :generatedMomentCount 0
            :omittedMomentCount 4
            :requestedDurationSeconds 20
            :retryable false}
           (select-keys failed
                        [:failureCode :stage :reason :requestedMomentCount
                         :generatedMomentCount :omittedMomentCount
                         :requestedDurationSeconds :retryable])))
    (is (= {:code "composition_encode_failed"
            :category "preview_rendering"
            :requestId job-id
            :retryable false
            :stage "composition_encode"
            :reason "source_duration_too_short"
            :requestedMomentCount 4
            :generatedMomentCount 0
            :omittedMomentCount 4
            :requestedDurationSeconds 20}
           (dissoc (:error operation) :elapsedMs)))
    (is (nat-int? (get-in operation [:error :elapsedMs])))
    (is (= {:severity "ERROR"
            :component "renderer"
            :event "cloud_render_failed"
            :message "Cloud renderer job failed"
            :failureCode "composition_encode_failed"
            :stage "composition_encode"
            :reason "source_duration_too_short"
            :requestId job-id
            :requestedMomentCount 4
            :generatedMomentCount 0
            :omittedMomentCount 4
            :requestedDurationSeconds 20
            :retryable false
            :attempt 1}
           (dissoc event :elapsedMs)))
    (is (not (re-find #"private-source" (pr-str [failed operation event]))))))

(deftest invalid-source-duration-limits-are-not-published
  (let [cause
        (try
          (jobs/with-durable-stage
            "composition_encode"
            #(errors/raise!
              "private malformed limits"
              {:type ::source-duration-too-short
               :reason "source_duration_too_short"
               :limits {:requested-moment-count 100
                        :generated-moment-count 0
                        :omitted-moment-count 100
                        :requested-duration-seconds 481}
               :retryable false}))
          (catch Throwable error error))
        diagnostics (jobs/failure-diagnostics cause 1)]
    (is (= {:failure-code "composition_encode_failed"
            :stage "composition_encode"
            :reason "source_duration_too_short"
            :retryable false
            :elapsed-ms 1}
           diagnostics))
    (is (not (re-find #"private|100|481"
                      (pr-str diagnostics))))))

(deftest durable-render-stages-have-bounded-owned-failure-codes
  (doseq [[stage failure-code]
          [["request_load" "request_load_failed"]
           ["request_prepare" "request_prepare_failed"]
           ["source_content" "source_content_failed"]
           ["overlay_render" "overlay_render_failed"]
           ["composition_encode" "composition_encode_failed"]
           ["artifact_upload" "artifact_upload_failed"]
           ["drive_delivery" "drive_delivery_failed"]
           ["completion_persistence" "completion_persistence_failed"]]]
    (let [cause (try
                  (jobs/with-durable-stage
                    stage
                    #(errors/raise! "owned stage failure"
                                    {:type ::owned-stage-failure
                                     :status 503
                                     :retryable true}))
                  (catch Throwable error error))
          diagnostics (jobs/failure-diagnostics cause 17)]
      (is (= {:failure-code failure-code
              :stage stage
              :status 503
              :retryable true
              :elapsed-ms 17}
             diagnostics)))))

(deftest unknown-private-stage-failure-remains-sanitized
  (let [cause (try
                (jobs/with-durable-stage
                  "composition_encode"
                  #(throw (ex-info "private.mov secret-token"
                                   {:token "secret-token"}
                                   (RuntimeException. "private-file-id"))))
                (catch Throwable error error))
        diagnostics (jobs/failure-diagnostics cause 9)]
    (is (= {:failure-code "worker_failed"
            :stage "composition_encode"
            :retryable false
            :elapsed-ms 9}
           diagnostics))
    (is (not (re-find #"private|secret|token|file-id"
                      (pr-str diagnostics))))))

(deftest typed-drive-failures-keep-their-existing-classification
  (doseq [[type failure-code status]
          [[:agg.auth.core/drive-grant-required "drive_grant_required" nil]
           [:agg.drive.gcp/source-metadata-failed "source_metadata_failed" 404]
           [:agg.drive.gcp/source-download-failed "source_download_failed" 503]]]
    (let [cause (try
                  (jobs/with-durable-stage
                    "source_content"
                    #(errors/raise! "private Drive value"
                                    (cond-> {:type type :retryable false}
                                      status (assoc :status status))))
                  (catch Throwable error error))
          diagnostics (jobs/failure-diagnostics cause 1)]
      (is (= failure-code (:failure-code diagnostics)))
      (is (= "source_content" (:stage diagnostics)))
      (is (= status (:status diagnostics))))))

(defn- caught-diagnostics [operation]
  (jobs/failure-diagnostics
   (try
     (operation)
     (catch Throwable error error))
   1))

(deftest cloud-worker-identifies-request-preparation-failures
  (let [worker (renderer/->CloudRenderWorker
                "temporary-bucket"
                nil
                (fn [_ _]
                  (throw (AssertionError. "render must not start"))))]
    (is (= {:failure-code "request_prepare_failed"
            :stage "request_prepare"
            :retryable false
            :elapsed-ms 1}
           (caught-diagnostics
            #(jobs/perform-render! worker "job-1" {}))))))

(deftest cloud-worker-identifies-drive-delivery-failures
  (let [delivery (reify drive/OutputDelivery
                   (deliver-output! [_ _job-id _subject _path]
                     (errors/raise! "private Drive failure"
                                    {:type ::drive-delivery-failure
                                     :status 503
                                     :retryable true})))
        render-cloud!
        (fn [{:keys [output-path]} _]
          (Files/writeString output-path "movie" (make-array OpenOption 0))
          {:output-bytes 5
           :sha256 (apply str (repeat 64 "a"))
           :objects {:media {:object "jobs/job-1/output.mov"}}})
        worker (renderer/->CloudRenderWorker "temporary-bucket" delivery
                                             render-cloud!)]
    (is (= {:failure-code "drive_delivery_failed"
            :stage "drive_delivery"
            :status 503
            :retryable true
            :elapsed-ms 1}
           (caught-diagnostics
            #(jobs/perform-render! worker "job-1"
                                   (assoc (fixture/render-request)
                                          :requesterSubject
                                          "subject-1")))))))
