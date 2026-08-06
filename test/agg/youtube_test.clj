(ns agg.youtube-test
  (:require [agg.youtube :as youtube]
            [agg.api.main :as api-main]
            [agg.http-test-support :as test-http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.net.http HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util.concurrent CountDownLatch)))

(defn- api [pages durations]
  (reify youtube/YouTubeApi
    (playlist-page! [_ token]
      (or (get pages token) (throw (ex-info "unexpected page" {}))))
    (video-durations! [_ ids]
      (select-keys durations ids))))

(deftest playlist-metadata-is-owner-ordered-bounded-and-safe
  (let [source
        (api
         {nil {:items [{:video-id "validOne_1" :position 2
                        :title (apply str (repeat 260 "T"))
                        :description "https://example.com\n#onlytag\n\nA useful first paragraph with   spacing.\n\nIgnored."
                        :privacy-status "public"}
                       {:video-id "private22" :position 1
                        :title "Private" :description "hidden"
                        :privacy-status "private"}]
               :next-page-token "next"}
          "next" {:items [{:video-id "validTwo-2" :position 0
                           :title "Second" :description "Summary"
                           :privacy-status "public"}
                          {:video-id "missingPos1" :position nil
                           :title "Missing position" :description "Bad"
                           :privacy-status "public"}
                          {:video-id "hugePos000" :position 1000001
                           :title "Unbounded position" :description "Bad"
                           :privacy-status "public"}
                          {:video-id "bad/id" :position 3
                           :title "Bad" :description "Bad"
                           :privacy-status "public"}]}}
         {"validOne_1" "PT1H2M3S" "validTwo-2" "PT4M5S"
          "missingPos1" "PT1M" "hugePos000" "PT1M"})
        result (youtube/fetch-playlist! source)]
    (is (= ["validTwo-2" "validOne_1"] (mapv :id result)))
    (is (= ["4:05" "1:02:03"] (mapv :duration result)))
    (is (= "A useful first paragraph with spacing."
           (:summary (second result))))
    (is (= 200 (count (:title (second result)))))
    (is (= "https://i.ytimg.com/vi/validOne_1/hqdefault.jpg"
           (:thumbnailUrl (second result))))
    (is (= "https://www.youtube.com/watch?v=validOne_1&list=PLIIYTIXqGbuE"
           (:watchUrl (second result))))))

(deftest duration-requests-are-batched
  (let [batches (atom [])
        items (mapv (fn [position]
                      {:video-id (format "video%06d" position)
                       :position position :title "Title"
                       :description "Summary" :privacy-status "public"})
                    (range 51))
        source (reify youtube/YouTubeApi
                 (playlist-page! [_ _] {:items items})
                 (video-durations! [_ ids]
                   (swap! batches conj ids)
                   (zipmap ids (repeat "PT1M"))))]
    (is (= 51 (count (youtube/fetch-playlist! source))))
    (is (= [50 1] (mapv count @batches)))))

(deftest duration-formatting-is-valid-and-bounded
  (is (= "0:00" (youtube/format-duration "PT0S")))
  (is (= "59:59" (youtube/format-duration "PT59M59S")))
  (is (= "1:02:03" (youtube/format-duration "PT1H2M3S")))
  (is (nil? (youtube/format-duration "PT-1S")))
  (is (nil? (youtube/format-duration "P32D")))
  (is (nil? (youtube/format-duration (apply str (repeat 65 "P")))))
  (is (nil? (youtube/format-duration "not-a-duration"))))

