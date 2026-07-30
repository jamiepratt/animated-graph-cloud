(ns agg.derivative.storage
  (:require [agg.derivative.contract :as contract]
            [agg.errors :as errors]
            [clojure.string :as str])
  (:import (com.google.cloud.storage Blob BlobId BlobInfo Storage
                                     Storage$BlobGetOption
                                     Storage$BlobSourceOption
                                     Storage$BlobTargetOption
                                     Storage$BlobWriteOption
                                     Storage$CopyRequest StorageException
                                     StorageOptions)
           (java.io ByteArrayInputStream)
           (java.nio ByteBuffer)
           (java.nio.file Files Path)
           (java.util Arrays UUID)))

(defprotocol AssetStore
  (publish-verified! [store publication])
  (delete-generation! [store asset])
  (open-range! [store asset byte-range]))

(def ^:private max-range-bytes
  (get-in contract/contract-v1 [:limits :transfer :max-range-bytes]))

(defn- present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-object-key? [value]
  (and (string? value)
       (re-matches #"derivatives/[0-9a-f]{64}\.mp4" value)))

(defn- invalid-publication! []
  (throw
   (errors/raise! "Derivative publication is invalid"
                  {:type ::invalid-publication})))

(defn- require-publication!
  [{:keys [job-id attempt asset-id object-key output-path content-type size
           profile-version]
    :as publication}]
  (when-not
   (and (present-string? job-id)
        (pos-int? attempt)
        (= job-id asset-id)
        (valid-object-key? object-key)
        (instance? Path output-path)
        (Files/isRegularFile ^Path output-path
                             (make-array java.nio.file.LinkOption 0))
        (= "video/mp4" content-type)
        (pos-int? size)
        (= size (Files/size ^Path output-path))
        (present-string? profile-version))
    (invalid-publication!))
  publication)

(defn- require-range!
  [{:keys [size] :as asset} {:keys [start end] :as byte-range}]
  (let [length (when (and (integer? start) (integer? end))
                 (inc (- end start)))]
    (when-not (and (pos-int? size)
                   (integer? start)
                   (integer? end)
                   (<= 0 start end)
                   (< end size)
                   (pos-int? length)
                   (<= length max-range-bytes))
      (throw
       (errors/raise! "Derivative playback range is invalid"
                      {:type ::invalid-range
                       :size (long (or size 0))}))))
  byte-range)

(defn- asset-result [generation size content-type profile-version]
  {:generation generation
   :size size
   :content-type content-type
   :profile-version profile-version})

(defrecord InMemoryAssetStore [state]
  AssetStore
  (publish-verified! [_ raw-publication]
    (let [{:keys [object-key output-path content-type size profile-version]}
          (require-publication! raw-publication)
          bytes (Files/readAllBytes ^Path output-path)]
      (locking state
        (if-let [existing (get-in @state [:objects object-key])]
          (if (and (= size (:size existing))
                   (= content-type (:content-type existing))
                   (= profile-version (:profile-version existing))
                   (Arrays/equals ^bytes bytes ^bytes (:bytes existing)))
            (asset-result (:generation existing) size content-type
                          profile-version)
            (throw
             (errors/raise! "Immutable derivative publication conflicts"
                            {:type ::publication-conflict})))
          (let [generation (:next-generation @state)
                stored {:generation generation
                        :size size
                        :content-type content-type
                        :profile-version profile-version
                        :bytes bytes}]
            (swap! state
                   (fn [current]
                     (-> current
                         (assoc-in [:objects object-key] stored)
                         (update :next-generation inc))))
            (asset-result generation size content-type profile-version))))))
  (delete-generation! [_ {:keys [object-key generation]}]
    (locking state
      (if (= generation (get-in @state [:objects object-key :generation]))
        (do
          (swap! state update :objects dissoc object-key)
          true)
        false)))
  (open-range! [_ {:keys [object-key generation size content-type
                          profile-version]
                   :as asset}
                byte-range]
    (require-range! asset byte-range)
    (let [stored (get-in @state [:objects object-key])]
      (when-not (and stored
                     (= generation (:generation stored))
                     (= size (:size stored))
                     (= content-type (:content-type stored))
                     (= profile-version (:profile-version stored)))
        (throw
         (errors/raise! "Derivative asset is unavailable"
                        {:type ::asset-unavailable})))
      (let [{:keys [start end]} byte-range
            bytes (Arrays/copyOfRange ^bytes (:bytes stored)
                                      (int start) (int (inc end)))
            length (alength ^bytes bytes)]
        {:status 206
         :headers {"content-range" (str "bytes " start "-" end "/" size)
                   "content-length" (str length)}
         :body (ByteArrayInputStream. bytes)}))))

(defn in-memory-asset-store []
  (->InMemoryAssetStore (atom {:next-generation 1 :objects {}})))

(defn- blob-result [^Blob blob]
  (asset-result (.getGeneration blob)
                (.getSize blob)
                (.getContentType blob)
                (get (.getMetadata blob) "profileVersion")))

(defn- matching-blob?
  [^Blob blob {:keys [size content-type profile-version]} checksum]
  (and blob
       (= (long size) (long (.getSize blob)))
       (= content-type (.getContentType blob))
       (= profile-version (get (.getMetadata blob) "profileVersion"))
       (= checksum (.getCrc32c blob))))

(defn- precondition-failed? [^StorageException error]
  (= 412 (.getCode error)))

(defrecord GcsAssetStore [^Storage storage bucket]
  AssetStore
  (publish-verified! [_ raw-publication]
    (let [{:keys [job-id attempt asset-id object-key output-path content-type
                  size profile-version]
           :as publication}
          (require-publication! raw-publication)
          temporary-key
          (str "temporary/" job-id "/attempt-" attempt "/"
               (UUID/randomUUID) ".mp4")
          temporary-id (BlobId/of bucket temporary-key)
          temporary (atom nil)]
      (try
        (let [temporary-info
              (-> (BlobInfo/newBuilder temporary-id)
                  (.setContentType content-type)
                  (.setCacheControl "no-store")
                  (.setMetadata {"profileVersion" profile-version})
                  .build)
              uploaded
              (.createFrom
               storage temporary-info ^Path output-path
               (into-array
                Storage$BlobWriteOption
                [(Storage$BlobWriteOption/doesNotExist)
                 (Storage$BlobWriteOption/expectedObjectSize (long size))]))
              _ (reset! temporary uploaded)
              verified
              (.get storage temporary-id
                    (into-array
                     Storage$BlobGetOption
                     [(Storage$BlobGetOption/generationMatch
                       (.getGeneration uploaded))]))
              checksum (.getCrc32c ^Blob verified)]
          (when-not (matching-blob? verified publication checksum)
            (invalid-publication!))
          (let [target-info
                (-> (BlobInfo/newBuilder bucket object-key)
                    (.setContentType content-type)
                    (.setCacheControl "no-store")
                    (.setMetadata {"profileVersion" profile-version
                                   "assetId" asset-id})
                    .build)
                copied
                (try
                  (-> (.copy
                       storage
                       (-> (Storage$CopyRequest/newBuilder)
                           (.setSource temporary-id)
                           (.setSourceOptions
                            [(Storage$BlobSourceOption/generationMatch
                              (.getGeneration uploaded))])
                           (.setTarget
                            target-info
                            (into-array
                             Storage$BlobTargetOption
                             [(Storage$BlobTargetOption/doesNotExist)]))
                           .build))
                      .getResult)
                  (catch StorageException error
                    (if (precondition-failed? error)
                      (.get storage bucket object-key
                            (make-array Storage$BlobGetOption 0))
                      (throw error))))]
            (when-not (matching-blob? copied publication checksum)
              (throw
               (errors/raise! "Immutable derivative publication conflicts"
                              {:type ::publication-conflict})))
            (blob-result copied)))
        (finally
          (try
            (if-let [^Blob uploaded @temporary]
              (.delete storage temporary-id
                       (into-array
                        Storage$BlobSourceOption
                        [(Storage$BlobSourceOption/generationMatch
                          (.getGeneration uploaded))]))
              (.delete storage temporary-id
                       (make-array Storage$BlobSourceOption 0)))
            (catch Throwable _
              nil))))))
  (delete-generation! [_ {:keys [object-key generation]}]
    (when (and (valid-object-key? object-key)
               (pos-int? generation))
      (try
        (.delete storage bucket object-key
                 (into-array
                  Storage$BlobSourceOption
                  [(Storage$BlobSourceOption/generationMatch generation)]))
        (catch StorageException error
          (if (contains? #{404 412} (.getCode error))
            false
            (throw error))))))
  (open-range! [_ {:keys [object-key generation size content-type
                          profile-version]
                   :as asset}
                byte-range]
    (require-range! asset byte-range)
    (try
      (let [blob-id (BlobId/of bucket object-key)
            ^Blob blob
            (.get storage blob-id
                  (into-array
                   Storage$BlobGetOption
                   [(Storage$BlobGetOption/generationMatch generation)]))]
        (when-not (matching-blob? blob asset (.getCrc32c blob))
          (throw
           (errors/raise! "Derivative asset is unavailable"
                          {:type ::asset-unavailable})))
        (let [{:keys [start end]} byte-range
              length (inc (- end start))
              bytes (byte-array length)]
          (with-open [channel
                      (.reader
                       storage blob-id
                       (into-array
                        Storage$BlobSourceOption
                        [(Storage$BlobSourceOption/generationMatch generation)]))]
            (.seek channel start)
            (.limit channel length)
            (let [buffer (ByteBuffer/wrap bytes)]
              (loop []
                (when (.hasRemaining buffer)
                  (let [read (.read channel buffer)]
                    (when (neg? read)
                      (throw
                       (errors/raise! "Derivative range ended early"
                                      {:type ::asset-unavailable})))
                    (recur))))))
          {:status 206
           :headers {"content-range" (str "bytes " start "-" end "/" size)
                     "content-length" (str length)}
           :body (ByteArrayInputStream. bytes)}))
      (catch clojure.lang.ExceptionInfo error
        (throw error))
      (catch Throwable error
        (throw
         (errors/raise! "Derivative asset is unavailable"
                        {:type ::asset-unavailable}
                        error))))))

(defn gcs-asset-store
  ([bucket]
   (gcs-asset-store
    (.getService (StorageOptions/getDefaultInstance)) bucket))
  ([storage bucket]
   (when-not (and storage (present-string? bucket))
     (throw
      (errors/raise! "Derivative asset storage is unavailable"
                     {:type ::invalid-configuration})))
   (->GcsAssetStore storage bucket)))
