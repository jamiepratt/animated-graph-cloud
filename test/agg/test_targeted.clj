(ns agg.test-targeted
  (:require [clojure.test :as test]))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: clojure -M:test -m agg.test-targeted <test-ns> ...")))

(defn run-tests [namespaces]
  (let [default-report test/report]
    (with-redefs
     [test/report
      (fn [{:keys [type var] :as event}]
        (when (= :begin-test-var type)
          (let [{:keys [ns name]} (meta var)]
            (binding [*out* test/*test-out*]
              (println "Running" (str (ns-name ns) "/" name))
              (flush))))
        (default-report event))]
      (apply test/run-tests namespaces))))

(defn -main [& args]
  (if (seq args)
    (let [namespaces (map symbol args)
          _          (doseq [ns namespaces]
                       (require ns))
          result     (run-tests namespaces)]
      (System/exit (if (pos? (+ (:error result) (:fail result))) 1 0)))
    (do
      (usage)
      (System/exit 1))))
