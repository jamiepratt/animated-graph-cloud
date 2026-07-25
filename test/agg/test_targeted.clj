(ns agg.test-targeted
  (:require [clojure.test :as test]))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: clojure -M:test -m agg.test-targeted <test-ns> ...")))

(defn -main [& args]
  (if (seq args)
    (let [namespaces (map symbol args)
          _          (doseq [ns namespaces]
                       (require ns))
          result     (apply test/run-tests namespaces)]
      (System/exit (if (pos? (+ (:error result) (:fail result))) 1 0)))
    (do
      (usage)
      (System/exit 1))))
