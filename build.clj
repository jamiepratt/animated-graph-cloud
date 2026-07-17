(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/animated-graph-cloud.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :class-dir class-dir
                  :ns-compile '[agg.api.main agg.renderer.main]
                  :src-dirs ["src"]})
  (b/uber {:basis @basis
           :class-dir class-dir
           :exclude ["(?i)META-INF/license"]
           :uber-file uber-file})
  {:uber-file uber-file})
