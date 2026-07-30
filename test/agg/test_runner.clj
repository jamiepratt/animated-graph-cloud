(ns agg.test-runner
  (:require [agg.test-catalogue :as catalogue]
            [agg.test-targeted :as targeted]))

(defn- parse-options [args]
  (loop [remaining args
         options {}]
    (if (empty? remaining)
      options
      (let [[flag value & more] remaining]
        (when-not (#{"--area" "--shard" "--namespace"} flag)
          (throw (ex-info (str "Unknown test selector " flag) {:flag flag})))
        (when-not value
          (throw (ex-info (str "Missing value for " flag) {:flag flag})))
        (recur more (update options flag (fnil conj []) value))))))

(defn- validate-values! [kind known values]
  (doseq [value values]
    (when-not (contains? known value)
      (throw (ex-info (str "Unknown test " kind ": " value)
                      {:kind kind :value value}))))
  values)

(defn selected-namespaces [args]
  (if (empty? args)
    (catalogue/all-namespaces)
    (let [options (parse-options args)]
      (cond
        (contains? options "--namespace")
        (let [known (set (catalogue/all-namespaces))
              values (mapv symbol (options "--namespace"))]
          (validate-values! "namespace" known values))

        (contains? options "--area")
        (let [known (catalogue/areas)
              values (mapv keyword (options "--area"))]
          (validate-values! "area" known values)
          (catalogue/namespaces-for-areas values))

        (contains? options "--shard")
        (let [known (catalogue/shards)
              values (mapv keyword (options "--shard"))]
          (validate-values! "shard" known values)
          (catalogue/namespaces-for-shards values))))))

(defn run-selected [namespaces]
  (doseq [namespace namespaces]
    (require namespace))
  (targeted/run-tests namespaces))

(defn -main [& args]
  (let [{:keys [error fail]} (run-selected (selected-namespaces args))]
    (System/exit (if (pos? (+ error fail)) 1 0))))
