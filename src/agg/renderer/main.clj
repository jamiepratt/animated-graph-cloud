(ns agg.renderer.main
  (:require [agg.errors :as errors]
            [agg.contracts.render :as contract]
            [agg.auth.core :as auth]
            [agg.auth.gcp :as auth-gcp]
            [agg.drive.core :as drive]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.logs.core :as logs]
            [agg.logs.gcp :as logs-gcp]
            [agg.observability :as observability]
            [agg.render.audio :as audio]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.render.profile :as profile]
            [agg.render.spec :as spec]
            [agg.render.storage :as storage]
            [clojure.data.json :as json]
            [taoensso.truss :as truss]
            [taoensso.tufte :as tufte])
  (:gen-class)
  (:import (java.nio.file Files OpenOption Path StandardOpenOption)
           (com.google.cloud.firestore FirestoreOptions)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(defn- sha256 [^Path path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (Files/newInputStream path (make-array OpenOption 0))]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn render!
  "Runs one bounded render and returns its validated media report."
  [{:keys [output-path profile? jfr-path] :as render-spec}
   {:keys [media video-encoder frame-renderer source-stream!]}]
  (let [video-encoder (or video-encoder media)
        frame-renderer (or frame-renderer frames/java2d-frame-renderer)
        compositing? (some? (:source-video render-spec))
        source-stream!
        (when source-stream!
          (fn [output]
            (jobs/with-durable-stage
              "source_content"
              #(source-stream! output))))
        audio-path (Files/createTempFile "agg-audio-" ".wav"
                                         (make-array java.nio.file.attribute.FileAttribute 0))
        started (System/nanoTime)
        frame-result (atom nil)
        memory-sampler (when profile? (profile/start-memory-sampler))
        recording (when profile? (profile/start-recording))]
    (tufte/profile {:id ::render}
                   (try
                     (jobs/with-durable-stage
                       "overlay_render"
                       #(tufte/p ::audio-generation
                                 (with-open [audio-output
                                             (Files/newOutputStream
                                              audio-path
                                              (make-array OpenOption 0))]
                                   (audio/write-wav! render-spec audio-output))))
                     (let [write-overlay!
                           (fn [output]
                             (jobs/with-durable-stage
                               "overlay_render"
                               #(reset! frame-result
                                        (tufte/p
                                         ::frame-streaming
                                         (frames/stream-frames!
                                          frame-renderer render-spec output)))))
                           encode-result
                           (jobs/with-durable-stage
                             "composition_encode"
                             #(tufte/p
                               ::encoding
                               (if compositing?
                                 (do
                                   (when-not (and source-stream!
                                                  (satisfies?
                                                   media/CompositeEncoder
                                                   video-encoder))
                                     (throw (errors/raise!
                                             "Video compositing dependencies are incomplete"
                                             {:type ::missing-compositing-dependencies})))
                                   (media/encode-composite! video-encoder
                                                            render-spec
                                                            audio-path
                                                            output-path
                                                            source-stream!
                                                            write-overlay!))
                                 (media/encode! video-encoder
                                                render-spec
                                                audio-path
                                                output-path
                                                write-overlay!))))
                           verified
                           (jobs/with-durable-stage
                             "composition_encode"
                             #(tufte/p ::verification
                                       (if compositing?
                                         (media/verify-composite! video-encoder
                                                                  render-spec
                                                                  output-path)
                                         (media/verify! video-encoder
                                                        render-spec
                                                        output-path))))
                           wall-seconds (/ (- (System/nanoTime) started) 1000000000.0)
                           frame-count (truss/have! pos?
                                                    (:frame-count @frame-result))
                           jfr-summary (when recording
                                         (profile/finish-recording! recording jfr-path))
                           peak-memory (when memory-sampler
                                         ((:stop! memory-sampler)))]
                       {:preset (:id render-spec)
                        :width (:width render-spec)
                        :height (:height render-spec)
                        :fps (:fps render-spec)
                        :duration-seconds (:duration-seconds render-spec)
                        :frame-count frame-count
                        :effective-fps (/ frame-count wall-seconds)
                        :wall-seconds wall-seconds
                        :ffmpeg-exit-status (:exit-status encode-result)
                        :output-bytes (Files/size output-path)
                        :sha256 (sha256 output-path)
                        :peak-cgroup-memory-bytes peak-memory
                        :jfr jfr-summary
                        :content-type (if compositing?
                                        (if (= "prores-422-mov" (:output-format render-spec))
                                          "video/quicktime"
                                          "video/mp4")
                                        "video/quicktime")
                        :media verified})
                     (finally
                       (when recording
                         (try
                           (.close recording)
                           (catch Throwable _)))
                       (when memory-sampler
                         ((:stop! memory-sampler)))
                       (Files/deleteIfExists audio-path))))))

