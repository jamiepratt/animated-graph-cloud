(ns agg.drive-gcp-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.drive.gcp :as gcp]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (com.google.cloud.firestore FirestoreOptions)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption)))

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

(deftest source-metadata-normalizes-drive-int64-size
  (let [gateway (gcp/->RestDriveGateway
                 (fn [_]
                   {:status 200
                    :body (json/write-str {:id "video-1"
                                           :name "source.mp4"
                                           :mimeType "video/mp4"
                                           :size "2147483648"
                                           :trashed false})})
                 (* 8 1024 1024))]
    (is (= 2147483648
           (:size (drive/source-metadata! gateway "access" "video-1"))))))

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
    (is (.contains ^String (:url (second @requests)) "mimeType+contains+%27video%2F%27"))
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
