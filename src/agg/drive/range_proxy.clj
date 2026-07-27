(ns agg.drive.range-proxy
  (:require [agg.drive.core :as drive]
            [agg.drive.limits :as drive-limits]
            [agg.errors :as errors]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io Closeable InputStream)
           (java.net InetSocketAddress)
           (java.util UUID)
           (java.util.concurrent Callable ExecutionException FutureTask
                                 TimeUnit TimeoutException)))

(def ^:private required-limit-keys
  #{:max-upstream-bytes
    :max-request-count
    :max-range-bytes
    :max-concurrent-requests
    :request-timeout-ms
    :max-retries
    :max-cache-bytes
    :lifetime-ms})

(def renderer-limits-v1 drive-limits/renderer-range-limits-v1)

(defn- valid-limits? [limits]
  (and (= required-limit-keys (set (keys limits)))
       (every? #(and (integer? (get limits %))
                     (pos? (get limits %)))
               (disj required-limit-keys :max-retries))
       (nat-int? (:max-retries limits))
       (<= (:max-cache-bytes limits) (:max-range-bytes limits))))

(defn- require-configuration!
  [{:keys [gateway access-token file-id size limits]}]
  (when-not (and (satisfies? drive/PlaybackGateway gateway)
                 (string? access-token)
                 (not (str/blank? access-token))
                 (string? file-id)
                 (not (str/blank? file-id))
                 (integer? size)
                 (pos? size)
                 (valid-limits? limits))
    (throw (errors/raise! "Renderer range proxy configuration is invalid"
                          {:type ::invalid-configuration}))))

(defn- invalid-range! [size]
  (throw (errors/raise! "Renderer source range is not satisfiable"
                        {:type ::invalid-range :size size})))

