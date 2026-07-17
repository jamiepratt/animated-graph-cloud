(ns agg.drive.core
  (:import (java.nio.file Files Path)))

(defprotocol DeliveryStore
  (load-delivery [store job-id])
  (reserve-output! [store job-id candidate])
  (save-upload-session! [store job-id session-uri])
  (complete-delivery! [store job-id result]))

(defprotocol DriveGateway
  (generate-output-id! [gateway access-token])
  (begin-resumable-upload! [gateway access-token request])
  (upload-resumable! [gateway access-token session-uri path size])
  (resume-resumable! [gateway access-token session-uri path size]))

(defprotocol OutputDelivery
  (deliver-output! [delivery job-id subject path]))

(declare deliver-resumable!)

(defrecord ResumableDelivery [store gateway access-provider]
  OutputDelivery
  (deliver-output! [this job-id subject path]
    (deliver-resumable! this job-id subject path)))

(defn delivery [{:keys [store gateway access-provider]}]
  (when-not (and store gateway access-provider)
    (throw (ex-info "Drive delivery dependencies are incomplete"
                    {:type ::invalid-configuration})))
  (->ResumableDelivery store gateway access-provider))

(defn- public-result [file-id]
  {:fileId file-id
   :webViewLink (str "https://drive.google.com/file/d/" file-id "/view")})

(defn- ensure-reservation!
  [{:keys [store gateway]} job-id access-token folder-id]
  (or (load-delivery store job-id)
      (reserve-output! store job-id
                       {:file-id (generate-output-id! gateway access-token)
                        :folder-id folder-id})))

(defn- begin-session!
  [{:keys [store gateway]} job-id access-token
   {:keys [file-id folder-id]} size]
  (let [session-uri
        (begin-resumable-upload!
         gateway access-token
         {:file-id file-id
          :folder-id folder-id
          :name (str "animated-graph-" job-id ".mov")
          :content-type "video/quicktime"
          :size size})]
    (save-upload-session! store job-id session-uri)
    session-uri))

(defn deliver-resumable!
  [{:keys [store gateway access-provider] :as delivery}
   job-id subject ^Path path]
  (let [existing (load-delivery store job-id)]
    (if (:complete? existing)
      (public-result (:file-id existing))
      (let [{:keys [access-token folder-id]} (access-provider subject)
            reservation (or existing
                            (ensure-reservation! delivery job-id access-token folder-id))
            size (Files/size path)]
        (loop [session-uri (or (:session-uri reservation)
                               (begin-session! delivery job-id access-token
                                               reservation size))
               recovered? (some? (:session-uri reservation))
               restarts 0]
          (let [{:keys [status file-id]}
                ((if recovered? resume-resumable! upload-resumable!)
                 gateway access-token session-uri path size)]
            (case status
              :complete
              (let [expected-id (:file-id reservation)]
                (when-not (= expected-id file-id)
                  (throw (ex-info "Drive completed a different output ID"
                                  {:type ::unexpected-output-id})))
                (complete-delivery! store job-id {:file-id expected-id})
                (public-result expected-id))

              :session-expired
              (if (< restarts 2)
                (recur (begin-session! delivery job-id access-token
                                       reservation size)
                       false
                       (inc restarts))
                (throw (ex-info "Drive resumable session repeatedly expired"
                                {:type ::resumable-session-expired})))

              (throw (ex-info "Drive resumable upload failed"
                              {:type ::upload-failed :status status})))))))))

(defn deliver! [delivery job-id subject path]
  (deliver-output! delivery job-id subject path))
