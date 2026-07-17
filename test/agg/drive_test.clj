(ns agg.drive-test
  (:require [agg.drive.core :as drive]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files OpenOption)))

(defrecord MemoryDeliveryStore [records]
  drive/DeliveryStore
  (load-delivery [_ job-id] (get @records job-id))
  (reserve-output! [_ job-id candidate]
    (get (swap! records #(if (contains? % job-id)
                           %
                           (assoc % job-id candidate)))
         job-id))
  (save-upload-session! [_ job-id session-uri]
    (get (swap! records assoc-in [job-id :session-uri] session-uri) job-id))
  (complete-delivery! [_ job-id result]
    (get (swap! records update job-id merge result {:complete? true}) job-id)))

(deftest resumable-recovery-reuses-one-preallocated-output-id
  (let [records (atom {})
        generated (atom [])
        sessions (atom [])
        uploads (atom [])
        responses (atom [{:status :session-expired}
                         {:status :complete :file-id "drive-output-1"}])
        gateway
        (reify drive/DriveGateway
          (generate-output-id! [_ access-token]
            (swap! generated conj access-token)
            "drive-output-1")
          (begin-resumable-upload! [_ access-token request]
            (let [session (str "https://upload.example/" (inc (count @sessions)))]
              (swap! sessions conj (assoc request
                                          :access-token access-token
                                          :session-uri session))
              session))
          (upload-resumable! [_ access-token session-uri path size]
            (swap! uploads conj {:access-token access-token
                                 :session-uri session-uri
                                 :size size
                                 :bytes (Files/readString path)})
            (let [response (first @responses)]
              (swap! responses subvec 1)
              response)))
        delivery (drive/delivery
                  {:store (->MemoryDeliveryStore records)
                   :gateway gateway
                   :access-provider (fn [subject]
                                      (is (= "google-subject-1" subject))
                                      {:access-token "drive-access"
                                       :folder-id "folder-1"})})
        output (Files/createTempFile "drive-output-test" ".mov"
                                     (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString output "movie" (make-array OpenOption 0))
      (is (= {:fileId "drive-output-1"
              :webViewLink "https://drive.google.com/file/d/drive-output-1/view"}
             (drive/deliver! delivery "job-1" "google-subject-1" output)))
      (is (= 1 (count @generated)))
      (is (= ["drive-output-1" "drive-output-1"]
             (mapv :file-id @sessions)))
      (is (= ["https://upload.example/1" "https://upload.example/2"]
             (mapv :session-uri @uploads)))
      (is (= true (get-in @records ["job-1" :complete?])))
      (is (= "drive-output-1" (get-in @records ["job-1" :file-id])))
      (is (= {:fileId "drive-output-1"
              :webViewLink "https://drive.google.com/file/d/drive-output-1/view"}
             (drive/deliver! delivery "job-1" "google-subject-1" output)))
      (is (= 1 (count @generated)))
      (is (= 2 (count @uploads)))
      (finally
        (Files/deleteIfExists output)))))
