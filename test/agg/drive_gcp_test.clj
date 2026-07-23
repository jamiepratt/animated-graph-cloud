(ns agg.drive-gcp-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.drive.gcp :as gcp]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (com.google.cloud.firestore FirestoreOptions)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption)))

(defn- atom-bytes [atom-type payload]
  (let [type-bytes (.getBytes ^String atom-type StandardCharsets/ISO_8859_1)
        buffer (doto (ByteBuffer/allocate (+ 8 (alength ^bytes payload)))
                 (.order ByteOrder/BIG_ENDIAN)
                 (.putInt (+ 8 (alength ^bytes payload)))
                 (.put type-bytes)
                 (.put ^bytes payload))]
    (.array buffer)))

(defn- quicktime-header [creation-seconds-since-1904]
  (let [buffer (doto (ByteBuffer/allocate 20)
                 (.order ByteOrder/BIG_ENDIAN)
                 (.putInt 0)
                 (.putInt (unchecked-int creation-seconds-since-1904))
                 (.putInt (unchecked-int creation-seconds-since-1904))
                 (.putInt 1000)
                 (.putInt 125500))]
    (.array buffer)))

(defn- joined-bytes [& chunks]
  (let [output (ByteArrayOutputStream.)]
    (doseq [chunk chunks]
      (.write output ^bytes chunk))
    (.toByteArray output)))

(deftest recording-clock-inspection-is-bounded-and-prefers-explicit-offsets
  (let [requests (atom [])
        explicit
        (atom-bytes "©day"
                    (.getBytes "2026-07-23T14:30:15+02:00"
                               StandardCharsets/UTF_8))
        local-movie (atom-bytes "mvhd" (quicktime-header 3867667200))
        bytes (joined-bytes local-movie explicit)
        gateway
        (reify drive/PlaybackGateway
          (open-source-range! [_ _ _ byte-range]
            (swap! requests conj byte-range)
            {:status 206
             :headers {}
             :body (ByteArrayInputStream.
                    (if (zero? (:start byte-range))
                      bytes
                      (.getBytes "no metadata" StandardCharsets/UTF_8)))}))
        result (gcp/inspect-recording-clock!
                gateway "access" "private-file"
                {:size (* 2 1024 1024)})]
    (is (= [{:start 0 :end 262143 :timeout-ms 3000}
            {:start 1835008 :end 2097151 :timeout-ms 3000}]
           @requests))
    (is (= {:maxBytes 524288
            :maxRanges 2
            :timeoutMillis 3000}
           (:limits result)))
    (is (= 125.5 (:durationSeconds result)))
    (is (= "candidate" (:status result)))
    (is (= "2026-07-23T14:30:15+02:00"
           (get-in result [:candidates 0 :value])))
    (is (= "explicit-offset"
           (get-in result [:candidates 0 :kind])))
    (is (= 0 (:recommendedIndex result)))
    (is (= false (:ambiguous result)))
    (is (some #(= "local-date-time" (:kind %))
              (:candidates result)))))

(deftest recording-clock-inspection-exposes-conflicts-and-falls-back-safely
  (let [conflicting
        (joined-bytes
         (atom-bytes "mvhd"
                     (.getBytes "2026-07-23T14:30:15+02:00"
                                StandardCharsets/UTF_8))
         (atom-bytes "tkhd"
                     (.getBytes "2026-07-23T13:30:15Z"
                                StandardCharsets/UTF_8)))
        inspect
        (fn [bytes]
          (gcp/inspect-recording-clock!
           (reify drive/PlaybackGateway
             (open-source-range! [_ _ _ _]
               {:status 206
                :headers {}
                :body (ByteArrayInputStream. bytes)}))
           "access" "private-file" {:size (alength ^bytes bytes)}))]
    (let [result (inspect conflicting)]
      (is (= "candidate" (:status result)))
      (is (= true (:ambiguous result)))
      (is (nil? (:recommendedIndex result)))
      (is (= #{"movie" "track"}
             (set (map :source (:candidates result))))))
    (doseq [bytes [(.getBytes "malformed 2026-99-99T88:00:00+02:00"
                              StandardCharsets/UTF_8)
                   (.getBytes "container has no clock" StandardCharsets/UTF_8)]]
      (is (= {:status "manual"
              :candidates []
              :recommendedIndex nil
              :ambiguous false
              :durationSeconds nil
              :limits {:maxBytes 524288
                       :maxRanges 2
                       :timeoutMillis 3000}}
             (inspect bytes))))))

