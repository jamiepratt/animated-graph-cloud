(ns agg.api-derivative-preparation-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.derivative.lifecycle :as derivative]
            [agg.derivative.storage :as storage]
            [agg.drive.core :as drive]
            [agg.drive.gcp :as drive-gcp]
            [agg.http-test-support :as test-http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Clock Instant ZoneOffset)))

(defn- auth-fixture [drive-gateway]
  (let [oauth
        (reify auth/OAuthClient
          (exchange-code! [_ _ _ _ _]
            (throw (UnsupportedOperationException.))))
        grant-store
        (reify auth/GrantStore
          (load-grant [_ _]
            {:refresh-token-ciphertext "kms:refresh"
             :folder-id "folder"})
          (save-grant! [_ _ grant] grant)
          (revoke-grant! [_ _]))
        cipher
        (reify auth/TokenCipher
          (encrypt-token! [_ value] (str "kms:" value))
          (decrypt-token! [_ value] (subs value 4)))
        token-client
        (reify auth/DriveTokenClient
          (refresh-drive-token! [_ _]
            {:access-token "private-access"}))
        system
        (auth/system
         {:client-id "client-id"
          :client-secret "client-secret"
          :base-url "https://app.example.com"
          :allowlist #{"owner@example.com" "other@example.com"}
          :session-key
          (.getBytes "01234567890123456789012345678901")
          :grant-store grant-store
          :oauth oauth
          :cipher cipher
          :drive drive-gateway
          :drive-token-client token-client})
        session
        (auth/issue-session
         system {:subject "private-owner" :email "owner@example.com"})
        other-session
        (auth/issue-session
         system {:subject "other-owner" :email "other@example.com"})]
    {:system system
     :cookie
     (auth/issue-browser-cookie system {:session session})
     :csrf
     (auth/issue-csrf-token system {:subject "private-owner"})
     :other-cookie
     (auth/issue-browser-cookie system {:session other-session})
     :other-csrf
     (auth/issue-csrf-token system {:subject "other-owner"})}))

(defn- request!
  [port method path body headers]
  (test-http/send-string!
   method (str "http://127.0.0.1:" port path)
   (when body (json/write-str body))
   (merge {"Content-Type" "application/json"} headers)))

(defn- completed-preparation!
  [service asset-store request bytes]
  (let [{:keys [job]}
        (derivative/submit-preparation! service "playback-fixture" request)
        job-id (:id job)
        _ (derivative/dispatch-preparation! service job-id)
        attempt (:attempt job)
        {:keys [asset]} (derivative/load-preparation-attempt service job-id
                                                             attempt)
        path (Files/createTempFile
              "derivative-playback-fixture-" ".mp4"
              (make-array FileAttribute 0))]
    (try
      (Files/write path bytes (make-array java.nio.file.OpenOption 0))
      (let [stored
            (storage/publish-verified!
             asset-store
             {:job-id job-id
              :attempt attempt
              :asset-id (:id asset)
              :object-key (:object-key asset)
              :output-path path
              :content-type "video/mp4"
              :size (alength ^bytes bytes)
              :profile-version "h264-aac-1080p25-v1"})]
        (derivative/complete-preparation-attempt!
         service job-id attempt
         (merge {:asset-id (:id asset)
                 :object-key (:object-key asset)
                 :measurements {:output-bytes (alength ^bytes bytes)}}
                stored))
        {:job-id job-id :stored stored :object-key (:object-key asset)})
      (finally
        (Files/deleteIfExists path)))))

