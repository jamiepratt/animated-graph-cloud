(ns agg.api.main
  (:require [agg.contracts.render :as contract]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json])
  (:gen-class)
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.time Instant)
           (java.util UUID)))

(def ^:private health-body "{\"status\":\"ok\"}")

(def max-request-bytes (+ contract/max-telemetry-bytes (* 64 1024)))

(defn- respond-bytes! [^HttpExchange exchange status content-type bytes]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" content-type))
  (.sendResponseHeaders exchange status (alength ^bytes bytes))
  (with-open [response-body (.getResponseBody exchange)]
    (.write response-body ^bytes bytes)))

(defn- respond! [exchange status content-type body]
  (respond-bytes! exchange
                  status
                  content-type
                  (.getBytes ^String body StandardCharsets/UTF_8)))

(defn- respond-json! [exchange status body]
  (respond! exchange status "application/json; charset=utf-8"
            (json/write-str body)))

(defn- respond-path! [^HttpExchange exchange content-type ^Path path]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" content-type))
  (.sendResponseHeaders exchange 200 (Files/size path))
  (with-open [input (Files/newInputStream path (make-array OpenOption 0))
              response-body (.getResponseBody exchange)]
    (.transferTo input response-body)))

(defn- request-json [^HttpExchange exchange]
  (let [bytes (with-open [input (.getRequestBody exchange)]
                (.readNBytes input (inc max-request-bytes)))]
    (when (> (alength bytes) max-request-bytes)
      (throw (ex-info "Request exceeds the size limit"
                      {:type ::request-too-large})))
    (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword)))

(defn- request-render-spec [exchange]
  (try
    (contract/prepare (request-json exchange))
    (catch clojure.lang.ExceptionInfo error
      (if (= ::request-too-large (:type (ex-data error)))
        (throw error)
        (throw (ex-info "Invalid render request"
                        {:type ::invalid-request}
                        error))))
    (catch Throwable error
      (throw (ex-info "Invalid render request"
                      {:type ::invalid-request}
                      error)))))

(defn- preview! [exchange frame-renderer]
  (let [render-spec (request-render-spec exchange)
        output (ByteArrayOutputStream.)]
    (frames/render-preview! frame-renderer render-spec output)
    (respond-bytes! exchange 200 "image/png" (.toByteArray output))))