(deftest recording-clock-inspection-timeout-prompts-manual-entry
  (let [result
        (gcp/inspect-recording-clock!
         (reify drive/PlaybackGateway
           (open-source-range! [_ _ _ _]
             (throw (java.net.http.HttpTimeoutException. "bounded timeout"))))
         "access" "private-file" {:size (* 4 1024 1024)})]
    (is (= "manual" (:status result)))
    (is (empty? (:candidates result)))))

(defn- local-upload-server [requests]
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)]
    (.createContext
     server "/upload"
     (reify HttpHandler
       (^void handle [_ ^HttpExchange exchange]
         (let [request {:content-length (some-> exchange .getRequestHeaders
                                                (.getFirst "Content-Length"))
                        :content-range (some-> exchange .getRequestHeaders
                                               (.getFirst "Content-Range"))
                        :body (String. (.readAllBytes (.getRequestBody exchange))
                                       StandardCharsets/UTF_8)}]
           (swap! requests conj request)
           (let [body (.getBytes (json/write-str {:id "file-1"})
                                 StandardCharsets/UTF_8)]
             (.sendResponseHeaders exchange 200 (alength body))
             (.write (.getResponseBody exchange) body))
           (.close exchange)))))
    (.start server)
    server))

(deftest stored-drive-folder-is-verified-and-reused
  (let [requests (atom [])
        gateway
        (gcp/->RestDriveGateway
         (fn [request]
           (swap! requests conj request)
           {:status 200
            :body (json/write-str {:id "folder-1"
                                   :mimeType "application/vnd.google-apps.folder"
                                   :trashed false})})
         (* 8 1024 1024))]
    (is (= "folder-1"
           (auth/ensure-output-folder! gateway "access-token" "folder-1")))
    (is (= :get (:method (first @requests))))
    (is (.contains ^String (:url (first @requests)) "/files/folder-1"))))

(deftest source-metadata-and-media-support-shared-drives
  (let [requests (atom [])
        stream-requests (atom [])
        gateway (assoc
                 (gcp/->RestDriveGateway
                  (fn [request]
                    (swap! requests conj request)
                    {:status 200
                     :body (json/write-str {:id "video-1"
                                            :name "source.mp4"
                                            :mimeType "video/mp4"
                                            :size "2147483648"
                                            :trashed false
                                            :videoMediaMetadata
                                            {:durationMillis "125500"
                                             :width 1920
                                             :height 1080}})})
                  (* 8 1024 1024))
                 :stream-source-request!
                 (fn [request]
                   (swap! stream-requests conj request)
                   {:status 200
                    :body (ByteArrayInputStream.
                           (.getBytes "video-bytes"
                                      StandardCharsets/UTF_8))}))
        output (ByteArrayOutputStream.)]
    (is (= 2147483648
           (:size (drive/source-metadata! gateway "access" "video-1"))))
    (drive/stream-source! gateway "access" "video-1" output)
    (is (= "video-bytes" (.toString output StandardCharsets/UTF_8)))
    (is (str/includes? (:url (first @requests))
                       "supportsAllDrives=true"))
    (is (str/includes? (:url (first @requests))
                       "videoMediaMetadata(durationMillis,width,height)"))
    (is (not (str/includes? (:url (first @requests)) "createdTime")))
    (is (str/includes? (:url (first @stream-requests))
                       "alt=media&supportsAllDrives=true"))
    (is (= "Bearer access"
           (get-in (first @stream-requests) [:headers "Authorization"])))))

