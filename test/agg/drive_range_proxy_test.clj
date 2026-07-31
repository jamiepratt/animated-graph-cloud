(ns agg.drive-range-proxy-test
  (:require [agg.drive.core :as drive]
            [agg.drive.range-proxy :as range-proxy]
            [clojure.test :refer [deftest is]])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- get-range [url byte-range]
  (-> (HttpClient/newHttpClient)
      (.send (-> (HttpRequest/newBuilder (URI/create url))
                 (.header "Range" byte-range)
                 (.GET)
                 (.build))
             (HttpResponse$BodyHandlers/ofByteArray))))

(defn- limits
  ([] (limits {}))
  ([overrides]
   (merge {:max-upstream-bytes 64
           :max-request-count 4
           :max-range-bytes 16
           :max-concurrent-requests 1
           :request-timeout-ms 1000
           :max-retries 0
           :max-cache-bytes 16
           :lifetime-ms 10000}
          overrides)))

(deftest late-selected-range-does-not-transfer-preceding-source-bytes
  (let [source-size (inc (* 100 1000 1000 1000))
        start (- source-size 32)
        upstream-ranges (atom [])
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end] :as byte-range}]
            (swap! upstream-ranges conj byte-range)
            (let [length (inc (- end start))]
              {:status 206
               :headers {"content-range"
                         (str "bytes " start "-" end "/" source-size)
                         "content-length" (str length)}
               :body (ByteArrayInputStream.
                      (byte-array (repeat length 120)))})))
        limits (limits)]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "private-access-token"
                         :file-id "private-drive-file"
                         :size source-size
                         :limits limits})]
      (let [response (get-range (:url proxy)
                                (str "bytes=" start "-" (+ start 9)))]
        (is (= 206 (.statusCode response)))
        (is (= 10 (alength ^bytes (.body response))))
        (is (= [{:start start
                 :end (+ start 15)
                 :timeout-ms 1000}]
               @upstream-ranges))
        (is (= {:upstream-bytes 16
                :request-count 1
                :retry-count 0
                :cache-hit-count 0}
               (select-keys
                ((:stats proxy))
                [:upstream-bytes :request-count
                 :retry-count :cache-hit-count])))
        (is (not (re-find #"private|drive|token"
                          (:url proxy))))))))

(deftest repeated-seeks-use-only-the-bounded-memory-cache
  (let [requests (atom 0)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end]}]
            (swap! requests inc)
            {:status 206
             :headers {"content-range" (str "bytes " start "-" end "/100")
                       "content-length" (str (inc (- end start)))}
             :body (ByteArrayInputStream.
                    (byte-array (repeat (inc (- end start)) 1)))}))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits)})]
      (is (= 206 (.statusCode (get-range (:url proxy) "bytes=10-14"))))
      (is (= 206 (.statusCode (get-range (:url proxy) "bytes=12-15"))))
      (is (= 1 @requests))
      (is (= {:upstream-bytes 16
              :request-count 1
              :retry-count 0
              :cache-hit-count 1}
             (select-keys
              ((:stats proxy))
              [:upstream-bytes :request-count
               :retry-count :cache-hit-count]))))))

(deftest malformed-drive-range-and-early-eof-fail-closed
  (doseq [response
          [{:status 206
            :headers {"content-range" "bytes 0-15/101"
                      "content-length" "16"}
            :body (ByteArrayInputStream. (byte-array 16))}
           {:status 206
            :headers {"content-range" "bytes 0-15/100"
                      "content-length" "16"}
            :body (ByteArrayInputStream. (byte-array 15))}]]
    (let [gateway
          (reify drive/PlaybackGateway
            (open-source-range! [_ _ _ _] response))]
      (with-open [proxy (range-proxy/start!
                          {:gateway gateway
                           :access-token "access"
                           :file-id "file"
                           :size 100
                           :limits (limits)})]
        (is (= 502 (.statusCode (get-range (:url proxy) "bytes=0-9"))))))))

(deftest stalled-upstream-body-is-stopped-by-the-request-deadline
  (let [gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end]}]
            {:status 206
             :headers {"content-range" (str "bytes " start "-" end "/100")
                       "content-length" (str (inc (- end start)))}
             :body
             (proxy [InputStream] []
               (read
                 ([] (Thread/sleep 5000) -1)
                 ([_ _ _] (Thread/sleep 5000) -1)))}))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:request-timeout-ms 25})})]
      (let [started (System/nanoTime)
            response (get-range (:url proxy) "bytes=0-9")
            elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
        (is (= 502 (.statusCode response)))
        (is (< elapsed-ms 1000))))))

(deftest transient-drive-http-timeout-is-retried-within-the-range-budget
  (let [requests (atom 0)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end]}]
            (if (= 1 (swap! requests inc))
              (throw (java.net.http.HttpTimeoutException.
                      "deterministic Drive timeout"))
              {:status 206
               :headers {"content-range" (str "bytes " start "-" end "/100")
                         "content-length" (str (inc (- end start)))}
               :body (ByteArrayInputStream.
                      (byte-array (repeat (inc (- end start)) 1)))})))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:max-retries 1})})]
      (is (= 206 (.statusCode (get-range (:url proxy) "bytes=0-9"))))
      (is (= 2 @requests))
      (is (= 1 (:retry-count ((:stats proxy))))))))

(deftest exhausted-drive-http-timeout-fails-with-a-bounded-proxy-response
  (let [requests (atom 0)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ _]
            (swap! requests inc)
            (throw (java.net.http.HttpTimeoutException.
                    "deterministic Drive timeout"))))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:max-retries 1})})]
      (is (= 502 (.statusCode (get-range (:url proxy) "bytes=0-9"))))
      (is (= 2 @requests))
      (is (= {:retry-count 1
              :failure-reason "upstream_timeout"}
             (select-keys ((:stats proxy))
                          [:retry-count :failure-reason]))))))

(deftest upstream-work-budget-stops-new-range-requests
  (let [requests (atom 0)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end]}]
            (swap! requests inc)
            {:status 206
             :headers {"content-range" (str "bytes " start "-" end "/100")
                       "content-length" (str (inc (- end start)))}
             :body (ByteArrayInputStream.
                    (byte-array (inc (- end start))))}))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:max-upstream-bytes 16
                                          :max-request-count 1})})]
      (is (= 206 (.statusCode (get-range (:url proxy) "bytes=0-9"))))
      (is (= 429 (.statusCode (get-range (:url proxy) "bytes=32-41"))))
      (is (= 1 @requests)))))

(deftest retry-and-lifetime-caps-fail-closed
  (let [attempts (atom 0)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end]}]
            (if (= 1 (swap! attempts inc))
              (throw (ex-info "retry" {:status 503}))
              {:status 206
               :headers {"content-range" (str "bytes " start "-" end "/100")
                         "content-length" (str (inc (- end start)))}
               :body (ByteArrayInputStream.
                      (byte-array (inc (- end start))))})))]
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:max-retries 1})})]
      (is (= 206 (.statusCode (get-range (:url proxy) "bytes=0-9"))))
      (is (= 2 @attempts))
      (is (= 1 (:retry-count ((:stats proxy))))))
    (with-open [proxy (range-proxy/start!
                        {:gateway gateway
                         :access-token "access"
                         :file-id "file"
                         :size 100
                         :limits (limits {:lifetime-ms 1})})]
      (Thread/sleep 10)
      (is (= 410 (.statusCode
                  (get-range (:url proxy) "bytes=0-9")))))))
