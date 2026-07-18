(ns agg.render.storage
  (:require [agg.errors :as errors]
            [clojure.data.json :as json])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Path)))

(defprotocol ArtifactStore
  (upload! [store artifact-kind path content-type]))

(def ^:private suffixes
  {:media ".mov"
   :profile ".jfr"
   :report ".json"})

(def ^:private token-uri
  (URI/create
   "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"))

(defn- access-token [^HttpClient client]
  (let [request (-> (HttpRequest/newBuilder token-uri)
                    (.header "Metadata-Flavor" "Google")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (errors/raise! "Ambient access token unavailable"
                      {:type ::ambient-token-unavailable
                       :status (.statusCode response)})))
    (:access_token (json/read-str (.body response) :key-fn keyword))))

(defrecord GcsArtifactStore [^HttpClient client bucket prefix]
  ArtifactStore
  (upload! [_ artifact-kind path content-type]
    (let [object (str prefix (get suffixes artifact-kind))
          encoded-bucket (URLEncoder/encode bucket StandardCharsets/UTF_8)
          encoded-object (URLEncoder/encode object StandardCharsets/UTF_8)
          uri (URI/create
               (str "https://storage.googleapis.com/upload/storage/v1/b/"
                    encoded-bucket
                    "/o?uploadType=media&name="
                    encoded-object))
          request (-> (HttpRequest/newBuilder uri)
                      (.header "Authorization" (str "Bearer " (access-token client)))
                      (.header "Content-Type" content-type)
                      (.POST (HttpRequest$BodyPublishers/ofFile ^Path path))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/discarding))]
      (when-not (<= 200 (.statusCode response) 299)
        (throw (errors/raise! "Artifact upload failed"
                        {:type ::upload-failed
                         :status (.statusCode response)})))
      {:bucket bucket :object object})))

(defn gcs-store [bucket prefix]
  (->GcsArtifactStore (HttpClient/newHttpClient) bucket prefix))