(defn- requested-range [header size max-range-bytes]
  (let [[_ start-text end-text]
        (when header
          (re-matches #"(?i)bytes=(\d*)-(\d*)" header))]
    (when-not (and (or (not (str/blank? start-text))
                       (not (str/blank? end-text)))
                   (not (str/includes? header ",")))
      (invalid-range! size))
    (if (str/blank? start-text)
      (let [suffix (parse-long end-text)]
        (when-not (and suffix (pos? suffix))
          (invalid-range! size))
        (let [length (min size suffix max-range-bytes)]
          {:start (- size length) :end (dec size)}))
      (let [start (parse-long start-text)
            end (if (str/blank? end-text)
                  (dec size)
                  (parse-long end-text))]
        (when-not (and start end (< start size) (<= start end))
          (invalid-range! size))
        {:start start
         :end (min (dec size)
                   end
                   (dec (+ start max-range-bytes)))}))))

(defn- write-response!
  [^HttpExchange exchange status headers ^bytes body]
  (doseq [[key value] headers]
    (.set (.getResponseHeaders exchange) key value))
  (.sendResponseHeaders exchange status (alength body))
  (with-open [output (.getResponseBody exchange)]
    (.write output body)))

(defn- write-empty! [^HttpExchange exchange status headers]
  (doseq [[key value] headers]
    (.set (.getResponseHeaders exchange) key value))
  (.sendResponseHeaders exchange status -1)
  (.close exchange))

(defn- cached-bytes [cache start end]
  (when (and cache
             (<= (:start cache) start)
             (>= (:end cache) end))
    (let [from (int (- start (:start cache)))
          length (int (inc (- end start)))
          result (byte-array length)]
      (System/arraycopy ^bytes (:bytes cache) from result 0 length)
      result)))

(defn- content-range [headers]
  (when-let [value (get headers "content-range")]
    (let [[_ start end total]
          (re-matches #"bytes (\d+)-(\d+)/(\d+)" value)]
      (when start
        {:start (parse-long start)
         :end (parse-long end)
         :total (parse-long total)}))))

(defn- exact-upstream-response?
  [{:keys [status headers body]} start end size]
  (let [expected-length (inc (- end start))
        returned (content-range headers)]
    (and (= 206 status)
         (instance? InputStream body)
         (= {:start start :end end :total size} returned)
         (= (str expected-length) (get headers "content-length")))))

(defn- read-exactly! [^InputStream input length]
  (with-open [input input]
    (let [bytes (.readNBytes input (int length))]
      (when-not (and (= length (alength bytes))
                     (= -1 (.read input)))
        (throw (errors/raise! "Drive range body length was invalid"
                              {:type ::invalid-upstream-response})))
      bytes)))

(defn- within-timeout! [timeout-ms operation]
  (let [task
        (FutureTask.
         ^Callable
         (reify Callable
           (call [_] (operation))))]
    (Thread/startVirtualThread task)
    (try
      (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException error
        (.cancel task true)
        (throw (errors/raise! "Drive range request exceeded its deadline"
                              {:type ::upstream-timeout}
                              error)))
      (catch InterruptedException error
        (.cancel task true)
        (.interrupt (Thread/currentThread))
        (throw error))
      (catch ExecutionException error
        (throw (.getCause error))))))

(defn- reserve-upstream!
  [counters {:keys [max-upstream-bytes max-request-count]} length]
  (let [accepted? (atom false)]
    (swap! counters
           (fn [{:keys [upstream-bytes request-count] :as current}]
             (if (and (<= (+ upstream-bytes length) max-upstream-bytes)
                      (< request-count max-request-count))
               (do
                 (reset! accepted? true)
                 (-> current
                     (update :upstream-bytes + length)
                     (update :request-count inc)))
               current)))
    (when-not @accepted?
      (throw (errors/raise! "Renderer source work exceeded its range budget"
                            {:type ::budget-exhausted})))))

(defn- retryable-upstream? [error]
  (or (= ::upstream-timeout (:type (ex-data error)))
      (contains? #{403 429 500 502 503 504} (:status (ex-data error)))))

(defn- load-range!
  [{:keys [gateway access-token file-id size limits counters cache]}
   start requested-end]
  (let [{:keys [max-cache-bytes max-retries request-timeout-ms]} limits
        fetch-end (min (dec size)
                       (max requested-end
                            (dec (+ start max-cache-bytes))))
        length (inc (- fetch-end start))]
    (loop [attempt 0]
      (reserve-upstream! counters limits length)
      (let [result
            (try
              {:value
               (within-timeout!
                request-timeout-ms
                (fn []
                  (let [response
                        (drive/open-source-range!
                         gateway access-token file-id
                         {:start start
                          :end fetch-end
                          :timeout-ms request-timeout-ms})]
                    (when-not
                     (exact-upstream-response? response start fetch-end size)
                      (some-> ^InputStream (:body response) .close)
                      (throw
                       (errors/raise! "Drive range response was invalid"
                                      {:type ::invalid-upstream-response
                                       :status
                                       (long (or (:status response) 0))})))
                    (let [bytes (read-exactly! (:body response) length)
                          loaded {:start start :end fetch-end :bytes bytes}]
                      (reset! cache loaded)
                      loaded))))}
              (catch Throwable error
                {:error error}))]
        (if-let [error (:error result)]
          (if (and (< attempt max-retries)
                   (retryable-upstream? error))
            (do
              (swap! counters update :retry-count inc)
              (recur (inc attempt)))
            (throw error))
          (:value result))))))

(defn- acquire! [active limit]
  (<= (swap! active inc) limit))

(defn- handler
  [{:keys [path size limits started-nanos active cache failure] :as state}]
  (reify HttpHandler
    (^void handle [_ ^HttpExchange exchange]
      (let [acquired? (acquire! active (:max-concurrent-requests limits))]
        (try
          (cond
            (not acquired?)
            (do
              (reset! failure "concurrency_exhausted")
              (write-empty! exchange 429 {"Cache-Control" "no-store"}))

            (> (quot (- (System/nanoTime) started-nanos) 1000000)
               (:lifetime-ms limits))
            (do
              (reset! failure "lifetime_exhausted")
              (write-empty! exchange 410 {"Cache-Control" "no-store"}))

            (not= path (some-> exchange .getRequestURI .getPath))
            (write-empty! exchange 404 {"Cache-Control" "no-store"})

            (not= "GET" (.getRequestMethod exchange))
            (write-empty! exchange 405 {"Allow" "GET"
                                        "Cache-Control" "no-store"})

            :else
            (try
              (let [{:keys [start end]}
                    (requested-range
                     (some-> exchange .getRequestHeaders (.getFirst "Range"))
                     size (:max-range-bytes limits))
                    cached (cached-bytes @cache start end)
                    bytes
                    (if cached
                      (do
                        (swap! (:counters state) update :cache-hit-count inc)
                        cached)
                      (cached-bytes (load-range! state start end) start end))]
                (write-response!
                 exchange 206
                 {"Content-Type" "video/mp4"
                  "Content-Range" (str "bytes " start "-" end "/" size)
                  "Accept-Ranges" "bytes"
                  "Cache-Control" "no-store"
                  "Content-Length" (str (alength bytes))}
                 bytes))
              (catch clojure.lang.ExceptionInfo error
                (let [data (ex-data error)
                      type (:type data)]
                  (cond
                    (= ::invalid-range type)
                    (write-empty! exchange 416
                                  {"Content-Range" (str "bytes */" size)
                                   "Cache-Control" "no-store"})

                    (= ::budget-exhausted type)
                    (do
                      (reset! failure "work_budget_exhausted")
                      (write-empty! exchange 429 {"Cache-Control" "no-store"}))

                    (= ::invalid-upstream-response type)
                    (do
                      (reset! failure "invalid_upstream_response")
                      (write-empty! exchange 502 {"Cache-Control" "no-store"}))

                    (= ::upstream-timeout type)
                    (do
                      (reset! failure "upstream_timeout")
                      (write-empty! exchange 502 {"Cache-Control" "no-store"}))

                    :else
                    (do
                      (reset! failure (or (some-> type name)
                                          "unexpected_failure"))
                      (write-empty! exchange 502 {"Cache-Control" "no-store"})))))))
          (finally
            (swap! active dec)))))))

(defn start!
  "Starts a task-scoped loopback HTTP range proxy for one Drive source."
  [{:keys [gateway access-token file-id size limits]}]
  (require-configuration!
   {:gateway gateway
    :access-token access-token
    :file-id file-id
    :size size
    :limits limits})
  (let [path (str "/source/" (UUID/randomUUID))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        counters (atom {:upstream-bytes 0
                        :request-count 0
                        :retry-count 0
                        :cache-hit-count 0})
        cache (atom nil)
        active (atom 0)
        failure (atom nil)
        started-nanos (System/nanoTime)
        state {:gateway gateway
               :access-token access-token
               :file-id file-id
               :size size
               :limits limits
               :path path
               :started-nanos started-nanos
               :counters counters
               :cache cache
               :active active
               :failure failure}]
    (.createContext server path (handler state))
    (.start server)
    (reify
      Closeable
      (close [_]
        (.stop server 0))
      Object
      (toString [_]
        (str "#<range-proxy " path ">"))
      clojure.lang.ILookup
      (valAt [_ key]
        (case key
          :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) path)
          :stats (fn []
                   (assoc @counters
                          :failure-reason @failure))
          nil))
      (valAt [_ key not-found]
        (or (.valAt ^clojure.lang.ILookup _ key) not-found)))))