(defn- path [value]
  (when value
    (Path/of value (make-array String 0))))

(defn- option-map [args]
  (when (odd? (count args))
    (throw (errors/raise! "Renderer options require flag/value pairs"
                          {:type ::invalid-options})))
  (into {} (map vec (partition 2 args))))

(defn parse-cloud-options [args]
  (let [options (option-map args)]
    (when-not (and (= 2 (count options))
                   (= #{"--job-id" "--attempt"} (set (keys options))))
      (throw (errors/raise! "Cloud renderer options are invalid"
                            {:type ::invalid-cloud-options})))
    (let [job-id (try
                   (str (UUID/fromString (get options "--job-id")))
                   (catch IllegalArgumentException _
                     (throw (errors/raise! "Cloud renderer job ID is invalid"
                                           {:type ::invalid-cloud-options}))))
          attempt (parse-long (get options "--attempt"))]
      (when-not (and (integer? attempt) (pos? attempt))
        (throw (errors/raise! "Cloud renderer attempt is invalid"
                              {:type ::invalid-cloud-options})))
      {:job-id job-id :attempt attempt})))

(defn parse-options [args]
  (let [options (option-map args)
        preset-id (get options "--preset")
        preset (spec/preset preset-id)
        duration-option (get options "--duration-seconds")
        duration (when duration-option
                   (or (parse-long duration-option)
                       (throw (errors/raise! "Duration must be an integer"
                                             {:type ::invalid-duration}))))
        output-option (get options "--output")
        output-path (or (path output-option)
                        (Files/createTempFile "agg-render-" ".mov"
                                              (make-array java.nio.file.attribute.FileAttribute 0)))
        profile? (not= "false" (get options "--profile" "true"))
        report-path (or (path (get options "--report"))
                        (if output-option
                          (path (str output-option ".json"))
                          (Files/createTempFile "agg-report-" ".json"
                                                (make-array java.nio.file.attribute.FileAttribute 0))))
        jfr-path (when profile?
                   (or (path (get options "--jfr"))
                       (if output-option
                         (path (str output-option ".jfr"))
                         (Files/createTempFile "agg-profile-" ".jfr"
                                               (make-array java.nio.file.attribute.FileAttribute 0)))))]
    (cond-> (assoc (if duration (spec/with-duration preset duration) preset)
                   :output-path output-path
                   :report-path report-path
                   :jfr-path jfr-path
                   :profile? profile?
                   :delete-local? (nil? output-option)
                   :ffmpeg (get options "--ffmpeg" "ffmpeg")
                   :ffprobe (get options "--ffprobe" "ffprobe"))
      (get options "--bucket")
      (assoc :bucket (get options "--bucket"))

      (get options "--object-prefix")
      (assoc :object-prefix (get options "--object-prefix")))))

(defn run-job!
  ([request]
   (run-job! request {}))
  ([{:keys [output-path report-path jfr-path bucket object-prefix delete-local?
            ffmpeg ffprobe]
     :as request}
    {:keys [media video-encoder artifact-store source-stream!]}]
   (when (not= (some? bucket) (some? object-prefix))
     (throw (errors/raise! "Bucket and object prefix must be supplied together"
                           {:type ::invalid-upload-options})))
   (let [video-encoder (or video-encoder
                           media
                           (if (or ffmpeg ffprobe)
                             (media/ffmpeg-video-encoder
                              (or ffmpeg "ffmpeg")
                              (or ffprobe "ffprobe"))
                             (media/ffmpeg-video-encoder)))
         artifact-store (or artifact-store
                            (when bucket (storage/gcs-store bucket object-prefix)))
         completed? (atom false)]
     (try
       (let [result (render! request {:video-encoder video-encoder
                                      :source-stream! source-stream!})]
         (jobs/with-durable-stage
           "artifact_upload"
           #(let [report-json (json/write-str (dissoc result :objects))]
              (Files/writeString report-path
                                 report-json
                                 (into-array OpenOption
                                             [StandardOpenOption/CREATE
                                              StandardOpenOption/TRUNCATE_EXISTING
                                              StandardOpenOption/WRITE]))
              (let [completed-result
                    (if artifact-store
                      (assoc result
                             :objects
                             {:media (tufte/p
                                      ::uploads
                                      (storage/upload!
                                       artifact-store :media output-path
                                       (or (:content-type result)
                                           "video/quicktime")))
                              :profile (when jfr-path
                                         (tufte/p
                                          ::uploads
                                          (storage/upload!
                                           artifact-store :profile jfr-path
                                           "application/octet-stream")))
                              :report (tufte/p
                                       ::uploads
                                       (storage/upload!
                                        artifact-store :report report-path
                                        "application/json"))})
                      result)]
                (reset! completed? true)
                completed-result))))
       (finally
         (when (and delete-local? @completed?)
           (doseq [candidate [output-path jfr-path report-path]
                   :when candidate]
             (Files/deleteIfExists candidate))))))))

(defrecord CloudRenderWorker [bucket drive-delivery render-cloud!]
  jobs/RenderWorker
  (perform-render! [_ job-id request]
    (tufte/profile {:id ::cloud-render}
                   (let [subject (:requesterSubject request)
                         render-spec
                         (jobs/with-durable-stage
                           "request_prepare"
                           #(tufte/p ::telemetry-parsing (contract/prepare request)))
                         output-suffix (if (= "h264-mp4"
                                              (:output-format render-spec))
                                         ".mp4"
                                         ".mov")
                         output-path
                         (jobs/with-durable-stage
                           "request_prepare"
                           #(Files/createTempFile
                             "agg-cloud-output-" output-suffix
                             (make-array
                              java.nio.file.attribute.FileAttribute 0)))
                         report-path
                         (jobs/with-durable-stage
                           "request_prepare"
                           #(Files/createTempFile
                             "agg-cloud-report-" ".json"
                             (make-array
                              java.nio.file.attribute.FileAttribute 0)))
                         object-prefix (str "jobs/" job-id "/output-"
                                            (java.util.UUID/randomUUID))]
                     (try
                       (let [result
                             (jobs/with-durable-stage
                               "composition_encode"
                               #((or render-cloud! run-job!)
                                 (assoc render-spec
                                        :output-path output-path
                                        :report-path report-path
                                        :profile? false
                                        :delete-local? false)
                                 {:artifact-store
                                  (storage/gcs-store bucket object-prefix)}))
                             delivered
                             (when drive-delivery
                               (jobs/with-durable-stage
                                 "drive_delivery"
                                 #(do
                                    (when-not subject
                                      (throw (errors/raise!
                                              "Drive delivery requires a requester"
                                              {:type ::missing-requester})))
                                    (tufte/p
                                     ::drive-delivery
                                     (drive/deliver-output! drive-delivery
                                                            job-id
                                                            subject
                                                            output-path)))))]
                         (cond-> {:output-bytes (:output-bytes result)
                                  :object (get-in result [:objects :media :object])
                                  :sha256 (:sha256 result)
                                  :contentType (:content-type result "video/quicktime")}
                           delivered (assoc :driveFileId (:fileId delivered)
                                            :driveWebViewLink (:webViewLink delivered))))
                       (finally
                         (jobs/with-durable-stage
                           "artifact_upload"
                           #(do
                              (Files/deleteIfExists output-path)
                              (Files/deleteIfExists report-path)))))))))

