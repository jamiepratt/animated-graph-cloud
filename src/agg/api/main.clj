(ns agg.api.main
  (:require [agg.contracts.render :as contract]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json])
  (:gen-class)
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)))

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

(defn- route-handler [{:keys [frame-renderer video-encoder]}]
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
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))]
    (start! port)
    (println (str "{\"severity\":\"INFO\","
                  "\"component\":\"api\","
                  "\"event\":\"server_started\","
                  "\"message\":\"API server started\","
                  "\"port\":" port "}"))))
