(ns agg.proto-release
  (:require [agg.release :as release]
            [clojure.java.io :as io]))

(defn- read-text [resource-path file-path]
  (if-let [resource (io/resource resource-path)]
    (slurp resource)
    (slurp (io/file file-path))))

(def version
  (release/parse-version-resource
   (read-text "agg/proto-version.edn"
              "resources/agg/proto-version.edn")))

(def current
  (release/release-identity
   {:version version
    :build-commit (System/getenv "AGG_BUILD_COMMIT")
    :production? (= "production"
                    (System/getenv "AGG_RELEASE_MODE"))}))

(def ^:private changelog-source
  (delay
    (read-text "proto/CHANGELOG.md"
               "docs/proto/CHANGELOG.md")))

(def ^:private released-changelog
  (delay
    (let [markdown @changelog-source
          newest (release/newest-released-version markdown)]
      (when-not (= version newest)
        (throw (IllegalStateException.
                "Proto version must match the newest released changelog entry.")))
      (release/public-changelog-html markdown))))

(defn changelog-markdown []
  @changelog-source)

(defn public-changelog-html []
  @released-changelog)

(defn assert-valid! []
  current)
