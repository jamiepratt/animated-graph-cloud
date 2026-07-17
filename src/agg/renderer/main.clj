(ns agg.renderer.main
  (:require [agg.contracts.render :as contract]
            [agg.auth.gcp :as auth-gcp]
            [agg.drive.core :as drive]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.render.audio :as audio]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.render.profile :as profile]
            [agg.render.spec :as spec]
            [agg.render.storage :as storage]
            [clojure.data.json :as json])
  (:gen-class)
  (:import (java.nio.file Files OpenOption Path StandardOpenOption)
           (com.google.cloud.firestore FirestoreOptions)
           (java.security MessageDigest)
           (java.util HexFormat)))

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
   {:keys [media video-encoder frame-renderer]}]
  (let [video-encoder (or video-encoder media)
        frame-renderer (or frame-renderer frames/java2d-frame-renderer)
        audio-path (Files/createTempFile "agg-audio-" ".wav"
                                         (make-array java.nio.file.attribute.FileAttribute 0))
        started (System/nanoTime)
        frame-result (atom nil)
        memory-sampler (when profile? (profile/start-memory-sampler))
        recording (when profile? (profile/start-recording))]
    (try
      (with-open [audio-output (Files/newOutputStream audio-path (make-array OpenOption 0))]
        (audio/write-wav! render-spec audio-output))
      (let [encode-result (media/encode! video-encoder
                                         render-spec
                                         audio-path
                                         output-path
                                         (fn [output]
                                           (reset! frame-result
                                                   (frames/stream-frames!
                                                    frame-renderer
                                                    render-spec
                                                    output))))
            verified (media/verify! video-encoder render-spec output-path)
            wall-seconds (/ (- (System/nanoTime) started) 1000000000.0)
            frame-count (:frame-count @frame-result)
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
         :media verified})
      (finally
        (when recording
          (try
            (.close recording)
            (catch Throwable _)))
        (when memory-sampler
          ((:stop! memory-sampler)))
        (Files/deleteIfExists audio-path)))))

(defn- path [value]
  (when value
    (Path/of value (make-array String 0))))

(defn- option-map [args]
  (when (odd? (count args))
    (throw (ex-info "Renderer options require flag/value pairs"
                    {:type ::invalid-options})))
  (into {} (map vec (partition 2 args))))

(defn parse-options [args]
  (let [options (option-map args)
        preset-id (get options "--preset")
        preset (spec/preset preset-id)
        duration-option (get options "--duration-seconds")
        duration (when duration-option
                   (or (parse-long duration-option)
                       (throw (ex-info "Duration must be an integer"
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
    {:keys [media video-encoder artifact-store]}]
   (when (not= (some? bucket) (some? object-prefix))
     (throw (ex-info "Bucket and object prefix must be supplied together"
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
       (let [result (render! request {:video-encoder video-encoder})
             report-json (json/write-str (dissoc result :objects))]
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
                        {:media (storage/upload! artifact-store
                                                 :media
                                                 output-path
                                                 "video/quicktime")
                         :profile (when jfr-path
                                    (storage/upload! artifact-store
                                                     :profile
                                                     jfr-path
                                                     "application/octet-stream"))
                         :report (storage/upload! artifact-store
                                                  :report
                                                  report-path
                                                  "application/json")})
                 result)]
           (reset! completed? true)
           completed-result))
       (finally
         (when (and delete-local? @completed?)
           (doseq [candidate [output-path jfr-path report-path]
                   :when candidate]
             (Files/deleteIfExists candidate))))))))

(defrecord CloudRenderWorker [bucket drive-delivery render-cloud!]
  jobs/RenderWorker
  (perform-render! [_ job-id request]
    (let [subject (:requesterSubject request)
          render-spec (contract/prepare request)
          output-path (Files/createTempFile
                       "agg-cloud-output-" ".mov"
                       (make-array java.nio.file.attribute.FileAttribute 0))
          report-path (Files/createTempFile
                       "agg-cloud-report-" ".json"
                       (make-array java.nio.file.attribute.FileAttribute 0))
          object-prefix (str "jobs/" job-id "/output-"
                             (java.util.UUID/randomUUID))]
      (try
        (let [result
              ((or render-cloud! run-job!)
               (assoc render-spec
                      :output-path output-path
                      :report-path report-path
                      :profile? false
                      :delete-local? false)
               {:artifact-store (storage/gcs-store bucket object-prefix)})
              delivered
              (when drive-delivery
                (when-not subject
                  (throw (ex-info "Drive delivery requires a requester"
                                  {:type ::missing-requester})))
                (drive/deliver-output! drive-delivery job-id subject output-path))]
          (cond-> {:output-bytes (:output-bytes result)
                   :object (get-in result [:objects :media :object])
                   :sha256 (:sha256 result)
                   :contentType "video/quicktime"}
            delivered (assoc :driveFileId (:fileId delivered)
                             :driveWebViewLink (:webViewLink delivered))))
        (finally
          (Files/deleteIfExists output-path)
          (Files/deleteIfExists report-path))))))

(defn run-cloud-job! [job-id]
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
            (get (System/getenv) "AGG_OAUTH_CLIENT_CREDENTIALS")}))]
    (jobs/run-job! (gcp/renderer-job-service
                    (->CloudRenderWorker bucket drive-delivery run-job!))
                   job-id)))

(defn -main [& args]
  (if (= "--job-id" (first args))
    (try
      (run-cloud-job! (second args))
      (println (str "{\"severity\":\"INFO\","
                    "\"component\":\"renderer\","
                    "\"event\":\"cloud_render_complete\","
                    "\"message\":\"Cloud renderer job completed\"}"))
      (catch Throwable _
        (println (str "{\"severity\":\"ERROR\","
                      "\"component\":\"renderer\","
                      "\"event\":\"cloud_render_failed\","
                      "\"message\":\"Cloud renderer job failed\"}"))
        (System/exit 1)))
    (if (empty? args)
      (println (str "{\"severity\":\"INFO\","
                    "\"component\":\"renderer\","
                    "\"event\":\"smoke_complete\","
                    "\"message\":\"Renderer smoke job completed\"}"))
      (try
        (run-job! (parse-options args))
        (println (str "{\"severity\":\"INFO\","
                      "\"component\":\"renderer\","
                      "\"event\":\"render_complete\","
                      "\"message\":\"Renderer job completed\"}"))
        (catch Throwable _
          (println (str "{\"severity\":\"ERROR\","
                        "\"component\":\"renderer\","
                        "\"event\":\"render_failed\","
                        "\"message\":\"Renderer job failed\"}"))
          (System/exit 1))))))