(deftest playback-range-forwards-authentication-and-validates-drive-headers
  (let [requests (atom [])
        gateway
        (assoc
         (gcp/->RestDriveGateway (constantly nil) (* 8 1024 1024))
         :stream-source-request!
         (fn [request]
           (swap! requests conj request)
           {:status 206
            :headers {"content-range" "bytes 6-10/20"
                      "content-length" "5"
                      "content-type" "video/mp4"}
            :body (ByteArrayInputStream.
                   (.getBytes "6789a" StandardCharsets/UTF_8))}))
        response (drive/open-source-range! gateway "drive-access" "video-1"
                                           {:start 6 :end 10})]
    (is (= 206 (:status response)))
    (is (= {"content-range" "bytes 6-10/20"
            "content-length" "5"
            "content-type" "video/mp4"}
           (:headers response)))
    (is (= "6789a"
           (with-open [body (:body response)]
             (String. (.readAllBytes body) StandardCharsets/UTF_8))))
    (is (= "Bearer drive-access"
           (get-in (first @requests) [:headers "Authorization"])))
    (is (= "bytes=6-10"
           (get-in (first @requests) [:headers "Range"])))
    (is (str/includes? (:url (first @requests))
                       "alt=media&supportsAllDrives=true"))))

(deftest playback-range-rejects-untruthful-drive-response-headers
  (doseq [response [{:status 200
                     :headers {"content-length" "5"}
                     :body "6789a"}
                    {:status 206
                     :headers {"content-range" "bytes 5-9/20"
                               "content-length" "5"}
                     :body "6789a"}
                    {:status 206
                     :headers {"content-range" "bytes 6-10/20"
                               "content-length" "4"}
                     :body "6789a"}]]
    (let [closed? (atom false)
          gateway
          (assoc
           (gcp/->RestDriveGateway (constantly nil) (* 8 1024 1024))
           :stream-source-request!
           (constantly
            (assoc response
                   :body
                   (proxy [ByteArrayInputStream]
                          [(.getBytes (:body response)
                                      StandardCharsets/UTF_8)]
                     (close []
                       (reset! closed? true)
                       (proxy-super close))))))]
      (is (= ::drive/invalid-playback-response
             (try
               (drive/open-source-range! gateway "drive-access" "video-1"
                                         {:start 6 :end 10})
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error))))))
      (is @closed?))))

(deftest picker-diagnostics-probe-account-and-video-index-without-returning-files
  (let [requests (atom [])
        responses (atom [{:status 200
                          :body (json/write-str {:user {:permissionId "account-1"}})}
                         {:status 200
                          :body (json/write-str {:files [{:mimeType "video/mp4"}]})}])
        gateway (gcp/->RestDriveGateway
                 (fn [request]
                   (swap! requests conj request)
                   (let [response (first @responses)]
                     (swap! responses subvec 1)
                     response))
                 (* 8 1024 1024))
        result (drive/picker-diagnostics! gateway "picker-access-token")]
    (is (= {:account-status "resolved"
            :index-status "video-found"}
           result))
    (is (= 2 (count @requests)))
    (is (every? #(= "Bearer picker-access-token"
                    (get-in % [:headers "Authorization"]))
                @requests))
    (is (not (.contains ^String (:url (second @requests))
                        "mimeType+contains+%27video%2F%27")))
    (is (.contains ^String (:url (second @requests)) "supportsAllDrives=true"))
    (is (.contains ^String (:url (second @requests))
                   "includeItemsFromAllDrives=true"))
    (doseq [mime-type drive/supported-source-video-mime-types]
      (is (.contains ^String (:url (second @requests))
                     (java.net.URLEncoder/encode
                      (str "mimeType = '" mime-type "'")
                      StandardCharsets/UTF_8))))
    (is (not (.contains ^String (:url (second @requests)) "video-1")))))

(deftest missing-output-folder-is-discovered-before-it-is-created
  (let [requests (atom [])
        responses (atom [{:status 200 :body (json/write-str {:files []})}
                         {:status 200 :body (json/write-str {:id "folder-new"})}])
        gateway
        (gcp/->RestDriveGateway
         (fn [request]
           (swap! requests conj request)
           (let [response (first @responses)]
             (swap! responses subvec 1)
             response))
         (* 8 1024 1024))]
    (is (= "folder-new"
           (auth/ensure-output-folder! gateway "access-token" nil)))
    (is (= [:get :post] (mapv :method @requests)))
    (is (.contains ^String (:url (first @requests)) "Alpha+Compose"))
    (is (= {:name "Alpha Compose"
            :mimeType "application/vnd.google-apps.folder"}
           (json/read-str (:body (second @requests)) :key-fn keyword)))))

