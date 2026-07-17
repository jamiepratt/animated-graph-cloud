(ns agg.drive-gcp-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.drive.gcp :as gcp]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files OpenOption)))

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

(deftest missing-output-folder-is-created-once
  (let [requests (atom [])
        gateway
        (gcp/->RestDriveGateway
         (fn [request]
           (swap! requests conj request)
           {:status 200 :body (json/write-str {:id "folder-new"})})
         (* 8 1024 1024))]
    (is (= "folder-new"
           (auth/ensure-output-folder! gateway "access-token" nil)))
    (is (= :post (:method (first @requests))))
    (is (= {:name "Animated Graph Cloud"
            :mimeType "application/vnd.google-apps.folder"}
           (json/read-str (:body (first @requests)) :key-fn keyword)))))

(deftest resumable-upload-queries-offset-and-continues-with-a-bounded-chunk
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
             (drive/upload-resumable! gateway "access" "https://upload/session"
                                      path 5)))
      (is (= "bytes */5" (get-in @requests [0 :headers "Content-Range"])))
      (is (= "bytes 2-4/5" (get-in @requests [1 :headers "Content-Range"])))
      (is (= "vie" (String. ^bytes (:body-bytes (second @requests)) "UTF-8")))
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
             (drive/upload-resumable! gateway "access" "https://upload/expired"
                                      path 0)))
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
