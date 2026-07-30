(ns agg.derivative-worker-test
  (:refer-clojure :exclude [proxy])
  (:require [agg.derivative.lifecycle :as derivative]
            [agg.derivative.storage :as storage]
            [agg.derivative.worker :as worker]
            [agg.drive.range-proxy :as range-proxy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io Closeable)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(defn- proxy [stats]
  (reify
    Closeable
    (close [_])
    clojure.lang.ILookup
    (valAt [_ key]
      (case key
        :url "http://127.0.0.1:43123/source/opaque"
        :stats (constantly stats)
        nil))
    (valAt [this key not-found]
      (or (.valAt ^clojure.lang.ILookup this key) not-found))))

(def derivative-request
  {:classification :derivative-required
   :source-duration-seconds 10
   :source-bytes 4096
   :source-width 320
   :source-height 180
   :source-has-audio? true
   :output-path (Path/of "/tmp/opaque-output.mp4"
                         (make-array String 0))})

(deftest directly-playable-sources-never-open-or-encode
  (let [calls (atom [])]
    (is (= {:classification :direct-passthrough}
           (worker/run!
            (assoc derivative-request :classification :direct-passthrough)
            {:proxy-config {:file-id "private-source-id"
                            :access-token "private-authority"}
             :start-source-proxy!
             (fn [_] (swap! calls conj :proxy))
             :encode!
             (fn [_] (swap! calls conj :encode))})))
    (is (empty? @calls))))

(deftest derivative-worker-confines-identity-and-returns-bounded-results
  (let [proxy-request (atom nil)
        encode-request (atom nil)
        private-values ["private-source-id" "private-authority"
                        "private-owner"]
        result
        (worker/run!
         derivative-request
         {:proxy-config {:gateway :private-gateway
                         :file-id "private-source-id"
                         :access-token "private-authority"
                         :owner "private-owner"}
          :start-source-proxy!
          (fn [request]
            (reset! proxy-request request)
            (proxy {:upstream-bytes 4096
                    :request-count 2
                    :retry-count 0
                    :cache-hit-count 1
                    :failure-reason nil}))
          :encode!
          (fn [request]
            (reset! encode-request request)
            {:output-path (:output-path request)
             :content-type "video/mp4"
             :output-bytes 2048
             :duration-seconds 10.0
             :video {:codec "h264"}
             :audio {:codec "aac"}
             :fast-start? true})
          :inspect-source!
          (fn [_] {:width 320 :height 180 :audio? true})
          :cancelled? (constantly false)})]
    (is (= "private-source-id" (:file-id @proxy-request)))
    (is (= {:classification :derivative-ready
            :output-path (:output-path derivative-request)
            :content-type "video/mp4"
            :output-bytes 2048
            :duration-seconds 10.0
            :video {:codec "h264"}
            :audio {:codec "aac"}
            :fast-start? true
            :transfer {:upstream-bytes 4096
                       :request-count 2
                       :retry-count 0
                       :cache-hit-count 1}}
           result))
    (is (= "http://127.0.0.1:43123/source/opaque"
           (:source-url @encode-request)))
    (is (identical? (:cancelled? @encode-request)
                    (:cancelled? @encode-request)))
    (doseq [private-value private-values]
      (is (not (str/includes? (pr-str @encode-request) private-value)))
      (is (not (str/includes? (pr-str result) private-value))))))

(deftest derivative-worker-validates-authoritative-and-transfer-boundaries
  (let [limits {:source-duration-seconds 480
                :source-bytes 2147483648}
        start-proxy!
        (fn [_]
          (proxy {:upstream-bytes 2415919104
                  :request-count 320
                  :retry-count 0
                  :cache-hit-count 0
                  :failure-reason nil}))
        encode!
        (fn [request]
          {:output-path (:output-path request)
           :content-type "video/mp4"
           :output-bytes 268435456
           :duration-seconds 480.0
           :video {:codec "h264"}
           :audio {:codec "aac"}
           :fast-start? true})
        dependencies {:proxy-config {:gateway :gateway
                                     :file-id "id"
                                     :access-token "authority"}
                      :start-source-proxy! start-proxy!
                      :inspect-source!
                      (fn [_] {:width 320 :height 180 :audio? true})
                      :encode! encode!}]
    (is (= :derivative-ready
           (:classification
            (worker/run! (merge derivative-request limits) dependencies))))
    (doseq [[field failure-code]
            [[:source-duration-seconds "source_duration_exceeded"]
             [:source-bytes "source_size_exceeded"]]]
      (let [error
            (try
              (worker/run! (update (merge derivative-request limits) field inc)
                           dependencies)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= failure-code (:failure-code (ex-data error))))))))

(deftest worker-cli-and-runtime-limits-match-the-cloud-run-contract
  (is (= {:job-id "00000000-0000-0000-0000-000000000194"
          :attempt 2}
         (worker/parse-options
          ["--job-id" "00000000-0000-0000-0000-000000000194"
           "--attempt" "2"])))
  (doseq [invalid-args
          [[] ["--job-id"]
           ["--job-id" "private-source-id" "--attempt" "1"]
           ["--job-id" "00000000-0000-0000-0000-000000000194"
            "--attempt" "0"]]]
    (is (= ::worker/invalid-options
           (:type
            (try
              (worker/parse-options invalid-args)
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))))))
  (let [environment
        {"AGG_DERIVATIVE_BUCKET" "opaque-bucket"
         "AGG_DERIVATIVE_WORKER_JOB" "agg-derivative-preview"
         "AGG_DERIVATIVE_WORKER_SERVICE_ACCOUNT" "opaque-worker"
         "AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS" "480"
         "AGG_DERIVATIVE_MAX_SOURCE_BYTES" "2147483648"
         "AGG_DERIVATIVE_MAX_UPSTREAM_BYTES" "2415919104"
         "AGG_DERIVATIVE_MAX_REQUEST_COUNT" "320"
         "AGG_DERIVATIVE_MAX_RANGE_BYTES" "8388608"
         "AGG_DERIVATIVE_MAX_OUTPUT_BYTES" "268435456"}]
    (is (= {:source-duration-seconds 480
            :source-bytes 2147483648
            :upstream-bytes 2415919104
            :request-count 320
            :range-bytes 8388608
            :output-bytes 268435456}
           (worker/runtime-limits-from-environment environment)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (worker/runtime-limits-from-environment
                  (assoc environment
                         "AGG_DERIVATIVE_MAX_OUTPUT_BYTES" "268435457")))))
  (is (= {:max-upstream-bytes 2415919104
          :max-request-count 320
          :max-range-bytes 8388608
          :max-cache-bytes 8388608
          :lifetime-ms 900000}
         (select-keys range-proxy/derivative-limits-v1
                      [:max-upstream-bytes :max-request-count
                       :max-range-bytes :max-cache-bytes :lifetime-ms]))))

(deftest cloud-attempt-consumes-only-the-exact-private-worker-record
  (let [job-id "00000000-0000-0000-0000-000000000194"
        output (Files/createTempFile
                "agg-worker-output-" ".mp4"
                (make-array FileAttribute 0))
        calls (atom [])
        events (atom [])
        operation-request-id "00000000-0000-0000-0000-000000000197"
        result
        (try
          (worker/run-cloud-attempt!
           {:job-id job-id :attempt 2}
           {:service :service
            :output-path output
            :load-preparation-attempt
            (fn [service loaded-job-id attempt]
              (swap! calls conj [:load service loaded-job-id attempt])
              {:job-id job-id
               :attempt 2
               :profile worker/profile
               :asset {:id job-id
                       :object-key "derivatives/opaque-final.mp4"}
               :source {:file-id "private-id"
                        :drive-version "private-version"
                        :bytes 4096
                        :duration-seconds 10}
               :owner {:subject "private-owner"
                       :membership-version "private-membership"}
               :observability
               {:request-id operation-request-id
                :trace "0123456789abcdef0123456789abcdef"
                :revision "agg-proto-00001-test"
                :reservation-minor-units 125}})
            :source-access!
            (fn [service loaded-job-id attempt]
              (swap! calls conj [:access service loaded-job-id attempt])
              {:gateway :gateway
               :access-token "private-authority"
               :file-id "private-id"})
            :preparation-cancellation-requested?
            (fn [service loaded-job-id attempt]
              (swap! calls conj [:cancel? service loaded-job-id attempt])
              false)
            :start-source-proxy!
            (fn [request]
              (is (= "private-authority" (:access-token request)))
              (proxy {:upstream-bytes 4096
                      :request-count 1
                      :retry-count 0
                      :cache-hit-count 0
                      :failure-reason nil}))
            :inspect-source!
            (fn [_] {:width 320 :height 180 :audio? true})
            :encode!
            (fn [request]
              (is (= "http://127.0.0.1:43123/source/opaque"
                     (:source-url request)))
              (is (false? ((:cancelled? request))))
              {:output-path (:output-path request)
               :content-type "video/mp4"
               :output-bytes 2048
               :duration-seconds 10.0
               :video {:codec "h264"}
               :audio {:codec "aac"}
               :fast-start? true})
            :publish-derivative!
            (fn [service publication]
              (swap! calls conj
                     [:publish service
                      (select-keys publication
                                   [:job-id :attempt :asset-id :object-key
                                    :content-type :size :profile-version])])
              {:generation 42
               :size 2048
               :content-type "video/mp4"
               :profile-version (:version worker/profile)})
            :complete-preparation-attempt!
            (fn [service completed-job-id attempt completion]
              (swap! calls conj
                     [:complete service completed-job-id attempt completion])
              {:id completed-job-id :state "succeeded"})
            :event-sink
            (fn [event fields]
              (swap! events conj [event fields]))})
          (finally
            (Files/deleteIfExists output)))]
    (is (= {:id job-id :state "succeeded"} result))
    (is (= [[:load :service job-id 2]
            [:access :service job-id 2]
            [:cancel? :service job-id 2]
            [:publish :service
             {:job-id job-id
              :attempt 2
              :asset-id job-id
              :object-key "derivatives/opaque-final.mp4"
              :content-type "video/mp4"
              :size 2048
              :profile-version (:version worker/profile)}]
            [:cancel? :service job-id 2]
            [:complete :service job-id 2
             {:asset-id job-id
              :object-key "derivatives/opaque-final.mp4"
              :generation 42
              :size 2048
              :content-type "video/mp4"
              :profile-version (:version worker/profile)
              :measurements {:output-bytes 2048}}]]
           @calls))
    (is (not (Files/exists
              output (make-array java.nio.file.LinkOption 0))))
    (is (not (str/includes? (pr-str result) "private")))
    (is (= ["derivative_encode_started"
            "derivative_encode_exited"
            "derivative_verification_succeeded"
            "derivative_publication_started"
            "derivative_publication_succeeded"
            "derivative_preparation_terminal"]
           (mapv first @events)))
    (is (every?
         #(= {:requestId operation-request-id
              :trace "0123456789abcdef0123456789abcdef"
              :revision "agg-proto-00001-test"
              :attempt 2
              :profileVersion "h264-aac-1080p25-v1"}
             (select-keys
              (second %)
              [:requestId :trace :revision :attempt :profileVersion]))
         @events))
    (is (= {:operation "derivative_encode"
            :status "succeeded"
            :sourceBytes 4096
            :upstreamBytes 4096
            :outputBytes 2048}
           (select-keys
            (second (nth @events 1))
            [:operation :status :sourceBytes :upstreamBytes :outputBytes])))
    (is (= {:operation "derivative_preparation"
            :status "succeeded"
            :reason "completed"
            :outputBytes 2048
            :reservedMinorUnits 125}
           (select-keys
            (second (last @events))
            [:operation :status :reason :outputBytes
             :reservedMinorUnits])))
    (is (not (re-find
              #"private-id|private-version|private-owner|private-authority|objectKey"
              (pr-str @events))))))