(deftest cache-is-fresh-coalesces-refreshes-and-serves-stale-on-error
  (let [calls (atom 0)
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        failing? (atom false)
        source (reify youtube/YouTubeApi
                 (playlist-page! [_ _]
                   (swap! calls inc)
                   (.countDown started)
                   (.await release)
                   (if @failing?
                     (throw (ex-info "quota body contains secret-value" {:status 403}))
                     {:items [{:video-id "validOne_1" :position 0
                               :title "Title" :description "Summary"
                               :privacy-status "public"}]}))
                 (video-durations! [_ ids] (zipmap ids (repeat "PT2M3S"))))
        now (atom (Instant/parse "2026-08-05T20:00:00Z"))
        service (youtube/cached-service source #(deref now))
        first-call (future (youtube/homepage-videos! service))]
    (.await started)
    (let [second-call (future (youtube/homepage-videos! service))]
      (.countDown release)
      (is (= @first-call @second-call))
      (is (= 1 @calls)))
    (is (false? (:stale (youtube/homepage-videos! service))))
    (is (= 1 @calls))
    (reset! failing? true)
    (reset! now (Instant/parse "2026-08-05T20:16:00Z"))
    (let [stale (youtube/homepage-videos! service)]
      (is (true? (:stale stale)))
      (is (= 2 @calls))
      (is (not (str/includes? (pr-str stale) "secret-value"))))))

(deftest cold-cache-errors-are-secret-safe
  (let [source (reify youtube/YouTubeApi
                 (playlist-page! [_ _]
                   (throw (ex-info "raw upstream key=secret-value" {:status 403})))
                 (video-durations! [_ _] {}))
        service (youtube/cached-service source #(Instant/now))]
    (try
      (youtube/homepage-videos! service)
      (is false "expected safe failure")
      (catch clojure.lang.ExceptionInfo error
        (is (= ::youtube/unavailable (:type (ex-data error))))
        (is (not (str/includes? (.getMessage error) "secret-value")))
        (is (not (str/includes? (pr-str (ex-data error)) "secret-value")))))))

(deftest malformed-quota-and-timeout-failures-are-bounded-and-secret-safe
  (doseq [[status body] [[200 "{malformed secret-value"]
                         [403 "{\"error\":\"quota secret-value\"}"]]]
    (try
      (youtube/decode-response! status
                                (.getBytes body StandardCharsets/UTF_8))
      (is false "expected upstream response rejection")
      (catch clojure.lang.ExceptionInfo error
        (is (= ::youtube/upstream-failure (:type (ex-data error))))
        (is (not (str/includes? (.getMessage error) "secret-value")))
        (is (not (str/includes? (pr-str (ex-data error)) "secret-value"))))))
  (let [source (reify youtube/YouTubeApi
                 (playlist-page! [_ _]
                   (throw (HttpTimeoutException. "secret-value")))
                 (video-durations! [_ _] {}))]
    (try
      (youtube/homepage-videos! (youtube/cached-service source))
      (is false "expected timeout rejection")
      (catch clojure.lang.ExceptionInfo error
        (is (= ::youtube/unavailable (:type (ex-data error))))
        (is (not (str/includes? (.getMessage error) "secret-value")))
        (is (not (str/includes? (pr-str (ex-data error)) "secret-value")))))))

(deftest public-endpoint-supports-browser-cache-and-conditional-requests
  (let [port (test-http/available-port)
        service (reify youtube/HomepageVideos
                  (homepage-videos! [_]
                    {:videos [{:id "validOne_1" :title "A title"
                               :summary "Summary" :duration "2:03"
                               :durationIso "PT2M3S" :position 0
                               :thumbnailUrl "https://i.ytimg.com/vi/validOne_1/hqdefault.jpg"
                               :watchUrl "https://www.youtube.com/watch?v=validOne_1&list=PLIIYTIXqGbuE"}]
                     :etag "\"metadata-etag\"" :stale false}))
        server (api-main/start! port {:youtube-metadata service})]
    (try
      (let [response (test-http/send-string!
                      :get (str "http://127.0.0.1:" port "/v1/homepage/videos")
                      nil {})
            body (json/read-str (.body response) :key-fn keyword)
            conditional (test-http/send-string!
                         :get (str "http://127.0.0.1:" port
                                   "/v1/homepage/videos")
                         nil {"If-None-Match" "\"metadata-etag\""})]
        (is (= 200 (.statusCode response)))
        (is (= 1 (count (:videos body))))
        (is (false? (:stale body)))
        (is (= "public, max-age=900, stale-if-error=900"
               (.orElse (.firstValue (.headers response) "Cache-Control") nil)))
        (is (= "\"metadata-etag\""
               (.orElse (.firstValue (.headers response) "ETag") nil)))
        (is (= 304 (.statusCode conditional)))
        (is (str/blank? (.body conditional))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest public-endpoint-never-reflects-upstream-secrets
  (let [port (test-http/available-port)
        service (reify youtube/HomepageVideos
                  (homepage-videos! [_]
                    (throw (ex-info "quota key=secret-value"
                                    {:type ::youtube/unavailable
                                     :raw "secret-value"}))))
        server (api-main/start! port {:youtube-metadata service})]
    (try
      (let [response (test-http/send-string!
                      :get (str "http://127.0.0.1:" port "/v1/homepage/videos")
                      nil {})]
        (is (= 503 (.statusCode response)))
        (is (= {:error "youtube_metadata_unavailable" :retryable true}
               (json/read-str (.body response) :key-fn keyword)))
        (is (not (str/includes? (.body response) "secret-value"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