(defn- overlay! [exchange frame-renderer video-encoder]
  (let [render-spec (request-render-spec exchange)
        output-path (Files/createTempFile
                     "agg-overlay-"
                     ".mov"
                     (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (renderer/render! (assoc render-spec
                               :output-path output-path
                               :profile? false)
                        {:frame-renderer frame-renderer
                         :video-encoder video-encoder})
      (respond-path! exchange "video/quicktime" output-path)
      (finally
        (Files/deleteIfExists output-path)))))

(defn- submit-job! [^HttpExchange exchange job-service]
  (let [idempotency-key (some-> exchange .getRequestHeaders
                                (.getFirst "Idempotency-Key"))
        {:keys [created? job]}
        (jobs/submit-job! job-service idempotency-key (request-json exchange))]
    (respond-json! exchange (if created? 202 200) job)))

(defn- poll-job! [exchange job-service job-id]
  (if-let [job (jobs/get-job job-service job-id)]
    (respond-json! exchange 200 job)
    (respond-json! exchange 404 {:error "job_not_found"})))

(defn- dispatch-job! [^HttpExchange exchange job-service job-id]
  (if-not (some-> exchange .getRequestHeaders
                  (.getFirst "X-CloudTasks-TaskName") not-empty)
    (respond-json! exchange 401 {:error "authenticated_task_required"})
    (let [{:keys [started? job]} (jobs/dispatch-job! job-service job-id)]
      (respond-json! exchange (if started? 202 200) job))))

(defn- cancel-job! [exchange job-service job-id]
  (respond-json! exchange 200 (jobs/cancel-job! job-service job-id)))

(defn- retry-job! [exchange job-service job-id]
  (respond-json! exchange 202 (jobs/retry-job! job-service job-id)))

(defn- issue-upload! [exchange upload-signer]
  (let [{:keys [contentType contentLength]} (request-json exchange)]
    (when-not (and (= "application/json" contentType)
                   (integer? contentLength)
                   (<= 1 contentLength max-request-bytes))
      (throw (ex-info "Invalid upload request"
                      {:type ::invalid-upload-request})))
    (let [upload-id (str (UUID/randomUUID))
          object-name (str "uploads/" upload-id "/request.json")
          expires-at (.plusSeconds (Instant/now) 900)]
      (respond-json!
       exchange 201
       {:id upload-id
        :method "PUT"
        :contentType contentType
        :contentLength contentLength
        :expiresAt (str expires-at)
        :uploadUrl (jobs/signed-upload upload-signer object-name
                                       contentType 900)}))))

(defn- route-handler [{:keys [frame-renderer video-encoder job-service
                              upload-signer]}]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (some-> exchange .getRequestURI .getPath)]
        (try
          (cond
            (and (= "GET" method) (= "/health" path))
            (respond! exchange 200 "application/json; charset=utf-8" health-body)

            (and (= "POST" method) (= "/v1/preview" path))
            (preview! exchange frame-renderer)

            (and (= "POST" method) (= "/v1/overlay" path))
            (overlay! exchange frame-renderer video-encoder)

            (and job-service (= "POST" method) (= "/v1/jobs" path))
            (submit-job! exchange job-service)

            (and upload-signer (= "POST" method) (= "/v1/uploads" path))
            (issue-upload! exchange upload-signer)

            (and job-service (= "GET" method)
                 (re-matches #"/v1/jobs/[^/]+" path))
            (poll-job! exchange job-service (last (.split path "/")))

            (and job-service (= "POST" method)
                 (re-matches #"/internal/v1/jobs/[^/]+/dispatch" path))
            (dispatch-job! exchange job-service
                           (nth (.split path "/") 4))

            (and job-service (= "POST" method)
                 (re-matches #"/v1/jobs/[^/]+/cancel" path))
            (cancel-job! exchange job-service (nth (.split path "/") 3))

            (and job-service (= "POST" method)
                 (re-matches #"/v1/jobs/[^/]+/retry" path))
            (retry-job! exchange job-service (nth (.split path "/") 3))

            :else
            (respond! exchange 404 "application/json; charset=utf-8"
                      "{\"error\":\"not_found\"}"))
          (catch clojure.lang.ExceptionInfo error
            (case (:type (ex-data error))
              ::request-too-large
              (respond! exchange 413 "application/json; charset=utf-8"
                        "{\"error\":\"payload_too_large\"}")

              ::invalid-request
              (respond! exchange 400 "application/json; charset=utf-8"
                        "{\"error\":\"invalid_request\"}")

              ::invalid-upload-request
              (respond-json! exchange 400 {:error "invalid_upload_request"})

              ::jobs/invalid-idempotency-key
              (respond-json! exchange 400 {:error "invalid_idempotency_key"})

              ::jobs/idempotency-conflict
              (respond-json! exchange 409 {:error "idempotency_conflict"})

              ::jobs/job-not-found
              (respond-json! exchange 404 {:error "job_not_found"})

              ::jobs/capacity-exhausted
              (respond-json! exchange 503 {:error "capacity_exhausted"})

              ::jobs/launch-failed
              (respond-json! exchange 502 {:error "launch_failed"})

              ::jobs/invalid-transition
              (respond-json! exchange 409 {:error "invalid_job_transition"})

              (respond! exchange 500 "application/json; charset=utf-8"
                        "{\"error\":\"render_failed\"}")))
          (catch Throwable _
            (respond! exchange 500 "application/json; charset=utf-8"
                      "{\"error\":\"render_failed\"}")))))))

(defn start!
  ([port]
   (start! port {}))
  ([port dependencies]
   (let [dependencies (merge {:frame-renderer frames/java2d-frame-renderer
                              :video-encoder (media/ffmpeg-video-encoder)}
                             dependencies)
         server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (route-handler dependencies))
     (.start server)
     (reify java.lang.AutoCloseable
       (close [_]
         (.stop server 0))))))

(defn -main [& _]
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))
        dependencies (if (= "true" (get (System/getenv)
                                        "AGG_JOB_LIFECYCLE_ENABLED"))
                       (gcp/api-system)
                       {})]
    (start! port dependencies)
    (println (str "{\"severity\":\"INFO\","
                  "\"component\":\"api\","
                  "\"event\":\"server_started\","
                  "\"message\":\"API server started\","
                  "\"port\":" port "}"))))