(deftest owner-bound-derivative-playback-streams-one-opaque-exact-range
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ _])
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf other-cookie other-csrf]}
        (auth-fixture drive-gateway)
        clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        preparation
        (derivative/in-memory-preparation-system
         {:clock clock :fingerprint-secret "fixture-secret"})
        service (:service preparation)
        asset-store (storage/in-memory-asset-store)
        bytes (.getBytes "0123456789abcdef")
        operation-request-id "00000000-0000-0000-0000-000000000197"
        events (atom [])
        {:keys [job-id object-key stored]}
        (completed-preparation!
         service asset-store
         {:subject "private-owner"
          :email "owner@example.com"
          :membership-version nil
          :file-id "private-drive-id"
          :drive-version "17"
          :source-bytes 4096
          :source-duration-seconds 120.0
          :request-id operation-request-id
          :trace "0123456789abcdef0123456789abcdef"
          :revision "agg-proto-00001-test"}
         bytes)
        session-path
        (str "/v1/derivative-preparations/" job-id "/playback-sessions")
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :derivative-preparation-service service
          :derivative-asset-store asset-store
          :clock clock
          :event-sink
          (fn [event fields]
            (swap! events conj [event fields]))})]
    (try
      (let [other
            (request! port :post session-path {}
                      {"Cookie" (str "__session=" other-cookie)
                       "X-CSRF-Token" other-csrf})
            created
            (request! port :post session-path {}
                      {"Cookie" (str "__session=" cookie)
                       "X-CSRF-Token" csrf})
            created-body (json/read-str (.body created) :key-fn keyword)
            browser-cookie
            (some-> (.firstValue (.headers created) "Set-Cookie")
                    (.orElse "")
                    (str/split #";")
                    first)
            streamed
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" "bytes=3-7"})
            open-ended
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" "bytes=8-"})
            suffix
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" "bytes=-4"})
            missing-authority
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" (str "__session=" cookie)
                       "Range" "bytes=3-7"})
            mismatched-uuid
            (request!
             port :get
             (str "/v1/derivative-preparations/" job-id
                  "/playback/00000000-0000-0000-0000-000000000197")
             nil
             {"Cookie" browser-cookie
              "Range" "bytes=3-7"})
            invalid-range
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" "bytes=16-"})
            empty-range
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" ""})
            _ (storage/delete-generation!
               asset-store
               {:object-key object-key
                :generation (:generation stored)})
            unavailable
            (request! port :get (:playbackUrl created-body) nil
                      {"Cookie" browser-cookie
                       "Range" "bytes=3-7"})]
        (is (= 404 (.statusCode other)))
        (is (= 201 (.statusCode created)) (.body created))
        (is (re-matches
             (re-pattern
              (str "/v1/derivative-preparations/" job-id
                   "/playback/[0-9a-f-]{36}"))
             (:playbackUrl created-body)))
        (is (= {:contentType "video/mp4" :size 16}
               (dissoc created-body :playbackUrl)))
        (is (not-any?
             #(str/includes? (.body created) %)
             [object-key "private-drive-id" "private-owner"
              "owner@example.com" "fixture-secret"]))
        (is (= 206 (.statusCode streamed)) (.body streamed))
        (is (= operation-request-id
               (.orElse
                (.firstValue (.headers streamed) "X-Request-Id") "")))
        (is (= "34567" (.body streamed)))
        (is (= "bytes 3-7/16"
               (.orElse
                (.firstValue (.headers streamed) "Content-Range") "")))
        (is (= "5"
               (.orElse
                (.firstValue (.headers streamed) "Content-Length") "")))
        (is (= "video/mp4"
               (.orElse
                (.firstValue (.headers streamed) "Content-Type") "")))
        (is (= "bytes"
               (.orElse
                (.firstValue (.headers streamed) "Accept-Ranges") "")))
        (is (= "no-store"
               (.orElse
                (.firstValue (.headers streamed) "Cache-Control") "")))
        (is (= "nosniff"
               (.orElse
                (.firstValue (.headers streamed)
                             "X-Content-Type-Options") "")))
        (is (= 206 (.statusCode open-ended)))
        (is (= "89abcdef" (.body open-ended)))
        (is (= "bytes 8-15/16"
               (.orElse
                (.firstValue (.headers open-ended) "Content-Range") "")))
        (is (= 206 (.statusCode suffix)))
        (is (= "cdef" (.body suffix)))
        (is (= "bytes 12-15/16"
               (.orElse
                (.firstValue (.headers suffix) "Content-Range") "")))
        (is (= 401 (.statusCode missing-authority)))
        (is (= 401 (.statusCode mismatched-uuid)))
        (is (= 416 (.statusCode invalid-range)))
        (is (= 416 (.statusCode empty-range)))
        (is (= "bytes */16"
               (.orElse
                (.firstValue (.headers invalid-range) "Content-Range") "")))
        (is (= 503 (.statusCode unavailable)))
        (is (= {:error "derivative_asset_unavailable"
                :retryable true}
               (dissoc
                (json/read-str (.body unavailable) :key-fn keyword)
                :requestId)))
        (is (= ""
               (.orElse
                (.firstValue (.headers unavailable) "Content-Range") "")))
        (let [range-events
              (filterv
               #(and (contains?
                      #{"derivative_playback_range_started"
                        "derivative_playback_range_succeeded"
                        "derivative_playback_range_failed"}
                      (first %))
                     (= 3 (:rangeStart (second %)))
                     (= 7 (:rangeEnd (second %))))
               @events)]
          (is (= ["derivative_playback_range_started"
                  "derivative_playback_range_succeeded"
                  "derivative_playback_range_started"
                  "derivative_playback_range_failed"]
                 (mapv first range-events)))
          (is (every?
               #(= {:operation "derivative_playback"
                    :requestId operation-request-id
                    :trace "0123456789abcdef0123456789abcdef"
                    :revision "agg-proto-00001-test"
                    :rangeStart 3
                    :rangeEnd 7
                    :bytesRequested 5}
                   (select-keys
                    (second %)
                    [:operation :requestId :trace :revision :rangeStart
                     :rangeEnd :bytesRequested]))
               range-events))
          (is (= {:status "succeeded" :bytesTransferred 5}
                 (select-keys
                  (second (second range-events))
                  [:status :bytesTransferred])))
          (is (= {:status "failed"
                  :reason "storage_unavailable"
                  :bytesTransferred 0
                  :retryable true}
                 (select-keys
                  (second (last range-events))
                  [:status :reason :bytesTransferred :retryable])))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-routes-revalidate-drive-and-fail-closed-by-owner
  (let [metadata-requests (atom [])
        drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ access-token file-id]
            (swap! metadata-requests conj [access-token file-id])
            {:id file-id
             :name "private-name.mov"
             :mimeType "video/quicktime"
             :size "4096"
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "120000"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf other-cookie]}
        (auth-fixture drive-gateway)
        clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        preparation
        (derivative/in-memory-preparation-system
         {:clock clock :fingerprint-secret "fixture-secret"})
        port (test-http/available-port)
        server
        (api/start! port
                    {:auth-system system
                     :derivative-preparation-service (:service preparation)
                     :clock clock})]
    (try
      (let [path "/v1/derivative-preparations"
            headers {"Idempotency-Key" "prepare-one"}
            unauthenticated (request! port :post path {:fileId "private-id"}
                                      headers)
            no-csrf
            (request! port :post path {:fileId "private-id"}
                      (assoc headers "Cookie" (str "__session=" cookie)))
            admitted
            (request! port :post path {:fileId "private-id"}
                      (assoc headers
                             "Cookie" (str "__session=" cookie)
                             "X-CSRF-Token" csrf))
            resource (json/read-str (.body admitted) :key-fn keyword)
            status-path (:statusUrl resource)
            owner-poll
            (request! port :get status-path nil
                      {"Cookie" (str "__session=" cookie)})
            other-poll
            (request! port :get status-path nil
                      {"Cookie" (str "__session=" other-cookie)})]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= 403 (.statusCode no-csrf)))
        (is (= 202 (.statusCode admitted)) (.body admitted))
        (is (= [["private-access" "private-id"]] @metadata-requests))
        (is (= 200 (.statusCode owner-poll)))
        (is (= 404 (.statusCode other-poll)))
        (is (not-any? #(str/includes? (.body admitted) %)
                      ["private-id" "private-name.mov" "private-owner"
                       "owner@example.com" "private-access"
                       "fixture-secret"])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-admission-uses-bounded-playback-duration-when-drive-omits-it
  (let [clock-inspections (atom [])
        playback-inspections (atom [])
        drive-gateway
        (reify
          drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "private-name.mov"
             :mimeType "video/quicktime"
             :size 4096
             :version "17"
             :trashed false})
          (stream-source! [_ _ _ _])
          drive/PlaybackGateway
          (open-source-range! [_ _ _ _]
            (throw (UnsupportedOperationException.)))
          drive/PlaybackAnalysisGateway
          (inspect-playback! [_ access-token file-id metadata]
            (swap! playback-inspections conj
                   [access-token file-id
                    (select-keys metadata [:size :mimeType])])
            {:durationSeconds 84.5
             :container {:format "mp4" :majorBrand "isom"}
             :video {:codec "hevc" :codecTag "hvc1"}}))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        preparation
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        port (test-http/available-port)
        server
        (api/start! port
                    {:auth-system system
                     :derivative-preparation-service (:service preparation)})]
    (try
      (with-redefs
       [drive-gcp/inspect-recording-clock!
        (fn [_ access-token file-id metadata]
          (swap! clock-inspections conj
                 [access-token file-id
                  (select-keys metadata [:size :mimeType])])
          {:durationSeconds nil})]
        (let [response
              (request! port :post "/v1/derivative-preparations"
                        {:fileId "private-id"}
                        {"Idempotency-Key" "bounded-duration"
                         "Cookie" (str "__session=" cookie)
                         "X-CSRF-Token" csrf})]
          (is (= 202 (.statusCode response)) (.body response))
          (is (= [["private-access" "private-id"
                   {:size 4096 :mimeType "video/quicktime"}]]
                 @clock-inspections))
          (is (= [["private-access" "private-id"
                   {:size 4096 :mimeType "video/quicktime"}]]
                 @playback-inspections))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-submission-propagates-correlation-and-bounded-cost-events
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "private-name.mov"
             :mimeType "video/quicktime"
             :size "4096"
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "120000"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        preparation
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        events (atom [])
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :derivative-preparation-service (:service preparation)
          :revision "agg-proto-00001-test"
          :event-sink
          (fn [event fields]
            (swap! events conj [event fields]))})]
    (try
      (let [response
            (request!
             port :post "/v1/derivative-preparations"
             {:fileId "private-id"}
             {"Idempotency-Key" "correlated-submit"
              "Cookie" (str "__session=" cookie)
              "X-CSRF-Token" csrf
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            body (json/read-str (.body response) :key-fn keyword)
            lifecycle-events
            (filterv #(str/starts-with? (first %) "derivative_") @events)]
        (is (= 202 (.statusCode response)))
        (is (= request-id (:requestId body)))
        (is (= ["derivative_preparation_submitted"
                "derivative_cache_miss"
                "derivative_preparation_queued"]
               (mapv first lifecycle-events)))
        (is (every?
             #(= {:requestId request-id
                  :trace "0123456789abcdef0123456789abcdef"
                  :revision "agg-proto-00001-test"
                  :attempt 1
                  :profileVersion "h264-aac-1080p25-v1"}
                 (select-keys
                  (second %)
                  [:requestId :trace :revision :attempt :profileVersion]))
             lifecycle-events))
        (is (= {:operation "derivative_preparation"
                :status "succeeded"
                :sourceBytes 4096
                :durationBucket "120_to_299_seconds"
                :reservedMinorUnits 125}
               (select-keys
                (second (first lifecycle-events))
                [:operation :status :sourceBytes :durationBucket
                 :reservedMinorUnits])))
        (is (= {:operation "derivative_cache"
                :status "resolved"
                :cacheOutcome "miss"}
               (select-keys
                (second (second lifecycle-events))
                [:operation :status :cacheOutcome])))
        (is (= {:operation "derivative_queue"
                :status "queued"
                :queueDepth 1}
               (select-keys
                (second (nth lifecycle-events 2))
                [:operation :status :queueDepth])))
        (is (not (re-find
                  #"private-id|private-name|private-owner|owner@example|fixture-secret"
                  (pr-str lifecycle-events))))
        (let [cancelled
              (request!
               port :post (:cancelUrl body) {}
               {"Cookie" (str "__session=" cookie)
                "X-CSRF-Token" csrf})
              cancelled-again
              (request!
               port :post (:cancelUrl body) {}
               {"Cookie" (str "__session=" cookie)
                "X-CSRF-Token" csrf})
              terminal-events
              (filterv
               #(= "derivative_preparation_terminal" (first %))
               @events)
              cancel-event
              (first terminal-events)]
          (is (= 200 (.statusCode cancelled)))
          (is (= 200 (.statusCode cancelled-again)))
          (is (= 1 (count terminal-events)))
          (is (= request-id
                 (.orElse
                  (.firstValue (.headers cancelled) "X-Request-Id") "")))
          (is (= {:operation "derivative_cancellation"
                  :status "cancelled"
                  :reason "user_cancelled"
                  :requestId request-id
                  :attempt 1
                  :profileVersion "h264-aac-1080p25-v1"
                  :cancellationLagMs 0}
                 (select-keys
                  (second cancel-event)
                  [:operation :status :reason :requestId :attempt
                   :profileVersion :cancellationLagMs])))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-cache-hit-emits-one-terminal-success-without-reservation
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "private-name.mov"
             :mimeType "video/quicktime"
             :size "4096"
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "120000"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        preparation
        (derivative/in-memory-preparation-system
         {:clock clock :fingerprint-secret "fixture-secret"})
        _ (derivative/put-preparation-cache!
           (:service preparation)
           {:subject "private-owner"
            :email "owner@example.com"
            :membership-version nil
            :file-id "private-id"
            :drive-version "17"
            :source-bytes 4096
            :source-duration-seconds 120.0}
           {:asset-id "00000000-0000-0000-0000-000000000195"
            :expires-at (.plusSeconds (Instant/now clock) 7200)})
        events (atom [])
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :derivative-preparation-service (:service preparation)
          :event-sink
          (fn [event fields]
            (swap! events conj [event fields]))})]
    (try
      (let [response
            (request!
             port :post "/v1/derivative-preparations"
             {:fileId "private-id"}
             {"Idempotency-Key" "cache-hit-submit"
              "Cookie" (str "__session=" cookie)
              "X-CSRF-Token" csrf})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            derivative-events
            (filterv #(str/starts-with? (first %) "derivative_") @events)
            terminal-events
            (filterv
             #(= "derivative_preparation_terminal" (first %))
             derivative-events)]
        (is (= 200 (.statusCode response)))
        (is (= ["derivative_preparation_submitted"
                "derivative_cache_hit"
                "derivative_preparation_terminal"]
               (mapv first derivative-events)))
        (is (= 1 (count terminal-events)))
        (is (= {:operation "derivative_preparation"
                :status "succeeded"
                :reason "cache_hit"
                :requestId request-id
                :cacheOutcome "hit"
                :reservedMinorUnits 0}
               (select-keys
                (second (first terminal-events))
                [:operation :status :reason :requestId :cacheOutcome
                 :reservedMinorUnits]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-errors-include-request-id-and-retryability
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ _]
            {:id "private-id"
             :mimeType "video/quicktime"
             :size 4096
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "999999999"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        preparation
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        events (atom [])
        port (test-http/available-port)
        server
        (api/start! port
                    {:auth-system system
                     :derivative-preparation-service (:service preparation)
                     :event-sink
                     (fn [event fields]
                       (swap! events conj [event fields]))})]
    (try
      (let [response
            (request! port :post "/v1/derivative-preparations"
                      {:fileId "private-id"}
                      {"Idempotency-Key" "too-long"
                       "Cookie" (str "__session=" cookie)
                       "X-CSRF-Token" csrf})
            body (json/read-str (.body response) :key-fn keyword)
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") nil)]
        (is (= 422 (.statusCode response)))
        (is (= "source_duration_exceeded" (:error body)))
        (is (= request-id (:requestId body)))
        (is (false? (:retryable body)))
        (is (not (str/includes? (.body response) "private-id")))
        (let [terminal-events
              (filterv
               #(= "derivative_preparation_terminal" (first %))
               @events)]
          (is (= 1 (count terminal-events)))
          (is (= {:operation "derivative_preparation"
                  :status "rejected"
                  :reason "source_duration_exceeded"
                  :requestId request-id
                  :retryable false}
                 (select-keys
                  (second (first terminal-events))
                  [:operation :status :reason :requestId :retryable])))
          (is (not (re-find
                    #"private-id|private-owner|owner@example"
                    (pr-str terminal-events))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest internal-preparation-dispatch-and-reconciliation-require-exact-callers
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ _])
          (stream-source! [_ _ _ _]))
        {:keys [system]} (auth-fixture drive-gateway)
        calls (atom [])
        events (atom [])
        operation-request-id "00000000-0000-0000-0000-000000000197"
        service
        (reify derivative/PreparationService
          (submit-preparation! [_ _ _])
          (get-preparation [_ _])
          (dispatch-preparation! [_ _])
          (dispatch-preparation! [_ job-id attempt]
            (swap! calls conj [:dispatch job-id attempt])
            {:started? true
             :job {:id job-id
                   :state "running"
                   :attempt attempt
                   :profileVersion "h264-aac-1080p25-v1"
                   :requestId operation-request-id
                   :createdAt "2026-07-30T10:00:00Z"}})
          (cancel-preparation! [_ _])
          (retry-preparation! [_ _])
          (reconcile-preparations! [_]
            (swap! calls conj [:reconcile])
            {:repairedJobs 2
             :terminalJobs
             [{:id "00000000-0000-0000-0000-000000000198"
               :state "expired"
               :attempt 1
               :profileVersion "h264-aac-1080p25-v1"
               :requestId operation-request-id
               :cancellationLagMs 2000}
              {:id "00000000-0000-0000-0000-000000000199"
               :state "cancelled"
               :attempt 2
               :profileVersion "h264-aac-1080p25-v1"
               :requestId operation-request-id
               :cancellationLagMs 3000}]}))
        verifier
        (reify auth/TaskTokenVerifier
          (verify-task-token! [_ token]
            (when-let [email
                       ({"derivative-task-token" "derivative-tasks@example.com"
                         "scheduler-token" "scheduler@example.com"}
                        token)]
              {:issuer "https://accounts.google.com"
               :audience "https://app.example.com"
               :email email
               :email-verified? true})))
        derivative-verifier
        (reify auth/TaskTokenVerifier
          (verify-task-token! [_ token]
            (when-let [email
                       ({"derivative-task-token"
                         "derivative-tasks@example.com"
                         "scheduler-token" "scheduler@example.com"}
                        token)]
              {:issuer "https://accounts.google.com"
               :audience "https://proto.example.com"
               :email email
               :email-verified? true})))
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :derivative-preparation-service service
          :task-token-verifier verifier
          :task-audience "https://app.example.com"
          :derivative-task-token-verifier derivative-verifier
          :derivative-task-audience "https://proto.example.com"
          :derivative-tasks-service-account
          "derivative-tasks@example.com"
          :scheduler-service-account "scheduler@example.com"
          :event-sink
          (fn [event fields]
            (swap! events conj [event fields]))})]
    (try
      (let [job-id "00000000-0000-0000-0000-000000000193"
            dispatch-path
            (str "/internal/v1/derivative-preparations/" job-id
                 "/attempts/3/dispatch")
            unauthenticated
            (request! port :post dispatch-path {} {})
            wrong-caller
            (request! port :post dispatch-path {}
                      {"X-CloudTasks-TaskName" "tasks/wrong"
                       "Authorization" "Bearer scheduler-token"})
            dispatched
            (request! port :post dispatch-path {}
                      {"X-CloudTasks-TaskName" "tasks/exact"
                       "Authorization" "Bearer derivative-task-token"})
            reconcile-path
            "/internal/v1/derivative-preparations/reconcile"
            task-spoofs-scheduler
            (request! port :post reconcile-path {}
                      {"X-CloudScheduler" "true"
                       "Authorization" "Bearer derivative-task-token"})
            reconciled
            (request! port :post reconcile-path {}
                      {"X-CloudScheduler" "true"
                       "Authorization" "Bearer scheduler-token"})]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= 401 (.statusCode wrong-caller)))
        (is (= 202 (.statusCode dispatched)))
        (is (= 401 (.statusCode task-spoofs-scheduler)))
        (is (= 200 (.statusCode reconciled)))
        (is (= [[:dispatch job-id 3] [:reconcile]] @calls))
        (let [dispatch-event
              (some #(when (= "derivative_preparation_dispatched" (first %))
                       %)
                    @events)
              terminal-events
              (filterv
               #(= "derivative_preparation_terminal" (first %))
               @events)
              reconciliation-event
              (some #(when (= "derivative_reconciliation_complete" (first %))
                       %)
                    @events)]
          (is (= {:operation "derivative_dispatch"
                  :status "succeeded"
                  :requestId operation-request-id
                  :attempt 3
                  :profileVersion "h264-aac-1080p25-v1"}
                 (select-keys
                  (second dispatch-event)
                  [:operation :status :requestId :attempt :profileVersion])))
          (is (<= 0 (:queueAgeMs (second dispatch-event))))
          (is (= [{:operation "derivative_preparation"
                   :status "expired"
                   :reason "expired"
                   :requestId operation-request-id
                   :attempt 1
                   :cancellationLagMs 2000}
                  {:operation "derivative_preparation"
                   :status "cancelled"
                   :reason "cancelled"
                   :requestId operation-request-id
                   :attempt 2
                   :cancellationLagMs 3000}]
                 (mapv
                  #(select-keys
                    (second %)
                    [:operation :status :reason :requestId :attempt
                     :cancellationLagMs])
                  terminal-events)))
          (is (= {:operation "derivative_reconciliation"
                  :status "succeeded"
                  :repairedJobs 2}
                 (select-keys
                  (second reconciliation-event)
                  [:operation :status :repairedJobs])))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