(deftest cancellation-racing-publication-removes-the-exact-published-generation
  (let [job-id "00000000-0000-0000-0000-000000000195"
        output (Files/createTempFile
                "agg-worker-publish-race-" ".mp4"
                (make-array FileAttribute 0))
        cancellation-checks (atom 0)
        deleted (atom [])
        completed? (atom false)
        dependencies
        {:service :service
         :output-path output
         :load-preparation-attempt
         (fn [_ _ _]
           {:job-id job-id
            :attempt 1
            :profile worker/profile
            :asset {:id job-id
                    :object-key "derivatives/opaque-final.mp4"}
            :source {:file-id "private-id"
                     :drive-version "private-version"
                     :bytes 4096
                     :duration-seconds 10}
            :owner {:subject "private-owner"
                    :membership-version "private-membership"}})
         :source-access!
         (fn [_ _ _]
           {:gateway :gateway
            :access-token "private-authority"
            :file-id "private-id"})
         :preparation-cancellation-requested?
         (fn [_ _ _]
           (pos? (swap! cancellation-checks inc)))
         :start-source-proxy!
         (fn [_]
           (proxy {:upstream-bytes 4096
                   :request-count 1
                   :retry-count 0
                   :cache-hit-count 0
                   :failure-reason nil}))
         :inspect-source! (fn [_] {:width 320 :height 180 :audio? true})
         :encode!
         (fn [request]
           {:output-path (:output-path request)
            :content-type "video/mp4"
            :output-bytes 2048
            :duration-seconds 10.0
            :video {:codec "h264"}
            :audio {:codec "aac"}
            :fast-start? true})
         :publish-derivative!
         (fn [_ _]
           {:generation 42
            :size 2048
            :content-type "video/mp4"
            :profile-version (:version worker/profile)})
         :delete-derivative!
         (fn [_ asset]
           (swap! deleted conj asset))
         :complete-preparation-attempt!
         (fn [& _] (reset! completed? true))
         :acknowledge-preparation-cancellation!
         (fn [& _])}]
    (try
      (is (= ::worker/publication-cancelled
             (:type
              (try
                (worker/run-cloud-attempt!
                 {:job-id job-id :attempt 1}
                 dependencies)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))))
      (is (false? @completed?))
      (is (= [{:object-key "derivatives/opaque-final.mp4"
               :generation 42}]
             @deleted))
      (is (not (Files/exists
                output (make-array java.nio.file.LinkOption 0))))
      (finally
        (Files/deleteIfExists output)))))

