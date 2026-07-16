(ns agg.api.main
  (:gen-class)
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def ^:private health-body "{\"status\":\"ok\"}")

(defn- respond! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" content-type))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [response-body (.getResponseBody exchange)]
      (.write response-body bytes))))

(defn- health-handler []
  (reify HttpHandler
    (handle [_ exchange]
      (if (and (= "GET" (.getRequestMethod exchange))
               (= "/health" (some-> exchange .getRequestURI .getPath)))
        (respond! exchange 200 "application/json; charset=utf-8" health-body)
        (respond! exchange 404 "application/json; charset=utf-8"
                  "{\"error\":\"not_found\"}")))))

(defn start! [port]
  (let [server (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext server "/" (health-handler))
    (.start server)
    (reify java.lang.AutoCloseable
      (close [_]
        (.stop server 0)))))

(defn -main [& _]
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))]
    (start! port)
    (println (str "{\"severity\":\"INFO\","
                  "\"component\":\"api\","
                  "\"event\":\"server_started\","
                  "\"message\":\"API server started\","
                  "\"port\":" port "}"))))
