(ns agg.youtube
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration Instant)
           (java.util HexFormat)))

(def playlist-id "PLIIYTIXqGbuE")
(def cache-seconds 900)
(def ^:private max-response-bytes (* 1024 1024))
(def ^:private max-videos 200)
(def ^:private max-title-length 200)
(def ^:private max-summary-length 160)
(def ^:private max-position 1000000)
(def ^:private max-duration-length 64)
(def ^:private max-duration-seconds (* 31 24 60 60))
(def ^:private video-id-pattern #"[A-Za-z0-9_-]{6,64}")

(defprotocol YouTubeApi
  (playlist-page! [api page-token])
  (video-durations! [api video-ids]))

(defprotocol HomepageVideos
  (homepage-videos! [service]))

(defn- bounded [value limit]
  (subs (str value) 0 (min limit (count (str value)))))

(defn- meaningful-line? [line]
  (let [line (str/trim line)]
    (and (not (str/blank? line))
         (not (re-matches #"(?i)https?://\S+" line))
         (not (re-matches #"(?:#\S+\s*)+" line)))))

(defn summary [description]
  (let [paragraph
        (or (->> (str/split (bounded (or description "") 5000) #"\r?\n\s*\r?\n")
                 (map (fn [paragraph]
                        (->> (str/split paragraph #"\r?\n")
                             (filter meaningful-line?)
                             (str/join " ")
                             (#(str/replace % #"\s+" " "))
                             str/trim)))
                 (remove str/blank?)
                 first)
            "")]
    (if (<= (count paragraph) max-summary-length)
      paragraph
      (let [prefix (subs paragraph 0 (dec max-summary-length))
            break (str/last-index-of prefix " ")]
        (str (subs prefix 0 (max 1 (or break (count prefix)))) "…")))))

(defn format-duration [iso-duration]
  (try
    (when (and (string? iso-duration)
               (<= (count iso-duration) max-duration-length))
      (let [seconds (.getSeconds (Duration/parse iso-duration))]
        (when (<= 0 seconds max-duration-seconds)
          (let [hours (quot seconds 3600)
                minutes (quot (mod seconds 3600) 60)
                seconds (mod seconds 60)]
            (if (pos? hours)
              (format "%d:%02d:%02d" hours minutes seconds)
              (format "%d:%02d" minutes seconds))))))
    (catch Exception _ nil)))

(defn- public-item? [{:keys [video-id position privacy-status]}]
  (and (string? video-id)
       (re-matches video-id-pattern video-id)
       (integer? position)
       (<= 0 position max-position)
       (= "public" privacy-status)))

(defn- item->video [item iso-duration]
  (when-let [duration (format-duration iso-duration)]
    (let [id (:video-id item)]
      {:id id
       :title (bounded (or (:title item) "Untitled video") max-title-length)
       :summary (summary (:description item))
       :duration duration
       :durationIso iso-duration
       :position (:position item)
       :thumbnailUrl (str "https://i.ytimg.com/vi/" id "/hqdefault.jpg")
       :watchUrl (str "https://www.youtube.com/watch?v=" id "&list=" playlist-id)})))

(defn fetch-playlist! [api]
  (let [items
        (loop [token nil
               pages 0
               result []]
          (when (>= pages 20)
            (throw (ex-info "YouTube metadata unavailable"
                            {:type ::unavailable :retryable true})))
          (let [{:keys [items next-page-token]} (playlist-page! api token)
                result (into result (take (- max-videos (count result)) items))]
            (if (and next-page-token (< (count result) max-videos))
              (recur next-page-token (inc pages) result)
              result)))
        items (->> items
                   (filter public-item?)
                   (sort-by :position)
                   (take max-videos)
                   vec)
        durations (->> items
                       (map :video-id)
                       (partition-all 50)
                       (map #(video-durations! api (vec %)))
                       (apply merge {}))]
    (->> items
         (keep #(item->video % (get durations (:video-id %))))
         vec)))

(defn- etag [videos]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (json/write-str videos)
                                   StandardCharsets/UTF_8))]
    (str "\"" (.formatHex (HexFormat/of) digest) "\"")))

(defrecord CachedHomepageVideos [api now state]
  HomepageVideos
  (homepage-videos! [_]
    (locking state
      (let [current @state
            instant (now)
            fresh? (and (:fetched-at current)
                        (.isBefore instant
                                   (.plusSeconds ^Instant (:fetched-at current)
                                                 cache-seconds)))]
        (if fresh?
          (assoc (:result current) :stale false)
          (try
            (let [videos (fetch-playlist! api)
                  result {:videos videos :etag (etag videos) :stale false}]
              (reset! state {:fetched-at instant :result result})
              result)
            (catch Exception error
              (if-let [result (:result current)]
                (assoc result :stale true)
                (throw (ex-info "YouTube metadata is temporarily unavailable"
                                {:type ::unavailable :retryable true}
                                error))))))))))

(defn cached-service
  ([api] (cached-service api #(Instant/now)))
  ([api now] (->CachedHomepageVideos api now (atom nil))))

(defn- encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn decode-response! [status ^bytes body]
  (when (or (not (<= 200 status 299))
            (> (alength body) max-response-bytes))
    (throw (ex-info "YouTube metadata request failed"
                    {:type ::upstream-failure
                     :status status
                     :retryable true})))
  (try
    (json/read-str (String. body StandardCharsets/UTF_8) :key-fn keyword)
    (catch Exception error
      (throw (ex-info "YouTube metadata response was invalid"
                      {:type ::upstream-failure :retryable true}
                      error)))))

(defn- response-json! [^HttpClient client api-key path params]
  (let [query (->> (assoc params :key api-key)
                   (map (fn [[key value]]
                          (str (encode (name key)) "=" (encode value))))
                   (str/join "&"))
        request (-> (HttpRequest/newBuilder
                     (URI/create (str "https://youtube.googleapis.com/youtube/v3/"
                                      path "?" query)))
                    (.timeout (Duration/ofSeconds 5))
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))]
    (decode-response! (.statusCode response) (.body response))))

(defrecord HttpYouTubeApi [api-key client]
  YouTubeApi
  (playlist-page! [_ page-token]
    (let [response
          (response-json!
           client api-key "playlistItems"
           (cond-> {:part "snippet,status"
                    :playlistId playlist-id
                    :maxResults 50}
             page-token (assoc :pageToken page-token)))]
      {:items
       (mapv (fn [item]
               {:video-id (get-in item [:snippet :resourceId :videoId])
                :position (get-in item [:snippet :position])
                :title (get-in item [:snippet :title])
                :description (get-in item [:snippet :description])
                :privacy-status (get-in item [:status :privacyStatus])})
             (:items response))
       :next-page-token (:nextPageToken response)}))
  (video-durations! [_ video-ids]
    (let [response (response-json! client api-key "videos"
                                   {:part "contentDetails"
                                    :id (str/join "," video-ids)})]
      (into {}
            (keep (fn [item]
                    (when-let [id (:id item)]
                      [id (get-in item [:contentDetails :duration])])))
            (:items response)))))

(defn http-api [api-key]
  (when (str/blank? api-key)
    (throw (IllegalArgumentException. "A server-only YouTube API key is required")))
  (->HttpYouTubeApi api-key (HttpClient/newHttpClient)))
