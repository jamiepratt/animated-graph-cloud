(ns agg.api-derivative-playback-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.derivative.lifecycle :as derivative]
            [agg.derivative.storage :as storage]
            [agg.http-test-support :as test-http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io ByteArrayInputStream)
           (java.time Clock Instant ZoneOffset)
           (java.util Arrays)))

(def ^:private owner
  {:subject "private-owner"
   :email "owner@example.com"})

(def ^:private preparation-id
  "00000000-0000-0000-0000-000000000214")

(def ^:private correlation-id
  "00000000-0000-0000-0000-000000000216")

(def ^:private asset
  {:asset-id "00000000-0000-0000-0000-000000000213"
   :environment "production"
   :object-key
   "production/derivative-previews/v1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mp4"
   :generation 42
   :size 20
   :content-type "video/mp4"
   :profile-version "h264-aac-1080p25-v1"
   :request-id correlation-id
   :revision "dev"
   :completed-at (Instant/parse "2026-07-30T09:00:00Z")
   :expires-at (Instant/parse "2026-07-31T09:00:00Z")})

(defn- auth-fixture [clock]
  (let [system
        (auth/system
         {:client-id "client-id"
          :client-secret "client-secret"
          :base-url "https://app.example.com"
          :allowlist #{"owner@example.com" "other@example.com"}
          :session-key
          (.getBytes "01234567890123456789012345678901")
          :clock clock
          :oauth
          (reify auth/OAuthClient
            (exchange-code! [_ _ _ _ _]
              (throw (UnsupportedOperationException.))))})
        session (auth/issue-session system owner)
        other
        {:subject "other-owner"
         :email "other@example.com"}
        other-session (auth/issue-session system other)]
    {:system system
     :session session
     :cookie (auth/issue-browser-cookie system {:session session})
     :csrf (auth/issue-csrf-token system owner)
     :other-session other-session
     :other-cookie
     (auth/issue-browser-cookie system {:session other-session})}))

(defn- mutable-clock [current]
  (letfn [(clock-for [zone]
            (proxy [Clock] []
              (getZone [] zone)
              (withZone [new-zone] (clock-for new-zone))
              (instant [] @current)
              (millis [] (.toEpochMilli ^Instant @current))))]
    (clock-for ZoneOffset/UTC)))

(defn- request! [port method path body headers]
  (test-http/send-bytes!
   method (str "http://127.0.0.1:" port path)
   body headers))