(deftest folder-created-before-a-persistence-failure-is-reused-on-retry
  (let [requests (atom [])
        gateway
        (gcp/->RestDriveGateway
         (fn [request]
           (swap! requests conj request)
           {:status 200
            :body (json/write-str {:files [{:id "recovered-folder"}]})})
         (* 8 1024 1024))]
    (is (= "recovered-folder"
           (auth/ensure-output-folder! gateway "access-token" nil)))
    (is (= [:get] (mapv :method @requests)))))

(deftest recovered-resumable-upload-queries-offset-and-continues-with-a-bounded-chunk
  (let [requests (atom [])
        responses (atom [{:status 308 :headers {"range" "bytes=0-1"} :body ""}
                         {:status 200 :headers {} :body (json/write-str {:id "file-1"})}])
        gateway (gcp/->RestDriveGateway
                 (fn [request]
                   (swap! requests conj request)
                   (let [response (first @responses)]
                     (swap! responses subvec 1)
                     response))
                 (* 8 1024 1024))
        path (Files/createTempFile "resumable" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString path "movie" (make-array OpenOption 0))
      (is (= {:status :complete :file-id "file-1"}
             (drive/resume-resumable! gateway "access" "https://upload/session"
                                      path 5)))
      (is (= "*/5" (get-in @requests [0 :headers "Content-Range"])))
      (is (= "bytes 2-4/5" (get-in @requests [1 :headers "Content-Range"])))
      (is (= "vie" (String. ^bytes (:body-bytes (second @requests)) "UTF-8")))
      (finally
        (Files/deleteIfExists path)))))

(deftest java-http-client-starts-fresh-session-with-data
  (let [requests (atom [])
        server (local-upload-server requests)
        path (Files/createTempFile "real-resumable" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))
        session-uri (str "http://127.0.0.1:"
                         (.getPort (.getAddress server)) "/upload")
        gateway (gcp/->RestDriveGateway gcp/http-send! (* 8 1024 1024))]
    (try
      (Files/writeString path "movie" (make-array OpenOption 0))
      (is (= {:status :complete :file-id "file-1"}
             (drive/upload-resumable! gateway "access" session-uri path 5)))
      (is (= [{:content-length "5"
               :content-range "bytes 0-4/5"
               :body "movie"}]
             @requests))
      (finally
        (.stop server 0)
        (Files/deleteIfExists path)))))

(deftest resumable-continuation-obeys-partial-acknowledgements
  (let [requests (atom [])
        responses (atom [{:status 308 :headers {"range" "bytes=0-1"} :body ""}
                         {:status 200 :headers {}
                          :body (json/write-str {:id "file-1"})}])
        gateway (gcp/->RestDriveGateway
                 (fn [request]
                   (swap! requests conj request)
                   (let [response (first @responses)]
                     (swap! responses subvec 1)
                     response))
                 5)
        path (Files/createTempFile "partial-ack" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString path "0123456789" (make-array OpenOption 0))
      (is (= {:status :complete :file-id "file-1"}
             (drive/upload-resumable! gateway "access" "https://upload/session"
                                      path 10)))
      (is (= ["bytes 0-4/10" "bytes 2-6/10"]
             (mapv #(get-in % [:headers "Content-Range"]) @requests)))
      (is (= ["01234" "23456"]
             (mapv #(String. ^bytes (:body-bytes %) StandardCharsets/UTF_8)
                   @requests)))
      (finally
        (Files/deleteIfExists path)))))

(deftest resumable-continuation-rejects-nonprogress-and-skipped-ranges
  (let [path (Files/createTempFile "invalid-progress" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString path "0123456789" (make-array OpenOption 0))
      (doseq [[label response]
              [["no acknowledged progress" {:status 308 :headers {} :body ""}]
               ["acknowledgement beyond sent bytes"
                {:status 308 :headers {"range" "bytes=0-8"} :body ""}]]]
        (testing label
          (let [responses (atom [response])
                gateway (gcp/->RestDriveGateway
                         (fn [_]
                           (let [next-response (first @responses)]
                             (swap! responses subvec 1)
                             next-response))
                         5)]
            (is (= ::gcp/invalid-resumable-progress
                   (try
                     (drive/upload-resumable! gateway "access"
                                              "https://upload/session" path 10)
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error)))))))))
      (finally
        (Files/deleteIfExists path)))))

