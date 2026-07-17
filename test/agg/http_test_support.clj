(ns agg.http-test-support
  (:import (java.net HttpURLConnection Proxy ServerSocket URI)
           (java.net.http HttpHeaders)
           (java.nio.charset StandardCharsets)
           (java.util.function BiPredicate)))

(definterface LocalHttpResponse
  (statusCode [])
  (body [])
  (headers []))

(deftype Response [status body-value header-values]
  LocalHttpResponse
  (statusCode [_] status)
  (body [_] body-value)
  (headers [_] header-values))

(def ^:private used-ports (atom #{}))

(defn available-port []
  (loop []
    (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))]
      (if (contains? @used-ports port)
        (recur)
        (do (swap! used-ports conj port) port)))))

(defn- response-headers [^HttpURLConnection connection]
  (HttpHeaders/of
   (into {}
         (keep (fn [[name values]] (when name [name values])))
         (.getHeaderFields connection))
   (reify BiPredicate
     (test [_ _ _] true))))

(defn- send! [method uri body headers decode]
  (let [connection ^HttpURLConnection
        (.openConnection (.toURL (URI/create uri)) Proxy/NO_PROXY)
        request-bytes (some-> body (.getBytes StandardCharsets/UTF_8))]
    (try
      (.setConnectTimeout connection 5000)
      (.setReadTimeout connection 10000)
      (.setInstanceFollowRedirects connection false)
      (.setRequestMethod connection (case method :get "GET" :post "POST"))
      (doseq [[name value] headers]
        (.setRequestProperty connection name value))
      (when request-bytes
        (.setDoOutput connection true)
        (with-open [output (.getOutputStream connection)]
          (.write output request-bytes)))
      (let [status (.getResponseCode connection)
            stream (if (>= status 400)
                     (.getErrorStream connection)
                     (.getInputStream connection))
            response-bytes (if stream
                             (with-open [input stream] (.readAllBytes input))
                             (byte-array 0))]
        (Response. status (decode response-bytes)
                   (response-headers connection)))
      (finally
        (.disconnect connection)))))

(defn send-string! [method uri body headers]
  (send! method uri body headers
         #(String. ^bytes % StandardCharsets/UTF_8)))

(defn send-bytes! [method uri body headers]
  (send! method uri body headers identity))