(deftest duplicate-worker-after-publication-keeps-winning-generation-playable
  (let [{:keys [service]}
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        request {:subject "private-owner"
                 :email "owner@example.com"
                 :membership-version "membership-v1"
                 :file-id "private-id"
                 :drive-version "17"
                 :source-bytes 4096
                 :source-duration-seconds 10.0}
        job-id
        (get-in (derivative/submit-preparation!
                 service "duplicate-worker" request)
                [:job :id])
        _ (derivative/dispatch-preparation! service job-id)
        asset-store (storage/in-memory-asset-store)
        output (Files/createTempFile
                "agg-worker-duplicate-" ".mp4"
                (make-array FileAttribute 0))
        bytes (.getBytes "0123456789abcdef")
        published (atom nil)
        dependencies
        {:service service
         :output-path output
         :load-preparation-attempt derivative/load-preparation-attempt
         :source-access!
         (fn [_ _ _]
           {:gateway :gateway
            :access-token "private-authority"
            :file-id "private-id"})
         :preparation-cancellation-requested?
         derivative/preparation-cancellation-requested?
         :start-source-proxy!
         (fn [_]
           (proxy {:upstream-bytes 4096
                   :request-count 1
                   :retry-count 0
                   :cache-hit-count 0
                   :failure-reason nil}))
         :inspect-source! (fn [_] {:width 320 :height 180 :audio? true})
         :encode!
         (fn [encode-request]
           (Files/write ^Path (:output-path encode-request)
                        ^bytes bytes
                        (make-array java.nio.file.OpenOption 0))
           {:output-path (:output-path encode-request)
            :content-type "video/mp4"
            :output-bytes (alength ^bytes bytes)
            :duration-seconds 10.0
            :video {:codec "h264"}
            :audio {:codec "aac"}
            :fast-start? true})
         :publish-derivative!
         (fn [_ publication]
           (let [stored (storage/publish-verified! asset-store publication)
                 completion
                 (merge stored
                        {:asset-id (:asset-id publication)
                         :object-key (:object-key publication)
                         :measurements {:output-bytes (:size publication)}})]
             (reset! published
                     (merge stored
                            {:object-key (:object-key publication)}))
             (derivative/complete-preparation-attempt!
              service job-id 1 completion)
             stored))
         :delete-derivative!
         (fn [_ asset]
           (storage/delete-generation! asset-store asset))
         :complete-preparation-attempt!
         derivative/complete-preparation-attempt!
         :fail-preparation-attempt!
         derivative/fail-preparation-attempt!
         :acknowledge-preparation-cancellation!
         derivative/acknowledge-preparation-cancellation!}
        result
        (try
          (worker/run-cloud-attempt!
           {:job-id job-id :attempt 1}
           dependencies)
          (finally
            (Files/deleteIfExists output)))
        response
        (storage/open-range! asset-store @published {:start 3 :end 7})]
    (is (= "succeeded" (:state result)))
    (is (= "34567"
           (with-open [body (:body response)]
             (String. (.readAllBytes body)))))
    (is (= "succeeded"
           (:state (derivative/get-preparation service job-id))))))

