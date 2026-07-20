(ns agg.preview.core
  (:require [agg.errors :as errors]
            [agg.jobs.lifecycle :as jobs]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [clojure.string :as str])
  (:import (java.awt RenderingHints)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.time Instant)
           (javax.imageio ImageIO)))

(def max-full-image-bytes (* 32 1024 1024))
(def max-thumbnail-bytes (* 4 1024 1024))

(defprotocol AssetStore
  (put-asset! [store operation-id asset-id size bytes])
  (get-asset [store operation-id asset-id size]))

(defn valid-asset-id? [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9_-]{1,64}" value))))

(defn valid-operation-id? [value]
  (and (string? value)
       (boolean
        (re-matches
         #"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
         value))))

(defn- require-asset-key! [operation-id asset-id size]
  (when-not (and (valid-operation-id? operation-id)
                 (valid-asset-id? asset-id)
                 (contains? #{:thumbnail :full} size))
    (throw (errors/raise! "Preview image key is invalid"
                          {:type ::invalid-asset-key}))))

(defn- size-limit [size]
  (if (= :thumbnail size) max-thumbnail-bytes max-full-image-bytes))

(defrecord InMemoryAssetStore [state]
  AssetStore
  (put-asset! [_ operation-id asset-id size bytes]
    (require-asset-key! operation-id asset-id size)
    (when-not (and (bytes? bytes) (<= (alength ^bytes bytes) (size-limit size)))
      (throw (errors/raise! "Preview image exceeds its size limit"
                            {:type ::asset-too-large
                             :limit (size-limit size)})))
    (swap! state assoc [operation-id asset-id size]
           {:bytes bytes :content-type "image/png"})
    {:asset-id asset-id :size size})
  (get-asset [_ operation-id asset-id size]
    (require-asset-key! operation-id asset-id size)
    (get @state [operation-id asset-id size])))

(defn in-memory-asset-store []
  (->InMemoryAssetStore (atom {})))

(defn image-reference [operation-id asset-id]
  {:thumbnailUrl (str "/v1/previews/" operation-id "/images/"
                      asset-id "/thumbnail")
   :fullUrl (str "/v1/previews/" operation-id "/images/"
                 asset-id "/full")})

(defn png-visible? [bytes]
  (let [image (ImageIO/read (ByteArrayInputStream. bytes))]
    (when-not image
      (throw (errors/raise! "Preview image is not PNG"
                            {:type ::invalid-png})))
    (boolean
     (some (fn [y]
             (some (fn [x]
                     (pos? (bit-and 0xff
                                    (unsigned-bit-shift-right
                                     (.getRGB image x y) 24))))
                   (range (.getWidth image))))
           (range (.getHeight image))))))

(defn thumbnail-png [bytes]
  (let [source (ImageIO/read (ByteArrayInputStream. bytes))]
    (when-not source
      (throw (errors/raise! "Preview image is not PNG"
                            {:type ::invalid-png})))
    (if (<= (.getWidth source) 640)
      bytes
      (let [width 640
            height (max 1 (int (Math/round
                                (* (.getHeight source)
                                   (/ width (double (.getWidth source)))))))
            thumbnail (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
            graphics (.createGraphics thumbnail)
            output (ByteArrayOutputStream.)]
        (try
          (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION
                             RenderingHints/VALUE_INTERPOLATION_BILINEAR)
          (.drawImage graphics source 0 0 width height nil)
          (finally
            (.dispose graphics)))
        (when-not (ImageIO/write thumbnail "png" output)
          (throw (errors/raise! "PNG encoder unavailable"
                                {:type ::png-unavailable})))
        (.toByteArray output)))))

(defn- store-image! [store operation-id asset-id bytes]
  (let [thumbnail (thumbnail-png bytes)]
    (put-asset! store operation-id asset-id :full bytes)
    (put-asset! store operation-id asset-id :thumbnail thumbnail)
    {:reference (image-reference operation-id asset-id)
     :bytes (+ (alength ^bytes bytes) (alength ^bytes thumbnail))}))

(defn- frame-assets [sections]
  (->> sections
       (mapcat :moments)
       (map :frameIndex)
       distinct
       sort
       (map-indexed (fn [index frame]
                      {:id (format "a%03d" index)
                       :frameIndex frame}))
       vec))

(defn- moment-title [{:keys [labels value elapsed]} unit]
  (str (str/join " / " labels) " - "
       (let [value (double value)]
         (if (== value (Math/rint value))
           (format "%.0f" value)
           (format "%.1f" value)))
       " " unit " - " elapsed))

(defn- manifest-sections [sections asset-id-by-frame]
  (mapv (fn [section]
          (update section :moments
                  (fn [moments]
                    (mapv (fn [moment]
                            (assoc moment
                                   :frameRef
                                   (get asset-id-by-frame (:frameIndex moment))
                                   :eventLabel (str/join " / " (:labels moment))
                                   :title (moment-title moment (:unit section))))
                          moments))))
        sections))

(defn render-gallery!
  "Renders and stores one bounded gallery. Selected-source decode occurs once."
  [operation-id render-spec asset-store frame-renderer gallery-renderer
   source-stream!]
  (let [sections (frames/preview-sections render-spec)
        assets (frame-assets sections)
        asset-id-by-frame (into {} (map (juxt :frameIndex :id)) assets)
        overlays
        (mapv (fn [{:keys [frameIndex] :as asset}]
                (let [output (ByteArrayOutputStream.)]
                  (frames/render-frame-png! frame-renderer render-spec
                                            frameIndex output)
                  (assoc asset :overlay (.toByteArray output))))
              assets)
        stored-by-frame (atom {})
        output-bytes (atom 0)
        store! (fn [asset-id bytes]
                 (let [{stored-bytes :bytes reference :reference}
                       (store-image! asset-store operation-id asset-id bytes)]
                   (swap! output-bytes + stored-bytes)
                   reference))]
    (if source-stream!
      (do
        (when-not (and gallery-renderer
                       (satisfies? media/CompositeGalleryRenderer
                                   gallery-renderer))
          (throw (errors/raise! "Selected-source gallery dependencies are incomplete"
                                {:type ::missing-gallery-renderer})))
        (media/render-composite-gallery!
         gallery-renderer render-spec source-stream! overlays
         (fn [frame-index source-png final-png]
           (let [{:keys [id overlay]}
                 (first (filter #(= frame-index (:frameIndex %)) overlays))
                 visible? (png-visible? overlay)
                 source-ref (store! (str id "-source") source-png)
                 final-ref (if visible?
                             (store! (str id "-final") final-png)
                             source-ref)]
             (swap! stored-by-frame assoc frame-index
                    {:id id
                     :frameIndex frame-index
                     :kind "source-final"
                     :merged (not visible?)
                     :source source-ref
                     :final final-ref}))))
        (when-not (= (set (map :frameIndex assets))
                     (set (keys @stored-by-frame)))
          (throw (errors/raise! "Source gallery did not emit every selected frame"
                                {:type ::incomplete-gallery}))))
      (doseq [{:keys [id frameIndex overlay]} overlays]
        (swap! stored-by-frame assoc frameIndex
               {:id id
                :frameIndex frameIndex
                :kind "overlay"
                :image (store! (str id "-overlay") overlay)})))
    {:output-bytes @output-bytes
     :version 1
     :mode (if source-stream! "source-final" "overlay")
     :sections (manifest-sections sections asset-id-by-frame)
     :assets (mapv #(get @stored-by-frame (:frameIndex %)) assets)}))

(defn operation-resource
  ([job]
   (operation-resource job (Instant/now)))
  ([job now]
   (when (= "preview" (:operationKind job))
     (let [state (:state job)
           expires-at (-> (Instant/parse (:createdAt job))
                          (.plusSeconds (* 24 60 60)))]
       (when (.isBefore ^Instant now expires-at)
         (let [expires-at (str expires-at)
               progress (case state
                          "queued" 0
                          "launching" 10
                          "running" 25
                          "cancellation-requested" 75
                          100)]
           (cond-> {:id (:id job)
                    :operationKind "key-moment-gallery"
                    :state state
                    :progressPercent progress
                    :statusUrl (str "/v1/previews/" (:id job))
                    :resultUrl (str "/v1/previews/" (:id job))
                    :createdAt (:createdAt job)
                    :updatedAt (:updatedAt job)
                    :expiresAt expires-at}
             (= "succeeded" state)
             (assoc :result (dissoc (:output job) :output-bytes)
                    :receiptExpiresAt
                    (str (.plusSeconds (Instant/parse (:updatedAt job))
                                       jobs/preview-evidence-seconds)))
             (= "failed" state)
             (assoc :error
                    (let [code (or (:failureCode job) "preview_failed")]
                      (cond-> {:code code
                               :category
                               (cond
                                 (= "source_metadata_failed" code)
                                 "drive_source_metadata"

                                 (contains? #{"source_download_failed"
                                              "source_content_failed"} code)
                                 "drive_source_content"

                                 :else "preview_rendering")
                               :requestId (:id job)
                               :retryable (true? (:retryable job))}
                        (:stage job) (assoc :stage (:stage job))
                        (:reason job) (assoc :reason (:reason job))
                        (:status job) (assoc :status (:status job))
                        (some? (:elapsedMs job))
                        (assoc :elapsedMs (:elapsedMs job))
                        (some? (:timeoutMs job))
                        (assoc :timeoutMs (:timeoutMs job)))))
             (= "cancelled" state)
             (assoc :error {:code "preview_cancelled"
                            :retryable false}))))))))
