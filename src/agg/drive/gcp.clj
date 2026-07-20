(ns agg.drive.gcp
  (:require [agg.errors :as errors]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.google.api.gax.rpc ApiException StatusCode$Code)
           (com.google.cloud.firestore Firestore)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio ByteBuffer)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption Path StandardOpenOption)
           (java.util.concurrent ExecutionException Future)))

(def ^:private folder-mime-type "application/vnd.google-apps.folder")

(defn- urlencode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn http-send!
  [{:keys [method url headers body body-bytes]}]
  (let [builder (HttpRequest/newBuilder (URI/create url))
        _ (doseq [[name value] headers]
            (.header builder name value))
        publisher (cond
                    body-bytes (HttpRequest$BodyPublishers/ofByteArray body-bytes)
                    body (HttpRequest$BodyPublishers/ofString body)
                    :else (HttpRequest$BodyPublishers/noBody))
        _ (case method
            :get (.GET builder)
            :post (.POST builder publisher)
            :put (.PUT builder publisher))
        response (.send (HttpClient/newHttpClient)
                        (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :headers (into {}
                    (map (fn [[key values]]
                           [(str/lower-case key) (first values)]))
                    (.map (.headers response)))
     :body (.body response)}))

(defn- authorized [request access-token]
  (update request :headers assoc "Authorization" (str "Bearer " access-token)))

(defn- parse-json [body]
  (json/read-str (or body "{}") :key-fn keyword))

(defn- source-url [file-id]
  (str "https://www.googleapis.com/drive/v3/files/"
       (urlencode file-id)))

(defn- drive-url [path query]
  (str "https://www.googleapis.com/drive/v3/" path "?" query))

(defn- require-success [{:keys [status body] :as response} error-type]
  (if (<= 200 status 299)
    response
    (throw (errors/raise! "Google Drive request failed"
                          {:type error-type :status status
                           :reason (:error (parse-json body))}))))

(defn- create-folder! [send! access-token]
  (let [{:keys [body]}
        (require-success
         (send! (authorized
                 {:method :post
                  :url "https://www.googleapis.com/drive/v3/files?fields=id"
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str {:name "Alpha Compose"
                                         :mimeType folder-mime-type})}
                 access-token))
         ::folder-create-failed)]
    (:id (parse-json body))))

(defn- find-output-folder! [send! access-token]
  (let [query (str "name = 'Alpha Compose' and mimeType = '"
                   folder-mime-type "' and trashed = false")
        {:keys [body]}
        (require-success
         (send! (authorized
                 {:method :get
                  :url (str "https://www.googleapis.com/drive/v3/files"
                            "?spaces=drive&pageSize=1&fields=files(id)&q="
                            (urlencode query))
                  :headers {}}
                 access-token))
         ::folder-lookup-failed)]
    (some-> (parse-json body) :files first :id not-empty)))

(defn- discover-or-create-folder! [send! access-token]
  (or (find-output-folder! send! access-token)
      (create-folder! send! access-token)))

(defn- range-offset [header]
  (if (nil? header)
    0
    (if-let [[_ last-byte] (re-matches #"bytes=0-(\d+)" header)]
      (inc (parse-long last-byte))
      (throw (errors/raise! "Drive returned an invalid resumable range"
                            {:type ::invalid-resumable-progress
                             :range header})))))

(defn- initial-offset [header size]
  (let [offset (range-offset header)]
    (when (or (neg? offset) (>= offset size))
      (throw (errors/raise! "Drive resumable query returned an invalid offset"
                            {:type ::invalid-resumable-progress
                             :offset offset
                             :size size})))
    offset))

(defn- continued-offset [header offset end size]
  (let [reported (range-offset header)]
    (when (or (<= reported offset)
              (> reported (inc end))
              (>= reported size))
      (throw (errors/raise! "Drive resumable upload made invalid progress"
                            {:type ::invalid-resumable-progress
                             :offset offset
                             :reported reported
                             :sent-through end
                             :size size})))
    reported))

(defn- read-chunk [^Path path offset length]
  (let [bytes (byte-array length)]
    (with-open [channel (FileChannel/open
                         path
                         (into-array OpenOption [StandardOpenOption/READ]))]
      (.position channel (long offset))
      (loop [buffer (ByteBuffer/wrap bytes)]
        (when (.hasRemaining buffer)
          (when (neg? (.read channel buffer))
            (throw (errors/raise! "Drive upload source ended early"
                                  {:type ::short-upload-source})))
          (recur buffer))))
    bytes))

(defn- upload-from!
  [send! chunk-size access-token session-uri path size initial]
  (loop [offset initial]
    (let [length (int (min (long chunk-size) (- (long size) offset)))
          end (dec (+ offset length))
          response
          (send! (authorized
                  {:method :put
                   :url session-uri
                   :headers {"Content-Type" "video/quicktime"
                             "Content-Range"
                             (str "bytes " offset "-" end "/" size)}
                   :body-bytes (read-chunk path offset length)}
                  access-token))]
      (cond
        (contains? #{404 410} (:status response))
        {:status :session-expired}

        (<= 200 (:status response) 299)
        {:status :complete :file-id (:id (parse-json (:body response)))}

        (= 308 (:status response))
        (recur (continued-offset (get-in response [:headers "range"])
                                 offset end size))

        :else
        (throw (errors/raise! "Drive resumable upload failed"
                              {:type ::resumable-upload-failed
                               :status (:status response)}))))))

(defrecord RestDriveGateway [send! chunk-size]
  auth/DriveClient
  (ensure-output-folder! [_ access-token existing-folder]
    (if-not existing-folder
      (discover-or-create-folder! send! access-token)
      (let [{:keys [status body]}
            (send! (authorized
                    {:method :get
                     :url (str "https://www.googleapis.com/drive/v3/files/"
                               (urlencode existing-folder)
                               "?fields=id,mimeType,trashed")
                     :headers {}}
                    access-token))]
        (cond
          (= 404 status) (discover-or-create-folder! send! access-token)
          (<= 200 status 299)
          (let [{:keys [id mimeType trashed]} (parse-json body)]
            (if (and (= folder-mime-type mimeType) (not trashed))
              id
              (discover-or-create-folder! send! access-token)))
          :else
          (throw (errors/raise! "Drive output folder lookup failed"
                                {:type ::folder-lookup-failed :status status}))))))
  drive/DriveGateway
  (generate-output-id! [_ access-token]
    (let [{:keys [body]}
          (require-success
           (send! (authorized
                   {:method :get
                    :url (str "https://www.googleapis.com/drive/v3/files/"
                              "generateIds?count=1&space=drive&type=files")
                    :headers {}}
                   access-token))
           ::id-generation-failed)]
      (or (first (:ids (parse-json body)))
          (throw (errors/raise! "Drive returned no generated output ID"
                                {:type ::id-generation-failed})))))
  (begin-resumable-upload! [_ access-token
                            {:keys [file-id folder-id name content-type size]}]
    (let [{:keys [headers]}
          (require-success
           (send! (authorized
                   {:method :post
                    :url (str "https://www.googleapis.com/upload/drive/v3/files"
                              "?uploadType=resumable&fields=id")
                    :headers {"Content-Type" "application/json"
                              "X-Upload-Content-Type" content-type
                              "X-Upload-Content-Length" (str size)}
                    :body (json/write-str {:id file-id
                                           :name name
                                           :parents [folder-id]})}
                   access-token))
           ::resumable-start-failed)
          location (get headers "location")]
      (or (not-empty location)
          (throw (errors/raise! "Drive returned no resumable session"
                                {:type ::resumable-start-failed})))))
  (upload-resumable! [_ access-token session-uri path size]
    (upload-from! send! chunk-size access-token session-uri path size 0))
  (resume-resumable! [_ access-token session-uri path size]
    (let [query-response
          (send! (authorized
                  {:method :put
                   :url session-uri
                   :headers {"Content-Range" (str "*/" size)}}
                  access-token))]
      (cond
        (contains? #{400 404 410} (:status query-response))
        {:status :session-expired}

        (<= 200 (:status query-response) 299)
        {:status :complete :file-id (:id (parse-json (:body query-response)))}

        (= 308 (:status query-response))
        (upload-from! send! chunk-size access-token session-uri path size
                      (initial-offset (get-in query-response [:headers "range"])
                                      size))

        :else
        (throw (errors/raise! "Drive resumable status query failed"
                              {:type ::resumable-status-failed
                               :status (:status query-response)})))))
  drive/SourceGateway
  (source-metadata! [_ access-token file-id]
    (let [{:keys [status body]}
          (send! (authorized
                  {:method :get
                   :url (str (source-url file-id)
                             "?fields=id,name,mimeType,size,trashed")
                   :headers {}}
                  access-token))]
      (if (<= 200 status 299)
        (let [metadata (parse-json body)]
          (assoc metadata :size (some-> (:size metadata) str parse-long)))
        (throw (errors/raise! "Google Drive source metadata request failed"
                              {:type ::source-metadata-failed :status status})))))
  (stream-source! [_ access-token file-id output]
    (let [request (-> (HttpRequest/newBuilder
                       (URI/create (str (source-url file-id) "?alt=media")))
                      (.header "Authorization" (str "Bearer " access-token))
                      (.GET)
                      (.build))
          response (.send (HttpClient/newHttpClient)
                          request
                          (HttpResponse$BodyHandlers/ofInputStream))]
      (if (<= 200 (.statusCode response) 299)
        (with-open [input (.body response)]
          (.transferTo ^java.io.InputStream input output))
        (do
          (.close ^java.io.InputStream (.body response))
          (throw (errors/raise! "Google Drive source download failed"
                                {:type ::source-download-failed
                                 :status (.statusCode response)}))))))
  drive/PickerDiagnostics
  (picker-diagnostics! [_ access-token]
    (let [about (send! (authorized
                        {:method :get
                         :url (drive-url "about"
                                         "fields=user(permissionId)")
                         :headers {}}
                        access-token))
          files (send! (authorized
                        {:method :get
                         :url (drive-url "files"
                                         (str "pageSize=1"
                                              "&spaces=drive"
                                              "&fields=files(mimeType)"
                                              "&q="
                                              (urlencode
                                               "trashed = false and mimeType contains 'video/'")))
                         :headers {}}
                        access-token))
          account-status (cond
                           (= 401 (:status about)) "token-rejected"
                           (<= 200 (:status about) 299) "resolved"
                           :else "unavailable")
          index-status (cond
                         (= 401 (:status files)) "token-rejected"
                         (<= 200 (:status files) 299)
                         (if (seq (:files (parse-json (:body files))))
                           "video-found"
                           "video-empty")
                         :else "unavailable")]
      {:account-status account-status
       :index-status index-status})))

(defn gateway []
  (->RestDriveGateway http-send! (* 8 1024 1024)))

(defn- await! [^Future future]
  (try
    (.get future)
    (catch ExecutionException error
      (throw (.getCause error)))))

(defn- duplicate? [^Throwable error]
  (and (instance? ApiException error)
       (= StatusCode$Code/ALREADY_EXISTS
          (some-> ^ApiException error .getStatusCode .getCode))))

(defn- snapshot-delivery [snapshot]
  (when (.exists snapshot)
    (let [data (.getData snapshot)]
      (cond-> {:file-id (get data "fileId")
               :folder-id (get data "folderId")
               :complete? (true? (get data "complete"))}
        (get data "sessionUri") (assoc :session-uri (get data "sessionUri"))))))

(defrecord FirestoreDeliveryStore [^Firestore firestore]
  drive/DeliveryStore
  (load-delivery [_ job-id]
    (snapshot-delivery
     (await! (.get (.document (.collection firestore "drive-deliveries") job-id)))))
  (reserve-output! [this job-id candidate]
    (let [reference (.document (.collection firestore "drive-deliveries") job-id)]
      (try
        (await! (.create reference
                         {"fileId" (:file-id candidate)
                          "folderId" (:folder-id candidate)
                          "complete" false}))
        candidate
        (catch Throwable error
          (if (duplicate? error)
            (drive/load-delivery this job-id)
            (throw error))))))
  (save-upload-session! [this job-id session-uri]
    (await! (.update (.document (.collection firestore "drive-deliveries") job-id)
                     {"sessionUri" session-uri}))
    (drive/load-delivery this job-id))
  (complete-delivery! [this job-id result]
    (await! (.update (.document (.collection firestore "drive-deliveries") job-id)
                     {"fileId" (:file-id result)
                      "complete" true
                      "sessionUri" nil}))
    (drive/load-delivery this job-id)))

(defn delivery-store [firestore]
  (->FirestoreDeliveryStore firestore))