(deftest proxy-budget-failure-wins-over-ffmpeg-symptoms
  (let [data
        (try
          (worker/run!
           derivative-request
           {:proxy-config {:gateway :gateway
                           :file-id "private-id"
                           :access-token "private-authority"}
            :start-source-proxy!
            (fn [_]
              (proxy {:upstream-bytes 2415915008
                      :request-count 288
                      :retry-count 0
                      :cache-hit-count 0
                      :failure-reason "work_budget_exhausted"}))
            :inspect-source!
            (fn [_] {:width 320 :height 180 :audio? true})
            :encode!
            (fn [_]
              (throw
               (ex-info "ffmpeg symptom"
                        {:type :agg.render.derivative/encode-failed
                         :failure-code "derivative_encode_failed"})))})
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))]
    (is (= "upstream_transfer_exceeded" (:failure-code data)))
    (is (= #{:type :source :failure-code}
           (set (keys data))))))

(deftest cloud-attempt-acknowledges-only-its-exact-cancellation
  (let [job-id "00000000-0000-0000-0000-000000000194"
        output (Files/createTempFile
                "agg-worker-cancel-" ".mp4"
                (make-array FileAttribute 0))
        calls (atom [])
        events (atom [])
        dependencies
        {:service :service
         :output-path output
         :load-preparation-attempt
         (fn [_ _ _]
           {:job-id job-id
            :attempt 3
            :profile worker/profile
            :asset {:id job-id
                    :object-key "derivatives/opaque-final.mp4"}
            :source {:file-id "private-id"
                     :drive-version nil
                     :bytes 4096
                     :duration-seconds 10}
            :owner {:subject "private-owner"
                    :membership-version nil}
            :observability
            {:request-id "00000000-0000-0000-0000-000000000197"
             :trace "0123456789abcdef0123456789abcdef"
             :revision "agg-proto-00001-test"
             :reservation-minor-units 125}})
         :source-access!
         (fn [_ _ _]
           {:gateway :gateway
            :access-token "private-authority"
            :file-id "private-id"})
         :preparation-cancellation-requested?
         (fn [service checked-job-id attempt]
           (swap! calls conj [:cancel? service checked-job-id attempt])
           true)
         :acknowledge-preparation-cancellation!
         (fn [service checked-job-id attempt]
           (swap! calls conj [:ack service checked-job-id attempt]))
         :fail-preparation-attempt!
         (fn [& args] (swap! calls conj (into [:fail] args)))
         :start-source-proxy!
         (fn [_]
           (proxy {:upstream-bytes 0
                   :request-count 0
                   :retry-count 0
                   :cache-hit-count 0
                   :failure-reason nil}))
         :inspect-source!
         (fn [_] {:width 320 :height 180 :audio? true})
         :encode!
         (fn [request]
           (when ((:cancelled? request))
             (throw
              (ex-info "cancelled"
                       {:type :agg.render.derivative/cancelled}))))
         :event-sink
         (fn [event fields]
           (swap! events conj [event fields]))}]
    (try
      (is (= :agg.render.derivative/cancelled
             (:type
              (try
                (worker/run-cloud-attempt!
                 {:job-id job-id :attempt 3}
                 dependencies)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))))
      (is (= [[:cancel? :service job-id 3]
              [:ack :service job-id 3]]
             @calls))
      (let [terminal-events
            (filterv
             #(= "derivative_preparation_terminal" (first %))
             @events)
            fields (second (first terminal-events))]
        (is (= 1 (count terminal-events)))
        (is (= {:operation "derivative_preparation"
                :status "cancelled"
                :reason "cancelled"
                :requestId "00000000-0000-0000-0000-000000000197"
                :trace "0123456789abcdef0123456789abcdef"
                :revision "agg-proto-00001-test"
                :attempt 3
                :reservedMinorUnits 125}
               (select-keys
                fields
                [:operation :status :reason :requestId :trace :revision
                 :attempt :reservedMinorUnits])))
        (is (<= 0 (:cancellationLagMs fields))))
      (is (not (Files/exists
                output (make-array java.nio.file.LinkOption 0))))
      (finally
        (Files/deleteIfExists output)))))

