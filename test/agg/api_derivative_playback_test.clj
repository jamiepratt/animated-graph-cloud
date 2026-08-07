(ns agg.api-derivative-playback-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.browser-process :as browser-process]
            [agg.derivative.lifecycle :as derivative]
            [agg.derivative.storage :as storage]
            [agg.http-test-support :as test-http]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (com.google.cloud ReadChannel)
           (com.google.cloud.storage BlobInfo Storage StorageOptions)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayInputStream)
           (java.lang.reflect InvocationHandler Proxy)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.time Clock Instant ZoneOffset)
           (java.util Arrays Base64)))

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

(defn- chrome-executable []
  (some (fn [candidate]
          (when candidate
            (let [file (java.io.File. candidate)]
              (when (.canExecute file) candidate))))
        [(System/getenv "CHROME_BIN")
         "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
         "/usr/bin/google-chrome"
         "/usr/bin/google-chrome-stable"
         "/usr/bin/chromium"
         "/usr/bin/chromium-browser"]))

(defn- respond-fixture!
  [^HttpExchange exchange status content-type body headers]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" content-type))
    (doseq [[name value] headers]
      (.set (.getResponseHeaders exchange) name value))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(defn- range-adapter-browser-outcome [page policy]
  (let [port (test-http/available-port)
        probe
        (str "<output id=\"browser-result\" data-outcome=\"\"></output>"
             "<script>(async()=>{let outcome;try{const registration=await navigator.serviceWorker.ready,deadline=Date.now()+7000;while(!navigator.serviceWorker.controller&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,10));const registrations=await navigator.serviceWorker.getRegistrations(),controller=navigator.serviceWorker.controller;outcome={controlled:!!controller,controllerScript:controller?.scriptURL||null,registrationCount:registrations.length,activeState:registration.active?.state||null,scope:registration.scope};}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();</script>")
        html (-> page
                 (str/replace #"<script src=\"https://cdn\.jsdelivr\.net/[^>]+></script>"
                              "")
                 (str/replace "</body>" (str probe "</body>")))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (case (some-> exchange .getRequestURI .getPath)
           "/" (respond-fixture! exchange 200 "text/html; charset=utf-8"
                                 html {"Content-Security-Policy" policy})
           "/derivative-playback-range-worker.js"
           (respond-fixture! exchange 200
                             "application/javascript; charset=utf-8"
                             ui/derivative-playback-range-worker
                             {"Cache-Control" "no-store"
                              "Service-Worker-Allowed" "/"})
           (respond-fixture! exchange 404 "text/plain; charset=utf-8" "" {})))))
    (.start server)
    (try
      (let [chrome (chrome-executable)]
        (is chrome "Range-adapter registration requires Chrome or Chromium")
        (when chrome
          (let [{:keys [exit output cleanup]}
                (browser-process/run!
                 {:executable chrome
                  :fixture "prepared playback range adapter registration"
                  :location (str "http://127.0.0.1:" port "/")
                  :virtual-time-budget-ms 8000
                  :timeout-ms 30000})
                encoded (second (re-find #"data-outcome=\"([^\"]+)\"" output))]
            (is (= 0 exit))
            (is (true? (:profile-removed? cleanup)))
            (is encoded)
            (when encoded
              (json/read-str
               (String. (.decode (Base64/getDecoder) ^String encoded)
                        StandardCharsets/UTF_8)
               :key-fn keyword)))))
      (finally
        (.stop server 0)))))

(defn- absolute-limit-channel [bytes]
  (let [position (atom 0)
        limit (atom Long/MAX_VALUE)
        open? (atom true)]
    (reify ReadChannel
      (read [_ buffer]
        (let [available (- (min @limit (alength ^bytes bytes)) @position)
              length (min (.remaining ^ByteBuffer buffer) available)]
          (if (pos? length)
            (do
              (.put ^ByteBuffer buffer ^bytes bytes (int @position) (int length))
              (swap! position + length)
              length)
            -1)))
      (seek [_ offset]
        (reset! position offset))
      (limit [this offset]
        (reset! limit offset)
        this)
      (limit [_]
        @limit)
      (setChunkSize [_ _])
      (isOpen [_]
        @open?)
      (close [_]
        (reset! open? false))
      (capture [_]
        nil))))

(defn- gcs-store [stored-asset bytes]
  (let [blob (atom nil)
        service
        (Proxy/newProxyInstance
         (.getClassLoader Storage)
         (into-array Class [Storage])
         (reify InvocationHandler
           (invoke [_ proxy method _]
             (case (.getName method)
               "get" @blob
               "reader" (absolute-limit-channel bytes)
               "getOptions" (StorageOptions/getDefaultInstance)
               "toString" "playback-test-storage"
               "hashCode" (System/identityHashCode proxy)
               "equals" false
               (throw (UnsupportedOperationException.
                       (.getName method)))))))
        builder
        (BlobInfo/newBuilder
         "playback-test-bucket"
         (:object-key stored-asset)
         (long (:generation stored-asset)))
        set-size (.getDeclaredMethod (class builder) "setSize"
                                     (into-array Class [Long]))
        _ (.setAccessible set-size true)
        _ (.invoke set-size builder
                   (object-array [(Long/valueOf (:size stored-asset))]))
        info
        (-> builder
            (.setContentType (:content-type stored-asset))
            (.setMetadata
             {"profileVersion" (:profile-version stored-asset)})
            (.setCrc32c "test-crc32c")
            .build)
        as-blob (.getDeclaredMethod BlobInfo "asBlob"
                                    (into-array Class [Storage]))]
    (.setAccessible as-blob true)
    (reset! blob (.invoke as-blob info (object-array [service])))
    (storage/gcs-asset-store service "playback-test-bucket")))

(deftest api-serves-the-hosting-range-adapter
  (let [port (test-http/available-port)
        server (api/start! port {:service-profile "api"})]
    (try
      (let [response
            (request! port :get "/derivative-playback-range-worker.js" nil {})
            body (String. ^bytes (.body response))]
        (is (= 200 (.statusCode response)))
        (is (= "application/javascript; charset=utf-8"
               (.orElse (.firstValue (.headers response) "Content-Type") "")))
        (is (= "no-store"
               (.orElse (.firstValue (.headers response) "Cache-Control") "")))
        (is (= "/"
               (.orElse (.firstValue (.headers response)
                                     "Service-Worker-Allowed") "")))
        (is (str/includes? body "headers.get('Range')"))
        (is (str/includes? body "__agg_range"))
        (is (str/includes? body "/v1/derivative-preparations/")))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest authenticated-compose-page-allows-the-range-adapter-worker
  (let [clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        {:keys [system cookie]} (auth-fixture clock)
        port (test-http/available-port)
        server (api/start! port {:auth-system system :service-profile "api"})]
    (try
      (let [response (request! port :get "/" nil
                               {"Cookie" (str "__session=" cookie)})
            policy (.orElse (.firstValue (.headers response)
                                         "Content-Security-Policy") "")
            outcome (range-adapter-browser-outcome
                     (String. ^bytes (.body response) StandardCharsets/UTF_8)
                     policy)]
        (is (= 200 (.statusCode response)))
        (is (str/includes? policy "worker-src 'self'"))
        (when outcome
          (is (= true (:controlled outcome)))
          (is (= 1 (:registrationCount outcome)))
          (is (= "activated" (:activeState outcome)))
          (is (str/ends-with? (:controllerScript outcome)
                              "/derivative-playback-range-worker.js"))
          (is (re-matches #"http://127\.0\.0\.1:[0-9]+/"
                          (:scope outcome)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest compose-page-awaits-the-range-adapter-before-prepared-playback
  (let [page (ui/page {:user owner :csrf "csrf"})
        registration
        (str/index-of
         page
         "const script='/derivative-playback-range-worker.js'")
        awaited (str/index-of page "await derivativePlaybackRangeAdapter")
        unavailable (str/index-of page "range_adapter_unavailable")
        playback-session
        (str/index-of page "preparationPath(activePreparation,'playback-sessions')")]
    (is (str/includes? page "navigator.serviceWorker.register(script"))
    (is (str/includes?
         page
         "Private video playback could not start because its range adapter is unavailable."))
    (is (every? integer? [registration awaited unavailable playback-session]))
    (when (every? integer? [registration awaited unavailable playback-session])
      (is (< registration awaited unavailable playback-session)))))

(deftest hosting-adapted-nonzero-prepared-playback-starts-at-requested-byte
  (let [clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        {:keys [system session cookie csrf]} (auth-fixture clock)
        events (atom [])
        service
        (reify derivative/PreparationPlaybackAccess
          (preparation-playback-asset [_ job-id identity]
            (when (and (= preparation-id job-id)
                       (= owner (select-keys identity [:subject :email])))
              asset)))
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :clock clock
          :derivative-preparation-service service
          :derivative-asset-store
          (gcs-store asset (.getBytes "0123456789abcdefghij"))
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
            playback-url
            (get (json/read-str (String. ^bytes (.body created)))
                 "playbackUrl")
            playback-cookie
            (-> (last (.allValues (.headers created) "Set-Cookie"))
                (.split ";" 2)
                first)
            response
            (request! port :get
                      (str playback-url "?__agg_range=bytes%3D15-")
                      nil
                      {"Cookie"
                       (str "agg_session=" session "; " playback-cookie)})]
        (is (= 206 (.statusCode response)))
        (is (= "fghij" (String. ^bytes (.body response))))
        (is (= "bytes 15-19/20"
               (.orElse
                (.firstValue (.headers response) "Content-Range") "")))
        (is (= "5"
               (.orElse
                (.firstValue (.headers response) "Content-Length") "")))
        (is (= {:rangeSource "query"
                :receivedRange true
                :rangeStart 15
                :rangeEnd 19
                :bytesRequested 5
                :bytesTransferred 5}
               (select-keys (second @events)
                            [:rangeSource :receivedRange :rangeStart :rangeEnd
                             :bytesRequested :bytesTransferred]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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
                :rangeSource "header"
                :receivedRange true
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
