(ns agg.test-catalogue
  (:require [clojure.java.io :as io]
            [clojure.set :as set]))

(defn- entry [namespace shard & areas]
  {:namespace namespace
   :shard shard
   :areas (set (conj areas shard))
   :tools #{}})

(defn- requires-tools [test-entry & tools]
  (assoc test-entry :tools (set tools)))

(def tests
  [(entry 'agg.admin-gcp-test :cloud :api :auth)
   (entry 'agg.admin-test :api :auth)
   (entry 'agg.api-admin-test :api :auth)
   (entry 'agg.api-auth-test :api :auth)
   (entry 'agg.api-derivative-playback-test :api :derivative :auth)
   (entry 'agg.api-derivative-preparation-test :api :derivative :proto)
   (entry 'agg.api-preview-test :api :render)
   (entry 'agg.api-profile-test :api)
   (entry 'agg.api-routes-test :api)
   (entry 'agg.api-tokens-test :api :auth)
   (entry 'agg.api-ui-test :api)
   (entry 'agg.auth-gcp-test :cloud :auth)
   (entry 'agg.auth-test :auth)
   (entry 'agg.browser-process-test :render)
   (entry 'agg.build-pipeline-test :release :cloud)
   (entry 'agg.contracts-test :api :render)
   (entry 'agg.deploy-workflow-test :release :cloud)
   (entry 'agg.derivative-contract-test :derivative :api)
   (entry 'agg.derivative-gcp-test :cloud :derivative)
   (entry 'agg.derivative-keys-test :derivative :cloud)
   (entry 'agg.derivative-lifecycle-test :derivative :cloud :proto)
   (requires-tools
    (entry 'agg.derivative-media-test :derivative :render :proto)
    :ffmpeg)
   (entry 'agg.derivative-preparation-test :derivative :cloud :proto)
   (entry 'agg.derivative-storage-test :derivative :cloud)
   (entry 'agg.derivative-worker-test :derivative :cloud :proto)
   (requires-tools (entry 'agg.drive-gcp-test :cloud :drive) :ffmpeg)
   (entry 'agg.drive-range-proxy-test :drive :api :proto)
   (entry 'agg.drive-test :drive)
   (entry 'agg.early-access-resend-test :cloud :api :auth)
   (entry 'agg.early-access-test :api :auth)
   (entry 'agg.errors-test :api :render)
   (entry 'agg.garmin-test :render)
   (entry 'agg.gcp-jobs-test :cloud :render)
   (entry 'agg.jobs-test :cloud :render)
   (entry 'agg.logs-gcp-test :cloud)
   (entry 'agg.logs-test :cloud)
   (entry 'agg.observability-test :cloud :proto)
   (entry 'agg.oxiwear-test :render)
   (entry 'agg.polar-test :render)
   (entry 'agg.proto-release-test :proto :release)
   (entry 'agg.proto-source-test :proto :drive :derivative)
   (entry 'agg.proto-ui-test :proto :derivative)
   (entry 'agg.release-config-test :release :cloud)
   (entry 'agg.release-test :release :api)
   (entry 'agg.render-test :render)
   (entry 'agg.renderer-drive-test :render :drive :cloud)
   (entry 'agg.smoke-test :render)
   (entry 'agg.test-catalogue-test :release)
   (entry 'agg.test-selection-test :release)
   (entry 'agg.test-targeted-test :release)
   (entry 'agg.timeline-property-test :render)
   (entry 'agg.tokens-gcp-test :cloud :auth)
   (entry 'agg.tokens-test :auth)
   (entry 'agg.ui-project-test :api)
   (entry 'agg.ui-wizard-test :api)
   (entry 'agg.watermark-test :render)
   (entry 'agg.youtube-test :api :cloud)])

(defn all-namespaces []
  (mapv :namespace tests))

(defn areas []
  (set (mapcat :areas tests)))

(defn shards []
  (set (map :shard tests)))

(defn namespaces-for-areas [requested]
  (let [requested (set requested)]
    (->> tests
         (filter #(seq (set/intersection requested (:areas %))))
         (mapv :namespace))))

(defn namespaces-for-shards [requested]
  (let [requested (set requested)]
    (->> tests
         (filter #(contains? requested (:shard %)))
         (mapv :namespace))))

(defn- namespace-from-file [file]
  (some->> (slurp file)
           (re-find #"(?m)^\(ns\s+([^\s()]+)")
           second
           symbol))

(defn discovered-test-namespaces []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile %))
       (filter #(re-find #"_test\.clj$" (.getName %)))
       (keep namespace-from-file)
       set))
