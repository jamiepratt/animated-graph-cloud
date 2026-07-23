(ns agg.drive.gcp
  (:require [agg.errors :as errors]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (com.google.api.gax.rpc ApiException StatusCode$Code)
           (com.google.cloud.firestore Firestore)
           (java.io InputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.channels FileChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file OpenOption Path StandardOpenOption)
           (java.time Duration Instant LocalDateTime OffsetDateTime ZoneOffset)
           (java.util.concurrent ExecutionException Future)))

(def ^:private folder-mime-type "application/vnd.google-apps.folder")
(def ^:private recording-clock-range-bytes (* 256 1024))
(def ^:private recording-clock-timeout-ms 3000)
(def ^:private quicktime-to-unix-seconds 2082844800)
(def ^:private recording-clock-limits
  {:maxBytes (* 2 recording-clock-range-bytes)
   :maxRanges 2
   :timeoutMillis recording-clock-timeout-ms})

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

(defn- http-stream-send! [{:keys [url headers timeout-ms]}]
  (let [builder (HttpRequest/newBuilder (URI/create url))
        _ (doseq [[name value] headers]
            (.header builder name value))
        _ (when timeout-ms
            (.timeout builder (Duration/ofMillis (long timeout-ms))))
        response (.send (HttpClient/newHttpClient)
                        (-> builder .GET .build)
                        (HttpResponse$BodyHandlers/ofInputStream))]
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

(defn- valid-playback-range-response?
  [{:keys [status headers body]} start end]
  (let [[_ returned-start returned-end total]
        (when-let [content-range (get headers "content-range")]
          (re-matches #"bytes (\d+)-(\d+)/(\d+)" content-range))
        returned-start (some-> returned-start parse-long)
        returned-end (some-> returned-end parse-long)
        total (some-> total parse-long)
        content-length (some-> (get headers "content-length") parse-long)]
    (and (= 206 status)
         (some? body)
         (= start returned-start)
         (= end returned-end)
         total
         (> total end)
         (= (inc (- end start)) content-length))))

(defn- plausible-recording-instant? [^Instant instant]
  (and (not (.isBefore instant (Instant/parse "1970-01-01T00:00:00Z")))
       (.isBefore instant (Instant/parse "2101-01-01T00:00:00Z"))))

(defn- candidate-source [^String text match-start]
  (let [context (.substring text (max 0 (- match-start 96)) match-start)
        movie (max (.lastIndexOf context "mvhd")
                   (.lastIndexOf context "©day")
                   (.lastIndexOf context "creationdate"))
        track (.lastIndexOf context "tkhd")]
    (cond
      (> track movie) "track"
      (not= -1 movie) "movie"
      :else "container")))

(defn- explicit-offset-candidates [^bytes bytes]
  (let [text (String. bytes StandardCharsets/ISO_8859_1)
        matcher
        (.matcher
         (re-pattern
          "(?<!\\d)(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2}))(?!\\d)")
         text)]
    (loop [candidates []]
      (if-not (.find matcher)
        candidates
        (let [raw (.group matcher 1)
              normalized (-> raw
                             (str/replace " " "T")
                             (str/replace #"([+-]\d{2})(\d{2})$" "$1:$2"))
              candidate
              (try
                (let [parsed (OffsetDateTime/parse normalized)]
                  (when (plausible-recording-instant? (.toInstant parsed))
                    {:source (candidate-source text (.start matcher 1))
                     :kind "explicit-offset"
                     :value normalized}))
                (catch Throwable _ nil))]
          (recur (cond-> candidates candidate (conj candidate))))))))

(defn- index-of-type [^bytes bytes ^bytes atom-type start]
  (let [limit (- (alength bytes) (alength atom-type))]
    (loop [index start]
      (cond
        (> index limit) -1
        (every? true?
                (map-indexed
                 (fn [offset expected]
                   (= expected (aget bytes (+ index offset))))
                 atom-type))
        index
        :else (recur (inc index))))))

(defn- unsigned-int [^ByteBuffer buffer]
  (Integer/toUnsignedLong (.getInt buffer)))

(defn- quicktime-creation-seconds [^bytes bytes type-index]
  (let [payload-index (+ type-index 4)]
    (when (< (+ payload-index 8) (alength bytes))
      (let [version (Byte/toUnsignedInt (aget bytes payload-index))
            buffer (doto (ByteBuffer/wrap bytes)
                     (.order ByteOrder/BIG_ENDIAN)
                     (.position (+ payload-index 4)))]
        (case version
          0 (unsigned-int buffer)
          1 (when (<= (+ payload-index 12) (alength bytes))
              (.getLong buffer))
          nil)))))

(defn- quicktime-local-candidates [^bytes bytes atom-type source]
  (let [type-bytes (.getBytes ^String atom-type
                              StandardCharsets/ISO_8859_1)]
    (loop [start 0
           candidates []]
      (let [index (index-of-type bytes type-bytes start)]
        (if (neg? index)
          candidates
          (let [candidate
                (when-let [quicktime-seconds
                           (quicktime-creation-seconds bytes index)]
                  (try
                    (let [instant
                          (Instant/ofEpochSecond
                           (- quicktime-seconds quicktime-to-unix-seconds))]
                      (when (plausible-recording-instant? instant)
                        {:source source
                         :kind "local-date-time"
                         :value
                         (str (LocalDateTime/ofInstant instant
                                                       ZoneOffset/UTC))}))
                    (catch Throwable _ nil)))]
            (recur (+ index 4)
                   (cond-> candidates candidate (conj candidate)))))))))

(defn- quicktime-duration-seconds [^bytes bytes type-index]
  (let [payload-index (+ type-index 4)]
    (when (< (+ payload-index 20) (alength bytes))
      (try
        (let [version (Byte/toUnsignedInt (aget bytes payload-index))
              buffer (doto (ByteBuffer/wrap bytes)
                       (.order ByteOrder/BIG_ENDIAN))
              [timescale duration]
              (case version
                0 (do
                    (.position buffer (+ payload-index 12))
                    [(unsigned-int buffer) (unsigned-int buffer)])
                1 (when (<= (+ payload-index 36) (alength bytes))
                    (.position buffer (+ payload-index 24))
                    [(unsigned-int buffer) (.getLong buffer)])
                nil)]
          (when (and timescale duration (pos? timescale) (not (neg? duration)))
            (/ (double duration) (double timescale))))
        (catch Throwable _ nil)))))

(defn- container-duration-seconds [segments]
  (some
   (fn [bytes]
     (let [atom-type (.getBytes "mvhd" StandardCharsets/ISO_8859_1)
           index (index-of-type bytes atom-type 0)]
       (when-not (neg? index)
         (quicktime-duration-seconds bytes index))))
   segments))

(defn- recording-clock-candidates [segments]
  (->> segments
       (mapcat
        (fn [bytes]
          (concat (explicit-offset-candidates bytes)
                  (quicktime-local-candidates bytes "mvhd" "movie")
                  (quicktime-local-candidates bytes "tkhd" "track"))))
       distinct
       (sort-by (juxt #(if (= "explicit-offset" (:kind %)) 0 1)
                      :source
                      :value))
       vec))

(defn- inspection-ranges [size]
  (let [size (long size)
        head {:start 0
              :end (dec (min size recording-clock-range-bytes))
              :timeout-ms recording-clock-timeout-ms}
        tail {:start (max 0 (- size recording-clock-range-bytes))
              :end (dec size)
              :timeout-ms recording-clock-timeout-ms}]
    (if (= (select-keys head [:start :end])
           (select-keys tail [:start :end]))
      [head]
      [head tail])))

(defn- read-inspection-range!
  [gateway access-token file-id {:keys [start end] :as byte-range}]
  (let [limit (int (inc (- end start)))
        {:keys [body]}
        (drive/open-source-range! gateway access-token file-id byte-range)]
    (with-open [input ^InputStream body]
      (.readNBytes input limit))))

(defn- metadata-duration-seconds [metadata]
  (try
    (when-let [duration-millis
               (get-in metadata [:videoMediaMetadata :durationMillis])]
      (/ (double (parse-long (str duration-millis))) 1000.0))
    (catch Throwable _ nil)))

(defn inspect-recording-clock!
  "Reads at most two bounded source ranges and returns advisory clock candidates."
  [gateway access-token file-id {:keys [size] :as metadata}]
  (let [duration-seconds (metadata-duration-seconds metadata)
        manual {:status "manual"
                :candidates []
                :recommendedIndex nil
                :ambiguous false
                :durationSeconds duration-seconds
                :limits recording-clock-limits}]
    (if-not (and (satisfies? drive/PlaybackGateway gateway)
                 (integer? size)
                 (pos? size))
      manual
      (try
        (let [segments (mapv #(read-inspection-range!
                               gateway access-token file-id %)
                             (inspection-ranges size))
              candidates (recording-clock-candidates segments)
              duration-seconds
              (or duration-seconds (container-duration-seconds segments))
              preferred-kind (when (seq candidates)
                               (if (some #(= "explicit-offset" (:kind %))
                                         candidates)
                                 "explicit-offset"
                                 "local-date-time"))
              preferred-indexes
              (keep-indexed
               (fn [index candidate]
                 (when (= preferred-kind (:kind candidate)) index))
               candidates)
              preferred-values
              (set (map #(get-in candidates [% :value]) preferred-indexes))
              ambiguous (> (count preferred-values) 1)
              recommended-index
              (when (and (seq preferred-indexes) (not ambiguous))
                (first preferred-indexes))]
          (assoc manual
                 :status (if (seq candidates) "candidate" "manual")
                 :candidates candidates
                 :recommendedIndex recommended-index
                 :ambiguous ambiguous
                 :durationSeconds duration-seconds))
        (catch Throwable _
          manual)))))

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
                             "?fields=id,name,mimeType,size,trashed,"
                             "videoMediaMetadata(durationMillis,width,height)"
                             "&supportsAllDrives=true")
                   :headers {}}
                  access-token))]
      (if (<= 200 status 299)
        (let [metadata (parse-json body)]
          (assoc metadata :size (some-> (:size metadata) str parse-long)))
        (throw (errors/raise! "Google Drive source metadata request failed"
                              {:type ::source-metadata-failed :status status})))))
  (stream-source! [gateway access-token file-id output]
    (let [send-stream! (or (:stream-source-request! gateway)
                           http-stream-send!)
          {:keys [status body]}
          (send-stream!
           (authorized
            {:method :get
             :url (str (source-url file-id)
                       "?alt=media&supportsAllDrives=true")
             :headers {}}
            access-token))]
      (if (<= 200 status 299)
        (with-open [input body]
          (.transferTo ^java.io.InputStream input output))
        (do
          (.close ^java.io.InputStream body)
          (throw (errors/raise! "Google Drive source download failed"
                                {:type ::source-download-failed
                                 :status status}))))))
  drive/PlaybackGateway
  (open-source-range! [gateway access-token file-id
                       {:keys [start end timeout-ms]}]
    (let [send-stream! (or (:stream-source-request! gateway)
                           http-stream-send!)
          response
          (send-stream!
           (authorized
            {:method :get
             :url (str (source-url file-id)
                       "?alt=media&supportsAllDrives=true")
             :headers {"Range" (str "bytes=" start "-" end)}
             :timeout-ms timeout-ms}
            access-token))]
      (if (valid-playback-range-response? response start end)
        response
        (do
          (some-> ^java.io.InputStream (:body response) .close)
          (throw (errors/raise! "Google Drive playback response was invalid"
                                {:type ::drive/invalid-playback-response
                                 :status (long (or (:status response) 0))
                                 :size (inc (- end start))}))))))
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
                                              "&supportsAllDrives=true"
                                              "&includeItemsFromAllDrives=true"
                                              "&q="
                                              (urlencode
                                               (str
                                                "trashed = false and ("
                                                (str/join
                                                 " or "
                                                 (map #(str "mimeType = '" % "'")
                                                      drive/supported-source-video-mime-types))
                                                ")"))))
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