(defn require-cloud-success! [result]
  (when (= "failed" (:state result))
    (throw (errors/raise! "Cloud renderer recorded a durable failure"
                          (cond-> {:type ::cloud-job-failed
                                   :failure-code (:failureCode result)
                                   :retryable (true? (:retryable result))
                                   :attempt (:attempt result)
                                   :elapsed-ms (:elapsedMs result)}
                            (:stage result) (assoc :stage (:stage result))
                            (:status result) (assoc :status (:status result))))))
  result)

(defn run-cloud-job! [job-id attempt]
  (let [project (get (System/getenv) "GOOGLE_CLOUD_PROJECT"
                     "animated-graph-cloud-jp")
        region (get (System/getenv) "AGG_REGION" "europe-central2")
        bucket (get (System/getenv) "AGG_TEMPORARY_BUCKET"
                    (str project "-temporary"))
        drive-delivery
        (when (= "true" (get (System/getenv) "AGG_DRIVE_DELIVERY_ENABLED"))
          (auth-gcp/renderer-delivery
           {:firestore (.getService (FirestoreOptions/getDefaultInstance))
            :project project
            :region region
            :oauth-client-credentials
            (get (System/getenv) "AGG_OAUTH_CLIENT_CREDENTIALS")}))
        source-system
        (when (= "true" (get (System/getenv) "AGG_DRIVE_SOURCE_ENABLED" "true"))
          (auth-gcp/renderer-source
           {:firestore (.getService (FirestoreOptions/getDefaultInstance))
            :project project
            :region region
            :oauth-client-credentials
            (get (System/getenv) "AGG_OAUTH_CLIENT_CREDENTIALS")}))
        render-cloud!
        (fn [request dependencies]
          (let [source-stream!
                (when (and source-system (:source-video request))
                  (let [{:keys [access-token]}
                        (jobs/with-durable-stage
                          "source_content"
                          #((:access-provider source-system)
                            (:requesterSubject request)))
                        file-id (get-in request [:source-video :file-id])]
                    (fn [output]
                      (jobs/with-durable-stage
                        "source_content"
                        #(drive/stream-source! (:gateway source-system)
                                               access-token file-id output)))))]
            (run-job! request (assoc dependencies :source-stream! source-stream!))))]
    (require-cloud-success!
     (jobs/run-job-attempt!
      (gcp/renderer-job-service
       (->CloudRenderWorker bucket drive-delivery render-cloud!))
      job-id
      attempt))))

