(ns agg.smoke-test
  (:require [agg.api.main :as api]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is]])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.net ServerSocket)))

(defn- available-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest api-health-is-served-over-http
  (let [port (available-port)
        server (api/start! port)]
    (try
      (let [request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://127.0.0.1:" port "/health")))
                        (.GET)
                        (.build))
            response (.send (HttpClient/newHttpClient)
                            request
                            (HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (some-> response .headers (.firstValue "content-type") (.orElse nil))))
        (is (= "{\"status\":\"ok\"}" (.body response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest renderer-smoke-job-emits-a-structured-completion-log
  (is (= (str "{\"severity\":\"INFO\","
              "\"component\":\"renderer\","
              "\"event\":\"smoke_complete\","
              "\"message\":\"Renderer smoke job completed\"}\n")
         (with-out-str (renderer/-main)))))