(deftest expired-resumable-session-is-reported-for-same-id-recovery
  (let [gateway (gcp/->RestDriveGateway
                 (constantly {:status 404 :headers {} :body ""})
                 (* 8 1024 1024))
        path (Files/createTempFile "expired-resumable" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (is (= {:status :session-expired}
             (drive/resume-resumable! gateway "access" "https://upload/expired"
                                      path 0)))
      (finally
        (Files/deleteIfExists path)))))

(deftest recovered-session-treats-a-drive-400-probe-as-expired
  (let [requests (atom [])
        gateway (gcp/->RestDriveGateway
                 (fn [request]
                   (swap! requests conj request)
                   {:status 400 :headers {} :body "invalid session"})
                 (* 8 1024 1024))
        path (Files/createTempFile "invalid-resumable" ".mov"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString path "movie" (make-array OpenOption 0))
      (is (= {:status :session-expired}
             (drive/resume-resumable! gateway "access" "https://upload/invalid"
                                      path 5)))
      (is (= ["*/5"]
             (mapv #(get-in % [:headers "Content-Range"]) @requests)))
      (finally
        (Files/deleteIfExists path)))))

(deftest generated-id-and-resumable-metadata-lock-one-output-resource
  (let [requests (atom [])
        responses (atom [{:status 200 :body (json/write-str {:ids ["file-1"]})}
                         {:status 200 :headers {"location" "https://upload/session"}
                          :body ""}])
        gateway (gcp/->RestDriveGateway
                 (fn [request]
                   (swap! requests conj request)
                   (let [response (first @responses)]
                     (swap! responses subvec 1)
                     response))
                 (* 8 1024 1024))]
    (is (= "file-1" (drive/generate-output-id! gateway "access")))
    (is (= "https://upload/session"
           (drive/begin-resumable-upload!
            gateway "access" {:file-id "file-1"
                              :folder-id "folder-1"
                              :name "output.mov"
                              :content-type "video/quicktime"
                              :size 5})))
    (is (= {:id "file-1" :name "output.mov" :parents ["folder-1"]}
           (json/read-str (:body (second @requests)) :key-fn keyword)))))

(deftest firestore-recovers-one-reserved-output-and-upload-session
  (if-let [host (System/getenv "FIRESTORE_EMULATOR_HOST")]
    (let [firestore (-> (FirestoreOptions/newBuilder)
                        (.setProjectId "animated-graph-cloud-drive-test")
                        (.setEmulatorHost host)
                        (.build)
                        (.getService))
          store (gcp/delivery-store firestore)
          job-id "firestore-resumable-job"
          reservation {:file-id "file-1" :folder-id "folder-1"}]
      (try
        (.get (.recursiveDelete firestore
                                (.collection firestore "drive-deliveries")))
        (is (= reservation
               (drive/reserve-output! store job-id reservation)))
        (is (= {:file-id "file-1"
                :folder-id "folder-1"
                :complete? false
                :session-uri "https://upload.example/session-1"}
               (drive/save-upload-session!
                store job-id "https://upload.example/session-1")))
        (let [recovered-store (gcp/delivery-store firestore)]
          (is (= {:file-id "file-1"
                  :folder-id "folder-1"
                  :complete? false
                  :session-uri "https://upload.example/session-1"}
                 (drive/load-delivery recovered-store job-id)))
          (is (= "file-1"
                 (:file-id
                  (drive/reserve-output! recovered-store job-id
                                         {:file-id "file-2"
                                          :folder-id "folder-1"})))))
        (finally
          (.close firestore))))
    (is true "Firestore emulator test is run by script/test_firestore_emulator.sh")))