(defn cloud-failure-event [cause]
  (let [data (loop [current cause
                    result []]
               (if current
                 (recur (.getCause ^Throwable current)
                        (conj result (ex-data current)))
                 result))
        drive-reauthorization?
        (some #(= ::auth/drive-grant-required (:type %)) data)
        durable-diagnosis? (some :failure-code data)
        elapsed-ms (or (some :elapsed-ms data) 0)
        diagnosis (when durable-diagnosis?
                    (jobs/failure-diagnostics cause elapsed-ms))
        attempt (some #(let [value (:attempt %)]
                         (when (and (integer? value) (pos? value)) value))
                      data)
        diagnostic-fields
        (cond-> {}
          diagnosis (assoc :failureCode (:failure-code diagnosis)
                           :retryable (:retryable diagnosis)
                           :elapsedMs (:elapsed-ms diagnosis))
          (:stage diagnosis) (assoc :stage (:stage diagnosis))
          (:status diagnosis) (assoc :status (:status diagnosis))
          attempt (assoc :attempt attempt))]
    (merge
     (if drive-reauthorization?
       {:severity "ERROR"
        :component "renderer"
        :event "drive_reauthorization_required"
        :message "Google Drive reauthorization is required"}
       {:severity "ERROR"
        :component "renderer"
        :event "cloud_render_failed"
        :message "Cloud renderer job failed"})
     diagnostic-fields)))

(defn- configure-log-persistence! []
  (try
    (let [store (logs-gcp/firestore-store
                 (.getService (FirestoreOptions/getDefaultInstance)))]
      (observability/configure-persistence!
       #(logs/append-log! store %)))
    (catch Throwable _
      true)))

(defn -main [& args]
  (configure-log-persistence!)
  (if (some #{"--job-id"} args)
    (try
      (let [{:keys [job-id attempt]} (parse-cloud-options args)]
        (run-cloud-job! job-id attempt))
      (observability/emit-event! "renderer" "cloud_render_complete"
                                 {:message "Cloud renderer job completed"})
      (catch Throwable cause
        (observability/emit-event! (cloud-failure-event cause))
        (System/exit 1)))
    (if (empty? args)
      (observability/emit-event! "renderer" "smoke_complete"
                                 {:message "Renderer smoke job completed"})
      (try
        (run-job! (parse-options args))
        (observability/emit-event! "renderer" "render_complete"
                                   {:message "Renderer job completed"})
        (catch Throwable cause
          (observability/emit-error! "renderer" "render_failed" cause
                                     {:message "Renderer job failed"})
          (System/exit 1))))))