(deftest cloud-attempt-identifies-publication-failure-without-storage-details
  (let [job-id "00000000-0000-0000-0000-000000000197"
        output (Files/createTempFile
                "agg-worker-publication-failure-" ".mp4"
                (make-array FileAttribute 0))
        events (atom [])
        failures (atom [])]
    (try
      (with-redefs
       [worker/run!
        (fn [_ _]
          {:output-path output
           :content-type "video/mp4"
           :output-bytes 2048
           :duration-seconds 10.0
           :video {:codec "h264"}
           :audio {:codec "aac"}
           :fast-start? true})]
        (is (thrown?
             clojure.lang.ExceptionInfo
             (worker/run-cloud-attempt!
              {:job-id job-id :attempt 1}
              {:service :service
               :output-path output
               :load-preparation-attempt
               (fn [_ _ _]
                 {:job-id job-id
                  :attempt 1
                  :profile worker/profile
                  :asset {:id job-id
                          :object-key "derivatives/opaque-final.mp4"}
                  :source {:file-id "private-id"
                           :bytes 4096
                           :duration-seconds 10}
                  :owner {:subject "private-owner"}
                  :observability
                  {:request-id job-id
                   :reservation-minor-units 125}})
               :source-access!
               (fn [_ _ _]
                 {:gateway :gateway
                  :access-token "private-authority"
                  :file-id "private-id"})
               :preparation-cancellation-requested?
               (fn [& _] false)
               :publish-derivative!
               (fn [& _]
                 (throw
                  (ex-info "storage symptom"
                           {:type ::storage-write-failed
                            :object-key "private-object-key"})))
               :fail-preparation-attempt!
               (fn [_ _ _ failure]
                 (swap! failures conj failure))
               :event-sink
               (fn [event fields]
                 (swap! events conj [event fields]))}))))
      (is (= [{:failure-code "derivative_failed"
               :retryable false}]
             @failures))
      (let [terminal
            (second
             (first
              (filter
               #(= "derivative_preparation_terminal" (first %))
               @events)))]
        (is (= {:operation "derivative_preparation"
                :status "failed"
                :reason "publication_failed"
                :requestId job-id}
               (select-keys
                terminal
                [:operation :status :reason :requestId])))
        (is (not (contains? terminal :objectKey)))
        (is (not (str/includes? (pr-str terminal) "private-object-key"))))
      (finally
        (Files/deleteIfExists output)))))
