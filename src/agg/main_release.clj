(ns agg.main-release
  (:require [agg.release :as release]
            [clojure.java.io :as io]))

(defn- read-text [path]
  (if-let [resource (io/resource path)]
    (slurp resource)
    (slurp (io/file path))))

(def version
  (release/parse-version-resource
   (read-text "agg/main-version.edn")))

(def current
  (release/release-identity
   {:version version
    :build-commit (System/getenv "AGG_BUILD_COMMIT")
    :production? (= "production"
                    (System/getenv "AGG_RELEASE_MODE"))}))

(def ^:private changelog-source
  (delay (read-text "CHANGELOG.md")))

(def ^:private released-changelog
  (delay
    (let [markdown @changelog-source
          newest (release/newest-released-version markdown)]
      (when-not (= version newest)
        (throw (IllegalStateException.
                "Main version must match the newest released changelog entry.")))
      (release/public-changelog-html markdown))))

(defn changelog-markdown []
  @changelog-source)

(defn public-changelog-html []
  @released-changelog)

(defn assert-valid! []
  current)