(deftest completed-production-preview-mints-opaque-bounded-playback
  (let [clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        {:keys [system session cookie csrf]} (auth-fixture clock)
        opened (atom [])
        events (atom [])
        accessed (atom [])
        truthful? (atom true)
        bytes (.getBytes "0123456789abcdefghij")
        service
        (reify derivative/PreparationPlaybackAccess
          (preparation-playback-asset [_ job-id identity]
            (swap! accessed conj [job-id identity])
            (when (and (= preparation-id job-id)
                       (= owner (select-keys identity [:subject :email])))
              asset)))
        asset-store
        (reify storage/AssetStore
          (publish-verified! [_ _])
          (delete-generation! [_ _])
          (open-range! [_ opened-asset {:keys [start end] :as byte-range}]
            (swap! opened conj [opened-asset byte-range])
            (let [body (Arrays/copyOfRange bytes start (inc end))]
              {:status 206
               :headers
               {"content-range"
                (if @truthful?
                  (str "bytes " start "-" end "/20")
                  "bytes 0-0/20")
                "content-length" (str (alength body))}
               :body (ByteArrayInputStream. body)})))
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :clock clock
          :derivative-preparation-service service
          :derivative-asset-store asset-store
          :event-sink
          (fn [event fields]
            (swap! events conj (assoc fields :event event)))})]
    (try
      (let [created
            (request!
             port :post
             (str "/v1/derivative-preparations/" preparation-id
                  "/playback-sessions")
             "{}"
             {"Content-Type" "application/json"
              "Cookie" (str "__session=" cookie)
              "X-CSRF-Token" csrf})
            created-text (String. ^bytes (.body created))
            created-body (json/read-str created-text)
            playback-url (get created-body "playbackUrl")
            set-cookie
            (or (last (.allValues (.headers created) "Set-Cookie")) "")
            playback-cookie (first (.split set-cookie ";" 2))
            streamed
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=3-7"})
            initial
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)})
            open-ended
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=15-"})
            suffix
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=-4"})
            seeking
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=8-11"})
            invalid
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=20-"})
            multiple
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=0-1,4-5"})
            _ (reset! truthful? false)
            untruthful
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=0-1"})]
        (is (= 201 (.statusCode created)) created-text)
        (is (= correlation-id
               (.orElse (.firstValue (.headers created) "X-Request-Id")
                        nil)))
        (is (re-matches
             (re-pattern
              (str "/v1/derivative-preparations/" preparation-id
                   "/playback/[0-9a-f-]{36}"))
             playback-url))
        (is (= {"playbackUrl" playback-url
                "contentType" "video/mp4"
                "size" 20}
               created-body))
        (is (str/starts-with? set-cookie "__session="))
        (is (str/includes? set-cookie "HttpOnly"))
        (is (not-any? #(str/includes? (str playback-url created-text) %)
                      [(:object-key asset)
                       (:asset-id asset)
                       (:subject owner)
                       (:email owner)]))
        (is (= 206 (.statusCode streamed)))
        (is (= correlation-id
               (.orElse (.firstValue (.headers streamed) "X-Request-Id")
                        nil)))
        (is (= "34567" (String. ^bytes (.body streamed))))
        (is (= "bytes 3-7/20"
               (.orElse
                (.firstValue (.headers streamed) "Content-Range") "")))
        (is (= "5"
               (.orElse
                (.firstValue (.headers streamed) "Content-Length") "")))
        (is (= "bytes"
               (.orElse
                (.firstValue (.headers streamed) "Accept-Ranges") "")))
        (is (= "video/mp4"
               (.orElse
                (.firstValue (.headers streamed) "Content-Type") "")))
        (is (= "no-store"
               (.orElse
                (.firstValue (.headers streamed) "Cache-Control") "")))
        (is (= "nosniff"
               (.orElse
                (.firstValue (.headers streamed)
                             "X-Content-Type-Options") "")))
        (is (= [[asset {:start 3 :end 7}]
                [asset {:start 0 :end 19}]
                [asset {:start 15 :end 19}]
                [asset {:start 16 :end 19}]
                [asset {:start 8 :end 11}]
                [asset {:start 0 :end 1}]]
               @opened))
        (is (= [206 206 206 206]
               (mapv #(.statusCode %) [initial open-ended suffix seeking])))
        (is (= ["0123456789abcdefghij" "fghij" "ghij" "89ab"]
               (mapv #(String. ^bytes (.body %))
                     [initial open-ended suffix seeking])))
        (doseq [response [invalid multiple]]
          (is (= 416 (.statusCode response)))
          (is (= "bytes */20"
                 (.orElse
                  (.firstValue (.headers response) "Content-Range") "")))
          (is (= "no-store"
                 (.orElse
                  (.firstValue (.headers response) "Cache-Control") ""))))
        (is (= 502 (.statusCode untruthful)))
        (is (= {:event "derivative_playback_session_created"
                :severity "INFO"
                :environment "production"
                :requestId correlation-id
                :operation "derivative_playback"
                :status "succeeded"
                :profileVersion "h264-aac-1080p25-v1"
                :revision "dev"
                :bytesRequested 20}
               (first @events)))
        (is (= {:event "derivative_playback_range_served"
                :severity "INFO"
                :environment "production"
                :requestId correlation-id
                :operation "derivative_playback"
                :status "succeeded"
                :profileVersion "h264-aac-1080p25-v1"
                :revision "dev"
                :rangeStart 3
                :rangeEnd 7
                :bytesRequested 5
                :bytesTransferred 5}
               (second @events)))
        (is (= "derivative_playback_unavailable"
               (get
                (json/read-str (String. ^bytes (.body untruthful)))
                "error")))
        (is (= 9 (count @accessed))
            "session minting and every media request revalidate access"))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-authority-fails-closed-across-races-users-and-expiry
  (let [now (atom (Instant/parse "2026-07-30T10:00:00Z"))
        clock (mutable-clock now)
        {:keys [system session cookie csrf other-session]} (auth-fixture clock)
        current-asset (atom asset)
        accesses (atom [])
        service
        (reify derivative/PreparationPlaybackAccess
          (preparation-playback-asset [_ job-id identity]
            (swap! accesses conj [job-id identity])
            (when (and (= preparation-id job-id)
                       (= (:subject owner) (:subject identity)))
              @current-asset)))
        asset-store
        (reify storage/AssetStore
          (publish-verified! [_ _])
          (delete-generation! [_ _])
          (open-range! [_ _ _]
            (throw
             (AssertionError.
              "Rejected authority must not reach derivative storage"))))
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :clock clock
          :derivative-preparation-service service
          :derivative-asset-store asset-store})]
    (try
      (let [session-path
            (str "/v1/derivative-preparations/" preparation-id
                 "/playback-sessions")
            create-session
            (fn []
              (request!
               port :post session-path "{}"
               {"Content-Type" "application/json"
                "Cookie" (str "__session=" cookie)
                "X-CSRF-Token" csrf}))
            created (create-session)
            created-body
            (json/read-str (String. ^bytes (.body created)))
            playback-url (get created-body "playbackUrl")
            set-cookie
            (or (last (.allValues (.headers created) "Set-Cookie")) "")
            playback-cookie (first (.split set-cookie ";" 2))
            playback-browser-token
            (second (.split playback-cookie "=" 2))
            playback-token
            (:playback
             (auth/browser-cookie system playback-browser-token))
            other-browser-token
            (auth/issue-browser-cookie
             system
             {:session other-session
              :playback playback-token})
            cross-user
            (request! port :get playback-url nil
                      {"Cookie" (str "__session=" other-browser-token)
                       "Range" "bytes=0-1"})
            cross-preparation
            (request!
             port :get
             (str/replace
              playback-url preparation-id
              "00000000-0000-0000-0000-000000000216")
             nil
             {"Cookie"
              (str "agg_session=" session "; " playback-cookie)
              "Range" "bytes=0-1"})
            cross-playback-path
            (request!
             port :get
             (str/replace
              playback-url
              (last (.split playback-url "/"))
              "00000000-0000-0000-0000-000000000217")
             nil
             {"Cookie"
              (str "agg_session=" session "; " playback-cookie)
              "Range" "bytes=0-1"})
            _ (swap! current-asset update :generation inc)
            changed-asset
            (request! port :get playback-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)
                       "Range" "bytes=0-1"})
            fresh (create-session)
            fresh-url
            (get
             (json/read-str (String. ^bytes (.body fresh)))
             "playbackUrl")
            fresh-cookie
            (-> (last (.allValues (.headers fresh) "Set-Cookie"))
                (.split ";" 2)
                first)
            _ (reset! current-asset nil)
            cancelled
            (request! port :get fresh-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " fresh-cookie)
                       "Range" "bytes=0-1"})
            _ (reset! current-asset asset)
            expiring (create-session)
            expiring-url
            (get
             (json/read-str (String. ^bytes (.body expiring)))
             "playbackUrl")
            expiring-cookie
            (-> (last (.allValues (.headers expiring) "Set-Cookie"))
                (.split ";" 2)
                first)
            _ (swap! now #(.plusSeconds ^Instant % 3601))
            expired
            (request! port :get expiring-url nil
                      {"Cookie"
                       (str "agg_session=" session "; " expiring-cookie)
                       "Range" "bytes=0-1"})]
        (is (= 201 (.statusCode created)))
        (is (= 401 (.statusCode cross-user)))
        (is (= 401 (.statusCode cross-preparation)))
        (is (= 401 (.statusCode cross-playback-path)))
        (is (= 401 (.statusCode changed-asset)))
        (is (= 201 (.statusCode fresh)))
        (is (= 404 (.statusCode cancelled)))
        (is (= "derivative_playback_unavailable"
               (get
                (json/read-str (String. ^bytes (.body cancelled)))
                "error")))
        (is (= 201 (.statusCode expiring)))
        (is (= 401 (.statusCode expired)))
        (is (= 5 (count @accesses))
            "cross-user, cross-path, and expired authority fail before lookup"))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest derivative-playback-routes-exist-only-on-the-api-profile
  (doseq [profile ["overlay" "proto"]]
    (let [port (test-http/available-port)
          server
          (api/start!
           port
           {:service-profile profile
            :derivative-preparation-service (Object.)
            :derivative-asset-store (Object.)})]
      (try
        (doseq [[method path]
                [[:post
                  (str "/v1/derivative-preparations/" preparation-id
                       "/playback-sessions")]
                 [:get
                  (str "/v1/derivative-preparations/" preparation-id
                       "/playback/00000000-0000-0000-0000-000000000215")]]]
          (let [response
                (request! port method path
                          (when (= :post method) "{}")
                          {"Content-Type" "application/json"})]
            (is (= 404 (.statusCode response)))
            (is (= "{\"error\":\"not_found\"}"
                   (String. ^bytes (.body response))))))
        (finally
          (.close ^java.lang.AutoCloseable server))))))
